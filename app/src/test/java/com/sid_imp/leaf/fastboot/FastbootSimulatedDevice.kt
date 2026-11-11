package com.sid_imp.leaf.fastboot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.InputStream

class FastbootSimulatedDevice : FastbootDevice {

    private var connected = true
    private var isUserspaceMode = false
    private var activeSlot = "a"
    private var productName = "Universal Android Device"
    private var bootloaderVersion = "UNIVERSAL-BOOTLOADER"

    override val isConnected: Boolean
        get() = connected

    override val currentMode: FastbootMode
        get() = if (!connected) FastbootMode.DISCONNECTED
                else if (isUserspaceMode) FastbootMode.FASTBOOTD
                else FastbootMode.BOOTLOADER

    override val serialNumber: String = "SIM-PONG-007"

    fun simulateRebootToFastbootd() {
        isUserspaceMode = true
    }

    fun simulateRebootToBootloader() {
        isUserspaceMode = false
    }

    override suspend fun sendCommand(
        command: String,
        timeoutMs: Int,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        if (!connected) return@withContext FastbootResponse(ResponseType.ERROR, "Device disconnected")

        val cmd = command.trim()
        delay(120) // Realistic USB command latency

        when {
            cmd.startsWith(FastbootConstants.CMD_GETVAR) -> {
                val varName = cmd.removePrefix(FastbootConstants.CMD_GETVAR)
                val value = when (varName) {
                    "product" -> productName
                    "version" -> "0.5"
                    "version-bootloader" -> bootloaderVersion
                    "current-slot" -> activeSlot
                    "slot-count" -> "2"
                    "is-userspace" -> if (isUserspaceMode) "yes" else "no"
                    "max-download-size" -> "0x20000000" // 512 MB
                    "unlocked" -> "yes"
                    "secure" -> "no"
                    else -> ""
                }
                FastbootResponse(ResponseType.OKAY, value)
            }

            cmd.startsWith(FastbootConstants.CMD_DOWNLOAD) -> {
                val hexSize = cmd.removePrefix(FastbootConstants.CMD_DOWNLOAD)
                val size = hexSize.toLongOrNull(16) ?: 0L
                FastbootResponse(ResponseType.DATA, hexSize, size)
            }

            cmd.startsWith(FastbootConstants.CMD_FLASH) -> {
                val partition = cmd.removePrefix(FastbootConstants.CMD_FLASH)
                infoCallback("Sending '$partition' (OKAY)...")
                delay(150)
                infoCallback("Writing '$partition'...")
                delay(300)
                FastbootResponse(ResponseType.OKAY, "Finished. Total time: 0.450s")
            }

            cmd.startsWith(FastbootConstants.CMD_ERASE) -> {
                val partition = cmd.removePrefix(FastbootConstants.CMD_ERASE)
                infoCallback("Erasing '$partition'...")
                delay(200)
                FastbootResponse(ResponseType.OKAY, "Finished. Total time: 0.210s")
            }

            cmd.startsWith(FastbootConstants.CMD_SET_ACTIVE) -> {
                val slot = cmd.removePrefix(FastbootConstants.CMD_SET_ACTIVE)
                activeSlot = slot
                infoCallback("Setting current slot to '$slot'...")
                delay(100)
                FastbootResponse(ResponseType.OKAY, "Slot switched to $slot")
            }

            cmd == FastbootConstants.CMD_REBOOT_FASTBOOT || cmd == "reboot fastboot" -> {
                infoCallback("Rebooting device into userspace fastboot (fastbootd)...")
                isUserspaceMode = true
                delay(300)
                FastbootResponse(ResponseType.OKAY, "Rebooting")
            }

            cmd == FastbootConstants.CMD_REBOOT_BOOTLOADER || cmd == "reboot bootloader" -> {
                infoCallback("Rebooting device into bootloader...")
                isUserspaceMode = false
                delay(300)
                FastbootResponse(ResponseType.OKAY, "Rebooting")
            }

            cmd == FastbootConstants.CMD_REBOOT || cmd == "reboot" -> {
                infoCallback("Rebooting target device to system...")
                delay(300)
                FastbootResponse(ResponseType.OKAY, "Rebooting")
            }

            cmd.startsWith("delete-logical-partition") -> {
                val part = cmd.removePrefix("delete-logical-partition").trim()
                infoCallback("Deleting logical partition '$part'...")
                delay(150)
                FastbootResponse(ResponseType.OKAY, "Partition deleted")
            }

            cmd.startsWith("create-logical-partition") -> {
                val args = cmd.removePrefix("create-logical-partition").trim()
                infoCallback("Creating logical partition '$args'...")
                delay(150)
                FastbootResponse(ResponseType.OKAY, "Partition created")
            }

            cmd.startsWith("resize-logical-partition") -> {
                val args = cmd.removePrefix("resize-logical-partition").trim()
                infoCallback("Resizing logical partition '$args'...")
                delay(150)
                FastbootResponse(ResponseType.OKAY, "Partition resized")
            }

            else -> {
                infoCallback("Executing raw fastboot command: $cmd")
                delay(150)
                FastbootResponse(ResponseType.OKAY, "Command succeeded")
            }
        }
    }

