package com.sid_imp.leaf.script

import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.BasicArrayCache
import org.tukaani.xz.SingleXZInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance, multi-threaded pure-Kotlin parser and extractor for Android OTA payload.bin.
 * Built following the architecture of otaripper:
 * - Zero-Copy extraction directly from ZIP FileChannel (no intermediate payload.bin written to disk).
 * - Instant ZERO/DISCARD extent bypass (sparse zero guarantee by filesystem pre-allocation).
 * - Lock-free multi-core parallel reads using NIO FileChannel.
 * - Single-extent streaming decompression straight into output disk channels.
 * - BasicArrayCache memory pooling to eliminate JVM GC thrashing.
 * - Real-time granular operation progress reporting so large partitions never appear frozen.
 * - Native Zstandard (ZSTD), XZ, BZip2, and Raw support with magic-byte auto-detection.
 */
object PayloadDumper {

    private const val PAYLOAD_MAGIC = 0x43724155 // "CrAU"

    // Global reusable BasicArrayCache for genuine LZMA2/XZ buffer pooling
    private val xzArrayCache = BasicArrayCache.getInstance()

    interface PayloadSource : AutoCloseable {
        val totalSize: Long
        fun readFully(position: Long, dest: ByteArray)
    }

    class ChannelPayloadSource(
        private val channel: FileChannel,
        private val baseOffset: Long,
        override val totalSize: Long
    ) : PayloadSource {
        override fun readFully(position: Long, dest: ByteArray) {
            val buf = ByteBuffer.wrap(dest)
            var currentPos = baseOffset + position
            while (buf.hasRemaining()) {
                val read = channel.read(buf, currentPos)
                if (read <= 0) break
                currentPos += read
            }
        }
        override fun close() {
            // Channel lifetime is managed by caller
        }
    }

    class FilePayloadSource(file: File) : PayloadSource {
        private val channel: FileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
        override val totalSize: Long = file.length()
        override fun readFully(position: Long, dest: ByteArray) {
            val buf = ByteBuffer.wrap(dest)
            var currentPos = position
            while (buf.hasRemaining()) {
                val read = channel.read(buf, currentPos)
                if (read <= 0) break
                currentPos += read
            }
        }
        override fun close() {
            channel.close()
        }
    }

    data class Extent(val startBlock: Long, val numBlocks: Long)

    data class InstallOperation(
        val type: Int, // 0: REPLACE, 1: REPLACE_BZ, 6: ZERO, 7: DISCARD, 8: REPLACE_XZ, 11: ZSTD
        val dataOffset: Long,
        val dataLength: Long,
        val dstExtents: List<Extent>
    )

    data class PartitionInfo(
        val name: String,
        val size: Long,
        val operations: List<InstallOperation>
    )

    data class Manifest(
        val blockSize: Long,
        val partitions: List<PartitionInfo>
    )

    data class PartitionSummary(
        val name: String,
        val sizeBytes: Long
    )

    class NonSequentialPayloadException(message: String) : Exception(message)

    data class PayloadProgressSnapshot(
        val completedFiles: Int,
        val totalFiles: Int,
        val bytesExtracted: Long,
        val totalBytes: Long,
        val speedMbPerSec: Double,
        val overallPercent: Int,
        val activeWorkers: List<ActiveExtractionWorker>
    )

    private data class PayloadHeader(
        val fileFormatVersion: Long,
        val manifestSize: Long,
        val metadataSignatureSize: Int,
        val dataOffsetStart: Long
    )

    private fun readHeaderFromSource(source: PayloadSource): PayloadHeader {
        val headerBuf = ByteArray(24)
        source.readFully(0, headerBuf)
        val bb = ByteBuffer.wrap(headerBuf)
        val magic = bb.getInt()
        if (magic != PAYLOAD_MAGIC) {
            throw IllegalArgumentException("Invalid payload.bin magic: 0x${Integer.toHexString(magic)}")
        }
        val fileFormatVersion = bb.getLong()
        val manifestSize = bb.getLong()
        val metadataSignatureSize = if (fileFormatVersion >= 2L) bb.getInt() else 0
        val manifestOffset = if (fileFormatVersion >= 2L) 24L else 20L
        if (manifestSize <= 0L || manifestSize > 256L * 1024 * 1024) {
            throw IllegalStateException("Invalid manifest size: $manifestSize")
        }
        val dataOffsetStart = manifestOffset + manifestSize + metadataSignatureSize
        return PayloadHeader(
            fileFormatVersion = fileFormatVersion,
            manifestSize = manifestSize,
            metadataSignatureSize = metadataSignatureSize,
            dataOffsetStart = dataOffsetStart
        )
    }

