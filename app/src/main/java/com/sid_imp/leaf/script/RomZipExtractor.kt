package com.sid_imp.leaf.script

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ActiveExtractionWorker(
    val partitionName: String,
    val fileSizeBytes: Long,
    val bytesWritten: Long,
    val percent: Int
)

data class ZipExtractionProgress(
    val isExtracting: Boolean = false,
    val currentFile: String = "",
    val filesExtracted: Int = 0,
    val totalFiles: Int = 0,
    val totalBytesExtracted: Long = 0L,
    val totalPayloadBytes: Long = 0L,
    val speedMbPerSec: Double = 0.0,
    val percent: Int = 0,
    val statusMessage: String = "",
    val activeWorkers: List<ActiveExtractionWorker> = emptyList()
)

data class ExtractionResult(
    val success: Boolean,
    val extractedDir: File,
    val extractedImages: List<File>,
    val detectedScriptFile: File?,
    val fileMap: Map<String, Uri>,
    val errorMessage: String? = null
)

data class PartitionImageSource(
    val name: String,
    val uri: Uri? = null,
    val size: Long,
    val stemOverride: String? = null
) {
    val stem: String
        get() = stemOverride?.takeIf { it.isNotBlank() }?.let { FlashScriptParser.normalizePartitionStem(it) }
            ?: FlashScriptParser.normalizePartitionStem(name)
}

data class ExtractedPartitionItem(
    val stem: String,
    val fileName: String,
    val fileSize: Long,
    val category: String,
    val flashMode: String,
    val phaseLabel: String,
    val targetPartitions: List<String>,
    val isFastbootd: Boolean = false,
    val isCustomOverride: Boolean = false,
    val isIncludedInFlashPlan: Boolean = true
)

enum class ZipPartitionOrigin {
    PAYLOAD_BIN,
    DIRECT_ZIP_ENTRY
}

data class ZipPartitionEntry(
    val id: String,
    val name: String,
    val outputFileName: String,
    val sizeBytes: Long,
    val origin: ZipPartitionOrigin,
    val category: String,
    val zipEntryPath: String? = null
)

data class ZipInspectionResult(
    val success: Boolean,
    val archiveName: String,
    val hasPayloadBin: Boolean,
    val partitions: List<ZipPartitionEntry>,
    val errorMessage: String? = null
)

data class SavedExtractedFile(
    val partitionName: String,
    val fileName: String,
    val sizeBytes: Long,
    val uri: Uri,
    val displayPath: String
)

data class SelectiveExtractionResult(
    val success: Boolean,
    val cancelled: Boolean = false,
    val savedFiles: List<SavedExtractedFile> = emptyList(),
    val destinationDisplayPath: String = "",
    val errorMessage: String? = null
)

object RomZipExtractor {

    private val CORE_STEMS = setOf(
        "boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "dtbo", "recovery"
    )

    private val FIRMWARE_STEMS = setOf(
        "apusys", "ccu", "connsys_bt", "connsys_gnss", "connsys_wifi", "dpm",
        "gpueb", "gz", "lk", "logo", "mcf_ota", "mcupm", "modem", "pi_img",
        "scp", "spmfw", "sspm", "tee", "vcp", "preloader", "abl", "xbl",
        "xbl_config", "xbl_ramdump", "tz", "hyp", "aop", "aop_config", "dsp",
        "bluetooth", "qupfw", "devcfg", "keymaster", "uefisecapp", "featenabler",
        "imagefv", "shrm", "cpucp", "audio_dsp", "md1img", "cam_vpu1", "cam_vpu2",
        "cam_vpu3", "spunvm", "storsec", "multiimgoem", "splash"
    )

    private val LOGICAL_STEMS = setOf(
        "odm_dlkm", "odm", "vendor_dlkm", "product", "vendor",
        "system_dlkm", "system_ext", "system"
    )

    fun classifyPartitionCategory(stem: String, phase: FlashingPhase? = null): String {
        val clean = stem.lowercase()
        return when {
            clean == "super_empty" -> "Super Wipe"
            clean == "super" || clean in LOGICAL_STEMS || clean.startsWith("my_") || phase == FlashingPhase.LOGICAL_DYNAMIC -> "Logical"
            clean == "vbmeta" || clean.startsWith("vbmeta_") -> "VBMeta"
            clean in CORE_STEMS -> "Core"
            clean in FIRMWARE_STEMS || phase == FlashingPhase.FIRMWARE_BOOTLOADER -> "Firmware"
            else -> "Extra"
        }
    }

    fun buildExtractedPartitionInventory(
        images: List<PartitionImageSource>,
        plannedTasks: List<FlashTask>,
        customOverrideStems: Set<String> = emptySet()
    ): List<ExtractedPartitionItem> {
        if (images.isEmpty()) return emptyList()

        val targetsByStem = linkedMapOf<String, MutableList<String>>()
        val phaseByStem = hashMapOf<String, FlashingPhase>()
        val orderByStem = hashMapOf<String, Int>()

        plannedTasks.forEachIndexed { idx, task ->
            if (task.type == TaskType.FLASH) {
                val isWipeSuper = FlashScriptParser.isWipeSuperTask(task)
                val stem = if (isWipeSuper) {
                    "super_empty"
                } else {
                    FlashScriptParser.normalizePartitionStem(task.partition.ifBlank { task.fileName })
                }
                if (stem.isNotBlank()) {
                    orderByStem.putIfAbsent(stem, idx)
                    phaseByStem.putIfAbsent(stem, task.phase)
                    val targetName = if (isWipeSuper) "super (wipe)" else task.partition.ifBlank { stem }
                    val list = targetsByStem.getOrPut(stem) { mutableListOf() }
                    if (targetName !in list) list.add(targetName)
                }
            }
        }

        // Deduplicate by normalized partition stem while preserving override precedence
        val chosenByStem = linkedMapOf<String, PartitionImageSource>()
        for (img in images) {
            val stem = img.stem
            if (stem.isBlank()) continue
            val existing = chosenByStem[stem]
            if (existing == null || img.stemOverride != null) {
                chosenByStem[stem] = img
            }
        }

        return chosenByStem.entries
            .map { (stem, img) ->
                val targets = targetsByStem[stem].orEmpty()
                val taskPhase = phaseByStem[stem]
                val isFastbootd = when {
                    taskPhase == FlashingPhase.LOGICAL_DYNAMIC -> true
                    taskPhase == FlashingPhase.FIRMWARE_BOOTLOADER -> false
                    stem == "super_empty" || stem == "super" || stem in LOGICAL_STEMS || stem.startsWith("my_") -> true
                    else -> false
                }
                val flashMode = if (isFastbootd) "FastbootD" else "Bootloader (Fastboot)"
                val category = classifyPartitionCategory(stem, taskPhase)
                val phaseLabel = if (stem == "super_empty") "Super Wipe" else flashMode

                ExtractedPartitionItem(
                    stem = stem,
                    fileName = img.name,
                    fileSize = img.size,
                    category = category,
                    flashMode = flashMode,
                    phaseLabel = phaseLabel,
                    targetPartitions = targets,
                    isFastbootd = isFastbootd,
                    isCustomOverride = stem in customOverrideStems || img.stemOverride != null,
                    isIncludedInFlashPlan = targets.isNotEmpty()
                )
            }
            .sortedWith(
                compareBy<ExtractedPartitionItem>(
                    { !it.isIncludedInFlashPlan },
                    { orderByStem[it.stem] ?: Int.MAX_VALUE },
                    {
                        when (it.flashMode) {
                            "Bootloader (Fastboot)" -> 0
                            "FastbootD" -> 1
                            else -> 2
                        }
                    },
                    { it.stem }
                )
            )
    }

    data class ZipEntryLocation(
        val name: String,
        val dataOffset: Long,
        val uncompressedSize: Long,
        val compressionMethod: Int,
        val compressedSize: Long = 0L
    )

