package com.sid_imp.leaf.fastboot

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference

class FastbootUsbManager(private val context: Context) {

    companion object {
        private const val TAG = "LeafUsbManager"
        const val ACTION_USB_PERMISSION = "com.sid_imp.leaf.USB_PERMISSION"
        private const val PERMISSION_COOLDOWN_MS = 5000L
        private const val RETRY_COOLDOWN_MS = 2500L

        private val MTK_NON_FASTBOOT_PIDS = setOf(
            0x0003, // MediaTek BootROM (BROM)
            0x2000, // MediaTek Preloader USB VCOM
            0x2001, // MediaTek Preloader
            0x2006, // MediaTek DA Composite
            0x2007, // MediaTek DA
            0x200A, // MediaTek Preloader/VCOM
            0x20FF  // MediaTek Service/Meta
        )

        /**
         * Strictly matches Google AOSP Bootloader & Userspace Fastbootd interfaces only
         * (Class 255 / Subclass 66 / Protocol 3) and rejects ADB (protocol 1), MediaTek BROM,
         * and MediaTek Preloader devices.
         */
        fun isStrictFastbootInterface(
            vendorId: Int,
            productId: Int,
            intfClass: Int,
            intfSubclass: Int,
            intfProtocol: Int
        ): Boolean {
            if (vendorId == 0x0E8D && productId in MTK_NON_FASTBOOT_PIDS) {
                return false
            }
            return intfClass == FastbootConstants.USB_CLASS_VENDOR_SPECIFIC &&
                intfSubclass == FastbootConstants.USB_SUBCLASS_FASTBOOT &&
                intfProtocol == FastbootConstants.USB_PROTOCOL_FASTBOOT
        }

        /**
         * Strictly matches Android ADB / Recovery / Recovery ADB Sideload interfaces
         * (Class 255 / Subclass 66 / Protocol 1), excluding MediaTek BROM/Preloader devices.
         */
        fun isStrictAdbInterface(
            vendorId: Int,
            productId: Int,
            intfClass: Int,
            intfSubclass: Int,
            intfProtocol: Int
        ): Boolean {
            if (vendorId == 0x0E8D && productId in MTK_NON_FASTBOOT_PIDS) {
                return false
            }
            return intfClass == FastbootConstants.USB_CLASS_VENDOR_SPECIFIC &&
                intfSubclass == FastbootConstants.USB_SUBCLASS_FASTBOOT &&
                intfProtocol == FastbootConstants.USB_PROTOCOL_ADB
        }
    }

    private data class UsbInterfaceMatch(
        val usbInterface: UsbInterface,
        val inEndpoint: UsbEndpoint,
        val outEndpoint: UsbEndpoint,
        val configurationToSelect: UsbConfiguration? = null
    )

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val packageUsbPermissionAction = "${context.packageName}.USB_PERMISSION"

    private val _deviceFlow = MutableStateFlow<FastbootDevice?>(null)
    val deviceFlow: StateFlow<FastbootDevice?> = _deviceFlow.asStateFlow()

    private val _adbDeviceFlow = MutableStateFlow<AdbUsbDevice?>(null)
    val adbDeviceFlow: StateFlow<AdbUsbDevice?> = _adbDeviceFlow.asStateFlow()

    private val _adbAwaitingAuthFlow = MutableStateFlow(false)
    val adbAwaitingAuthFlow: StateFlow<Boolean> = _adbAwaitingAuthFlow.asStateFlow()

    private val _deviceInfoFlow = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfoFlow: StateFlow<DeviceInfo?> = _deviceInfoFlow.asStateFlow()

    private val _modeFlow = MutableStateFlow(FastbootMode.DISCONNECTED)
    val modeFlow: StateFlow<FastbootMode> = _modeFlow.asStateFlow()

    var onDiagnosticLog: ((String) -> Unit)? = null