    private fun readExactFromStream(stream: InputStream, size: Int): ByteArray {
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = stream.read(buf, offset, size - offset)
            if (read <= 0) throw EOFException("Unexpected EOF while reading payload stream ($offset / $size)")
            offset += read
        }
        return buf
    }

    private fun skipExactFromStream(stream: InputStream, bytesToSkip: Long, isCancelled: () -> Boolean = { false }) {
        var remaining = bytesToSkip
        val scratch = ByteArray(minOf(remaining, 128L * 1024L).toInt().coerceAtLeast(1))
        while (remaining > 0L) {
            if (isCancelled()) throw CancellationException("Extraction cancelled")
            val chunk = minOf(remaining, scratch.size.toLong()).toInt()
            val read = stream.read(scratch, 0, chunk)
            if (read <= 0) throw EOFException("Unexpected EOF while skipping $remaining payload bytes")
            remaining -= read
        }
    }

    /**
     * Reads only the CrAU header + manifest bytes to list partition names and sizes in milliseconds.
     */
    fun inspectPayload(source: PayloadSource): List<PartitionSummary> {
        val hdr = readHeaderFromSource(source)
        val manifestOffset = if (hdr.fileFormatVersion >= 2L) 24L else 20L
        val manifestBytes = ByteArray(hdr.manifestSize.toInt())
        source.readFully(manifestOffset, manifestBytes)
        return parseManifestSummary(manifestBytes)
    }

    fun inspectPayloadFile(payloadFile: File): List<PartitionSummary> {
        return FilePayloadSource(payloadFile).use { inspectPayload(it) }
    }

    fun inspectPayloadFromChannel(
        channel: FileChannel,
        baseOffset: Long,
        totalSize: Long
    ): List<PartitionSummary> {
        return ChannelPayloadSource(channel, baseOffset, totalSize).use { inspectPayload(it) }
    }

    fun inspectPayloadFromStream(stream: InputStream): List<PartitionSummary> {
        val hdrPrefix = readExactFromStream(stream, 20)
        val bb = ByteBuffer.wrap(hdrPrefix)
        val magic = bb.getInt()
        if (magic != PAYLOAD_MAGIC) {
            throw IllegalArgumentException("Invalid payload.bin magic: 0x${Integer.toHexString(magic)}")
        }
        val fileFormatVersion = bb.getLong()
        val manifestSize = bb.getLong()
        if (fileFormatVersion >= 2L) {
            readExactFromStream(stream, 4) // metadata signature size
        }
        if (manifestSize <= 0L || manifestSize > 256L * 1024 * 1024) {
            throw IllegalStateException("Invalid manifest size: $manifestSize")
        }
        val manifestBytes = readExactFromStream(stream, manifestSize.toInt())
        return parseManifestSummary(manifestBytes)
    }

    /**
     * Extracts partition images from a File payload.bin into outputDir using parallel multi-core processing.
     */
    suspend fun extractPayload(
        payloadFile: File,
        outputDir: File,
        targetPartitions: Set<String>? = null,
        isCancelled: () -> Boolean = { false },
        onPartitionLog: (partitionName: String) -> Unit = {},
        onProgress: (partitionName: String, partitionIndex: Int, totalPartitions: Int, percent: Int) -> Unit = { _, _, _, _ -> },
        onSnapshot: ((PayloadProgressSnapshot) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        FilePayloadSource(payloadFile).use { source ->
            extractFromSource(source, outputDir, targetPartitions, isCancelled, onPartitionLog, onProgress, onSnapshot)
        }
    }

    /**
     * Extracts partition images directly from a FileChannel at baseOffset (Zero-Copy without intermediate payload.bin).
     */
    suspend fun extractPayloadFromChannel(
        channel: FileChannel,
        baseOffset: Long,
        totalSize: Long,
        outputDir: File,
        targetPartitions: Set<String>? = null,
        isCancelled: () -> Boolean = { false },
        onPartitionLog: (partitionName: String) -> Unit = {},
        onProgress: (partitionName: String, partitionIndex: Int, totalPartitions: Int, percent: Int) -> Unit = { _, _, _, _ -> },
        onSnapshot: ((PayloadProgressSnapshot) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        ChannelPayloadSource(channel, baseOffset, totalSize).use { source ->
            extractFromSource(source, outputDir, targetPartitions, isCancelled, onPartitionLog, onProgress, onSnapshot)
        }
    }

    /**
     * Extracts selected partition images sequentially straight from a compressed/non-seekable payload.bin stream
     * without writing multi-gigabyte intermediate payload.bin data to disk.
     */
    suspend fun extractPayloadFromStream(
        stream: InputStream,
        outputDir: File,
        targetPartitions: Set<String>,
        isCancelled: () -> Boolean = { false },
        onPartitionLog: (partitionName: String) -> Unit = {},
        onProgress: (partitionName: String, partitionIndex: Int, totalPartitions: Int, percent: Int) -> Unit = { _, _, _, _ -> },
        onSnapshot: ((PayloadProgressSnapshot) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        if (isCancelled()) throw CancellationException("Extraction cancelled")

        val hdrPrefix = readExactFromStream(stream, 20)
        val bb = ByteBuffer.wrap(hdrPrefix)
        val magic = bb.getInt()
        if (magic != PAYLOAD_MAGIC) {
            throw IllegalArgumentException("Invalid payload.bin magic: 0x${Integer.toHexString(magic)}")
        }
        val fileFormatVersion = bb.getLong()
        val manifestSize = bb.getLong()
        val metadataSignatureSize = if (fileFormatVersion >= 2L) {
            ByteBuffer.wrap(readExactFromStream(stream, 4)).getInt()
        } else 0
        if (manifestSize <= 0L || manifestSize > 256L * 1024 * 1024) {
            throw IllegalStateException("Invalid manifest size: $manifestSize")
        }
        val manifestBytes = readExactFromStream(stream, manifestSize.toInt())
        if (metadataSignatureSize > 0) {
            skipExactFromStream(stream, metadataSignatureSize.toLong(), isCancelled)
        }

        val manifest = parseManifest(manifestBytes)
        val blockSize = if (manifest.blockSize > 0L) manifest.blockSize else 4096L
        val targetLower = targetPartitions.map { it.lowercase(Locale.US) }.toSet()
        val selectedPartitions = manifest.partitions.filter { it.name.lowercase(Locale.US) in targetLower }
        if (selectedPartitions.isEmpty()) {
            return@withContext emptyList()
        }

        data class StreamOpTask(
            val part: PartitionInfo,
            val op: InstallOperation
        )

        val streamOps = mutableListOf<StreamOpTask>()
        for (part in selectedPartitions) {
            for (op in part.operations) {
                if (op.type != 6 && op.type != 7 && op.dataLength > 0L) {
                    streamOps.add(StreamOpTask(part, op))
                }
            }
        }
        streamOps.sortWith(compareBy<StreamOpTask>({ it.op.dataOffset }, { it.op.dataLength }))

        // Verify strictly non-backtracking offset progression before mutating files
        var checkPos = 0L
        var lastOff = -1L
        var lastLen = -1L
        for (task in streamOps) {
            val isSamePrev = task.op.dataOffset == lastOff && task.op.dataLength == lastLen
            if (!isSamePrev && task.op.dataOffset < checkPos) {
                throw NonSequentialPayloadException("Overlapping or out-of-order payload offsets")
            }
            if (!isSamePrev) {
                checkPos = task.op.dataOffset + task.op.dataLength
                lastOff = task.op.dataOffset
                lastLen = task.op.dataLength
            }
        }

        val totalPartitions = selectedPartitions.size
        val totalPayloadBytes = selectedPartitions.sumOf { maxOf(0L, it.size) }.coerceAtLeast(1L)
        val startTime = System.currentTimeMillis()
        var lastEmitMs = 0L
        var highestPct = 0
        val partReportedBytes = linkedMapOf<String, Long>()
        val partCompletedOps = linkedMapOf<String, Int>()
        val partTotalDataOps = selectedPartitions.associate { p ->
            p.name to p.operations.count { it.type != 6 && it.type != 7 && it.dataLength > 0L }
        }
        val outFiles = selectedPartitions.associateWith { File(outputDir, "${it.name}.img") }
        val channels = linkedMapOf<String, FileChannel>()
        val finishedPartitions = mutableSetOf<String>()
        var succeeded = false

        try {
            selectedPartitions.forEach { part ->
                val file = outFiles.getValue(part)
                val ch = FileChannel.open(
                    file.toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                if (part.size > 0L) ch.truncate(part.size)
                channels[part.name] = ch
                partReportedBytes[part.name] = 0L
                partCompletedOps[part.name] = 0
                onPartitionLog(part.name)
            }

            fun emitStreamSnapshot(triggerName: String, force: Boolean) {
                val now = System.currentTimeMillis()
                if (!force && now - lastEmitMs < 75L) return
                lastEmitMs = now
                val doneCount = finishedPartitions.size
                val writtenNow = partReportedBytes.values.sum()
                val elapsedSec = maxOf(0.1, (now - startTime) / 1000.0)
                val speedMb = (writtenNow / (1024.0 * 1024.0)) / elapsedSec
                val rawPct = if (doneCount >= totalPartitions) 100 else ((writtenNow * 100L) / totalPayloadBytes).toInt().coerceIn(0, 99)
                highestPct = maxOf(highestPct, rawPct)
                val speedStr = String.format(Locale.US, "%.1f MB/s", speedMb)
                onProgress("$triggerName.img • $speedStr", doneCount, totalPartitions, highestPct)
                if (onSnapshot != null) {
                    val active = selectedPartitions
                        .filter { it.name !in finishedPartitions }
                        .map { p ->
                            val written = partReportedBytes[p.name] ?: 0L
                            val pct = if (p.size > 0L) ((written * 100L) / p.size).toInt().coerceIn(0, 99) else 0
                            ActiveExtractionWorker(
                                partitionName = p.name,
                                fileSizeBytes = maxOf(0L, p.size),
                                bytesWritten = written,
                                percent = pct
                            )
                        }
                    onSnapshot(
                        PayloadProgressSnapshot(
                            completedFiles = doneCount,
                            totalFiles = totalPartitions,
                            bytesExtracted = writtenNow,
                            totalBytes = totalPayloadBytes,
                            speedMbPerSec = speedMb,
                            overallPercent = highestPct,
                            activeWorkers = active
                        )
                    )
                }
            }

            // Mark any partitions that have zero data operations (all ZERO/DISCARD) as immediately finished
            selectedPartitions.forEach { p ->
                if ((partTotalDataOps[p.name] ?: 0) == 0) {
                    partReportedBytes[p.name] = maxOf(0L, p.size)
                    finishedPartitions.add(p.name)
                }
            }
            emitStreamSnapshot(selectedPartitions.first().name, force = true)

            val threadBuffer = ByteArray(512 * 1024)
            var currentBlobPos = 0L
            var prevOffset = -1L
            var prevLen = -1L
            var prevCompData: ByteArray? = null

            for (task in streamOps) {
                if (isCancelled()) throw CancellationException("Extraction cancelled")
                val op = task.op
                val compData = if (op.dataOffset == prevOffset && op.dataLength == prevLen && prevCompData != null) {
                    prevCompData
                } else {
                    if (op.dataOffset > currentBlobPos) {
                        skipExactFromStream(stream, op.dataOffset - currentBlobPos, isCancelled)
                        currentBlobPos = op.dataOffset
                    }
                    val bytes = readExactFromStream(stream, op.dataLength.toInt())
                    currentBlobPos += op.dataLength
                    prevOffset = op.dataOffset
                    prevLen = op.dataLength
                    prevCompData = bytes
                    bytes
                }

                val ch = channels.getValue(task.part.name)
                writeOperationToChannel(compData, op, ch, blockSize, threadBuffer)

                val covered = (partReportedBytes[task.part.name] ?: 0L) + op.dstExtents.sumOf { it.numBlocks * blockSize }
                val clamped = if (task.part.size > 0L) minOf(task.part.size, covered) else covered
                partReportedBytes[task.part.name] = clamped

                val opsDone = (partCompletedOps[task.part.name] ?: 0) + 1
                partCompletedOps[task.part.name] = opsDone
                val totalOpsForPart = partTotalDataOps[task.part.name] ?: 0
                val isPartDone = opsDone >= totalOpsForPart
                if (isPartDone) {
                    partReportedBytes[task.part.name] = maxOf(0L, task.part.size)
                    finishedPartitions.add(task.part.name)
                }
                emitStreamSnapshot(task.part.name, force = isPartDone)
            }

            succeeded = true
            selectedPartitions.map { outFiles.getValue(it) }
        } finally {
            channels.values.forEach { ch ->
                try { ch.close() } catch (_: Exception) {}
            }
            if (!succeeded) {
                outFiles.values.forEach { f ->
                    try { f.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun extractFromSource(
        source: PayloadSource,
        outputDir: File,
        targetPartitions: Set<String>?,
        isCancelled: () -> Boolean,
        onPartitionLog: (partitionName: String) -> Unit,
        onProgress: (partitionName: String, partitionIndex: Int, totalPartitions: Int, percent: Int) -> Unit,
        onSnapshot: ((PayloadProgressSnapshot) -> Unit)?
    ): List<File> = coroutineScope {
        outputDir.mkdirs()
        if (isCancelled()) throw CancellationException("Extraction cancelled")

        val hdr = readHeaderFromSource(source)
        val manifestOffset = if (hdr.fileFormatVersion >= 2L) 24L else 20L
        val manifestBytes = ByteArray(hdr.manifestSize.toInt())
        source.readFully(manifestOffset, manifestBytes)

        val dataOffsetStart = hdr.dataOffsetStart

        // 4. Parse DeltaArchiveManifest & optionally filter to user-selected partitions
        val manifest = parseManifest(manifestBytes)
        val blockSize = if (manifest.blockSize > 0) manifest.blockSize else 4096L
        val targetLower = targetPartitions?.map { it.lowercase(Locale.US) }?.toSet()
        val partitionsToExtract = if (targetLower != null) {
            manifest.partitions.filter { it.name.lowercase(Locale.US) in targetLower }
        } else {
            manifest.partitions
        }
        val totalPartitions = partitionsToExtract.size
        val totalPayloadBytes = partitionsToExtract.sumOf { maxOf(0L, it.size) }.coerceAtLeast(1L)

        if (totalPartitions == 0) {
            return@coroutineScope emptyList()
        }

        val completedCount = AtomicInteger(0)
        val totalBytesWritten = AtomicLong(0L)
        val highestPercent = AtomicInteger(0)
        val lastProgressEmitMs = AtomicLong(0L)
        val startTime = System.currentTimeMillis()
        val activeWorkersMap = ConcurrentHashMap<String, ActiveExtractionWorker>()
        val snapshotLock = Any()

        fun emitSnapshot(triggerName: String, force: Boolean, finishPartitionName: String? = null) {
            synchronized(snapshotLock) {
                if (finishPartitionName != null) {
                    activeWorkersMap.remove(finishPartitionName)
                    completedCount.incrementAndGet()
                }
                val now = System.currentTimeMillis()
                if (finishPartitionName == null && !force) {
                    val prev = lastProgressEmitMs.get()
                    if (now - prev < 75L) {
                        return
                    }
                }
                lastProgressEmitMs.set(now)
                val currentDone = completedCount.get()
                val writtenNow = totalBytesWritten.get()
                val elapsedSec = maxOf(0.1, (now - startTime) / 1000.0)
                val speedMb = (writtenNow / (1024.0 * 1024.0)) / elapsedSec
                val rawPct = if (totalPartitions == 0 || currentDone >= totalPartitions) {
                    100
                } else {
                    ((writtenNow * 100L) / totalPayloadBytes).toInt().coerceIn(0, 99)
                }
                val monotonicPct = highestPercent.accumulateAndGet(rawPct) { prev, next -> maxOf(prev, next) }
                val speedStr = String.format(Locale.US, "%.1f MB/s", speedMb)
                onProgress("$triggerName.img • $speedStr", currentDone, totalPartitions, monotonicPct)

                if (onSnapshot != null) {
                    val sortedWorkers = activeWorkersMap.values.sortedWith(
                        compareByDescending<ActiveExtractionWorker> { it.fileSizeBytes }
                            .thenBy { it.partitionName }
                    )
                    onSnapshot(
                        PayloadProgressSnapshot(
                            completedFiles = currentDone,
                            totalFiles = totalPartitions,
                            bytesExtracted = writtenNow,
                            totalBytes = totalPayloadBytes,
                            speedMbPerSec = speedMb,
                            overallPercent = monotonicPct,
                            activeWorkers = sortedWorkers
                        )
                    )
                }
            }
        }

        // High-throughput multi-core parallelism (up to 6 concurrent partition workers)
        val maxConcurrency = maxOf(2, minOf(6, Runtime.getRuntime().availableProcessors()))
        val semaphore = Semaphore(maxConcurrency)

        // Prioritize firmware partitions first so device controls & fastbootd files are ready immediately
        val criticalFirmware = setOf(
            "boot", "init_boot", "dtbo", "vendor_boot", "lk", "vbmeta", "vbmeta_system",
            "vbmeta_vendor", "preloader_raw", "logo", "tee", "modem", "apusys", "dpm"
        )
        val sortedPartitions = partitionsToExtract.sortedByDescending { it.name.lowercase() in criticalFirmware }

        val jobs = sortedPartitions.map { part ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (isCancelled()) throw CancellationException("Extraction cancelled")
                    onPartitionLog(part.name)
                    activeWorkersMap[part.name] = ActiveExtractionWorker(
                        partitionName = part.name,
                        fileSizeBytes = maxOf(0L, part.size),
                        bytesWritten = 0L,
                        percent = 0
                    )
                    emitSnapshot(part.name, force = false)

                    val outFile = File(outputDir, "${part.name}.img")
                    var reportedPartBytes = 0L
                    var completedOk = false
                    try {
                        extractSinglePartition(
                            source = source,
                            dataOffsetStart = dataOffsetStart,
                            part = part,
                            blockSize = blockSize,
                            outFile = outFile,
                            isCancelled = isCancelled
                        ) { coveredBytes, partPct ->
                            val clampedBytes = if (part.size > 0L) minOf(part.size, coveredBytes) else coveredBytes
                            val delta = (clampedBytes - reportedPartBytes).coerceAtLeast(0L)
                            if (delta > 0L) {
                                reportedPartBytes += delta
                                totalBytesWritten.addAndGet(delta)
                            }
                            activeWorkersMap[part.name] = ActiveExtractionWorker(
                                partitionName = part.name,
                                fileSizeBytes = maxOf(0L, part.size),
                                bytesWritten = reportedPartBytes,
                                percent = partPct.coerceIn(0, 99)
                            )
                            emitSnapshot(part.name, force = false)
                        }
                        completedOk = true
                    } finally {
                        if (!completedOk) {
                            try { outFile.delete() } catch (_: Exception) {}
                        }
                    }

                    val remainingDelta = (maxOf(0L, part.size) - reportedPartBytes).coerceAtLeast(0L)
                    if (remainingDelta > 0L) {
                        totalBytesWritten.addAndGet(remainingDelta)
                    }
                    emitSnapshot(part.name, force = true, finishPartitionName = part.name)
                    outFile
                }
            }
        }

        jobs.awaitAll()
    }

    private fun writeBufferFully(ch: FileChannel, bb: ByteBuffer, writePos: Long) {
        var pos = writePos
        while (bb.hasRemaining()) {
            val w = ch.write(bb, pos)
            if (w <= 0) break
            pos += w
        }
    }

    private fun writeOperationToChannel(
        compData: ByteArray,
        op: InstallOperation,
        ch: FileChannel,
        blockSize: Long,
        threadBuffer: ByteArray
    ) {
        // FAST PATH: Single-extent operations stream directly into the FileChannel
        if (op.dstExtents.size == 1) {
            val ext = op.dstExtents[0]
            val writePos = ext.startBlock * blockSize
            val maxBytes = ext.numBlocks * blockSize

            if (op.type == 0) { // Raw uncompressed REPLACE
                val toWrite = minOf(compData.size.toLong(), maxBytes).toInt()
                writeBufferFully(ch, ByteBuffer.wrap(compData, 0, toWrite), writePos)
            } else {
                val stream = createDecompressorStream(compData, op.type)
                if (stream != null) {
                    stream.use { s ->
                        decompressStreamToChannel(s, ch, writePos, maxBytes, threadBuffer)
                    }
                } else {
                    val toWrite = minOf(compData.size.toLong(), maxBytes).toInt()
                    writeBufferFully(ch, ByteBuffer.wrap(compData, 0, toWrite), writePos)
                }
            }
        } else {
            // MULTI-EXTENT PATH: decompress into sized buffer and distribute across extents
            val expectedBytes = op.dstExtents.sumOf { it.numBlocks * blockSize }
            val decompressed = decompressBlock(compData, op.type, expectedBytes)
            var dataOffset = 0
            for (ext in op.dstExtents) {
                val writePos = ext.startBlock * blockSize
                val bytesToWrite = minOf(
                    ext.numBlocks * blockSize,
                    (decompressed.size - dataOffset).toLong()
                ).toInt()

                if (bytesToWrite > 0) {
                    val buf = ByteBuffer.wrap(decompressed, dataOffset, bytesToWrite)
                    writeBufferFully(ch, buf, writePos)
                    dataOffset += bytesToWrite
                }
            }
        }
    }

    private fun extractSinglePartition(
        source: PayloadSource,
        dataOffsetStart: Long,
        part: PartitionInfo,
        blockSize: Long,
        outFile: File,
        isCancelled: () -> Boolean = { false },
        onOpProgress: ((coveredBytes: Long, partPercent: Int) -> Unit)? = null
    ) {
        val outChannel = FileChannel.open(
            outFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.READ,
            StandardOpenOption.TRUNCATE_EXISTING
        )

        outChannel.use { ch ->
            // Pre-allocate full partition size upfront.
            // On modern filesystems, all unwritten sectors are guaranteed to read back as zeros (sparse layout).
            if (part.size > 0) {
                ch.truncate(part.size)
            }

            // Dedicated 512KB chunk buffer for high-speed streaming writes per worker
            val threadBuffer = ByteArray(512 * 1024)
            val totalOps = part.operations.size
            var bytesCovered = 0L
            var lastOpReportMs = 0L

            for ((opIdx, op) in part.operations.withIndex()) {
                if (isCancelled()) throw CancellationException("Extraction cancelled")
                val opBlocksBytes = op.dstExtents.sumOf { it.numBlocks * blockSize }

                // OTARIPPER OPTIMIZATION: ZERO and DISCARD require NO disk writes.
                if (op.type != 6 && op.type != 7 && op.dataLength > 0) {
                    val compData = ByteArray(op.dataLength.toInt())
                    source.readFully(dataOffsetStart + op.dataOffset, compData)
                    writeOperationToChannel(compData, op, ch, blockSize, threadBuffer)
                }

                bytesCovered += opBlocksBytes
                val now = System.currentTimeMillis()
                if (opIdx == totalOps - 1 || now - lastOpReportMs >= 65L) {
                    lastOpReportMs = now
                    val partPct = when {
                        part.size > 0L -> ((minOf(part.size, bytesCovered) * 100L) / part.size).toInt().coerceIn(0, 99)
                        totalOps > 0 -> (((opIdx + 1) * 100) / totalOps).coerceIn(0, 99)
                        else -> 0
                    }
                    onOpProgress?.invoke(bytesCovered, partPct)
                }
            }
        }
    }

    /**
     * Creates a high-speed streaming decompressor utilizing magic-byte auto-detection and cached memory.
     */
    private fun createDecompressorStream(compData: ByteArray, opType: Int): InputStream? {
        val bais = ByteArrayInputStream(compData)

        // 1. Magic byte auto-detection
        // XZ Magic: FD 37 7A 58 5A 00
        if (compData.size >= 6 &&
            compData[0] == 0xFD.toByte() &&
            compData[1] == '7'.code.toByte() &&
            compData[2] == 'z'.code.toByte() &&
            compData[3] == 'X'.code.toByte() &&
            compData[4] == 'Z'.code.toByte() &&
            compData[5] == 0x00.toByte()
        ) {
            return try {
                SingleXZInputStream(bais, xzArrayCache)
            } catch (_: Exception) {
                try { XZInputStream(ByteArrayInputStream(compData), xzArrayCache) } catch (_: Exception) { null }
            }
        }

        // ZSTD Magic: 28 B5 2F FD
        if (compData.size >= 4 &&
            compData[0] == 0x28.toByte() &&
            compData[1] == 0xB5.toByte() &&
            compData[2] == 0x2F.toByte() &&
            compData[3] == 0xFD.toByte()
        ) {
            return try {
                ZstdInputStream(bais)
            } catch (_: Exception) { null }
        }

        // BZip2 Magic: 'B' 'Z' 'h'
        if (compData.size >= 3 &&
            compData[0] == 'B'.code.toByte() &&
            compData[1] == 'Z'.code.toByte() &&
            compData[2] == 'h'.code.toByte()
        ) {
            return try {
                BZip2CompressorInputStream(bais)
            } catch (_: Exception) { null }
        }

        // 2. Protobuf operation type fallback
        return when (opType) {
            1 -> try { BZip2CompressorInputStream(bais) } catch (_: Exception) { null }
            8 -> try { SingleXZInputStream(bais, xzArrayCache) } catch (_: Exception) {
                try { XZInputStream(ByteArrayInputStream(compData), xzArrayCache) } catch (_: Exception) { null }
            }
            11 -> try { ZstdInputStream(bais) } catch (_: Exception) { null }
            else -> null
        }
    }

    /**
     * Streams decompressed bytes directly to disk at writePos without allocating large intermediary heap buffers.
     */
    private fun decompressStreamToChannel(
        inStream: InputStream,
        ch: FileChannel,
        startPos: Long,
        maxBytes: Long,
        buf: ByteArray
    ): Long {
        var totalWritten = 0L
        var len: Int
        while (totalWritten < maxBytes) {
            val toRead = minOf(buf.size.toLong(), maxBytes - totalWritten).toInt()
            len = inStream.read(buf, 0, toRead)
            if (len <= 0) break

            val bb = ByteBuffer.wrap(buf, 0, len)
            var writtenNow = 0
            while (writtenNow < len) {
                val w = ch.write(bb, startPos + totalWritten + writtenNow)
                if (w <= 0) break
                writtenNow += w
            }
            totalWritten += len
        }
        return totalWritten
    }

    /**
     * High-speed block decompression using magic-byte auto-detection and SingleXZInputStream with ArrayCache.
     */
    private fun decompressBlock(compData: ByteArray, opType: Int, expectedBytes: Long = 256 * 1024L): ByteArray {
        val stream = createDecompressorStream(compData, opType)
        if (stream != null) {
            return try {
                stream.use { s -> readStreamToBuffer(s, expectedBytes) }
            } catch (_: Exception) {
                compData
            }
        }
        return compData
    }

    private fun readStreamToBuffer(stream: InputStream, expectedBytes: Long): ByteArray {
        val initCap = minOf(maxOf(expectedBytes.coerceAtMost(8L * 1024 * 1024).toInt(), 64 * 1024), 8 * 1024 * 1024)
        val out = ByteArrayOutputStream(initCap)
        val buf = ByteArray(64 * 1024)
        var len: Int
        while (stream.read(buf).also { len = it } > 0) {
            out.write(buf, 0, len)
        }
        return out.toByteArray()
    }

    /**
     * Parses DeltaArchiveManifest protobuf schema directly without protoc code generation.
     */
    private fun parseManifest(data: ByteArray): Manifest {
        var blockSize = 4096L
        val partitions = mutableListOf<PartitionInfo>()

        val reader = ProtoStreamReader(data)
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                3 -> blockSize = reader.readVarint() // uint32 block_size
                13 -> { // repeated PartitionUpdate partitions
                    val partBytes = reader.readBytes()
                    val partInfo = parsePartitionUpdate(partBytes)
                    if (partInfo != null) {
                        partitions.add(partInfo)
                    }
                }
                else -> reader.skip(wireType)
            }
        }

        return Manifest(blockSize, partitions)
    }

    private fun parsePartitionUpdate(data: ByteArray): PartitionInfo? {
        var name = ""
        var size = 0L
        val operations = mutableListOf<InstallOperation>()

        val reader = ProtoStreamReader(data)
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                1 -> name = reader.readString() // string partition_name
                7 -> { // PartitionInfo new_partition_info
                    val infoBytes = reader.readBytes()
                    size = parseNewPartitionInfo(infoBytes)
                }
                8 -> { // repeated InstallOperation operations
                    val opBytes = reader.readBytes()
                    val op = parseInstallOperation(opBytes)
                    if (op != null) {
                        operations.add(op)
                    }
                }
                else -> reader.skip(wireType)
            }
        }

        return if (name.isNotEmpty()) PartitionInfo(name, size, operations) else null
    }

    private fun parseManifestSummary(data: ByteArray): List<PartitionSummary> {
        val partitions = mutableListOf<PartitionSummary>()
        val reader = ProtoStreamReader(data)
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                13 -> { // repeated PartitionUpdate partitions
                    val partBytes = reader.readBytes()
                    parsePartitionSummary(partBytes)?.let { partitions.add(it) }
                }
                else -> reader.skip(wireType)
            }
        }
        return partitions
    }

    private fun parsePartitionSummary(data: ByteArray): PartitionSummary? {
        var name = ""
        var size = 0L
        val reader = ProtoStreamReader(data)
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                1 -> name = reader.readString()
                7 -> size = parseNewPartitionInfo(reader.readBytes())
                else -> reader.skip(wireType)
            }
        }
        return if (name.isNotEmpty()) PartitionSummary(name, size) else null
    }

    private fun parseNewPartitionInfo(data: ByteArray): Long {
        var size = 0L
        val reader = ProtoStreamReader(data)
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                1 -> size = reader.readVarint() // uint64 size
                else -> reader.skip(wireType)
            }
        }
        return size
    }

    private fun parseInstallOperation(data: ByteArray): InstallOperation? {
        var type = 0
        var dataOffset = 0L
        var dataLength = 0L
        val dstExtents = mutableListOf<Extent>()

        val reader = ProtoStreamReader(data)
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                1 -> type = reader.readVarint().toInt() // Type type
                2 -> dataOffset = reader.readVarint() // uint64 data_offset
                3 -> dataLength = reader.readVarint() // uint64 data_length
                6 -> { // repeated Extent dst_extents
                    val extBytes = reader.readBytes()
                    val ext = parseExtent(extBytes)
                    if (ext != null) dstExtents.add(ext)
                }
                else -> reader.skip(wireType)
            }
        }

        return InstallOperation(type, dataOffset, dataLength, dstExtents)
    }

    private fun parseExtent(data: ByteArray): Extent? {
        var startBlock = 0L
        var numBlocks = 0L
        val reader = ProtoStreamReader(data)
        while (reader.hasMore()) {
            val (fieldNum, wireType) = reader.readTag()
            when (fieldNum) {
                1 -> startBlock = reader.readVarint() // uint64 start_block
                2 -> numBlocks = reader.readVarint() // uint64 num_blocks
                else -> reader.skip(wireType)
            }
        }
        return Extent(startBlock, numBlocks)
    }

    /**
     * Lightweight binary Protobuf wire-format stream decoder.
     */
    private class ProtoStreamReader(private val buffer: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < buffer.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < buffer.size && shift < 64) {
                val b = buffer[pos++].toInt()
                result = result or ((b.toLong() and 0x7F) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        fun readTag(): Pair<Int, Int> {
            val tag = readVarint()
            val wireType = (tag and 0x07).toInt()
            val fieldNumber = (tag ushr 3).toInt()
            return Pair(fieldNumber, wireType)
        }

        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > buffer.size) {
                return ByteArray(0)
            }
            val bytes = buffer.copyOfRange(pos, pos + len)
            pos += len
            return bytes
        }

        fun readString(): String {
            val bytes = readBytes()
            return String(bytes, Charsets.UTF_8)
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint() // Varint
                1 -> pos = minOf(buffer.size, pos + 8) // 64-bit
                2 -> { // Length-delimited
                    val len = readVarint().toInt()
                    pos = minOf(buffer.size, pos + len)
                }
                5 -> pos = minOf(buffer.size, pos + 4) // 32-bit
                else -> pos = buffer.size // Unknown or end group
            }
        }
    }
}