    override suspend fun downloadData(
        stream: InputStream,
        totalBytes: Long,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        val buffer = ByteArray(FastbootConstants.BULK_CHUNK_SIZE)
        var sent = 0L

        // Read stream and simulate upload chunks
        while (sent < totalBytes) {
            val toRead = minOf(buffer.size.toLong(), totalBytes - sent).toInt()
            val read = stream.read(buffer, 0, toRead)
            if (read <= 0) {
                // If stream is empty or mock, simulate fake progress
                sent += toRead
            } else {
                sent += read
            }
            progressCallback(sent, totalBytes)
            delay(15) // Simulate realistic high-speed USB OTG transfer
        }

        FastbootResponse(ResponseType.OKAY, "Download complete")
    }

    override suspend fun flashPartition(
        partition: String,
        stream: InputStream,
        totalBytes: Long,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        infoCallback("Sending '$partition' (${formatBytes(totalBytes)})...")
        val dlResp = downloadData(stream, totalBytes, progressCallback)
        if (!dlResp.isSuccess) return@withContext dlResp

        infoCallback("Writing '$partition'...")
        delay(400)
        FastbootResponse(ResponseType.OKAY, "Writing '$partition' OKAY [0.392s]")
    }

    override suspend fun flashPartitionChunked(
        partition: String,
        totalBytes: Long,
        openStream: () -> InputStream?,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        val stream = openStream() ?: return@withContext FastbootResponse(ResponseType.ERROR, "Cannot open stream for $partition")
        try {
            flashPartition(partition, stream, totalBytes, progressCallback, infoCallback)
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    override suspend fun erasePartition(
        partition: String,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        infoCallback("Erasing '$partition'...")
        delay(250)
        FastbootResponse(ResponseType.OKAY, "Erasing '$partition' OKAY [0.150s]")
    }

    override suspend fun reboot(
        target: String,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        val cmd = when (target.lowercase().trim()) {
            "fastboot", "fastbootd" -> FastbootConstants.CMD_REBOOT_FASTBOOT
            "bootloader" -> FastbootConstants.CMD_REBOOT_BOOTLOADER
            "recovery" -> FastbootConstants.CMD_REBOOT_RECOVERY
            else -> FastbootConstants.CMD_REBOOT
        }
        sendCommand(cmd, infoCallback = infoCallback)
    }

    override suspend fun setActiveSlot(slot: String): FastbootResponse = withContext(Dispatchers.IO) {
        sendCommand("${FastbootConstants.CMD_SET_ACTIVE}$slot")
    }

    override suspend fun getVariable(name: String): String? = withContext(Dispatchers.IO) {
        val resp = sendCommand("${FastbootConstants.CMD_GETVAR}$name")
        if (resp.isSuccess) resp.payload else null
    }

    override suspend fun fetchDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        DeviceInfo(
            productName = productName,
            bootloaderVersion = bootloaderVersion,
            currentSlot = activeSlot,
            slotCount = 2,
            isUserspace = isUserspaceMode,
            maxDownloadSize = 512 * 1024 * 1024L,
            serialNumber = serialNumber,
            isVerifiedFastboot = true
        )
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format("%.2f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    override fun close() {
        connected = false
    }
}