    suspend fun extractRomZip(
        context: Context,
        zipUri: Uri,
        onLog: (String) -> Unit = {},
        onProgress: (ZipExtractionProgress) -> Unit
    ): ExtractionResult = withContext(Dispatchers.IO) {
        val targetDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "leaf_extracted_rom")
        try {
            // Clean working directory
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            onProgress(
                ZipExtractionProgress(
                    isExtracting = true,
                    statusMessage = "Scanning ROM archive...",
                    percent = 3
                )
            )

            val extractedFiles = mutableListOf<File>()
            var detectedScript: File? = null

            // 1. FAST PATH: Check if uncompressed payload.bin can be extracted directly (Zero-Copy)
            var zeroCopyHandled = false
            try {
                val pfd = context.contentResolver.openFileDescriptor(zipUri, "r")
                if (pfd != null) {
                    val fis = FileInputStream(pfd.fileDescriptor)
                    val channel = fis.channel
                    val payloadEntry = findZipEntry(channel, "payload.bin")

                    if (payloadEntry != null && payloadEntry.compressionMethod == 0) {
                        zeroCopyHandled = true
                        onProgress(
                            ZipExtractionProgress(
                                isExtracting = true,
                                percent = 10,
                                statusMessage = "Extracting OTA payload..."
                            )
                        )

                        // Extract any flash script (.bat / .sh) from ZIP if present
                        try {
                            val scriptEntry = findZipEntry(channel, ".bat", "flash")
                                ?: findZipEntry(channel, ".sh", "flash")
                            if (scriptEntry != null && scriptEntry.uncompressedSize in 1..(2 * 1024 * 1024L)) {
                                val sName = File(scriptEntry.name).name
                                val sFile = File(targetDir, sName)
                                if (scriptEntry.compressionMethod == 0) {
                                    channel.position(scriptEntry.dataOffset)
                                    val sBytes = ByteArray(scriptEntry.uncompressedSize.toInt())
                                    val bb = ByteBuffer.wrap(sBytes)
                                    while (bb.hasRemaining()) {
                                        if (channel.read(bb) <= 0) break
                                    }
                                    sFile.writeBytes(sBytes)
                                    detectedScript = sFile
                                    extractedFiles.add(sFile)
                                } else if (scriptEntry.compressionMethod == 8 && scriptEntry.compressedSize > 0) {
                                    channel.position(scriptEntry.dataOffset)
                                    val compBytes = ByteArray(scriptEntry.compressedSize.toInt())
                                    val bb = ByteBuffer.wrap(compBytes)
                                    while (bb.hasRemaining()) {
                                        if (channel.read(bb) <= 0) break
                                    }
                                    val inflater = Inflater(true)
                                    InflaterInputStream(ByteArrayInputStream(compBytes), inflater).use { inf ->
                                        sFile.writeBytes(inf.readBytes())
                                    }
                                    inflater.end()
                                    detectedScript = sFile
                                    extractedFiles.add(sFile)
                                }
                            }
                        } catch (_: Exception) {}

                        // Extract partition images in parallel directly from channel
                        val dumpedImages = PayloadDumper.extractPayloadFromChannel(
                            channel = channel,
                            baseOffset = payloadEntry.dataOffset,
                            totalSize = payloadEntry.uncompressedSize,
                            outputDir = targetDir,
                            onSnapshot = { snap ->
                                val activeCount = snap.activeWorkers.size
                                val msg = when {
                                    activeCount > 1 -> "Unpacking $activeCount files side by side..."
                                    activeCount == 1 -> "Unpacking ${snap.activeWorkers.first().partitionName}.img..."
                                    else -> "Finishing ROM files..."
                                }
                                onProgress(
                                    ZipExtractionProgress(
                                        isExtracting = true,
                                        currentFile = snap.activeWorkers.firstOrNull()?.partitionName?.let { "$it.img" } ?: "",
                                        filesExtracted = snap.completedFiles,
                                        totalFiles = snap.totalFiles,
                                        totalBytesExtracted = snap.bytesExtracted,
                                        totalPayloadBytes = snap.totalBytes,
                                        speedMbPerSec = snap.speedMbPerSec,
                                        percent = minOf(99, 6 + ((snap.overallPercent * 93) / 100)),
                                        statusMessage = msg,
                                        activeWorkers = snap.activeWorkers
                                    )
                                )
                            }
                        )
                        extractedFiles.addAll(dumpedImages)
                    }
                    fis.close()
                    pfd.close()
                }
            } catch (e: Exception) {
                zeroCopyHandled = false
            }

            // 2. FALLBACK PATH: High-speed buffered streaming ZIP extraction (2MB buffer)
            if (!zeroCopyHandled) {
                var totalBytes = 0L
                val pfd = context.contentResolver.openFileDescriptor(zipUri, "r")
                val zipFileSize = pfd?.statSize ?: 0L
                pfd?.close()

                val rawInputStream: InputStream? = context.contentResolver.openInputStream(zipUri)
                if (rawInputStream == null) {
                    return@withContext ExtractionResult(false, targetDir, emptyList(), null, emptyMap(), "Could not open ZIP stream")
                }

                val zipIn = ZipInputStream(BufferedInputStream(rawInputStream, 2 * 1024 * 1024))
                var entry: ZipEntry? = zipIn.nextEntry
                var count = 0
                val buffer = ByteArray(2 * 1024 * 1024) // 2MB buffer for high-speed I/O
                var lastUiUpdateMs = 0L
                val fallbackStartMs = System.currentTimeMillis()

                while (entry != null) {
                    val entryName = entry.name
                    val cleanName = File(entryName).name
                    val declaredEntrySize = entry.size.coerceAtLeast(0L)

                    val isTarget = !entry.isDirectory && (
                            cleanName.endsWith(".img", ignoreCase = true) ||
                            cleanName.endsWith(".bin", ignoreCase = true) ||
                            cleanName.endsWith(".raw", ignoreCase = true) ||
                            cleanName.endsWith(".bat", ignoreCase = true) ||
                            cleanName.endsWith(".sh", ignoreCase = true) ||
                            cleanName.equals("payload.bin", ignoreCase = true)
                    )

                    if (isTarget) {
                        val outFile = File(targetDir, cleanName)
                        count++
                        var entryBytesRead = 0L

                        fun buildStreamWorker(written: Long): ActiveExtractionWorker {
                            val workerPct = when {
                                declaredEntrySize > 0L -> ((written * 100L) / declaredEntrySize).toInt().coerceIn(0, 99)
                                zipFileSize > 0L -> ((totalBytes * 100L) / zipFileSize).toInt().coerceIn(0, 99)
                                else -> 50
                            }
                            return ActiveExtractionWorker(
                                partitionName = cleanName.substringBeforeLast('.'),
                                fileSizeBytes = declaredEntrySize,
                                bytesWritten = written,
                                percent = workerPct
                            )
                        }

                        val initElapsedSec = maxOf(0.1, (System.currentTimeMillis() - fallbackStartMs) / 1000.0)
                        onProgress(
                            ZipExtractionProgress(
                                isExtracting = true,
                                currentFile = cleanName,
                                filesExtracted = count - 1,
                                totalFiles = count,
                                totalBytesExtracted = totalBytes,
                                totalPayloadBytes = zipFileSize,
                                speedMbPerSec = (totalBytes / (1024.0 * 1024.0)) / initElapsedSec,
                                percent = if (zipFileSize > 0) minOf(70, ((totalBytes * 70) / zipFileSize).toInt()).coerceAtLeast(4) else 30,
                                statusMessage = "Unpacking $cleanName...",
                                activeWorkers = listOf(buildStreamWorker(0L))
                            )
                        )

                        FileOutputStream(outFile).use { fos ->
                            var len: Int
                            while (zipIn.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                                totalBytes += len
                                entryBytesRead += len
                                val now = System.currentTimeMillis()
                                if (now - lastUiUpdateMs >= 80L) {
                                    lastUiUpdateMs = now
                                    val elapsedSec = maxOf(0.1, (now - fallbackStartMs) / 1000.0)
                                    val speedMb = (totalBytes / (1024.0 * 1024.0)) / elapsedSec
                                    onProgress(
                                        ZipExtractionProgress(
                                            isExtracting = true,
                                            currentFile = cleanName,
                                            filesExtracted = count - 1,
                                            totalFiles = count,
                                            totalBytesExtracted = totalBytes,
                                            totalPayloadBytes = zipFileSize,
                                            speedMbPerSec = speedMb,
                                            percent = if (zipFileSize > 0) minOf(70, ((totalBytes * 70) / zipFileSize).toInt()).coerceAtLeast(4) else 35,
                                            statusMessage = "Unpacking $cleanName...",
                                            activeWorkers = listOf(buildStreamWorker(entryBytesRead))
                                        )
                                    )
                                }
                            }
                        }

                        extractedFiles.add(outFile)

                        if (cleanName.endsWith(".bat", ignoreCase = true) || cleanName.endsWith(".sh", ignoreCase = true)) {
                            if (detectedScript == null || cleanName.contains("flash", ignoreCase = true)) {
                                detectedScript = outFile
                            }
                        }
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
                zipIn.close()

                // If package contains payload.bin, unpack partition images from it in parallel
                val payloadBin = File(targetDir, "payload.bin")
                if (payloadBin.exists() && payloadBin.length() > 0) {
                    onProgress(
                        ZipExtractionProgress(
                            isExtracting = true,
                            percent = 70,
                            statusMessage = "Preparing parallel unpack...",
                            activeWorkers = emptyList()
                        )
                    )
                    try {
                        val payloadImages = PayloadDumper.extractPayload(
                            payloadFile = payloadBin,
                            outputDir = targetDir,
                            onSnapshot = { snap ->
                                val activeCount = snap.activeWorkers.size
                                val msg = when {
                                    activeCount > 1 -> "Unpacking $activeCount files side by side..."
                                    activeCount == 1 -> "Unpacking ${snap.activeWorkers.first().partitionName}.img..."
                                    else -> "Finishing ROM files..."
                                }
                                onProgress(
                                    ZipExtractionProgress(
                                        isExtracting = true,
                                        currentFile = snap.activeWorkers.firstOrNull()?.partitionName?.let { "$it.img" } ?: "",
                                        filesExtracted = snap.completedFiles,
                                        totalFiles = snap.totalFiles,
                                        totalBytesExtracted = snap.bytesExtracted,
                                        totalPayloadBytes = snap.totalBytes,
                                        speedMbPerSec = snap.speedMbPerSec,
                                        percent = minOf(99, 70 + ((snap.overallPercent * 29) / 100)),
                                        statusMessage = msg,
                                        activeWorkers = snap.activeWorkers
                                    )
                                )
                            }
                        )
                        extractedFiles.addAll(payloadImages)
                        try { payloadBin.delete() } catch (_: Exception) {}
                    } catch (e: Exception) {
                        onLog("✗ Payload error: ${e.message}")
                    }
                }
            }

            // 3. Index extracted files into fileMap
            val fileMap = mutableMapOf<String, Uri>()
            val imageList = mutableListOf<File>()

            for (file in extractedFiles) {
                if (file.exists()) {
                    val uri = file.toUri()
                    val name = file.name
                    val noExt = file.nameWithoutExtension
                    val clean = noExt.lowercase().removeSuffix("_a").removeSuffix("_b")

                    fileMap[name] = uri
                    fileMap[name.lowercase()] = uri
                    fileMap[noExt] = uri
                    fileMap[noExt.lowercase()] = uri
                    fileMap[clean] = uri
                    fileMap["${clean}.img"] = uri
                    fileMap["${clean}.bin"] = uri
                    fileMap["${clean}_a"] = uri
                    fileMap["${clean}_b"] = uri
                    fileMap["${clean}_a.img"] = uri
                    fileMap["${clean}_b.img"] = uri

                    if (clean == "preloader_raw" || clean.startsWith("preloader")) {
                        fileMap["preloader"] = uri
                        fileMap["preloader.img"] = uri
                        fileMap["preloader_a"] = uri
                        fileMap["preloader_a.img"] = uri
                        fileMap["preloader_b"] = uri
                        fileMap["preloader_b.img"] = uri
                    }

                    if (name.endsWith(".img", ignoreCase = true) || name.endsWith(".bin", ignoreCase = true) || name.endsWith(".raw", ignoreCase = true)) {
                        imageList.add(file)
                    }
                }
            }

            onProgress(
                ZipExtractionProgress(
                    isExtracting = false,
                    filesExtracted = fileMap.size,
                    percent = 100,
                    statusMessage = "${imageList.size} images ready"
                )
            )

            ExtractionResult(
                success = true,
                extractedDir = targetDir,
                extractedImages = imageList,
                detectedScriptFile = detectedScript,
                fileMap = fileMap
            )
        } catch (e: Exception) {
            onProgress(
                ZipExtractionProgress(
                    isExtracting = false,
                    statusMessage = "Extraction failed: ${e.message}"
                )
            )
            ExtractionResult(false, targetDir, emptyList(), null, emptyMap(), e.message)
        }
    }

    internal data class ZipCdRecord(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHdrOffset: Long
    )

    private class BoundedChannelInputStream(
        private val channel: FileChannel,
        startOffset: Long,
        length: Long
    ) : InputStream() {
        private var cursor = startOffset
        private val endOffset = startOffset + length
        private val single = ByteArray(1)

        override fun read(): Int {
            val r = read(single, 0, 1)
            return if (r == -1) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val remaining = endOffset - cursor
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val buf = ByteBuffer.wrap(b, off, toRead)
            val read = channel.read(buf, cursor)
            if (read <= 0) return -1
            cursor += read
            return read
        }
    }

    private fun readChannelFully(channel: FileChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            if (channel.read(buf) <= 0) break
        }
    }

