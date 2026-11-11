package com.sid_imp.leaf.script

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
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

    /**
     * Fast binary parser that reads the ZIP Central Directory (supporting both standard ZIP and ZIP64)
     * to locate an entry in < 5ms without unpacking any files.
     */
    internal fun findZipEntry(
        channel: FileChannel,
        targetSuffix: String,
        preferredContains: String? = null
    ): ZipEntryLocation? {
        val fileSize = channel.size()
        if (fileSize < 22) return null

        val searchSize = minOf(fileSize, 65536L).toInt()
        val tailBuf = ByteBuffer.allocate(searchSize).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(fileSize - searchSize)
        channel.read(tailBuf)
        tailBuf.flip()

        var eocdPos = -1
        for (i in (searchSize - 22) downTo 0) {
            if (tailBuf.getInt(i) == 0x06054B50) {
                eocdPos = i
                break
            }
        }
        if (eocdPos < 0) return null

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

        if (cdOffset >= fileSize) return null

        channel.position(cdOffset)
        val headerBuf = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
        var fallbackMatch: ZipEntryLocation? = null

        for (i in 0 until minOf(totalEntries, 65535L)) {
            headerBuf.clear()
            val read = channel.read(headerBuf)
            if (read < 46) break
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
            channel.read(ByteBuffer.wrap(nameBytes))
            val entryName = String(nameBytes, Charsets.UTF_8)

            var extraBytes: ByteArray? = null
            if (extraLen > 0) {
                extraBytes = ByteArray(extraLen)
                channel.read(ByteBuffer.wrap(extraBytes))
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

            if (entryName.endsWith(targetSuffix, ignoreCase = true)) {
                val savedPos = channel.position()
                val locBuf = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                channel.position(localHdrOffset)
                val locRead = channel.read(locBuf)
                if (locRead < 30) {
                    channel.position(savedPos)
                    continue
                }
                locBuf.flip()
                if (locBuf.getInt(0) != 0x04034B50) {
                    channel.position(savedPos)
                    continue
                }

                val locNameLen = locBuf.getShort(26).toInt() and 0xFFFF
                val locExtraLen = locBuf.getShort(28).toInt() and 0xFFFF
                val dataStart = localHdrOffset + 30 + locNameLen + locExtraLen
                val found = ZipEntryLocation(entryName, dataStart, uncompressedSize, method, compressedSize)

                if (preferredContains == null || entryName.contains(preferredContains, ignoreCase = true)) {
                    return found
                }
                if (fallbackMatch == null) {
                    fallbackMatch = found
                }
                channel.position(savedPos)
            }
        }
        return fallbackMatch
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
