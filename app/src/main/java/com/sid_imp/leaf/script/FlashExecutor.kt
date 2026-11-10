package com.sid_imp.leaf.script

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.sid_imp.leaf.fastboot.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

enum class FlashExecutionState {
    IDLE,
    RUNNING,
    WAITING_FOR_RECONNECT,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ExecutionProgress(
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentTaskName: String = "",
    val stepPercent: Int = 0,
    val overallPercent: Int = 0,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val transferSpeedMbPerSec: Double = 0.0,
    val state: FlashExecutionState = FlashExecutionState.IDLE,
    val statusMessage: String = "Ready"
)

class FlashExecutor(
    private val context: Context,
    private val usbManager: FastbootUsbManager
) {

    private data class ResolvedSource(
        val sizeBytes: Long,
        val openStream: () -> InputStream?
    )

    private val _progressFlow = MutableStateFlow(ExecutionProgress())
    val progressFlow: StateFlow<ExecutionProgress> = _progressFlow.asStateFlow()

    private val _logFlow = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _logFlow.asStateFlow()

    private val logLock = Any()
    private var executionJob: Job? = null
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false

    companion object {
        private const val MAX_LOG_LINES = 250
        private const val PROGRESS_EMIT_INTERVAL_MS = 65L
        private val KNOWN_LOGICAL_STEMS = setOf(
            "odm_dlkm", "odm", "vendor_dlkm", "product", "vendor",
            "system_dlkm", "system_ext", "system"
        )

        fun isLogicalPartitionTarget(target: String): Boolean {
            val clean = target.lowercase().removeSuffix("_a").removeSuffix("_b")
            return clean in KNOWN_LOGICAL_STEMS
        }
    }

    private val fileResolverMap = ConcurrentHashMap<String, Uri>()

    fun setFileMap(map: Map<String, Uri>) {
        fileResolverMap.clear()
        fileResolverMap.putAll(map)
    }

    fun addLog(message: String) {
        val clean = message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return
        synchronized(logLock) {
            val current = _logFlow.value
            if (current.lastOrNull() == clean) return
            val trimmed = if (current.size >= MAX_LOG_LINES) current.drop(current.size - (MAX_LOG_LINES - 1)) else current
            _logFlow.value = trimmed + clean
        }
    }

    fun clearLogs() {
        synchronized(logLock) {
            _logFlow.value = emptyList()
        }
    }

    fun resetProgress() {
        val currentState = _progressFlow.value.state
        if (currentState != FlashExecutionState.RUNNING &&
            currentState != FlashExecutionState.WAITING_FOR_RECONNECT &&
            currentState != FlashExecutionState.PAUSED) {
            _progressFlow.value = ExecutionProgress()
        }
    }

    fun cancel() {
        isCancelled = true
        executionJob?.cancel()
        _progressFlow.value = _progressFlow.value.copy(
            state = FlashExecutionState.CANCELLED,
            statusMessage = "Flash cancelled"
        )
        addLog("✗ Flash cancelled")
    }

    fun pause() {
        isPaused = true
        _progressFlow.value = _progressFlow.value.copy(
            state = FlashExecutionState.PAUSED,
            statusMessage = "Paused"
        )
        addLog("Flashing paused")
    }

    fun resume() {
        isPaused = false
        _progressFlow.value = _progressFlow.value.copy(
            state = FlashExecutionState.RUNNING,
            statusMessage = "Resuming..."
        )
        addLog("Flashing resumed")
    }

    fun executeTasks(
        tasks: List<FlashTask>,
        scope: CoroutineScope,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (tasks.isEmpty()) {
            addLog("No tasks queued")
            return
        }
        if (executionJob?.isActive == true) {
            return
        }

        isCancelled = false
        isPaused = false

        executionJob = scope.launch(Dispatchers.IO) {
            val initialDevice = usbManager.deviceFlow.value
            val initialMode = usbManager.modeFlow.value
            if (initialDevice == null || !initialDevice.isConnected || !initialMode.isBootloaderOrFastbootd) {
                addLog("✗ Bootloader or Fastbootd not detected")
                _progressFlow.value = _progressFlow.value.copy(
                    state = FlashExecutionState.FAILED,
                    statusMessage = "Bootloader or Fastbootd required"
                )
                onComplete(false)
                return@launch
            }
            var activeDevice: FastbootDevice = initialDevice

            val hasBootloaderPhaseFlash = tasks.any {
                it.phase == FlashingPhase.FIRMWARE_BOOTLOADER && it.type == TaskType.FLASH
            }
            if (initialMode == FastbootMode.FASTBOOTD && hasBootloaderPhaseFlash) {
                addLog("Rebooting Fastbootd -> Bootloader...")
                _progressFlow.value = _progressFlow.value.copy(
                    state = FlashExecutionState.WAITING_FOR_RECONNECT,
                    statusMessage = "Switching to Bootloader..."
                )
                activeDevice.reboot("bootloader")
                val blDev = usbManager.waitForReconnection(
                    expectedMode = FastbootMode.BOOTLOADER,
                    onStatusUpdate = { status ->
                        _progressFlow.value = _progressFlow.value.copy(statusMessage = status)
                    }
                )
                if (blDev == null) {
                    addLog("✗ Bootloader reconnect timed out")
                    _progressFlow.value = _progressFlow.value.copy(
                        state = FlashExecutionState.FAILED,
                        statusMessage = "Bootloader reconnect failed"
                    )
                    onComplete(false)
                    return@launch
                }
                activeDevice = blDev
                addLog("✓ Connected to Bootloader")
            }

            var liveSlotCache: String? = usbManager.deviceInfoFlow.value?.currentSlot
                ?.takeIf { it.equals("a", true) || it.equals("b", true) }
                ?.lowercase()

            val flashCount = FlashScriptParser.countUniquePartitions(tasks).takeIf { it > 0 } ?: tasks.size
            addLog("Flashing $flashCount partitions...")

            _progressFlow.value = ExecutionProgress(
                currentStep = 0,
                totalSteps = tasks.size,
                state = FlashExecutionState.RUNNING,
                statusMessage = "Starting flash..."
            )

            var success = true

            for (index in tasks.indices) {
                if (isCancelled) {
                    addLog("✗ Flash cancelled")
                    success = false
                    break
                }

                while (isPaused) {
                    delay(250)
                    if (isCancelled) break
                }
                if (isCancelled) {
                    success = false
                    break
                }

                val task = tasks[index]
                task.status = TaskStatus.RUNNING

                val stepNum = index + 1
                val baseOverallPercent = ((index * 100) / tasks.size).coerceIn(0, 99)

                _progressFlow.value = _progressFlow.value.copy(
                    currentStep = stepNum,
                    totalSteps = tasks.size,
                    currentTaskName = task.displayName,
                    stepPercent = 0,
                    overallPercent = baseOverallPercent,
                    transferSpeedMbPerSec = 0.0,
                    state = FlashExecutionState.RUNNING,
                    statusMessage = "${task.displayName} ($stepNum/${tasks.size})"
                )

                val startTime = System.currentTimeMillis()

                try {
                    when (task.type) {
                        TaskType.FLASH -> {
                            if (!activeDevice.isConnected) {
                                task.status = TaskStatus.FAILED
                                task.errorMessage = "Device disconnected"
                                addLog("✗ Fastboot device disconnected")
                                success = false
                                break
                            }

                            val isPreloader = task.partition.startsWith("preloader", ignoreCase = true) ||
                                              task.fileName.startsWith("preloader", ignoreCase = true)
                            val isSuperWipe = FlashScriptParser.isWipeSuperTask(task)

                            var initialTarget = if (isPreloader && task.partition.isBlank()) "preloader_a" else task.partition

                            val source = resolveFileSource(task.fileName, initialTarget, task.fileUri)
                                ?: resolveFileSource(task.fileName, task.partition, task.fileUri)
                            if (source == null) {
                                task.status = TaskStatus.FAILED
                                val err = "Missing image: ${task.fileName}"
                                task.errorMessage = err
                                addLog("✗ Missing image: ${task.fileName}")
                                success = false
                                break
                            }

                            if (isSuperWipe) {
                                addLog("Wiping super partition...")
                                _progressFlow.value = _progressFlow.value.copy(
                                    statusMessage = "Wiping super partition..."
                                )

                                activeDevice.sendCommand("snapshot-update:cancel")

                                val superStream = source.openStream()
                                if (superStream == null) {
                                    task.status = TaskStatus.FAILED
                                    task.errorMessage = "Cannot open super_empty stream"
                                    addLog("✗ Failed opening super_empty.img")
                                    success = false
                                    break
                                }

                                var lastSuperEmitMs = 0L
                                val dlResp = superStream.use { stream ->
                                    activeDevice.downloadData(
                                        stream = stream,
                                        totalBytes = source.sizeBytes,
                                        progressCallback = { sent, total ->
                                            val percent = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                                            val now = System.currentTimeMillis()
                                            if (percent >= 100 || sent >= total || now - lastSuperEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                                lastSuperEmitMs = now
                                                _progressFlow.value = _progressFlow.value.copy(
                                                    stepPercent = percent,
                                                    overallPercent = (((index * 100) + percent) / tasks.size).coerceIn(0, 99),
                                                    bytesTransferred = sent,
                                                    totalBytes = total
                                                )
                                            }
                                        }
                                    )
                                }

                                if (!dlResp.isSuccess) {
                                    task.status = TaskStatus.FAILED
                                    task.errorMessage = "Super upload failed: ${dlResp.payload}"
                                    addLog("✗ Wipe super failed: ${dlResp.payload}")
                                    success = false
                                    break
                                }

                                val wipeInfos = mutableListOf<String>()
                                var wipeResp = activeDevice.sendCommand("update-super:super:wipe") { info ->
                                    if (info.isNotBlank()) wipeInfos.add(info)
                                }
                                if (!wipeResp.isSuccess && !wipeResp.payload.contains("lock", ignoreCase = true)) {
                                    wipeResp = activeDevice.sendCommand("update-super:super") { info ->
                                        if (info.isNotBlank()) wipeInfos.add(info)
                                    }
                                }
                                if (!wipeResp.isSuccess && !wipeResp.payload.contains("lock", ignoreCase = true)) {
                                    wipeResp = activeDevice.sendCommand("flash:super") { info ->
                                        if (info.isNotBlank()) wipeInfos.add(info)
                                    }
                                }

                                if (!wipeResp.isSuccess && (wipeResp.payload.contains("lock", ignoreCase = true) || wipeResp.payload.contains("not allowed", ignoreCase = true))) {
                                    task.status = TaskStatus.FAILED
                                    task.errorMessage = wipeResp.payload
                                    addLog("✗ Super wipe blocked (${wipeResp.payload})")
                                    success = false
                                    break
                                }

                                task.status = TaskStatus.COMPLETED
                                if (wipeResp.isSuccess) {
                                    addLog("✓ Super partition wiped")
                                } else {
                                    addLog("Note: wipe-super (${wipeResp.payload})")
                                }
                                delay(120)
                                continue
                            }

                            val isLogical = task.phase == FlashingPhase.LOGICAL_DYNAMIC || isLogicalPartitionTarget(initialTarget)
                            // Per AOSP fastboot.cpp: resolve un-suffixed logical partitions to active slot in fastbootd
                            if (isLogical && !initialTarget.endsWith("_a") && !initialTarget.endsWith("_b")) {
                                val hasSlot = activeDevice.getVariable("has-slot:$initialTarget")?.equals("yes", ignoreCase = true) == true
                                if (hasSlot) {
                                    val resolvedSlot = liveSlotCache
                                        ?: activeDevice.getVariable("current-slot")?.lowercase()?.takeIf { it == "a" || it == "b" }
                                        ?: "a"
                                    liveSlotCache = resolvedSlot
                                    initialTarget = "${initialTarget}_$resolvedSlot"
                                }
                            }

                            addLog("Flashing $initialTarget...")

                            var targetPartitionSize = source.sizeBytes
                            if (isLogical) {
                                activeDevice.sendCommand("snapshot-update:cancel")

                                val headerBytes = ByteArray(28)
                                source.openStream()?.use { s ->
                                    var offset = 0
                                    while (offset < 28) {
                                        val r = s.read(headerBytes, offset, 28 - offset)
                                        if (r <= 0) break
                                        offset += r
                                    }
                                }
                                targetPartitionSize = SparseChunkHelper.computeTargetPartitionSize(headerBytes, source.sizeBytes)

                                val resizeResp = activeDevice.sendCommand("resize-logical-partition:$initialTarget:$targetPartitionSize")
                                if (!resizeResp.isSuccess) {
                                    val payload = resizeResp.payload.lowercase()
                                    if (payload.contains("lock") || payload.contains("not allowed")) {
                                        task.status = TaskStatus.FAILED
                                        task.errorMessage = resizeResp.payload
                                        addLog("✗ Failed $initialTarget: ${resizeResp.payload}")
                                        success = false
                                        break
                                    } else if (payload.contains("not found") ||
                                               payload.contains("does not exist") ||
                                               payload.contains("no such") ||
                                               payload.contains("unknown partition") ||
                                               payload.contains("failed to find")) {
                                        val createResp = activeDevice.sendCommand("create-logical-partition:$initialTarget:$targetPartitionSize")
                                        if (!createResp.isSuccess && (createResp.payload.contains("lock", ignoreCase = true) || createResp.payload.contains("not allowed", ignoreCase = true))) {
                                            task.status = TaskStatus.FAILED
                                            task.errorMessage = createResp.payload
                                            addLog("✗ Failed $initialTarget: ${createResp.payload}")
                                            success = false
                                            break
                                        }
                                    } else if (payload.contains("no space") || payload.contains("not enough space")) {
                                        val oppSlot = if (initialTarget.endsWith("_a")) "b" else "a"
                                        for (stem in listOf("product", "system_ext", "system", "vendor", "vendor_dlkm", "odm_dlkm", "odm")) {
                                            activeDevice.sendCommand("delete-logical-partition:${stem}_$oppSlot")
                                        }
                                        activeDevice.sendCommand("resize-logical-partition:$initialTarget:$targetPartitionSize")
                                    }
                                }
                            }

                            var lastBytes = 0L
                            var lastTime = System.currentTimeMillis()
                            var lastProgressEmitMs = 0L
                            var currentSpeed = 0.0
                            val flashInfos = mutableListOf<String>()

                            val effectiveOpenStream = FlashScriptParser.wrapStreamWithAvbPatch(
                                openStream = source.openStream,
                                disableVerity = task.disableVerity,
                                disableVerification = task.disableVerification
                            )

                            var resp = activeDevice.flashPartitionChunked(
                                partition = initialTarget,
                                totalBytes = source.sizeBytes,
                                openStream = effectiveOpenStream,
                                progressCallback = { sent, total ->
                                    val now = System.currentTimeMillis()
                                    val elapsedSec = (now - lastTime) / 1000.0
                                    if (elapsedSec >= 0.4) {
                                        val bytesDelta = sent - lastBytes
                                        if (bytesDelta >= 0) {
                                            currentSpeed = (bytesDelta / (1024.0 * 1024.0)) / elapsedSec
                                        }
                                        lastBytes = sent
                                        lastTime = now
                                    }

                                    val percent = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                                    if (percent >= 100 || sent >= total || now - lastProgressEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                        lastProgressEmitMs = now
                                        _progressFlow.value = _progressFlow.value.copy(
                                            stepPercent = percent,
                                            overallPercent = (((index * 100) + percent) / tasks.size).coerceIn(0, 99),
                                            bytesTransferred = sent,
                                            totalBytes = total,
                                            transferSpeedMbPerSec = currentSpeed
                                        )
                                    }
                                },
                                infoCallback = { info ->
                                    if (info.isNotBlank()) flashInfos.add(info.trim())
                                }
                            )

                            // Preloader multi-target retry
                            if (!resp.isSuccess && isPreloader) {
                                val altPartitions = listOf("preloader_a", "preloader", "preloader1", "preloader_raw").filter { it != initialTarget }
                                for (alt in altPartitions) {
                                    val altSource = resolveFileSource(task.fileName, alt, task.fileUri)
                                        ?: resolveFileSource(task.fileName, task.partition, task.fileUri)
                                    val altStream = altSource?.openStream?.invoke()
                                    if (altSource != null && altStream != null) {
                                        var lastAltEmitMs = 0L
                                        resp = altStream.use { stream ->
                                            activeDevice.flashPartition(
                                                partition = alt,
                                                stream = stream,
                                                totalBytes = altSource.sizeBytes,
                                                progressCallback = { sent, total ->
                                                    val percent = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                                                    val now = System.currentTimeMillis()
                                                    if (percent >= 100 || sent >= total || now - lastAltEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                                        lastAltEmitMs = now
                                                        _progressFlow.value = _progressFlow.value.copy(
                                                            stepPercent = percent,
                                                            overallPercent = (((index * 100) + percent) / tasks.size).coerceIn(0, 99),
                                                            bytesTransferred = sent,
                                                            totalBytes = total
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                        if (resp.isSuccess) break
                                    }
                                }
                            }

                            // Dynamic partition space recovery retry
                            val noSpaceFailure = !resp.isSuccess && isLogical && (
                                resp.payload.contains("no space", ignoreCase = true) ||
                                resp.payload.contains("not enough space", ignoreCase = true)
                            )
                            if (noSpaceFailure) {
                                val oppSlot = if (initialTarget.endsWith("_a")) "b" else "a"
                                for (stem in listOf("product", "system_ext", "system", "vendor", "vendor_dlkm", "odm_dlkm", "odm")) {
                                    activeDevice.sendCommand("delete-logical-partition:${stem}_$oppSlot")
                                }
                                activeDevice.sendCommand("resize-logical-partition:$initialTarget:$targetPartitionSize")
                                delay(180)
                            }

                            // Auto-retry ONCE on transient USB TIMEOUT/ERROR (or after space recovery),
                            // but NEVER blindly retry permanent protocol FAIL (e.g. Lock State, Invalid size).
                            val shouldRetry = !resp.isSuccess && !isPreloader && (
                                noSpaceFailure ||
                                resp.type == ResponseType.TIMEOUT ||
                                resp.type == ResponseType.ERROR
                            )
                            if (shouldRetry) {
                                val errDetail = resp.payload.ifBlank { resp.type.name }
                                addLog("Retrying $initialTarget ($errDetail)...")
                                delay(400)
                                var lastRetryEmitMs = 0L
                                resp = activeDevice.flashPartitionChunked(
                                    partition = initialTarget,
                                    totalBytes = source.sizeBytes,
                                    openStream = effectiveOpenStream,
                                    progressCallback = { sent, total ->
                                        val percent = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                                        val now = System.currentTimeMillis()
                                        if (percent >= 100 || sent >= total || now - lastRetryEmitMs >= PROGRESS_EMIT_INTERVAL_MS) {
                                            lastRetryEmitMs = now
                                            _progressFlow.value = _progressFlow.value.copy(
                                                stepPercent = percent,
                                                overallPercent = (((index * 100) + percent) / tasks.size).coerceIn(0, 99),
                                                bytesTransferred = sent,
                                                totalBytes = total
                                            )
                                        }
                                    },
                                    infoCallback = { info ->
                                        if (info.isNotBlank()) flashInfos.add(info.trim())
                                    }
                                )
                            }

                            if (!resp.isSuccess) {
                                if (isPreloader) {
                                    val payloadLower = resp.payload.lowercase()
                                    val isNotExist = payloadLower.contains("does not exist") ||
                                            payloadLower.contains("not found") ||
                                            payloadLower.contains("no such partition") ||
                                            payloadLower.contains("cannot determine") ||
                                            payloadLower.contains("partition table does not exist") ||
                                            payloadLower.contains("not supported")

                                    if (isNotExist) {
                                        task.status = TaskStatus.SKIPPED
                                        task.errorMessage = "Safe skip: ${resp.payload}"
                                        addLog("Skipping $initialTarget (not in GPT)")
                                        delay(80)
                                    } else {
                                        task.status = TaskStatus.FAILED
                                        task.errorMessage = resp.payload
                                        addLog("✗ Failed $initialTarget: ${resp.payload}")
                                        success = false
                                        break
                                    }
                                } else {
                                    val detail = resp.payload.ifBlank { flashInfos.lastOrNull() ?: resp.type.name }
                                    task.status = TaskStatus.FAILED
                                    task.errorMessage = detail
                                    addLog("✗ Failed $initialTarget: $detail")
                                    success = false
                                    break
                                }
                            } else {
                                task.status = TaskStatus.COMPLETED
                                addLog("✓ Flashed $initialTarget")
                                val settleDelay = if (source.sizeBytes >= 32 * 1024 * 1024L) 100L else 30L
                                delay(settleDelay)
                            }
                        }

                        TaskType.ERASE -> {
                            addLog("Erasing ${task.partition}...")
                            val resp = activeDevice.erasePartition(task.partition)
                            if (!resp.isSuccess) {
                                task.status = TaskStatus.FAILED
                                task.errorMessage = resp.payload
                                addLog("✗ Erase failed ${task.partition}: ${resp.payload}")
                                success = false
                                break
                            } else {
                                task.status = TaskStatus.COMPLETED
                                addLog("✓ Erased ${task.partition}")
                            }
                        }

                        TaskType.SET_ACTIVE -> {
                            val slot = task.slot ?: "a"
                            val resp = activeDevice.setActiveSlot(slot)
                            task.status = TaskStatus.COMPLETED
                            if (!resp.isSuccess) {
                                addLog("Note: Active slot ${slot.uppercase()} (${resp.payload})")
                            } else {
                                liveSlotCache = slot.removePrefix("_").lowercase()
                                addLog("✓ Active slot: ${slot.uppercase()}")
                                delay(80)
                            }
                        }

                        TaskType.REBOOT_FASTBOOT -> {
                            addLog("Rebooting to Fastbootd...")
                            activeDevice.reboot("fastboot")

                            _progressFlow.value = _progressFlow.value.copy(
                                state = FlashExecutionState.WAITING_FOR_RECONNECT,
                                statusMessage = "Waiting for Fastbootd..."
                            )

                            val reconnectedDevice = usbManager.waitForReconnection(
                                expectedMode = FastbootMode.FASTBOOTD,
                                onStatusUpdate = { status ->
                                    _progressFlow.value = _progressFlow.value.copy(statusMessage = status)
                                }
                            )

                            if (reconnectedDevice != null) {
                                val fdInfo = usbManager.deviceInfoFlow.value ?: reconnectedDevice.fetchDeviceInfo()
                                if (!fdInfo.isVerifiedFastboot || !fdInfo.isUserspace) {
                                    task.status = TaskStatus.FAILED
                                    task.errorMessage = "Device did not enter Fastbootd mode"
                                    addLog("✗ Device did not enter Fastbootd mode")
                                    success = false
                                    break
                                }
                                activeDevice = reconnectedDevice
                                fdInfo.currentSlot.takeIf { it.equals("a", true) || it.equals("b", true) }?.let {
                                    liveSlotCache = it.lowercase()
                                }
                                task.status = TaskStatus.COMPLETED
                                addLog("✓ Connected to Fastbootd")
                            } else {
                                task.status = TaskStatus.FAILED
                                task.errorMessage = "Timed out waiting for Fastbootd"
                                addLog("✗ Fastbootd reconnect timed out")
                                success = false
                                break
                            }
                        }

                        TaskType.REBOOT_BOOTLOADER -> {
                            val hasMoreTasksAfter = index < tasks.lastIndex && task.phase != FlashingPhase.FINALIZATION
                            addLog("Rebooting to Bootloader...")
                            activeDevice.reboot("bootloader")
                            if (!hasMoreTasksAfter) {
                                task.status = TaskStatus.COMPLETED
                            } else {
                                _progressFlow.value = _progressFlow.value.copy(
                                    state = FlashExecutionState.WAITING_FOR_RECONNECT,
                                    statusMessage = "Waiting for Bootloader..."
                                )

                                val reconnectedDevice = usbManager.waitForReconnection(
                                    expectedMode = FastbootMode.BOOTLOADER,
                                    onStatusUpdate = { status ->
                                        _progressFlow.value = _progressFlow.value.copy(statusMessage = status)
                                    }
                                )

                                if (reconnectedDevice != null) {
                                    activeDevice = reconnectedDevice
                                    task.status = TaskStatus.COMPLETED
                                    addLog("✓ Connected to Bootloader")
                                } else {
                                    task.status = TaskStatus.FAILED
                                    addLog("✗ Bootloader reconnect timed out")
                                    success = false
                                    break
                                }
                            }
                        }

                        TaskType.REBOOT_RECOVERY -> {
                            addLog("Rebooting to Recovery...")
                            activeDevice.reboot("recovery")
                            task.status = TaskStatus.COMPLETED
                        }

                        TaskType.REBOOT -> {
                            addLog("Rebooting to System...")
                            activeDevice.reboot("")
                            task.status = TaskStatus.COMPLETED
                        }

                        TaskType.WIPE -> {
                            addLog("Wiping user data...")
                            val udResp = activeDevice.erasePartition("userdata")
                            val metaResp = activeDevice.erasePartition("metadata")
                            val cacheResp = activeDevice.erasePartition("cache")

                            if (udResp.isSuccess || metaResp.isSuccess || cacheResp.isSuccess) {
                                addLog("✓ User data wiped")
                            } else {
                                addLog("Note: Data wipe requires Bootloader/Recovery mode")
                            }
                            task.status = TaskStatus.COMPLETED
                        }

                        TaskType.GETVAR -> {
                            val varVal = activeDevice.getVariable(task.partition)
                            addLog("${task.partition}: $varVal")
                            task.status = TaskStatus.COMPLETED
                        }

                        TaskType.RAW, TaskType.DELETE_LOGICAL, TaskType.CREATE_LOGICAL, TaskType.RESIZE_LOGICAL -> {
                            val resp = activeDevice.sendCommand(task.rawCommandLine)
                            if (resp.isSuccess) {
                                task.status = TaskStatus.COMPLETED
                                addLog("✓ ${task.displayName}")
                            } else {
                                task.status = TaskStatus.FAILED
                                task.errorMessage = resp.payload
                                addLog("✗ Failed: ${resp.payload}")
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    task.status = TaskStatus.FAILED
                    task.errorMessage = e.message
                    addLog("✗ Error: ${e.message}")
                    success = false
                    break
                }

                task.durationMs = System.currentTimeMillis() - startTime
            }

            val finalState = if (success) FlashExecutionState.COMPLETED else FlashExecutionState.FAILED
            val finalMsg = if (success) "Flashing completed!" else "Flashing failed"

            _progressFlow.value = _progressFlow.value.copy(
                overallPercent = if (success) 100 else _progressFlow.value.overallPercent,
                stepPercent = if (success) 100 else _progressFlow.value.stepPercent,
                state = finalState,
                statusMessage = finalMsg
            )

            addLog(if (success) "✓ Flash completed successfully!" else "✗ Flashing stopped due to error")
            onComplete(success)
        }
    }

    private fun resolveUriMetadataSize(uri: Uri): Long {
        try {
            if (uri.scheme == "file" && uri.path != null) {
                return File(uri.path!!).takeIf { it.exists() }?.length() ?: 0L
            }
            val pfdSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize
            } ?: -1L
            if (pfdSize > 0L) return pfdSize

            // Fallback for custom ROM SAF providers that report statSize = -1
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val colSize = cursor.getLong(idx)
                        if (colSize > 0L) return colSize
                    }
                }
            }
        } catch (_: Exception) {}
        return 0L
    }

    private fun openStreamFromUri(uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "file" && uri.path != null) {
                val f = File(uri.path!!)
                if (f.exists()) f.inputStream() else null
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveFileSource(fileName: String, partition: String, fileUri: Uri?): ResolvedSource? {
        // 1. Direct Uri if provided on task
        if (fileUri != null) {
            val size = resolveUriMetadataSize(fileUri)
            if (size > 0) {
                return ResolvedSource(size) { openStreamFromUri(fileUri) }
            }
        }

        // 2. Lookup in fileResolverMap with candidate keys
        val cleanPart = partition.lowercase().removeSuffix("_a").removeSuffix("_b")
        val cleanFile = fileName.lowercase().removeSuffix(".img").removeSuffix(".bin").removeSuffix(".raw")
        val candidateKeys = linkedSetOf(
            fileName,
            fileName.lowercase(),
            "$fileName.img",
            "${fileName.lowercase()}.img",
            cleanFile,
            "$cleanFile.img",
            "${cleanFile}_a.img",
            "${cleanFile}_b.img",
            partition,
            partition.lowercase(),
            "${partition}.img",
            "${partition.lowercase()}.img",
            cleanPart,
            "${cleanPart}.img",
            "${cleanPart}.bin",
            "${cleanPart}.raw",
            "${cleanPart}_raw.img",
            "${cleanPart}_1.img",
            "${cleanPart}_2.img",
            "${cleanPart}_a.img",
            "${cleanPart}_b.img",
            "${partition}_a.img",
            "${partition}_b.img"
        )

        for (k in candidateKeys) {
            val uri = fileResolverMap[k]
            if (uri != null) {
                val size = resolveUriMetadataSize(uri)
                if (size > 0) {
                    return ResolvedSource(size) { openStreamFromUri(uri) }
                }
            }
        }

        // 3. Search extracted local directory recursively (leaf_extracted_rom)
        val extractedDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "leaf_extracted_rom")
        if (extractedDir.exists()) {
            val matchingFile = extractedDir.walkTopDown().firstOrNull { f ->
                if (!f.isFile || f.length() <= 0L) return@firstOrNull false
                val fn = f.name.lowercase()
                val fnNoExt = f.nameWithoutExtension.lowercase()
                fn.equals(fileName, ignoreCase = true) ||
                fn.equals("${fileName}.img", ignoreCase = true) ||
                fnNoExt == cleanFile ||
                fn.equals("${partition}.img", ignoreCase = true) ||
                fn.equals("${cleanPart}.img", ignoreCase = true) ||
                fn.equals("${cleanPart}.bin", ignoreCase = true) ||
                fnNoExt == cleanPart ||
                fnNoExt == "${cleanPart}_a" ||
                fn.startsWith("${cleanPart}.") ||
                fn.startsWith("${cleanPart}_")
            }
            if (matchingFile != null) {
                val size = matchingFile.length()
                return ResolvedSource(size) { matchingFile.inputStream() }
            }
        }

        return null
    }
}