    internal fun scanZipCentralDirectory(channel: FileChannel): List<ZipCdRecord> {
        val fileSize = channel.size()
        if (fileSize < 22) return emptyList()

        val searchSize = minOf(fileSize, 65536L).toInt()
        val tailBuf = ByteBuffer.allocate(searchSize).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(fileSize - searchSize)
        readChannelFully(channel, tailBuf)
        tailBuf.flip()

        var eocdPos = -1
        for (i in (searchSize - 22) downTo 0) {
            if (tailBuf.getInt(i) == 0x06054B50) {
                eocdPos = i
                break
            }
        }
        if (eocdPos < 0) return emptyList()

        var cdOffset = tailBuf.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL
        var totalEntries = (tailBuf.getShort(eocdPos + 10).toInt() and 0xFFFF).toLong()

        // Check for ZIP64 End of Central Directory Locator (0x07064B50) before standard EOCD
        val locPos = (eocdPos - 20)
        if (locPos >= 0 && tailBuf.getInt(locPos) == 0x07064B50) {
            val zip64EocdOffset = tailBuf.getLong(locPos + 8)
            if (zip64EocdOffset in 0 until fileSize) {
                val z64Buf = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(zip64EocdOffset)
                if (channel.read(z64Buf) >= 56) {
                    z64Buf.flip()
                    if (z64Buf.getInt(0) == 0x06064B50) {
                        totalEntries = z64Buf.getLong(32)
                        cdOffset = z64Buf.getLong(48)
                    }
                }
            }
        }

        if (cdOffset >= fileSize) return emptyList()

        channel.position(cdOffset)
        val headerBuf = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        val result = ArrayList<ZipCdRecord>(minOf(totalEntries, 4096L).toInt().coerceAtLeast(16))

        for (i in 0 until minOf(totalEntries, 65535L)) {
            headerBuf.clear()
            readChannelFully(channel, headerBuf)
            if (headerBuf.position() < 46) break
            headerBuf.flip()
            if (headerBuf.getInt(0) != 0x02014B50) break

            val method = headerBuf.getShort(10).toInt() and 0xFFFF
            var compressedSize = headerBuf.getInt(20).toLong() and 0xFFFFFFFFL
            var uncompressedSize = headerBuf.getInt(24).toLong() and 0xFFFFFFFFL
            val nameLen = headerBuf.getShort(28).toInt() and 0xFFFF
            val extraLen = headerBuf.getShort(30).toInt() and 0xFFFF
            val commentLen = headerBuf.getShort(32).toInt() and 0xFFFF
            var localHdrOffset = headerBuf.getInt(42).toLong() and 0xFFFFFFFFL

            val nameBytes = ByteArray(nameLen)
            readChannelFully(channel, ByteBuffer.wrap(nameBytes))
            val entryName = String(nameBytes, Charsets.UTF_8)

            var extraBytes: ByteArray? = null
            if (extraLen > 0) {
                extraBytes = ByteArray(extraLen)
                readChannelFully(channel, ByteBuffer.wrap(extraBytes))
            }

            if (commentLen > 0) {
                channel.position(channel.position() + commentLen)
            }

            // ZIP64 Extended Information Extra Field (Tag 0x0001)
            if (extraBytes != null && (uncompressedSize == 0xFFFFFFFFL || compressedSize == 0xFFFFFFFFL || localHdrOffset == 0xFFFFFFFFL)) {
                val eb = ByteBuffer.wrap(extraBytes).order(ByteOrder.LITTLE_ENDIAN)
                while (eb.remaining() >= 4) {
                    val tag = eb.getShort().toInt() and 0xFFFF
                    val sz = eb.getShort().toInt() and 0xFFFF
                    val nextPos = eb.position() + sz
                    if (tag == 0x0001) {
                        if (uncompressedSize == 0xFFFFFFFFL && eb.remaining() >= 8) {
                            uncompressedSize = eb.getLong()
                        }
                        if (compressedSize == 0xFFFFFFFFL && eb.remaining() >= 8) {
                            compressedSize = eb.getLong()
                        }
                        if (localHdrOffset == 0xFFFFFFFFL && eb.remaining() >= 8) {
                            localHdrOffset = eb.getLong()
                        }
                        break
                    }
                    if (nextPos <= eb.limit()) {
                        eb.position(nextPos)
                    } else break
                }
            }

            result.add(ZipCdRecord(entryName, method, compressedSize, uncompressedSize, localHdrOffset))
        }
        return result
    }

    internal fun resolveZipEntryLocation(channel: FileChannel, record: ZipCdRecord): ZipEntryLocation? {
        val locBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(record.localHdrOffset)
        readChannelFully(channel, locBuf)
        if (locBuf.position() < 30) return null
        locBuf.flip()
        if (locBuf.getInt(0) != 0x04034B50) return null

        val locNameLen = locBuf.getShort(26).toInt() and 0xFFFF
        val locExtraLen = locBuf.getShort(28).toInt() and 0xFFFF
        val dataStart = record.localHdrOffset + 30 + locNameLen + locExtraLen
        return ZipEntryLocation(
            name = record.name,
            dataOffset = dataStart,
            uncompressedSize = record.uncompressedSize,
            compressionMethod = record.method,
            compressedSize = record.compressedSize
        )
    }

    /**
     * Fast binary parser that reads the ZIP Central Directory (supporting both standard ZIP and ZIP64)
     * to locate an entry in < 5ms without unpacking any files.
     */
    internal fun findZipEntry(
        channel: FileChannel,
        targetSuffix: String,
        preferredContains: String? = null
    ): ZipEntryLocation? {
        val records = scanZipCentralDirectory(channel)
        var fallbackMatch: ZipCdRecord? = null
        for (rec in records) {
            if (rec.name.endsWith(targetSuffix, ignoreCase = true)) {
                if (preferredContains == null || rec.name.contains(preferredContains, ignoreCase = true)) {
                    return resolveZipEntryLocation(channel, rec)
                }
                if (fallbackMatch == null) {
                    fallbackMatch = rec
                }
            }
        }
        return fallbackMatch?.let { resolveZipEntryLocation(channel, it) }
    }