    private var activityRef: WeakReference<Activity>? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isReceiverRegistered = false
    private val lock = Any()
    private val permissionCooldownUntil = mutableMapOf<Int, Long>()
    private val retryCooldownUntil = mutableMapOf<Int, Long>()
    private var connectionProbeJob: Job? = null
    @Volatile private var pendingProbeDeviceId: Int? = null
    private var missingPollCount = 0
    private var lastDiagMessage = ""
    private var lastDiagTimeMs = 0L

    fun attachActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    private fun logDiag(message: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && message == lastDiagMessage && now - lastDiagTimeMs < 8000L) return
        lastDiagMessage = message
        lastDiagTimeMs = now
        onDiagnosticLog?.invoke(message)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                packageUsbPermissionAction, ACTION_USB_PERMISSION -> {
                    synchronized(lock) {
                        try {
                            val device: UsbDevice? = try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                                }
                            } catch (_: Exception) {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }

                            val extraGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            val targetDev = (if (device != null && isSupportedUsbDevice(device)) device else null)
                                ?: usbManager.deviceList?.values?.firstOrNull { isSupportedUsbDevice(it) }
                            val isGranted = extraGranted || (targetDev != null && usbManager.hasPermission(targetDev))
                            Log.d(TAG, "USB permission callback: granted=$isGranted (extra=$extraGranted), device=${targetDev?.deviceName}")

                            if (isGranted && targetDev != null) {
                                permissionCooldownUntil.remove(targetDev.deviceId)
                                retryCooldownUntil.remove(targetDev.deviceId)
                                connectToUsbDevice(targetDev)
                            } else if (isGranted) {
                                scanForConnectedDevices(userTriggered = false)
                            } else {
                                Log.w(TAG, "USB permission denied for ${device?.deviceName}")
                                logDiag("Note: USB permission denied — tap status card to retry", force = true)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error handling USB permission result", e)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    synchronized(lock) {
                        try {
                            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                            device?.let {
                                permissionCooldownUntil.remove(it.deviceId)
                                retryCooldownUntil.remove(it.deviceId)
                                handleDeviceAttached(it)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in device attach", e)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    synchronized(lock) {
                        try {
                            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                            handleDeviceDetached(device)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in device detach", e)
                        }
                    }
                }
            }
        }
    }

    private var continuousDetectionJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (!isReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(packageUsbPermissionAction)
                    addAction(ACTION_USB_PERMISSION)
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        context.registerReceiver(usbReceiver, filter)
                    }
                    isReceiverRegistered = true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register receiver", e)
                }
            }
        }
        scanForConnectedDevices(userTriggered = false)
        startContinuousDetection()
    }

    fun stop() {
        synchronized(lock) {
            continuousDetectionJob?.cancel()
            continuousDetectionJob = null
            if (isReceiverRegistered) {
                try {
                    context.unregisterReceiver(usbReceiver)
                } catch (_: Exception) {}
                isReceiverRegistered = false
            }
            disconnect()
        }
    }

    /**
     * Polling fallback loop ensuring OnePlus, Samsung, and all custom ROM host devices discover
     * plugged-in Fastboot & ADB/Sideload targets without delay.
     */
    private fun startContinuousDetection() {
        continuousDetectionJob?.cancel()
        continuousDetectionJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    if (_modeFlow.value != FastbootMode.RECONNECTING) {
                        val currentFb = _deviceFlow.value
                        val currentAdb = _adbDeviceFlow.value
                        val fbAlive = currentFb != null && currentFb.isConnected
                        val adbAlive = currentAdb != null && currentAdb.isConnected

                        val deviceList = usbManager.deviceList
                        if (!fbAlive && !adbAlive) {
                            missingPollCount = 0
                            if (deviceList != null) {
                                // Prioritize Fastboot interfaces before ADB interfaces
                                val matchDev = deviceList.values.firstOrNull { isFastbootDevice(it) }
                                    ?: deviceList.values.firstOrNull { isAdbDevice(it) }
                                if (matchDev != null) {
                                    handleDeviceAttached(matchDev)
                                }
                            }
                        } else if (adbAlive && currentAdb != null) {
                            val stillAttached = deviceList?.values?.any {
                                it.deviceId == currentAdb.usbDevice.deviceId
                            } == true
                            if (!stillAttached) {
                                missingPollCount++
                                if (missingPollCount >= 2) {
                                    missingPollCount = 0
                                    disconnect()
                                }
                            } else {
                                missingPollCount = 0
                            }
                        } else {
                            missingPollCount = 0
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in continuous USB detection loop", e)
                }
                delay(650) // Low-overhead fallback polling; plug-in broadcasts still fire immediately
            }
        }
    }

    fun scanForConnectedDevices(userTriggered: Boolean = false) {
        try {
            if (userTriggered) {
                // Only manual scans reset cooldowns & stalled probes; passive onResume() must not cancel an active dialog
                permissionCooldownUntil.clear()
                retryCooldownUntil.clear()
                if (_modeFlow.value == FastbootMode.DISCONNECTED) {
                    synchronized(lock) {
                        connectionProbeJob?.cancel()
                        connectionProbeJob = null
                        pendingProbeDeviceId = null
                        _adbAwaitingAuthFlow.value = false
                    }
                } else if (_modeFlow.value == FastbootMode.ADB_RECOVERY || _modeFlow.value == FastbootMode.ADB) {
                    // Re-handshake in case recovery switched from adbd -> minadbd Sideload on the same USB deviceId
                    disconnect()
                }
            }
            val deviceList = usbManager.deviceList
            if (deviceList.isNullOrEmpty()) {
                if (userTriggered) {
                    logDiag("Note: OS reports 0 USB devices — enable OTG in ROM Settings / check cable", force = true)
                }
                return
            }

            val fastbootDev = deviceList.values.firstOrNull { isFastbootDevice(it) }
            if (fastbootDev != null) {
                handleDeviceAttached(fastbootDev, forcePermissionPrompt = userTriggered)
                return
            }

            val adbDev = deviceList.values.firstOrNull { isAdbDevice(it) }
            if (adbDev != null) {
                handleDeviceAttached(adbDev, forcePermissionPrompt = userTriggered)
                return
            }

            if (userTriggered) {
                logDiag("Note: ${deviceList.size} USB device(s) seen, waiting for Fastboot or ADB", force = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB devices", e)
            if (userTriggered) {
                logDiag("✗ USB scan failed: ${e.message}", force = true)
            }
        }
    }

    private fun handleDeviceAttached(device: UsbDevice, forcePermissionPrompt: Boolean = false) {
        if (!isSupportedUsbDevice(device)) return
        val now = System.currentTimeMillis()
        if (!forcePermissionPrompt && now < (retryCooldownUntil[device.deviceId] ?: 0L)) return

        val currentFb = (_deviceFlow.value as? FastbootUsbDevice)?.usbDevice
        val currentAdb = _adbDeviceFlow.value?.usbDevice
        if ((currentFb != null && currentFb.deviceId == device.deviceId) ||
            (currentAdb != null && currentAdb.deviceId == device.deviceId)
        ) {
            return
        }

        if (usbManager.hasPermission(device)) {
            connectToUsbDevice(device)
        } else {
            requestUsbPermission(device, force = forcePermissionPrompt)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice?) {
        if (device != null) {
            permissionCooldownUntil.remove(device.deviceId)
            retryCooldownUntil.remove(device.deviceId)
            if (pendingProbeDeviceId == device.deviceId) {
                connectionProbeJob?.cancel()
                connectionProbeJob = null
                pendingProbeDeviceId = null
                _adbAwaitingAuthFlow.value = false
            }
        }
        val currentFb = (_deviceFlow.value as? FastbootUsbDevice)?.usbDevice
        val currentAdb = _adbDeviceFlow.value?.usbDevice
        val activeUsb = currentFb ?: currentAdb ?: return

        if (device != null) {
            val isOurDevice = (device.deviceId == activeUsb.deviceId)
            if (!isOurDevice) {
                Log.d(TAG, "Unrelated USB device detached (${device.deviceName}), ignoring")
                return
            }
        } else {
            try {
                val list = usbManager.deviceList
                val stillExists = (list != null && list.containsKey(activeUsb.deviceName))
                if (stillExists) {
                    Log.d(TAG, "Spurious null detach event while device is still active in deviceList, ignoring")
                    return
                }
            } catch (_: Exception) {}
        }
        val isReconnecting = _modeFlow.value == FastbootMode.RECONNECTING
        permissionCooldownUntil.clear()
        retryCooldownUntil.clear()
        disconnect(keepReconnectingState = isReconnecting)
    }

    fun onDeviceAttached(device: UsbDevice) {
        handleDeviceAttached(device, forcePermissionPrompt = true)
    }

    private fun requestUsbPermission(device: UsbDevice, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now < (permissionCooldownUntil[device.deviceId] ?: 0L)) return
        permissionCooldownUntil[device.deviceId] = now + PERMISSION_COOLDOWN_MS

        mainHandler.post {
            try {
                if (usbManager.hasPermission(device)) {
                    permissionCooldownUntil.remove(device.deviceId)
                    connectToUsbDevice(device)
                    return@post
                }
                val hostCtx: Context = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val intent = Intent(packageUsbPermissionAction).apply {
                    setPackage(context.packageName)
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    hostCtx,
                    device.deviceId,
                    intent,
                    flags
                )
                usbManager.requestPermission(device, permissionIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request USB permission", e)
            }
        }
    }

    /**
     * Strict Fastboot interface identification matching Bootloader and Fastbootd ONLY
     * (Class 255, Subclass 66, Protocol 3). Excludes ADB, MediaTek BROM, Preloader, CDC, and MTP.
     */
    private fun findFastbootInterface(device: UsbDevice): UsbInterfaceMatch? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (isStrictFastbootInterface(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    intfClass = intf.interfaceClass,
                    intfSubclass = intf.interfaceSubclass,
                    intfProtocol = intf.interfaceProtocol
                )
            ) {
                val eps = findBulkEndpoints(intf)
                if (eps != null) return UsbInterfaceMatch(intf, eps.first, eps.second, null)
            }
        }

        // Fallback scan across alternate configurations on composite descriptors
        for (c in 0 until device.configurationCount) {
            val cfg = device.getConfiguration(c)
            for (i in 0 until cfg.interfaceCount) {
                val intf = cfg.getInterface(i)
                if (isStrictFastbootInterface(
                        vendorId = device.vendorId,
                        productId = device.productId,
                        intfClass = intf.interfaceClass,
                        intfSubclass = intf.interfaceSubclass,
                        intfProtocol = intf.interfaceProtocol
                    )
                ) {
                    val eps = findBulkEndpoints(intf)
                    if (eps != null) return UsbInterfaceMatch(intf, eps.first, eps.second, cfg)
                }
            }
        }
        return null
    }

    /**
     * Strict ADB interface identification matching Android ADB / Recovery / Recovery Sideload
     * (Class 255, Subclass 66, Protocol 1).
     */
    private fun findAdbInterface(device: UsbDevice): UsbInterfaceMatch? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (isStrictAdbInterface(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    intfClass = intf.interfaceClass,
                    intfSubclass = intf.interfaceSubclass,
                    intfProtocol = intf.interfaceProtocol
                )
            ) {
                val eps = findBulkEndpoints(intf)
                if (eps != null) return UsbInterfaceMatch(intf, eps.first, eps.second, null)
            }
        }

        for (c in 0 until device.configurationCount) {
            val cfg = device.getConfiguration(c)
            for (i in 0 until cfg.interfaceCount) {
                val intf = cfg.getInterface(i)
                if (isStrictAdbInterface(
                        vendorId = device.vendorId,
                        productId = device.productId,
                        intfClass = intf.interfaceClass,
                        intfSubclass = intf.interfaceSubclass,
                        intfProtocol = intf.interfaceProtocol
                    )
                ) {
                    val eps = findBulkEndpoints(intf)
                    if (eps != null) return UsbInterfaceMatch(intf, eps.first, eps.second, cfg)
                }
            }
        }
        return null
    }

    fun isFastbootDevice(device: UsbDevice): Boolean {
        return findFastbootInterface(device) != null
    }

    fun isAdbDevice(device: UsbDevice): Boolean {
        return findAdbInterface(device) != null
    }

    fun isSupportedUsbDevice(device: UsbDevice): Boolean {
        return findFastbootInterface(device) != null || findAdbInterface(device) != null
    }

    private fun findBulkEndpoints(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    inEndpoint = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    outEndpoint = ep
                }
            }
        }

        return if (inEndpoint != null && outEndpoint != null) {
            Pair(inEndpoint, outEndpoint)
        } else null
    }

    fun connectToUsbDevice(device: UsbDevice): Boolean {
        synchronized(lock) {
            val isFb = findFastbootInterface(device) != null
            val isAdb = !isFb && findAdbInterface(device) != null
            if (!isFb && !isAdb) return false

            val currentFb = _deviceFlow.value as? FastbootUsbDevice
            if (isFb && currentFb != null && currentFb.isConnected && currentFb.usbDevice.deviceId == device.deviceId) {
                return true
            }
            val currentAdb = _adbDeviceFlow.value
            if (isAdb && currentAdb != null && currentAdb.isConnected && currentAdb.usbDevice.deviceId == device.deviceId) {
                return true
            }
            if (connectionProbeJob?.isActive == true) {
                if (pendingProbeDeviceId == device.deviceId) {
                    return true
                }
                // Cancel stale probe (e.g. Recovery adbd auth timeout) when a new USB deviceId enumerates
                connectionProbeJob?.cancel()
                connectionProbeJob = null
                _adbAwaitingAuthFlow.value = false
            }

            pendingProbeDeviceId = device.deviceId
            connectionProbeJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (isFb) {
                        connectAndVerifyDevice(device)
                    } else {
                        connectAndVerifyAdbDevice(device)
                    }
                } finally {
                    if (pendingProbeDeviceId == device.deviceId) {
                        pendingProbeDeviceId = null
                    }
                }
            }
            return true
        }
    }

    private suspend fun connectAndVerifyDevice(
        device: UsbDevice,
        keepReconnectingOnFail: Boolean = false
    ): FastbootUsbDevice? {
        val match = findFastbootInterface(device) ?: return null
        val intf = match.usbInterface

        var connection = usbManager.openDevice(device)
        if (connection == null) {
            delay(150)
            connection = usbManager.openDevice(device)
        }
        if (connection == null) {
            retryCooldownUntil[device.deviceId] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            logDiag("Note: Cannot open USB device (check OTG permission or replug)")
            return null
        }

        if (match.configurationToSelect != null) {
            try {
                connection.setConfiguration(match.configurationToSelect)
            } catch (_: Exception) {}
        }

        var claimed = connection.claimInterface(intf, true)
        if (!claimed) {
            delay(150)
            claimed = connection.claimInterface(intf, true)
        }
        if (!claimed) {
            try { connection.close() } catch (_: Exception) {}
            retryCooldownUntil[device.deviceId] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            logDiag("Note: USB interface busy — close Termux or other USB app and replug OTG")
            return null
        }

        val fastbootUsb = FastbootUsbDevice(
            usbDevice = device,
            connection = connection,
            usbInterface = intf,
            inEndpoint = match.inEndpoint,
            outEndpoint = match.outEndpoint
        )

        var info = fastbootUsb.fetchDeviceInfo()
        if (!info.isVerifiedFastboot) {
            delay(250)
            info = fastbootUsb.fetchDeviceInfo()
        }
        if (!info.isVerifiedFastboot) {
            delay(400)
            info = fastbootUsb.fetchDeviceInfo()
        }

        if (!info.isVerifiedFastboot || !info.mode.isBootloaderOrFastbootd) {
            fastbootUsb.close()
            retryCooldownUntil[device.deviceId] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            if (!keepReconnectingOnFail) {
                logDiag("Note: Fastboot handshake timed out — retrying automatically")
                _deviceFlow.value = null
                _adbDeviceFlow.value?.close()
                _adbDeviceFlow.value = null
                _adbAwaitingAuthFlow.value = false
                _deviceInfoFlow.value = null
                _modeFlow.value = FastbootMode.DISCONNECTED
            }
            return null
        }

        synchronized(lock) {
            retryCooldownUntil.remove(device.deviceId)
            permissionCooldownUntil.remove(device.deviceId)
            _adbAwaitingAuthFlow.value = false
            _adbDeviceFlow.value?.close()
            _adbDeviceFlow.value = null
            _deviceFlow.value?.takeIf { it !== fastbootUsb }?.close()
            _deviceFlow.value = fastbootUsb
            _deviceInfoFlow.value = info
            _modeFlow.value = info.mode
        }
        return fastbootUsb
    }

    private suspend fun connectAndVerifyAdbDevice(
        device: UsbDevice,
        keepReconnectingOnFail: Boolean = false
    ): AdbUsbDevice? {
        val match = findAdbInterface(device) ?: return null
        val intf = match.usbInterface

        var connection = usbManager.openDevice(device)
        if (connection == null) {
            delay(150)
            connection = usbManager.openDevice(device)
        }
        if (connection == null) {
            retryCooldownUntil[device.deviceId] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            logDiag("Note: Cannot open ADB USB device (check OTG permission or replug)")
            return null
        }

        if (match.configurationToSelect != null) {
            try {
                connection.setConfiguration(match.configurationToSelect)
            } catch (_: Exception) {}
        }

        var claimed = connection.claimInterface(intf, true)
        if (!claimed) {
            delay(150)
            claimed = connection.claimInterface(intf, true)
        }
        if (!claimed) {
            try { connection.close() } catch (_: Exception) {}
            retryCooldownUntil[device.deviceId] = System.currentTimeMillis() + RETRY_COOLDOWN_MS
            logDiag("Note: ADB USB interface busy — close other USB apps and replug OTG")
            return null
        }

        val keyDir = File(context.filesDir, "adb_keys")
        val adbUsb = AdbUsbDevice(
            usbDevice = device,
            connection = connection,
            usbInterface = intf,
            inEndpoint = match.inEndpoint,
            outEndpoint = match.outEndpoint,
            keyStorageDir = keyDir,
            isDevicePresent = {
                try {
                    usbManager.deviceList?.values?.any { it.deviceId == device.deviceId } == true
                } catch (_: Exception) {
                    false
                }
            }
        )

        val waitMs = if (keepReconnectingOnFail) 6_000L else 8_000L
        val banner = adbUsb.connectHandshake(waitForUserAuthMs = waitMs) { awaiting ->
            _adbAwaitingAuthFlow.value = awaiting
            if (awaiting && !keepReconnectingOnFail) {
                logDiag("Note: Allow USB Debugging in OS, or open 'Apply from ADB / Sideload' in Recovery", force = true)
            }
        }

        if (banner == null) {
            val authRequested = adbUsb.lastAuthRequestedByTarget
            adbUsb.close()
            _adbAwaitingAuthFlow.value = false
            retryCooldownUntil[device.deviceId] = System.currentTimeMillis() + if (authRequested) 1200L else RETRY_COOLDOWN_MS
            if (!keepReconnectingOnFail) {
                if (authRequested) {
                    logDiag("Note: Target recovery/adbd requested RSA key — open 'Apply from ADB' on device or authorize once in OS", force = true)
                } else {
                    logDiag("Note: ADB handshake timed out — check Sideload/Recovery mode and tap Status card")
                }
                _deviceFlow.value = null
                _adbDeviceFlow.value = null
                _deviceInfoFlow.value = null
                _modeFlow.value = FastbootMode.DISCONNECTED
            }
            return null
        }

        val info = DeviceInfo(
            productName = banner.productName,
            bootloaderVersion = when (banner.mode) {
                FastbootMode.ADB_SIDELOAD -> "Recovery Sideload"
                FastbootMode.ADB_RECOVERY -> "Recovery ADB"
                else -> "ADB Authorized"
            },
            currentSlot = "unknown",
            slotCount = 0,
            isUserspace = false,
            isAdbMode = true,
            maxDownloadSize = 0L,
            serialNumber = "USB",
            isVerifiedFastboot = false,
            adbSubMode = banner.mode,
            isAdbAuthorized = true
        )

        synchronized(lock) {
            retryCooldownUntil.remove(device.deviceId)
            permissionCooldownUntil.remove(device.deviceId)
            _adbAwaitingAuthFlow.value = false
            _deviceFlow.value?.close()
            _deviceFlow.value = null
            _adbDeviceFlow.value?.takeIf { it !== adbUsb }?.close()
            _adbDeviceFlow.value = adbUsb
            _deviceInfoFlow.value = info
            _modeFlow.value = banner.mode
        }
        val modeLabel = when (banner.mode) {
            FastbootMode.ADB_SIDELOAD -> "Recovery ADB Sideload"
            FastbootMode.ADB_RECOVERY -> "ADB Recovery"
            else -> "ADB"
        }
        logDiag("✓ Connected ($modeLabel): ${banner.productName}", force = true)
        return adbUsb
    }

    fun disconnect(keepReconnectingState: Boolean = false) {
        synchronized(lock) {
            connectionProbeJob?.cancel()
            connectionProbeJob = null
            pendingProbeDeviceId = null
            try {
                _deviceFlow.value?.close()
            } catch (_: Exception) {}
            try {
                _adbDeviceFlow.value?.close()
            } catch (_: Exception) {}

            _deviceFlow.value = null
            _adbDeviceFlow.value = null
            _adbAwaitingAuthFlow.value = false
            _deviceInfoFlow.value = null
            if (!keepReconnectingState) {
                _modeFlow.value = FastbootMode.DISCONNECTED
            }
        }
    }

    suspend fun refreshDeviceInfo(): DeviceInfo? = withContext(Dispatchers.IO) {
        val current = _deviceFlow.value ?: return@withContext _deviceInfoFlow.value
        try {
            val info = current.fetchDeviceInfo()
            if (info.isVerifiedFastboot && info.mode.isBootloaderOrFastbootd) {
                _deviceInfoFlow.value = info
                _modeFlow.value = info.mode
                info
            } else {
                disconnect()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing device info: ${e.message}")
            null
        }
    }

    suspend fun waitForReconnection(
        timeoutMs: Long = FastbootConstants.RECONNECT_TIMEOUT_MS,
        minResettleDelayMs: Long = 3500L,
        expectedMode: FastbootMode? = null,
        onStatusUpdate: (String) -> Unit = {}
    ): FastbootDevice? = withContext(Dispatchers.IO) {
        val previousDeviceId = (_deviceFlow.value as? FastbootUsbDevice)?.usbDevice?.deviceId
            ?: _adbDeviceFlow.value?.usbDevice?.deviceId
        _modeFlow.value = FastbootMode.RECONNECTING
        disconnect(keepReconnectingState = true)

        val startTime = System.currentTimeMillis()
        permissionCooldownUntil.clear()
        retryCooldownUntil.clear()

        // Allow previous bootloader/recovery USB session to drop cleanly before grabbing new enumeration
        while (System.currentTimeMillis() - startTime < minResettleDelayMs) {
            val stillPresent = previousDeviceId != null && try {
                usbManager.deviceList?.values?.any { it.deviceId == previousDeviceId } == true
            } catch (_: Exception) { false }
            if (!stillPresent && (System.currentTimeMillis() - startTime) >= 2200L) {
                break
            }
            delay(200)
        }

        var lastNotifiedSec = -1
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            if (elapsedSec != lastNotifiedSec) {
                lastNotifiedSec = elapsedSec
                onStatusUpdate("Waiting for device (${elapsedSec}s)...")
            }

            try {
                val deviceList = usbManager.deviceList
                if (deviceList != null) {
                    for (device in deviceList.values) {
                        if (isFastbootDevice(device)) {
                            if (usbManager.hasPermission(device)) {
                                val verified = connectAndVerifyDevice(device, keepReconnectingOnFail = true)
                                if (verified != null) {
                                    var info = _deviceInfoFlow.value ?: verified.fetchDeviceInfo()
                                    // If expecting FASTBOOTD / BOOTLOADER transition, poll briefly until target state settles
                                    if (expectedMode != null && info.mode != expectedMode) {
                                        val settleDeadline = minOf(timeoutMs + startTime, System.currentTimeMillis() + 10000L)
                                        while (System.currentTimeMillis() < settleDeadline && verified.isConnected) {
                                            delay(450)
                                            info = verified.fetchDeviceInfo()
                                            if (info.isVerifiedFastboot && info.mode == expectedMode) {
                                                _deviceInfoFlow.value = info
                                                _modeFlow.value = info.mode
                                                break
                                            }
                                        }
                                    }
                                    return@withContext verified
                                }
                            } else {
                                requestUsbPermission(device, force = false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Polling check exception: ${e.message}")
            }
            delay(250)
        }

        _modeFlow.value = FastbootMode.DISCONNECTED
        null
    }

    suspend fun waitForAnyReconnection(
        timeoutMs: Long = FastbootConstants.RECONNECT_TIMEOUT_MS,
        minResettleDelayMs: Long = 2800L,
        expectedMode: FastbootMode? = null,
        onStatusUpdate: (String) -> Unit = {}
    ): FastbootMode = withContext(Dispatchers.IO) {
        val previousDeviceId = (_deviceFlow.value as? FastbootUsbDevice)?.usbDevice?.deviceId
            ?: _adbDeviceFlow.value?.usbDevice?.deviceId
        _modeFlow.value = FastbootMode.RECONNECTING
        disconnect(keepReconnectingState = true)

        val startTime = System.currentTimeMillis()
        permissionCooldownUntil.clear()
        retryCooldownUntil.clear()

        while (System.currentTimeMillis() - startTime < minResettleDelayMs) {
            val stillPresent = previousDeviceId != null && try {
                usbManager.deviceList?.values?.any { it.deviceId == previousDeviceId } == true
            } catch (_: Exception) { false }
            if (!stillPresent && (System.currentTimeMillis() - startTime) >= 1800L) {
                break
            }
            delay(200)
        }

        var lastNotifiedSec = -1
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            if (elapsedSec != lastNotifiedSec) {
                lastNotifiedSec = elapsedSec
                onStatusUpdate("Waiting for device (${elapsedSec}s)...")
            }

            try {
                val deviceList = usbManager.deviceList
                if (deviceList != null) {
                    val preferAdb = expectedMode?.isAdbConnected == true
                    val preferFastboot = expectedMode?.isBootloaderOrFastbootd == true

                    for (device in deviceList.values) {
                        if (!preferAdb && isFastbootDevice(device)) {
                            if (usbManager.hasPermission(device)) {
                                val verified = connectAndVerifyDevice(device, keepReconnectingOnFail = true)
                                if (verified != null) {
                                    var info = _deviceInfoFlow.value ?: verified.fetchDeviceInfo()
                                    if (expectedMode != null && expectedMode.isBootloaderOrFastbootd && info.mode != expectedMode) {
                                        val settleDeadline = minOf(timeoutMs + startTime, System.currentTimeMillis() + 8000L)
                                        while (System.currentTimeMillis() < settleDeadline && verified.isConnected) {
                                            delay(400)
                                            info = verified.fetchDeviceInfo()
                                            if (info.isVerifiedFastboot && info.mode == expectedMode) {
                                                _deviceInfoFlow.value = info
                                                _modeFlow.value = info.mode
                                                break
                                            }
                                        }
                                    }
                                    return@withContext _modeFlow.value
                                }
                            } else {
                                requestUsbPermission(device, force = false)
                            }
                        } else if (!preferFastboot && isAdbDevice(device)) {
                            if (usbManager.hasPermission(device)) {
                                val verified = connectAndVerifyAdbDevice(device, keepReconnectingOnFail = true)
                                if (verified != null) {
                                    return@withContext verified.detectedMode
                                }
                            } else {
                                requestUsbPermission(device, force = false)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect poll error: ${e.message}")
            }
            delay(250)
        }

        _modeFlow.value = FastbootMode.DISCONNECTED
        FastbootMode.DISCONNECTED
    }
}
