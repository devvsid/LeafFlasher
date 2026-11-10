package com.sid_imp.leaf.fastboot

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class FastbootUsbDevice(
    val usbDevice: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) : FastbootDevice {

    companion object {
        /**
         * Adaptive USB bulk packet fallback schedule (`16 KB -> 4 KB -> endpoint maxPacketSize`),
         * allowing strict/custom-kernel Qualcomm xHCI host controllers to step down cleanly on OTG errors
         * without sending raw `CLEAR_FEATURE(ENDPOINT_HALT)` packets that desync DATA0/DATA1 toggles.
         */
        fun nextFallbackChunkSize(currentSize: Int, maxPacketSize: Int): Int? {
            val minChunk = maxOf(maxPacketSize, 512)
            return when {
                currentSize > 4096 -> 4096
                currentSize > minChunk -> minChunk
                else -> null
            }
        }
    }

    private val mutex = Mutex()

    @Volatile
    private var connected: Boolean = true

    @Volatile
    private var activeBulkChunkSize: Int = FastbootConstants.BULK_CHUNK_SIZE

    @Volatile
    private var lastUsbFailureDetail: String = ""

    private var outScratchBuffer: ByteArray? = null

    override val isConnected: Boolean
        get() = connected

    private var cachedDeviceInfo: DeviceInfo? = null

    override val currentMode: FastbootMode
        get() {
            if (!connected) return FastbootMode.DISCONNECTED
            return cachedDeviceInfo?.mode ?: FastbootMode.DISCONNECTED
        }

    override val serialNumber: String
        get() = try {
            val s = connection.serial
            if (!s.isNullOrBlank()) s else (usbDevice.serialNumber ?: "Device")
        } catch (_: Exception) {
            try {
                usbDevice.serialNumber ?: "Device"
            } catch (_: Exception) {
                "Device"
            }
        }

    /**
     * Performs a 4-arg zero-offset `bulkTransfer` (copying into an internal scratch buffer only
     * when `offset > 0`), avoiding 5-arg JNI offset bugs across custom ROMs and kernels.
     */
    private fun transferBulkOut(bytes: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (offset == 0) {
            return connection.bulkTransfer(outEndpoint, bytes, length, timeoutMs)
        }
        var scratch = outScratchBuffer
        if (scratch == null || scratch.size < length) {
            scratch = ByteArray(maxOf(length, FastbootConstants.BULK_CHUNK_SIZE))
            outScratchBuffer = scratch
        }
        System.arraycopy(bytes, offset, scratch, 0, length)
        return connection.bulkTransfer(outEndpoint, scratch, length, timeoutMs)
    }

    /**
     * Writes a short control/command packet cleanly with one settle retry. Never calls raw
     * `CLEAR_FEATURE(ENDPOINT_HALT)` via `controlTransfer`, which resets device DATA0 without
     * resetting Linux xHCI host toggle (`USBDEVFS_CLEAR_HALT`) and corrupts the USB pipe.
     */
    private fun sendCommandPacket(cmdBytes: ByteArray, timeoutMs: Int): Int {
        var sent = transferBulkOut(cmdBytes, 0, cmdBytes.size, timeoutMs)
        if (sent < 0 && connected) {
            try {
                Thread.sleep(15)
            } catch (_: InterruptedException) {}
            sent = transferBulkOut(cmdBytes, 0, cmdBytes.size, timeoutMs)
        }
        if (sent < 0) {
            lastUsbFailureDetail = "cmd bulkTransfer=$sent (len=${cmdBytes.size})"
        }
        return sent
    }

    /**
     * Reads up to [maxBytes] into [dest] starting at [offset], looping over short InputStream reads
     * so every USB packet transfer is packed up to full block alignment (preventing premature short packets).
     */
    private fun readFullyUpTo(stream: InputStream, dest: ByteArray, offset: Int, maxBytes: Int): Int {
        var total = 0
        while (total < maxBytes) {
            val r = stream.read(dest, offset + total, maxBytes - total)
            if (r <= 0) break
            total += r
        }
        return total
    }

    /**
     * Transmits [length] bytes from [buffer] starting at [startOffset] over [outEndpoint]
     * using adaptive chunks (starting at AOSP 16 KB `MAX_USBFS_BUFFER_SIZE`) with automatic
     * chunk downgrade + short settle retry on OTG write failures.
     */
    private fun writeBulkBuffer(buffer: ByteArray, startOffset: Int, length: Int): Boolean {
        var pos = 0
        var retries = 0
        while (pos < length && connected) {
            val chunkLen = minOf(activeBulkChunkSize, length - pos)
            val written = transferBulkOut(
                buffer,
                startOffset + pos,
                chunkLen,
                FastbootConstants.BULK_WRITE_TIMEOUT_MS
            )
            if (written > 0) {
                pos += written
                retries = 0
                continue
            }

            val nextChunk = nextFallbackChunkSize(activeBulkChunkSize, outEndpoint.maxPacketSize)
            if (nextChunk != null) {
                activeBulkChunkSize = nextChunk
                try {
                    Thread.sleep(15)
                } catch (_: InterruptedException) {}
                continue
            }

            if (retries < 1) {
                retries++
                try {
                    Thread.sleep(40)
                } catch (_: InterruptedException) {}
                continue
            }

            lastUsbFailureDetail = "bulkTransfer=$written at $pos/$length (chunk=$activeBulkChunkSize, maxPkt=${outEndpoint.maxPacketSize})"
            return false
        }
        return pos == length
    }

    override suspend fun sendCommand(
        command: String,
        timeoutMs: Int,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!connected) return@withContext FastbootResponse(ResponseType.ERROR, "Device not connected")

            val cmdBytes = command.toByteArray(StandardCharsets.US_ASCII)
            val sent = sendCommandPacket(cmdBytes, timeoutMs)
            if (sent < 0) {
                return@withContext FastbootResponse(
                    ResponseType.ERROR,
                    "USB write failed ($sent) for $command"
                )
            }

            readResponse(timeoutMs, infoCallback)
        }
    }

    private fun readResponse(
        timeoutMs: Int,
        infoCallback: (String) -> Unit
    ): FastbootResponse {
        val buffer = ByteArray(maxOf(inEndpoint.maxPacketSize, 512))
        while (connected) {
            val bytesRead = connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeoutMs)
            if (bytesRead < 0) {
                return FastbootResponse(
                    ResponseType.TIMEOUT,
                    "USB read timeout (${timeoutMs}ms, code=$bytesRead)"
                )
            }
            if (bytesRead == 0) {
                continue // Zero-length packet or ping, read next
            }

            val prefix = String(buffer, 0, minOf(4, bytesRead), StandardCharsets.US_ASCII)
            val payload = if (bytesRead > 4) {
                String(buffer, 4, bytesRead - 4, StandardCharsets.UTF_8).trim()
            } else ""

            when (prefix) {
                FastbootConstants.RESPONSE_INFO -> {
                    infoCallback(payload)
                    // Continue reading remaining response
                }
                FastbootConstants.RESPONSE_OKAY -> {
                    return FastbootResponse(ResponseType.OKAY, payload)
                }
                FastbootConstants.RESPONSE_FAIL -> {
                    return FastbootResponse(ResponseType.FAIL, payload)
                }
                FastbootConstants.RESPONSE_DATA -> {
                    val hexSize = payload.take(8)
                    val size = hexSize.toLongOrNull(16) ?: 0L
                    return FastbootResponse(ResponseType.DATA, payload, size)
                }
                FastbootConstants.RESPONSE_TEXT -> {
                    infoCallback(payload)
                }
                else -> {
                    val raw = String(buffer, 0, bytesRead, StandardCharsets.UTF_8).trim()
                    if (raw.startsWith(FastbootConstants.RESPONSE_OKAY)) {
                        return FastbootResponse(ResponseType.OKAY, raw.removePrefix("OKAY").trim())
                    } else if (raw.startsWith(FastbootConstants.RESPONSE_FAIL)) {
                        return FastbootResponse(ResponseType.FAIL, raw.removePrefix("FAIL").trim())
                    } else if (raw.startsWith(FastbootConstants.RESPONSE_DATA)) {
                        val hexSize = raw.removePrefix("DATA").take(8)
                        val size = hexSize.toLongOrNull(16) ?: 0L
                        return FastbootResponse(ResponseType.DATA, hexSize, size)
                    }
                    return FastbootResponse(ResponseType.TEXT, raw)
                }
            }
        }
        return FastbootResponse(ResponseType.ERROR, "Device disconnected during read")
    }

    override suspend fun downloadData(
        stream: InputStream,
        totalBytes: Long,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!connected) return@withContext FastbootResponse(ResponseType.ERROR, "Device not connected")

            val hexSize = String.format("%08x", totalBytes)
            val downloadCmd = "${FastbootConstants.CMD_DOWNLOAD}$hexSize"
            val cmdBytes = downloadCmd.toByteArray(StandardCharsets.US_ASCII)
            val sent = sendCommandPacket(cmdBytes, FastbootConstants.DEFAULT_TIMEOUT_MS)
            if (sent < 0) {
                return@withContext FastbootResponse(ResponseType.ERROR, "USB download command failed ($sent)")
            }

            val response = readResponse(FastbootConstants.DEFAULT_TIMEOUT_MS) {}
            if (response.type != ResponseType.DATA) {
                return@withContext response
            }

            val bufferedStream = if (stream is BufferedInputStream) stream else BufferedInputStream(stream, FastbootConstants.STREAM_BUFFER_SIZE)
            val buffer = ByteArray(FastbootConstants.BULK_CHUNK_SIZE)
            var bytesUploaded = 0L
            var lastProgressEmitMs = 0L

            try {
                while (bytesUploaded < totalBytes && connected) {
                    val toRead = minOf(activeBulkChunkSize.toLong(), buffer.size.toLong(), totalBytes - bytesUploaded).toInt()
                    val readNow = readFullyUpTo(bufferedStream, buffer, 0, toRead)
                    if (readNow <= 0) break

                    if (!writeBulkBuffer(buffer, 0, readNow)) {
                        val detail = lastUsbFailureDetail.ifBlank { "transfer interrupted" }
                        return@withContext FastbootResponse(
                            ResponseType.ERROR,
                            "USB write failed at $bytesUploaded/$totalBytes ($detail)"
                        )
                    }
                    bytesUploaded += readNow

                    val now = System.currentTimeMillis()
                    if (now - lastProgressEmitMs >= 100L || bytesUploaded >= totalBytes) {
                        lastProgressEmitMs = now
                        progressCallback(bytesUploaded, totalBytes)
                    }
                }
            } catch (e: Exception) {
                return@withContext FastbootResponse(ResponseType.ERROR, "Exception during download: ${e.message}")
            }

            if (bytesUploaded < totalBytes) {
                return@withContext FastbootResponse(
                    ResponseType.ERROR,
                    "File stream ended prematurely: sent $bytesUploaded of $totalBytes bytes"
                )
            }

            // Wait for final response acknowledging the download
            readResponse(FastbootConstants.DOWNLOAD_TIMEOUT_MS) {}
        }
    }

    override suspend fun flashPartition(
        partition: String,
        stream: InputStream,
        totalBytes: Long,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        val downloadResp = downloadData(stream, totalBytes, progressCallback)
        if (!downloadResp.isSuccess) {
            return@withContext downloadResp
        }

        val flashCmd = "${FastbootConstants.CMD_FLASH}$partition"
        sendCommand(flashCmd, FastbootConstants.FLASH_TIMEOUT_MS, infoCallback)
    }

    override suspend fun flashPartitionChunked(
        partition: String,
        totalBytes: Long,
        openStream: () -> InputStream?,
        progressCallback: (bytesSent: Long, totalBytes: Long) -> Unit,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        val queriedLimit = (cachedDeviceInfo?.maxDownloadSize?.takeIf { it > 0 }
            ?: parseHexOrDec(try { getVariable("max-download-size") } catch (_: Exception) { null }))

        // Respect the hardware buffer limit (up to 1GB safe upper bound for USB), or default to 768MB
        val maxChunkLimit = when {
            queriedLimit in (64 * 1024 * 1024L)..(1024 * 1024 * 1024L) -> queriedLimit - (1024 * 1024L) // 1MB safety margin
            queriedLimit > 1024 * 1024 * 1024L -> 1024 * 1024 * 1024L
            else -> 768 * 1024 * 1024L // 768 MB default for modern Android Fastboot/Fastbootd
        }

        // Probe if image is already an Android sparse file (0xed26ff3a)
        val isAlreadySparse = try {
            val probeStream = openStream()
            val probeHdr = ByteArray(4)
            val readBytes = probeStream?.use { it.read(probeHdr) } ?: 0
            readBytes >= 4 && SparseChunkHelper.isSparse(probeHdr)
        } catch (_: Exception) {
            false
        }

        // If file fits within hardware download limit, or is an already-sparse image fitting in device buffer, flash directly!
        if (totalBytes <= maxChunkLimit || (isAlreadySparse && (queriedLimit <= 0 || totalBytes <= queriedLimit))) {
            val stream = openStream() ?: return@withContext FastbootResponse(ResponseType.ERROR, "Cannot open stream for $partition")
            return@withContext try {
                flashPartition(partition, stream, totalBytes, progressCallback, infoCallback)
            } finally {
                try { stream.close() } catch (_: Exception) {}
            }
        }

        // File genuinely exceeds max download size! Split raw image into sparse chunks
        val sliceSize = ((maxChunkLimit - 64) / SparseChunkHelper.BLOCK_SIZE) * SparseChunkHelper.BLOCK_SIZE
        val numSlices = ((totalBytes + sliceSize - 1) / sliceSize).toInt()

        var totalSentAcrossChunks = 0L
        var lastProgressEmitMs = 0L

        for (sliceIndex in 0 until numSlices) {
            if (!connected) return@withContext FastbootResponse(ResponseType.ERROR, "Device disconnected during chunk flash")

            val sliceOffset = sliceIndex * sliceSize
            val sliceDataBytes = minOf(sliceSize, totalBytes - sliceOffset)
            val startBlock = sliceOffset / SparseChunkHelper.BLOCK_SIZE
            val chunkBlocks = (sliceDataBytes + SparseChunkHelper.BLOCK_SIZE - 1) / SparseChunkHelper.BLOCK_SIZE

            val chunkHeader = SparseChunkHelper.createRawSliceHeader(startBlock, chunkBlocks, sliceDataBytes)
            val chunkTotalWireBytes = chunkHeader.size.toLong() + sliceDataBytes

            // Open stream and seek/skip BEFORE locking & sending download command
            val inStream = openStream() ?: return@withContext FastbootResponse(ResponseType.ERROR, "Cannot open stream at chunk ${sliceIndex + 1}")
            val dlAckResp: FastbootResponse = try {
                if (sliceOffset > 0L) {
                    if (inStream is FileInputStream) {
                        inStream.channel.position(sliceOffset)
                    } else {
                        var skipped = 0L
                        while (skipped < sliceOffset) {
                            val n = inStream.skip(sliceOffset - skipped)
                            if (n <= 0) break
                            skipped += n
                        }
                        if (skipped < sliceOffset) {
                            val discard = ByteArray(128 * 1024)
                            while (skipped < sliceOffset) {
                                val toRead = minOf(discard.size.toLong(), sliceOffset - skipped).toInt()
                                val r = inStream.read(discard, 0, toRead)
                                if (r <= 0) break
                                skipped += r
                            }
                        }
                    }
                }

                val bufferedStream = if (inStream is BufferedInputStream) inStream else BufferedInputStream(inStream, FastbootConstants.STREAM_BUFFER_SIZE)

                // Preload first buffer with header + initial data.
                // Coalescing header into first buffer prevents premature USB Short Packet termination!
                val buffer = ByteArray(FastbootConstants.BULK_CHUNK_SIZE)
                val headerLen = chunkHeader.size
                System.arraycopy(chunkHeader, 0, buffer, 0, headerLen)

                val firstDataToRead = minOf((buffer.size - headerLen).toLong(), sliceDataBytes).toInt()
                val firstDataRead = if (firstDataToRead > 0) readFullyUpTo(bufferedStream, buffer, headerLen, firstDataToRead) else 0
                val initialSendSize = headerLen + maxOf(0, firstDataRead)

                mutex.withLock {
                    if (!connected) {
                        return@withLock FastbootResponse(ResponseType.ERROR, "Device disconnected")
                    }

                    // 1. Send download command for this chunk
                    val hexSize = String.format("%08x", chunkTotalWireBytes)
                    val cmdBytes = "${FastbootConstants.CMD_DOWNLOAD}$hexSize".toByteArray(StandardCharsets.US_ASCII)
                    val sent = sendCommandPacket(cmdBytes, FastbootConstants.DEFAULT_TIMEOUT_MS)
                    if (sent < 0) {
                        return@withLock FastbootResponse(ResponseType.ERROR, "USB download command failed ($sent) for chunk ${sliceIndex + 1}")
                    }

                    val dlResp = readResponse(FastbootConstants.DEFAULT_TIMEOUT_MS) {}
                    if (dlResp.type != ResponseType.DATA) {
                        return@withLock dlResp
                    }

                    // 2. Upload coalesced initial buffer (header + first data)
                    if (!writeBulkBuffer(buffer, 0, initialSendSize)) {
                        val detail = lastUsbFailureDetail.ifBlank { "transfer interrupted" }
                        return@withLock FastbootResponse(ResponseType.ERROR, "Failed writing initial sparse block of chunk ${sliceIndex + 1} ($detail)")
                    }

                    var bytesUploadedThisSlice = maxOf(0, firstDataRead).toLong()
                    totalSentAcrossChunks += maxOf(0, firstDataRead).toLong()

                    val initNow = System.currentTimeMillis()
                    if (initNow - lastProgressEmitMs >= 100L || totalSentAcrossChunks >= totalBytes) {
                        lastProgressEmitMs = initNow
                        progressCallback(totalSentAcrossChunks, totalBytes)
                    }

                    // 3. Upload remainder of slice data
                    while (bytesUploadedThisSlice < sliceDataBytes && connected) {
                        val toRead = minOf(buffer.size.toLong(), sliceDataBytes - bytesUploadedThisSlice).toInt()
                        val r = readFullyUpTo(bufferedStream, buffer, 0, toRead)
                        if (r <= 0) break

                        if (!writeBulkBuffer(buffer, 0, r)) {
                            return@withLock FastbootResponse(ResponseType.ERROR, "Failed streaming chunk ${sliceIndex + 1} to USB")
                        }
                        bytesUploadedThisSlice += r
                        totalSentAcrossChunks += r

                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmitMs >= 100L || totalSentAcrossChunks >= totalBytes) {
                            lastProgressEmitMs = now
                            progressCallback(totalSentAcrossChunks, totalBytes)
                        }
                    }

                    if (bytesUploadedThisSlice != sliceDataBytes) {
                        return@withLock FastbootResponse(ResponseType.ERROR, "Incomplete sparse slice ${sliceIndex + 1}")
                    }

                    // 4. Acknowledge download
                    readResponse(FastbootConstants.DOWNLOAD_TIMEOUT_MS) {}
                }
            } catch (e: Exception) {
                FastbootResponse(ResponseType.ERROR, "Chunk ${sliceIndex + 1} error: ${e.message}")
            } finally {
                try { inStream.close() } catch (_: Exception) {}
            }

            if (!dlAckResp.isSuccess) {
                return@withContext dlAckResp
            }

            // 5. Flash chunk to partition
            val flashResp = sendCommand("${FastbootConstants.CMD_FLASH}$partition", FastbootConstants.FLASH_TIMEOUT_MS, infoCallback)
            if (!flashResp.isSuccess) {
                return@withContext flashResp
            }
        }

        FastbootResponse(ResponseType.OKAY, "Chunked flash successful")
    }

    override suspend fun erasePartition(
        partition: String,
        infoCallback: (String) -> Unit
    ): FastbootResponse = withContext(Dispatchers.IO) {
        sendCommand("${FastbootConstants.CMD_ERASE}$partition", FastbootConstants.FLASH_TIMEOUT_MS, infoCallback)
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
        sendCommand(cmd, FastbootConstants.DEFAULT_TIMEOUT_MS, infoCallback)
    }

    override suspend fun setActiveSlot(slot: String): FastbootResponse = withContext(Dispatchers.IO) {
        val cleanSlot = slot.removePrefix("_").lowercase()
        sendCommand("${FastbootConstants.CMD_SET_ACTIVE}$cleanSlot", FastbootConstants.DEFAULT_TIMEOUT_MS)
    }

    override suspend fun getVariable(name: String): String? = withContext(Dispatchers.IO) {
        val resp = sendCommand("${FastbootConstants.CMD_GETVAR}$name", FastbootConstants.FAST_QUERY_TIMEOUT_MS)
        if (resp.isSuccess) resp.payload else null
    }

    override suspend fun fetchDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        var protocolReplied = false

        suspend fun queryVar(name: String): String? {
            val resp = try {
                sendCommand("${FastbootConstants.CMD_GETVAR}$name", FastbootConstants.FAST_QUERY_TIMEOUT_MS)
            } catch (_: Exception) {
                return null
            }
            if (resp.type == ResponseType.OKAY || resp.type == ResponseType.FAIL) {
                protocolReplied = true
            }
            return if (resp.isSuccess) resp.payload else null
        }

        val isUserspaceStr = queryVar("is-userspace")
        val product = queryVar("product") ?: queryVar("variant")
        val version = queryVar("version-bootloader") ?: queryVar("version")
        // If basic vars didn't even get an OKAY/FAIL Fastboot reply, this is NOT Bootloader/Fastbootd!
        if (!protocolReplied) {
            val unverified = DeviceInfo(
                productName = "Unknown",
                serialNumber = serialNumber,
                isVerifiedFastboot = false
            )
            cachedDeviceInfo = unverified
            return@withContext unverified
        }

        val currentSlot = queryVar("current-slot") ?: "none"
        val slotCountStr = queryVar("slot-count")
        val maxDownloadSizeStr = queryVar("max-download-size")

        val info = DeviceInfo(
            productName = product?.takeIf { it.isNotBlank() } ?: "Fastboot Device",
            bootloaderVersion = version?.takeIf { it.isNotBlank() } ?: "Bootloader",
            currentSlot = currentSlot,
            slotCount = slotCountStr?.toIntOrNull() ?: 1,
            isUserspace = isUserspaceStr?.equals("yes", ignoreCase = true) == true,
            isAdbMode = false,
            maxDownloadSize = parseHexOrDec(maxDownloadSizeStr),
            serialNumber = serialNumber,
            isVerifiedFastboot = true
        )
        cachedDeviceInfo = info
        info
    }

    private fun parseHexOrDec(str: String?): Long {
        if (str == null) return 0L
        val clean = str.trim()
        return if (clean.startsWith("0x", ignoreCase = true)) {
            clean.substring(2).toLongOrNull(16) ?: 0L
        } else {
            clean.toLongOrNull() ?: 0L
        }
    }

    override fun close() {
        connected = false
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
        } catch (_: Exception) {}
    }
}