    fun sanitizeArchiveFolderName(archiveName: String): String {
        val stripped = archiveName.trim()
            .replace(Regex("\\.zip$", RegexOption.IGNORE_CASE), "")
        val cleaned = stripped
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.', ' ')
            .take(56)
        return cleaned.ifBlank { "extracted_rom" }
    }

    private fun isDirectImageEntryName(entryName: String): Boolean {
        val cleanName = entryName.replace('\\', '/').substringAfterLast('/')
        if (cleanName.isBlank() || cleanName.equals("payload.bin", ignoreCase = true)) return false
        val low = cleanName.lowercase(Locale.US)
        return low.endsWith(".img") || low.endsWith(".bin") || low.endsWith(".raw")
    }

    private fun zipPartitionSortPriority(stem: String): Int {
        val low = stem.lowercase(Locale.US)
        return when (low) {
            "init_boot" -> 0
            "boot" -> 1
            "vendor_boot" -> 2
            "vendor_kernel_boot" -> 3
            "recovery" -> 4
            "dtbo" -> 5
            "vbmeta" -> 6
            "vbmeta_system", "vbmeta_vendor" -> 7
            else -> when (classifyPartitionCategory(low)) {
                "Core" -> 8
                "VBMeta" -> 9
                "Firmware" -> 10
                "Extra" -> 11
                "Super Wipe" -> 12
                else -> 13
            }
        }
    }

    private fun sortZipPartitionEntries(entries: List<ZipPartitionEntry>): List<ZipPartitionEntry> {
        return entries.sortedWith(
            compareBy<ZipPartitionEntry>(
                { zipPartitionSortPriority(FlashScriptParser.normalizePartitionStem(it.name)) },
                { it.outputFileName.lowercase(Locale.US) },
                { it.id }
            )
        )
    }

    private fun inspectZipChannel(channel: FileChannel, archiveName: String): ZipInspectionResult {
        val records = scanZipCentralDirectory(channel)
        if (records.isEmpty()) {
            return ZipInspectionResult(
                success = false,
                archiveName = archiveName,
                hasPayloadBin = false,
                partitions = emptyList(),
                errorMessage = "Invalid or empty ZIP archive"
            )
        }

        val entries = mutableListOf<ZipPartitionEntry>()
        var hasPayload = false
        val payloadRecord = records.firstOrNull {
            it.name.replace('\\', '/').substringAfterLast('/').equals("payload.bin", ignoreCase = true)
        }
        if (payloadRecord != null) {
            hasPayload = true
            val loc = resolveZipEntryLocation(channel, payloadRecord)
                ?: return ZipInspectionResult(
                    success = false,
                    archiveName = archiveName,
                    hasPayloadBin = true,
                    partitions = emptyList(),
                    errorMessage = "Unable to read payload.bin header"
                )
            val summaries = when (loc.compressionMethod) {
                0 -> PayloadDumper.inspectPayloadFromChannel(channel, loc.dataOffset, loc.uncompressedSize)
                8 -> {
                    val inflater = Inflater(true)
                    try {
                        InflaterInputStream(
                            BufferedInputStream(
                                BoundedChannelInputStream(channel, loc.dataOffset, loc.compressedSize),
                                64 * 1024
                            ),
                            inflater
                        ).use { inf ->
                            PayloadDumper.inspectPayloadFromStream(inf)
                        }
                    } finally {
                        inflater.end()
                    }
                }
                else -> throw IllegalStateException("Unsupported ZIP compression method ${loc.compressionMethod} for payload.bin")
            }
            for (part in summaries) {
                entries.add(
                    ZipPartitionEntry(
                        id = "PAYLOAD:${part.name}",
                        name = part.name,
                        outputFileName = "${part.name}.img",
                        sizeBytes = maxOf(0L, part.sizeBytes),
                        origin = ZipPartitionOrigin.PAYLOAD_BIN,
                        category = classifyPartitionCategory(part.name)
                    )
                )
            }
        }

        for (rec in records) {
            val normalizedPath = rec.name.replace('\\', '/')
            if (normalizedPath.endsWith("/")) continue
            val cleanName = normalizedPath.substringAfterLast('/')
            if (isDirectImageEntryName(cleanName)) {
                val stem = FlashScriptParser.normalizePartitionStem(cleanName)
                entries.add(
                    ZipPartitionEntry(
                        id = "ZIP:$normalizedPath",
                        name = cleanName.substringBeforeLast('.'),
                        outputFileName = cleanName,
                        sizeBytes = maxOf(0L, rec.uncompressedSize),
                        origin = ZipPartitionOrigin.DIRECT_ZIP_ENTRY,
                        category = classifyPartitionCategory(stem),
                        zipEntryPath = rec.name
                    )
                )
            }
        }

        val sorted = sortZipPartitionEntries(entries)
        return if (sorted.isEmpty()) {
            ZipInspectionResult(
                success = false,
                archiveName = archiveName,
                hasPayloadBin = hasPayload,
                partitions = emptyList(),
                errorMessage = "No .img partitions or payload.bin entries found in archive"
            )
        } else {
            ZipInspectionResult(
                success = true,
                archiveName = archiveName,
                hasPayloadBin = hasPayload,
                partitions = sorted
            )
        }
    }

    private fun inspectZipStream(rawStream: InputStream, archiveName: String): ZipInspectionResult {
        val entries = mutableListOf<ZipPartitionEntry>()
        var hasPayload = false
        ZipInputStream(BufferedInputStream(rawStream, 512 * 1024)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val normalizedPath = entry.name.replace('\\', '/')
                val cleanName = normalizedPath.substringAfterLast('/')
                if (!entry.isDirectory) {
                    if (cleanName.equals("payload.bin", ignoreCase = true)) {
                        hasPayload = true
                        val summaries = PayloadDumper.inspectPayloadFromStream(zipIn)
                        for (part in summaries) {
                            entries.add(
                                ZipPartitionEntry(
                                    id = "PAYLOAD:${part.name}",
                                    name = part.name,
                                    outputFileName = "${part.name}.img",
                                    sizeBytes = maxOf(0L, part.sizeBytes),
                                    origin = ZipPartitionOrigin.PAYLOAD_BIN,
                                    category = classifyPartitionCategory(part.name)
                                )
                            )
                        }
                        // AOSP OTA zips contain only payload.bin as their image container; avoid scanning GBs on non-seekable streams
                        if (entries.isNotEmpty()) break
                    } else if (isDirectImageEntryName(cleanName)) {
                        val stem = FlashScriptParser.normalizePartitionStem(cleanName)
                        entries.add(
                            ZipPartitionEntry(
                                id = "ZIP:$normalizedPath",
                                name = cleanName.substringBeforeLast('.'),
                                outputFileName = cleanName,
                                sizeBytes = entry.size.coerceAtLeast(0L),
                                origin = ZipPartitionOrigin.DIRECT_ZIP_ENTRY,
                                category = classifyPartitionCategory(stem),
                                zipEntryPath = entry.name
                            )
                        )
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        val sorted = sortZipPartitionEntries(entries)
        return if (sorted.isEmpty()) {
            ZipInspectionResult(
                success = false,
                archiveName = archiveName,
                hasPayloadBin = hasPayload,
                partitions = emptyList(),
                errorMessage = "No .img partitions or payload.bin entries found in archive"
            )
        } else {
            ZipInspectionResult(
                success = true,
                archiveName = archiveName,
                hasPayloadBin = hasPayload,
                partitions = sorted
            )
        }
    }

    /**
     * Pure-JVM testable inspection helper operating on a local ZIP file.
     */
    suspend fun inspectZipFilePartitions(
        zipFile: File,
        archiveDisplayName: String = zipFile.name
    ): ZipInspectionResult = withContext(Dispatchers.IO) {
        try {
            if (!zipFile.exists() || !zipFile.isFile) {
                return@withContext ZipInspectionResult(
                    success = false,
                    archiveName = archiveDisplayName,
                    hasPayloadBin = false,
                    partitions = emptyList(),
                    errorMessage = "ZIP file not found"
                )
            }
            RandomAccessFile(zipFile, "r").use { raf ->
                inspectZipChannel(raf.channel, archiveDisplayName)
            }
        } catch (e: Exception) {
            ZipInspectionResult(
                success = false,
                archiveName = archiveDisplayName,
                hasPayloadBin = false,
                partitions = emptyList(),
                errorMessage = e.message ?: "Failed to read ZIP archive"
            )
        }
    }

    /**
     * Quickly inspects any ROM ZIP (A/B payload.bin or Fastboot .img archive) via zero-copy FileChannel
     * or streaming fallback without extracting multi-gigabyte images.
     */
    suspend fun inspectRomZipPartitions(
        context: Context,
        zipUri: Uri,
        archiveDisplayName: String
    ): ZipInspectionResult = withContext(Dispatchers.IO) {
        try {
            // 1. Seekable FileChannel path (handles STORED & DEFLATE payload/img entries in milliseconds)
            val channelResult = try {
                context.contentResolver.openFileDescriptor(zipUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        val ch = fis.channel
                        if (ch.size() >= 22L) {
                            inspectZipChannel(ch, archiveDisplayName)
                        } else null
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (channelResult != null && (channelResult.success || channelResult.hasPayloadBin)) {
                return@withContext channelResult
            }

            // 2. Non-seekable SAF InputStream fallback
            val stream = context.contentResolver.openInputStream(zipUri)
                ?: return@withContext ZipInspectionResult(
                    success = false,
                    archiveName = archiveDisplayName,
                    hasPayloadBin = false,
                    partitions = emptyList(),
                    errorMessage = "Could not open ZIP archive"
                )
            stream.use { inspectZipStream(it, archiveDisplayName) }
        } catch (e: Exception) {
            ZipInspectionResult(
                success = false,
                archiveName = archiveDisplayName,
                hasPayloadBin = false,
                partitions = emptyList(),
                errorMessage = e.message ?: "Failed to read ZIP archive"
            )
        }
    }

    private fun canWriteDirectlyInDir(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false
            val probe = File(dir, ".leaf_write_probe_${System.nanoTime()}")
            probe.writeText("ok")
            val ok = probe.exists()
            probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pure-JVM testable selective extraction directly into [outputDir].
     */
    suspend fun extractSelectedPartitionsToDir(
        zipFile: File,
        selectedEntries: List<ZipPartitionEntry>,
        outputDir: File,
        isCancelled: () -> Boolean = { false },
        onLog: (String) -> Unit = {},
        onProgress: (ZipExtractionProgress) -> Unit = {}
    ): List<File> = withContext(Dispatchers.IO) {
        RandomAccessFile(zipFile, "r").use { raf ->
            extractSelectedFromSeekableChannel(
                channel = raf.channel,
                selectedEntries = selectedEntries,
                outputDir = outputDir,
                tempRootDir = File(outputDir, ".leaf_tmp"),
                isCancelled = isCancelled,
                onLog = onLog,
                onProgress = onProgress
            )
        }
    }

    private suspend fun extractSelectedFromSeekableChannel(
        channel: FileChannel,
        selectedEntries: List<ZipPartitionEntry>,
        outputDir: File,
        tempRootDir: File,
        isCancelled: () -> Boolean,
        onLog: (String) -> Unit,
        onProgress: (ZipExtractionProgress) -> Unit
    ): List<File> {
        outputDir.mkdirs()
        if (selectedEntries.isEmpty()) return emptyList()

        val payloadSelections = selectedEntries
            .filter { it.origin == ZipPartitionOrigin.PAYLOAD_BIN }
            .map { it.name }
            .toSet()
        val directSelections = selectedEntries.filter { it.origin == ZipPartitionOrigin.DIRECT_ZIP_ENTRY }
        val totalSelectedCount = selectedEntries.size
        val totalExpectedBytes = selectedEntries.sumOf { maxOf(0L, it.sizeBytes) }.coerceAtLeast(1L)

        val records = scanZipCentralDirectory(channel)
        val outFiles = mutableListOf<File>()
        var cumulativeBytesDone = 0L
        var filesDoneCount = 0
        val startMs = System.currentTimeMillis()
        var highestPct = 0

        try {
            // 1. Extract selected payload.bin partitions
            if (payloadSelections.isNotEmpty()) {
                if (isCancelled()) throw CancellationException("Extraction cancelled")
                val payloadRec = records.firstOrNull {
                    it.name.replace('\\', '/').substringAfterLast('/').equals("payload.bin", ignoreCase = true)
                } ?: throw IllegalStateException("payload.bin not found in archive")
                val loc = resolveZipEntryLocation(channel, payloadRec)
                    ?: throw IllegalStateException("Unable to locate payload.bin inside archive")

                val payloadWeightTotal = selectedEntries
                    .filter { it.origin == ZipPartitionOrigin.PAYLOAD_BIN }
                    .sumOf { maxOf(0L, it.sizeBytes) }
                    .coerceAtLeast(1L)

                fun forwardPayloadSnapshot(snap: PayloadDumper.PayloadProgressSnapshot) {
                    val elapsedSec = maxOf(0.1, (System.currentTimeMillis() - startMs) / 1000.0)
                    val combinedDone = snap.bytesExtracted
                    val speedMb = (combinedDone / (1024.0 * 1024.0)) / elapsedSec
                    val rawPct = if (directSelections.isEmpty() && snap.completedFiles >= snap.totalFiles) {
                        100
                    } else {
                        ((combinedDone * 100L) / totalExpectedBytes).toInt().coerceIn(0, 99)
                    }
                    highestPct = maxOf(highestPct, rawPct)
                    val activeNames = snap.activeWorkers.size
                    val msg = when {
                        activeNames > 1 -> "Extracting $activeNames partitions..."
                        activeNames == 1 -> "Extracting ${snap.activeWorkers.first().partitionName}.img..."
                        else -> "Finishing payload extraction..."
                    }
                    onProgress(
                        ZipExtractionProgress(
                            isExtracting = true,
                            currentFile = snap.activeWorkers.firstOrNull()?.partitionName?.let { "$it.img" } ?: "",
                            filesExtracted = snap.completedFiles,
                            totalFiles = totalSelectedCount,
                            totalBytesExtracted = combinedDone,
                            totalPayloadBytes = totalExpectedBytes,
                            speedMbPerSec = speedMb,
                            percent = highestPct,
                            statusMessage = msg,
                            activeWorkers = snap.activeWorkers
                        )
                    )
                }

                val dumped: List<File> = when (loc.compressionMethod) {
                    0 -> {
                        PayloadDumper.extractPayloadFromChannel(
                            channel = channel,
                            baseOffset = loc.dataOffset,
                            totalSize = loc.uncompressedSize,
                            outputDir = outputDir,
                            targetPartitions = payloadSelections,
                            isCancelled = isCancelled,
                            onSnapshot = ::forwardPayloadSnapshot
                        )
                    }
                    8 -> {
                        try {
                            val inflater = Inflater(true)
                            try {
                                InflaterInputStream(
                                    BufferedInputStream(
                                        BoundedChannelInputStream(channel, loc.dataOffset, loc.compressedSize),
                                        512 * 1024
                                    ),
                                    inflater
                                ).use { inf ->
                                    PayloadDumper.extractPayloadFromStream(
                                        stream = inf,
                                        outputDir = outputDir,
                                        targetPartitions = payloadSelections,
                                        isCancelled = isCancelled,
                                        onSnapshot = ::forwardPayloadSnapshot
                                    )
                                }
                            } finally {
                                inflater.end()
                            }
                        } catch (_: PayloadDumper.NonSequentialPayloadException) {
                            // Rare fallback for out-of-order extents: spool payload.bin temporarily, extract, and delete
                            tempRootDir.mkdirs()
                            val tempPayload = File(tempRootDir, "payload_spool.bin")
                            try {
                                val inflater = Inflater(true)
                                try {
                                    InflaterInputStream(
                                        BufferedInputStream(
                                            BoundedChannelInputStream(channel, loc.dataOffset, loc.compressedSize),
                                            512 * 1024
                                        ),
                                        inflater
                                    ).use { inf ->
                                        FileOutputStream(tempPayload).use { fos ->
                                            val buf = ByteArray(512 * 1024)
                                            var r: Int
                                            while (inf.read(buf).also { r = it } > 0) {
                                                if (isCancelled()) throw CancellationException("Extraction cancelled")
                                                fos.write(buf, 0, r)
                                            }
                                        }
                                    }
                                } finally {
                                    inflater.end()
                                }
                                PayloadDumper.extractPayload(
                                    payloadFile = tempPayload,
                                    outputDir = outputDir,
                                    targetPartitions = payloadSelections,
                                    isCancelled = isCancelled,
                                    onSnapshot = ::forwardPayloadSnapshot
                                )
                            } finally {
                                try { tempPayload.delete() } catch (_: Exception) {}
                                try { tempRootDir.deleteRecursively() } catch (_: Exception) {}
                            }
                        }
                    }
                    else -> throw IllegalStateException("Unsupported ZIP compression method ${loc.compressionMethod}")
                }
                outFiles.addAll(dumped)
                filesDoneCount += dumped.size
                cumulativeBytesDone += payloadWeightTotal
            }

            // 2. Extract selected direct .img/.bin entries
            val usedNames = outFiles.mapTo(mutableSetOf()) { it.name.lowercase(Locale.US) }
            val recordByPath = records.associateBy { it.name }
            val buf = ByteArray(512 * 1024)
            var lastUiMs = 0L

            for (sel in directSelections) {
                if (isCancelled()) throw CancellationException("Extraction cancelled")
                val cd = sel.zipEntryPath?.let { recordByPath[it] }
                    ?: records.firstOrNull {
                        it.name.replace('\\', '/').substringAfterLast('/')
                            .equals(sel.outputFileName, ignoreCase = true)
                    }
                    ?: continue
                val loc = resolveZipEntryLocation(channel, cd) ?: continue

                var targetName = sel.outputFileName
                if (targetName.lowercase(Locale.US) in usedNames) {
                    val base = targetName.substringBeforeLast('.')
                    val ext = targetName.substringAfterLast('.', "img")
                    var suffix = 2
                    while ("${base}_$suffix.$ext".lowercase(Locale.US) in usedNames) suffix++
                    targetName = "${base}_$suffix.$ext"
                }
                usedNames.add(targetName.lowercase(Locale.US))

                val outFile = File(outputDir, targetName)
                var writtenEntryBytes = 0L
                val entryExpected = maxOf(0L, loc.uncompressedSize)
                var completedEntry = false

                fun emitDirectProgress(force: Boolean) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastUiMs < 75L) return
                    lastUiMs = now
                    val totalNow = cumulativeBytesDone + writtenEntryBytes
                    val elapsedSec = maxOf(0.1, (now - startMs) / 1000.0)
                    val speedMb = (totalNow / (1024.0 * 1024.0)) / elapsedSec
                    val rawPct = if (filesDoneCount >= totalSelectedCount) 100 else ((totalNow * 100L) / totalExpectedBytes).toInt().coerceIn(0, 99)
                    highestPct = maxOf(highestPct, rawPct)
                    val workerPct = if (entryExpected > 0L) ((writtenEntryBytes * 100L) / entryExpected).toInt().coerceIn(0, 99) else 50
                    onProgress(
                        ZipExtractionProgress(
                            isExtracting = true,
                            currentFile = targetName,
                            filesExtracted = filesDoneCount,
                            totalFiles = totalSelectedCount,
                            totalBytesExtracted = totalNow,
                            totalPayloadBytes = totalExpectedBytes,
                            speedMbPerSec = speedMb,
                            percent = highestPct,
                            statusMessage = "Extracting $targetName...",
                            activeWorkers = if (force && filesDoneCount >= totalSelectedCount) emptyList() else listOf(
                                ActiveExtractionWorker(
                                    partitionName = targetName.substringBeforeLast('.'),
                                    fileSizeBytes = entryExpected,
                                    bytesWritten = writtenEntryBytes,
                                    percent = workerPct
                                )
                            )
                        )
                    )
                }

                onLog("Extracting $targetName...")
                emitDirectProgress(force = true)

                try {
                    val entryInput: InputStream = when (loc.compressionMethod) {
                        0 -> BoundedChannelInputStream(channel, loc.dataOffset, loc.uncompressedSize)
                        8 -> {
                            val inf = Inflater(true)
                            object : InflaterInputStream(
                                BufferedInputStream(
                                    BoundedChannelInputStream(channel, loc.dataOffset, loc.compressedSize),
                                    256 * 1024
                                ),
                                inf,
                                256 * 1024
                            ) {
                                override fun close() {
                                    try { super.close() } finally { inf.end() }
                                }
                            }
                        }
                        else -> throw IllegalStateException("Unsupported ZIP compression method ${loc.compressionMethod}")
                    }
                    entryInput.use { inStream ->
                        FileOutputStream(outFile).use { fos ->
                            var r: Int
                            while (inStream.read(buf).also { r = it } > 0) {
                                if (isCancelled()) throw CancellationException("Extraction cancelled")
                                fos.write(buf, 0, r)
                                writtenEntryBytes += r
                                emitDirectProgress(force = false)
                            }
                        }
                    }
                    completedEntry = true
                    cumulativeBytesDone += writtenEntryBytes
                    filesDoneCount++
                    outFiles.add(outFile)
                    emitDirectProgress(force = true)
                } finally {
                    if (!completedEntry) {
                        try { outFile.delete() } catch (_: Exception) {}
                    }
                }
            }
            return outFiles
        } catch (ce: CancellationException) {
            outFiles.forEach { f -> try { f.delete() } catch (_: Exception) {} }
            throw ce
        }
    }

    /**
     * Extracts only user-selected partition images from a ROM ZIP and saves them straight into
     * user-accessible storage (public Downloads/LeafFlasher/<Archive>/ or a user-picked SAF folder).
     */
    suspend fun extractSelectedPartitionsFromZip(
        context: Context,
        zipUri: Uri,
        archiveDisplayName: String,
        selectedEntries: List<ZipPartitionEntry>,
        outputTreeUri: Uri? = null,
        isCancelled: () -> Boolean = { false },
        onLog: (String) -> Unit = {},
        onProgress: (ZipExtractionProgress) -> Unit
    ): SelectiveExtractionResult = withContext(Dispatchers.IO) {
        if (selectedEntries.isEmpty()) {
            return@withContext SelectiveExtractionResult(
                success = false,
                errorMessage = "Select 1 or more partitions to extract"
            )
        }

        val archiveFolderName = sanitizeArchiveFolderName(archiveDisplayName)
        val sessionId = UUID.randomUUID().toString().take(8)
        val tempWorkDir = File(context.cacheDir, "leaf_selective_tmp/$sessionId")

        try {
            var directDestDir: File? = null
            var targetSafDoc: DocumentFile? = null
            val destDisplayPath: String

            if (outputTreeUri != null) {
                val doc = DocumentFile.fromTreeUri(context, outputTreeUri)
                if (doc == null || !doc.isDirectory || !doc.canWrite()) {
                    return@withContext SelectiveExtractionResult(
                        success = false,
                        errorMessage = "Selected output folder is not writable"
                    )
                }
                targetSafDoc = doc
                destDisplayPath = doc.name?.takeIf { it.isNotBlank() } ?: "Selected Folder"
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val leafFolder = File(downloadsDir, "LeafFlasher/$archiveFolderName")
                if (canWriteDirectlyInDir(leafFolder)) {
                    directDestDir = leafFolder
                    destDisplayPath = "Downloads/LeafFlasher/$archiveFolderName"
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    destDisplayPath = "Downloads/LeafFlasher/$archiveFolderName"
                } else {
                    return@withContext SelectiveExtractionResult(
                        success = false,
                        errorMessage = "Allow storage permission or choose an output folder"
                    )
                }
            }

            val extractionDir = directDestDir ?: tempWorkDir.apply { mkdirs() }
            val needsMediaStoreOrSafTransfer = directDestDir == null

            fun reportProgressScaled(raw: ZipExtractionProgress) {
                if (!needsMediaStoreOrSafTransfer) {
                    onProgress(raw)
                } else {
                    val scaledPct = ((raw.percent * 88) / 100).coerceIn(0, 88)
                    onProgress(raw.copy(percent = scaledPct))
                }
            }

            onProgress(
                ZipExtractionProgress(
                    isExtracting = true,
                    totalFiles = selectedEntries.size,
                    statusMessage = "Preparing selective extraction...",
                    percent = 2
                )
            )

            // Try fast seekable FileChannel path first
            var extractedLocalFiles: List<File>? = null
            var handledViaChannel = false
            try {
                context.contentResolver.openFileDescriptor(zipUri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        val ch = fis.channel
                        if (ch.size() >= 22L) {
                            handledViaChannel = true
                            extractedLocalFiles = extractSelectedFromSeekableChannel(
                                channel = ch,
                                selectedEntries = selectedEntries,
                                outputDir = extractionDir,
                                tempRootDir = File(context.cacheDir, "leaf_selective_tmp/spool_$sessionId"),
                                isCancelled = isCancelled,
                                onLog = onLog,
                                onProgress = ::reportProgressScaled
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                handledViaChannel = false
            }

            // Streaming fallback for non-seekable SAF sources
            if (!handledViaChannel || extractedLocalFiles == null) {
                val payloadSelections = selectedEntries
                    .filter { it.origin == ZipPartitionOrigin.PAYLOAD_BIN }
                    .map { it.name }
                    .toSet()
                val directSelections = selectedEntries
                    .filter { it.origin == ZipPartitionOrigin.DIRECT_ZIP_ENTRY }
                val wantedDirectNames = directSelections.map { it.outputFileName.lowercase(Locale.US) }.toSet()
                val localResults = mutableListOf<File>()

                val rawStream = context.contentResolver.openInputStream(zipUri)
                    ?: return@withContext SelectiveExtractionResult(
                        success = false,
                        errorMessage = "Could not open ROM ZIP stream"
                    )

                rawStream.use { input ->
                    ZipInputStream(BufferedInputStream(input, 512 * 1024)).use { zipIn ->
                        var entry = zipIn.nextEntry
                        val buf = ByteArray(512 * 1024)
                        while (entry != null) {
                            if (isCancelled()) throw CancellationException("Extraction cancelled")
                            val cleanName = File(entry.name).name
                            if (!entry.isDirectory) {
                                if (payloadSelections.isNotEmpty() && cleanName.equals("payload.bin", ignoreCase = true)) {
                                    val dumped = try {
                                        PayloadDumper.extractPayloadFromStream(
                                            stream = zipIn,
                                            outputDir = extractionDir,
                                            targetPartitions = payloadSelections,
                                            isCancelled = isCancelled,
                                            onSnapshot = { snap ->
                                                reportProgressScaled(
                                                    ZipExtractionProgress(
                                                        isExtracting = true,
                                                        currentFile = snap.activeWorkers.firstOrNull()?.partitionName?.let { "$it.img" } ?: "",
                                                        filesExtracted = snap.completedFiles,
                                                        totalFiles = selectedEntries.size,
                                                        totalBytesExtracted = snap.bytesExtracted,
                                                        totalPayloadBytes = snap.totalBytes,
                                                        speedMbPerSec = snap.speedMbPerSec,
                                                        percent = snap.overallPercent,
                                                        statusMessage = "Extracting from payload.bin...",
                                                        activeWorkers = snap.activeWorkers
                                                    )
                                                )
                                            }
                                        )
                                    } catch (_: PayloadDumper.NonSequentialPayloadException) {
                                        throw IllegalStateException("Please pick a local ZIP file for non-sequential payload fallback")
                                    }
                                    localResults.addAll(dumped)
                                    if (directSelections.isEmpty()) break
                                } else if (cleanName.lowercase(Locale.US) in wantedDirectNames) {
                                    val outFile = File(extractionDir, cleanName)
                                    var ok = false
                                    try {
                                        FileOutputStream(outFile).use { fos ->
                                            var r: Int
                                            while (zipIn.read(buf).also { r = it } > 0) {
                                                if (isCancelled()) throw CancellationException("Extraction cancelled")
                                                fos.write(buf, 0, r)
                                            }
                                        }
                                        ok = true
                                        localResults.add(outFile)
                                    } finally {
                                        if (!ok) {
                                            try { outFile.delete() } catch (_: Exception) {}
                                        }
                                    }
                                    if (localResults.size >= selectedEntries.size) break
                                }
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                }
                extractedLocalFiles = localResults
            }

            val finishedLocalFiles = extractedLocalFiles.orEmpty()
            if (finishedLocalFiles.isEmpty()) {
                return@withContext SelectiveExtractionResult(
                    success = false,
                    errorMessage = "No matching partitions were found to extract"
                )
            }

            val savedList = mutableListOf<SavedExtractedFile>()

            if (directDestDir != null) {
                val pathsToScan = finishedLocalFiles.map { it.absolutePath }.toTypedArray()
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        pathsToScan,
                        Array(pathsToScan.size) { "application/octet-stream" },
                        null
                    )
                } catch (_: Exception) {}

                for (f in finishedLocalFiles) {
                    val partStem = f.nameWithoutExtension
                    savedList.add(
                        SavedExtractedFile(
                            partitionName = partStem,
                            fileName = f.name,
                            sizeBytes = f.length(),
                            uri = f.toUri(),
                            displayPath = "$destDisplayPath/${f.name}"
                        )
                    )
                    onLog("✓ Saved ${f.name} -> $destDisplayPath/${f.name}")
                }
            } else if (targetSafDoc != null) {
                val totalToCopy = finishedLocalFiles.size
                val totalCopyBytes = finishedLocalFiles.sumOf { maxOf(0L, it.length()) }.coerceAtLeast(1L)
                var copiedBytesSoFar = 0L
                val buf = ByteArray(256 * 1024)

                finishedLocalFiles.forEachIndexed { idx, localFile ->
                    if (isCancelled()) throw CancellationException("Extraction cancelled")
                    val fName = localFile.name
                    val fSize = localFile.length()
                    onProgress(
                        ZipExtractionProgress(
                            isExtracting = true,
                            currentFile = fName,
                            filesExtracted = idx,
                            totalFiles = totalToCopy,
                            percent = (88 + ((copiedBytesSoFar * 12) / totalCopyBytes).toInt()).coerceIn(88, 99),
                            statusMessage = "Saving $fName to folder..."
                        )
                    )
                    targetSafDoc.findFile(fName)?.let { existing ->
                        try { existing.delete() } catch (_: Exception) {}
                    }
                    val docFile = targetSafDoc.createFile("application/octet-stream", fName)
                        ?: throw IllegalStateException("Cannot create $fName in chosen folder")
                    val outUri = docFile.uri
                    var copyOk = false
                    try {
                        val outStream = context.contentResolver.openOutputStream(outUri)
                            ?: throw IllegalStateException("Cannot write $fName to chosen folder")
                        outStream.use { os ->
                            FileInputStream(localFile).use { fis ->
                                var r: Int
                                while (fis.read(buf).also { r = it } > 0) {
                                    if (isCancelled()) throw CancellationException("Extraction cancelled")
                                    os.write(buf, 0, r)
                                    copiedBytesSoFar += r
                                }
                                os.flush()
                            }
                        }
                        copyOk = true
                    } finally {
                        if (!copyOk) {
                            try { docFile.delete() } catch (_: Exception) {}
                        }
                        try { localFile.delete() } catch (_: Exception) {}
                    }
                    savedList.add(
                        SavedExtractedFile(
                            partitionName = localFile.nameWithoutExtension,
                            fileName = fName,
                            sizeBytes = fSize,
                            uri = outUri,
                            displayPath = "$destDisplayPath/$fName"
                        )
                    )
                    onLog("✓ Saved $fName -> $destDisplayPath/$fName")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val relPath = "${Environment.DIRECTORY_DOWNLOADS}/LeafFlasher/$archiveFolderName"
                val relPathSlash = "$relPath/"
                val totalToCopy = finishedLocalFiles.size
                val totalCopyBytes = finishedLocalFiles.sumOf { maxOf(0L, it.length()) }.coerceAtLeast(1L)
                var copiedBytesSoFar = 0L
                val buf = ByteArray(256 * 1024)

                finishedLocalFiles.forEachIndexed { idx, localFile ->
                    if (isCancelled()) throw CancellationException("Extraction cancelled")
                    val fName = localFile.name
                    val fSize = localFile.length()
                    onProgress(
                        ZipExtractionProgress(
                            isExtracting = true,
                            currentFile = fName,
                            filesExtracted = idx,
                            totalFiles = totalToCopy,
                            percent = (88 + ((copiedBytesSoFar * 12) / totalCopyBytes).toInt()).coerceIn(88, 99),
                            statusMessage = "Saving $fName to Downloads..."
                        )
                    )

                    // Replace existing app-owned entry cleanly so filename stays exact (no duplicate "(1)" suffix)
                    try {
                        resolver.query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.MediaColumns._ID),
                            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND (${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)",
                            arrayOf(fName, relPathSlash, relPath),
                            null
                        )?.use { cursor ->
                            val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                            while (cursor.moveToNext() && idCol >= 0) {
                                val delUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                                try { resolver.delete(delUri, null, null) } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relPathSlash)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Could not create $fName in Downloads")
                    var insertedOk = false
                    try {
                        val os = resolver.openOutputStream(itemUri)
                            ?: throw IllegalStateException("Could not open Downloads output for $fName")
                        os.use { outStream ->
                            FileInputStream(localFile).use { fis ->
                                var r: Int
                                while (fis.read(buf).also { r = it } > 0) {
                                    if (isCancelled()) throw CancellationException("Extraction cancelled")
                                    outStream.write(buf, 0, r)
                                    copiedBytesSoFar += r
                                }
                                outStream.flush()
                            }
                        }
                        val publishValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }
                        resolver.update(itemUri, publishValues, null, null)
                        insertedOk = true
                    } finally {
                        if (!insertedOk) {
                            try { resolver.delete(itemUri, null, null) } catch (_: Exception) {}
                        }
                        try { localFile.delete() } catch (_: Exception) {}
                    }
                    savedList.add(
                        SavedExtractedFile(
                            partitionName = localFile.nameWithoutExtension,
                            fileName = fName,
                            sizeBytes = fSize,
                            uri = itemUri,
                            displayPath = "$destDisplayPath/$fName"
                        )
                    )
                    onLog("✓ Saved $fName -> $destDisplayPath/$fName")
                }
            }

            onProgress(
                ZipExtractionProgress(
                    isExtracting = false,
                    filesExtracted = savedList.size,
                    totalFiles = savedList.size,
                    percent = 100,
                    statusMessage = "Saved ${savedList.size} images to $destDisplayPath"
                )
            )

            SelectiveExtractionResult(
                success = true,
                savedFiles = savedList,
                destinationDisplayPath = destDisplayPath
            )
        } catch (_: CancellationException) {
            onProgress(ZipExtractionProgress(isExtracting = false, statusMessage = "Extraction cancelled"))
            SelectiveExtractionResult(
                success = false,
                cancelled = true,
                errorMessage = "Extraction cancelled"
            )
        } catch (e: Exception) {
            onProgress(
                ZipExtractionProgress(
                    isExtracting = false,
                    statusMessage = "Extraction failed: ${e.message}"
                )
            )
            SelectiveExtractionResult(
                success = false,
                errorMessage = e.message ?: "Selective extraction failed"
            )
        } finally {
            try { tempWorkDir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    /**
     * Cleans up the temporary extracted ROM folder to free storage after successful flash.
     */
    fun cleanupExtractedDirectory(context: Context) {
        try {
            val targetDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "leaf_extracted_rom")
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    /**
     * Generates a clean, robust flashing sequence from extracted ROM images.
     * Follows the exact universal A/B and CMF Phone 1 (Tetris) flashing methodology from flash.sh.
     * Supports targeting Slot A, Slot B, or A + B both slots.
     */
    fun generateSequenceFromImages(
        images: List<PartitionImageSource>,
        targetSlot: String = "a",
        wipeSuper: Boolean = false,
        wipeData: Boolean = false,
        rebootAfterFlash: Boolean = false,
        rebootTarget: String = "system"
    ): List<FlashTask> {
        val tasks = mutableListOf<FlashTask>()
        var id = 1

        val slotsToFlash = when (targetSlot.lowercase()) {
            "b" -> listOf("b")
            "both" -> listOf("a", "b")
            else -> listOf("a")
        }
        val activeSlot = if (targetSlot.lowercase() == "b") "b" else "a"

        val coreOrder = listOf(
            "boot", "dtbo", "init_boot", "vendor_boot", "apusys", "ccu", "connsys_bt",
            "connsys_gnss", "connsys_wifi", "dpm", "gpueb", "gz", "lk", "logo", "mcf_ota",
            "mcupm", "modem", "pi_img", "scp", "spmfw", "sspm", "tee", "vcp", "preloader"
        )
        val vbmetaOrder = listOf("vbmeta", "vbmeta_system", "vbmeta_vendor")
        val logicalOrder = listOf(
            "odm_dlkm", "odm", "vendor_dlkm", "product", "vendor",
            "system_dlkm", "system_ext", "system"
        )

        // 1. Initial slot
        tasks.add(
            FlashTask(
                id = id++,
                type = TaskType.SET_ACTIVE,
                rawCommandLine = "fastboot --set-active=$activeSlot",
                slot = activeSlot,
                phase = FlashingPhase.FIRMWARE_BOOTLOADER
            )
        )

        // Helper to find image file by stem name, preferring slot-specific image when available
        fun findImage(stem: String, preferredSlot: String? = null): PartitionImageSource? {
            val lowStem = stem.lowercase()
            if (preferredSlot != null) {
                val slotMatch = images.firstOrNull { f ->
                    val noExt = f.name.lowercase().substringBeforeLast('.')
                    noExt == "${lowStem}_$preferredSlot" ||
                        (lowStem == "preloader" && noExt == "preloader_$preferredSlot")
                }
                if (slotMatch != null) return slotMatch
            }
            return images.firstOrNull { f ->
                val base = f.stem
                base == lowStem ||
                (lowStem == "preloader" && (base == "preloader_raw" || base.startsWith("preloader")))
            }
        }

        // 2. Core Firmware Partitions
        val handledStems = mutableSetOf<String>()
        for (stem in coreOrder) {
            val defaultImg = findImage(stem) ?: continue
            handledStems.add(stem)
            handledStems.add(defaultImg.stem)
            if (stem == "preloader") {
                val img = findImage(stem, "a") ?: defaultImg
                tasks.add(
                    FlashTask(
                        id = id++,
                        type = TaskType.FLASH,
                        rawCommandLine = "fastboot flash preloader_a ${img.name}",
                        partition = "preloader_a",
                        fileName = img.name,
                        fileUri = img.uri,
                        fileSize = img.size,
                        phase = FlashingPhase.FIRMWARE_BOOTLOADER
                    )
                )
            } else {
                for (slot in slotsToFlash) {
                    val img = findImage(stem, slot) ?: defaultImg
                    val partTarget = "${stem}_$slot"
                    tasks.add(
                        FlashTask(
                            id = id++,
                            type = TaskType.FLASH,
                            rawCommandLine = "fastboot flash $partTarget ${img.name}",
                            partition = partTarget,
                            fileName = img.name,
                            fileUri = img.uri,
                            fileSize = img.size,
                            phase = FlashingPhase.FIRMWARE_BOOTLOADER
                        )
                    )
                }
            }
        }

        // 3. VBMeta Partitions
        for (stem in vbmetaOrder) {
            val defaultImg = findImage(stem) ?: continue
            handledStems.add(stem)
            handledStems.add(defaultImg.stem)
            for (slot in slotsToFlash) {
                val img = findImage(stem, slot) ?: defaultImg
                val partTarget = "${stem}_$slot"
                tasks.add(
                    FlashTask(
                        id = id++,
                        type = TaskType.FLASH,
                        rawCommandLine = "fastboot flash $partTarget ${img.name}",
                        partition = partTarget,
                        fileName = img.name,
                        fileUri = img.uri,
                        fileSize = img.size,
                        phase = FlashingPhase.FIRMWARE_BOOTLOADER
                    )
                )
            }
        }

        // Any remaining loose firmware images not covered in coreOrder, vbmetaOrder, or logicalOrder
        for (img in images) {
            val base = img.stem
            if (base.isNotBlank() &&
                base !in logicalOrder &&
                base != "super" &&
                base != "super_empty" &&
                base !in handledStems) {
                handledStems.add(base)
                for (slot in slotsToFlash) {
                    val partTarget = "${base}_$slot"
                    tasks.add(
                        FlashTask(
                            id = id++,
                            type = TaskType.FLASH,
                            rawCommandLine = "fastboot flash $partTarget ${img.name}",
                            partition = partTarget,
                            fileName = img.name,
                            fileUri = img.uri,
                            fileSize = img.size,
                            phase = FlashingPhase.FIRMWARE_BOOTLOADER
                        )
                    )
                }
            }
        }

        // 4. Fastbootd reboot transition
        val superImg = images.firstOrNull { it.name.equals("super.img", ignoreCase = true) || it.stem == "super" }
        val superEmpty = images.firstOrNull { it.name.equals("super_empty.img", ignoreCase = true) || it.stem == "super_empty" }
        val hasLogical = images.any { f -> f.stem in logicalOrder }

        if (hasLogical || superImg != null || superEmpty != null) {
            tasks.add(
                FlashTask(
                    id = id++,
                    type = TaskType.REBOOT_FASTBOOT,
                    rawCommandLine = "fastboot reboot fastboot",
                    phase = FlashingPhase.FASTBOOTD_TRANSITION
                )
            )

            // Only wipe-super when a device-matched super_empty image exists in the ROM or user overrides.
            if (superImg == null && superEmpty != null && (wipeSuper || superEmpty.uri != null || superEmpty.size > 0L)) {
                tasks.add(
                    FlashTask(
                        id = id++,
                        type = TaskType.FLASH,
                        rawCommandLine = "fastboot wipe-super ${superEmpty.name}",
                        partition = "super",
                        fileName = superEmpty.name,
                        fileUri = superEmpty.uri,
                        fileSize = superEmpty.size,
                        phase = FlashingPhase.LOGICAL_DYNAMIC
                    )
                )
            }

            // If super.img is present, flash super directly (e.g. for Port ROMs bundling monolithic super.img)
            if (superImg != null) {
                tasks.add(
                    FlashTask(
                        id = id++,
                        type = TaskType.FLASH,
                        rawCommandLine = "fastboot flash super ${superImg.name}",
                        partition = "super",
                        fileName = superImg.name,
                        fileUri = superImg.uri,
                        fileSize = superImg.size,
                        phase = FlashingPhase.LOGICAL_DYNAMIC
                    )
                )
            } else if (hasLogical) {
                // Per AOSP/source.android.com fastbootd docs, logical partitions inside super target
                // ONLY the active slot (never duplicate _a + _b in fastbootd even when A+B is chosen for bootloader).
                for (stem in logicalOrder) {
                    val img = findImage(stem) ?: continue
                    val partTarget = "${stem}_$activeSlot"
                    tasks.add(
                        FlashTask(
                            id = id++,
                            type = TaskType.FLASH,
                            rawCommandLine = "fastboot flash $partTarget ${img.name}",
                            partition = partTarget,
                            fileName = img.name,
                            fileUri = img.uri,
                            fileSize = img.size,
                            phase = FlashingPhase.LOGICAL_DYNAMIC
                        )
                    )
                }
            }
        }

        // 5. Finalize: Set active slot, optional data wipe, and optional post-flash reboot
        tasks.add(
            FlashTask(
                id = id++,
                type = TaskType.SET_ACTIVE,
                rawCommandLine = "fastboot --set-active=$activeSlot",
                slot = activeSlot,
                phase = FlashingPhase.FINALIZATION
            )
        )
        if (wipeData) {
            tasks.add(
                FlashTask(
                    id = id++,
                    type = TaskType.WIPE,
                    rawCommandLine = "fastboot -w",
                    phase = FlashingPhase.FINALIZATION
                )
            )
        }
        if (rebootAfterFlash) {
            val isBootloaderTarget = rebootTarget.equals("bootloader", ignoreCase = true)
            tasks.add(
                FlashTask(
                    id = id,
                    type = if (isBootloaderTarget) TaskType.REBOOT_BOOTLOADER else TaskType.REBOOT,
                    rawCommandLine = if (isBootloaderTarget) "fastboot reboot bootloader" else "fastboot reboot",
                    phase = FlashingPhase.FINALIZATION
                )
            )
        }

        return tasks
    }

    @JvmName("generateSequenceFromFiles")
    fun generateSequenceFromImages(
        files: List<File>,
        targetSlot: String = "a",
        wipeSuper: Boolean = false,
        wipeData: Boolean = false,
        rebootAfterFlash: Boolean = false,
        rebootTarget: String = "system"
    ): List<FlashTask> {
        return generateSequenceFromImages(
            images = files.map { PartitionImageSource(it.name, it.toUri(), it.length()) },
            targetSlot = targetSlot,
            wipeSuper = wipeSuper,
            wipeData = wipeData,
            rebootAfterFlash = rebootAfterFlash,
            rebootTarget = rebootTarget
        )
    }
}
