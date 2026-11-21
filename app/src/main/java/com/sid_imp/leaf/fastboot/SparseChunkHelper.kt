package com.sid_imp.leaf.fastboot

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SparseChunkHelper {

    const val SPARSE_MAGIC = 0xed26ff3a.toInt()
    const val CHUNK_TYPE_RAW = 0xCAC1.toShort()
    const val CHUNK_TYPE_FILL = 0xCAC2.toShort()
    const val CHUNK_TYPE_DONT_CARE = 0xCAC3.toShort()
    const val CHUNK_TYPE_CRC32 = 0xCAC4.toShort()

    const val DEFAULT_MAX_CHUNK_BYTES = 512 * 1024 * 1024L // 512 MB safe chunk size
    const val BLOCK_SIZE = 4096L

    data class PlannedChunk(
        val chunkType: Short,
        val chunkBlocks: Long,
        val totalSz: Long,
        val dataStreamOffset: Long,
        val dataLength: Long
    )

    data class SparseSlicePlan(
        val sliceIndex: Int,
        val startBlock: Long,
        val sliceBlocks: Long,
        val blkSz: Long,
        val chunks: List<PlannedChunk>,
        val wireBytes: Long
    )

    /**
     * Inspects header bytes to determine if image is an Android sparse file.
     */
    fun isSparse(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < 4) return false
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.int == SPARSE_MAGIC
    }

    /**
     * Computes the real target partition size needed in bytes.
     * If sparse image, returns total_blks * blk_sz.
     * If raw image, returns 4KB sector-aligned file size.
     */
    fun computeTargetPartitionSize(headerBytes: ByteArray, fileLength: Long): Long {
        if (headerBytes.size >= 28 && isSparse(headerBytes)) {
            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.int // magic
            buffer.short // major
            buffer.short // minor
            buffer.short // file_hdr_sz
            buffer.short // chunk_hdr_sz
            val blkSz = buffer.int.toLong() and 0xFFFFFFFFL
            val totalBlks = buffer.int.toLong() and 0xFFFFFFFFL
            if (blkSz > 0L && totalBlks > 0L) {
                return totalBlks * blkSz
            }
        }
        return (fileLength + (BLOCK_SIZE - 1L)) / BLOCK_SIZE * BLOCK_SIZE
    }

    /**
     * Builds a 28-byte sparse header + optional 12-byte DONT_CARE prefix header for a slice.
     */
    fun createSliceSparseHeader(
        totalBlks: Long,
        totalChunks: Int,
        blkSz: Long = BLOCK_SIZE,
        startBlock: Long = 0L
    ): ByteArray {
        val hasDontCare = startBlock > 0L
        val headerSize = 28 + (if (hasDontCare) 12 else 0)
        val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(SPARSE_MAGIC)
        buffer.putShort(1.toShort()) // major_version
        buffer.putShort(0.toShort()) // minor_version
        buffer.putShort(28.toShort()) // file_hdr_sz
        buffer.putShort(12.toShort()) // chunk_hdr_sz
        buffer.putInt(blkSz.toInt()) // blk_sz
        buffer.putInt(totalBlks.toInt()) // total_blks
        buffer.putInt(totalChunks) // total_chunks
        buffer.putInt(0) // image_checksum

        if (hasDontCare) {
            buffer.putShort(CHUNK_TYPE_DONT_CARE)
            buffer.putShort(0.toShort()) // reserved1
            buffer.putInt(startBlock.toInt()) // chunk_sz (blocks)
            buffer.putInt(12) // total_sz (header only)
        }

        return buffer.array()
    }

    /**
     * Builds a 12-byte chunk header.
     */
    fun createChunkHeader(
        chunkType: Short,
        chunkBlocks: Long,
        totalSz: Long
    ): ByteArray {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(chunkType)
        buffer.putShort(0.toShort()) // reserved1
        buffer.putInt(chunkBlocks.toInt())
        buffer.putInt(totalSz.toInt())
        return buffer.array()
    }

    /**
     * Builds a 28-byte sparse header + chunk headers for a raw slice.
     */
    fun createRawSliceHeader(
        startBlock: Long,
        chunkBlocks: Long,
        sliceDataBytes: Long
    ): ByteArray {
        val hasDontCare = startBlock > 0L
        val headerSize = 28 + (if (hasDontCare) 12 else 0) + 12
        val buffer = ByteBuffer.allocate(headerSize).order(ByteOrder.LITTLE_ENDIAN)

        // 28-byte sparse_header
        buffer.putInt(SPARSE_MAGIC)
        buffer.putShort(1.toShort()) // major_version
        buffer.putShort(0.toShort()) // minor_version
        buffer.putShort(28.toShort()) // file_hdr_sz
        buffer.putShort(12.toShort()) // chunk_hdr_sz
        buffer.putInt(BLOCK_SIZE.toInt()) // blk_sz
        buffer.putInt((startBlock + chunkBlocks).toInt()) // total_blks
        buffer.putInt(if (hasDontCare) 2 else 1) // total_chunks
        buffer.putInt(0) // image_checksum

        // Optional 12-byte CHUNK_TYPE_DONT_CARE
        if (hasDontCare) {
            buffer.putShort(CHUNK_TYPE_DONT_CARE)
            buffer.putShort(0.toShort()) // reserved1
            buffer.putInt(startBlock.toInt()) // chunk_sz (blocks)
            buffer.putInt(12) // total_sz (header only)
        }

        // 12-byte CHUNK_TYPE_RAW
        val alignedWireBytes = chunkBlocks * BLOCK_SIZE
        buffer.putShort(CHUNK_TYPE_RAW)
        buffer.putShort(0.toShort()) // reserved1
        buffer.putInt(chunkBlocks.toInt()) // chunk_sz (blocks)
        buffer.putInt((12L + alignedWireBytes).toInt()) // total_sz (header + aligned data)

        return buffer.array()
    }

    /**
     * Parses an Android sparse file and creates an AOSP-compliant resparse plan,
     * splitting at chunk boundaries into sub-files each strictly <= maxSliceBytes.
     */
    fun buildSparseResparsePlan(
        openStream: () -> InputStream?,
        maxSliceBytes: Long
    ): List<SparseSlicePlan>? {
        val stream = openStream() ?: return null
        try {
            val bis = if (stream is BufferedInputStream) stream else BufferedInputStream(stream, 64 * 1024)
            val headerBuf = ByteArray(28)
            var readHdr = 0
            while (readHdr < 28) {
                val r = bis.read(headerBuf, readHdr, 28 - readHdr)
                if (r <= 0) break
                readHdr += r
            }
            if (readHdr < 28 || !isSparse(headerBuf)) return null

            val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
            bb.int // magic
            val major = bb.short
            val minor = bb.short
            val fileHdrSz = bb.short.toInt() and 0xFFFF
            val chunkHdrSz = bb.short.toInt() and 0xFFFF
            val blkSz = bb.int.toLong() and 0xFFFFFFFFL
            val totalBlks = bb.int.toLong() and 0xFFFFFFFFL
            val totalChunks = bb.int.toLong() and 0xFFFFFFFFL

            if (blkSz <= 0L || totalBlks <= 0L || totalChunks <= 0L || fileHdrSz < 28 || chunkHdrSz < 12) {
                return null
            }

            var currentStreamPos = 28L
            val extraHdr = fileHdrSz - 28
            if (extraHdr > 0) {
                var skippedExtra = 0L
                while (skippedExtra < extraHdr) {
                    val s = bis.skip(extraHdr.toLong() - skippedExtra)
                    if (s <= 0) {
                        val d = bis.read()
                        if (d < 0) break
                        skippedExtra += 1
                    } else {
                        skippedExtra += s
                    }
                }
                currentStreamPos += extraHdr
            }

            val chunkHdrBuf = ByteArray(chunkHdrSz)
            val allChunks = mutableListOf<PlannedChunk>()
            for (i in 0L until totalChunks) {
                var readChdr = 0
                while (readChdr < chunkHdrSz) {
                    val r = bis.read(chunkHdrBuf, readChdr, chunkHdrSz - readChdr)
                    if (r <= 0) break
                    readChdr += r
                }
                if (readChdr < chunkHdrSz) return null

                val cbb = ByteBuffer.wrap(chunkHdrBuf).order(ByteOrder.LITTLE_ENDIAN)
                val cType = cbb.short
                cbb.short // reserved
                val cBlocks = cbb.int.toLong() and 0xFFFFFFFFL
                val cTotalSz = cbb.int.toLong() and 0xFFFFFFFFL

                if (cTotalSz < chunkHdrSz) return null

                val dataLen = cTotalSz - chunkHdrSz
                val dataOffset = currentStreamPos + chunkHdrSz

                if (cType != CHUNK_TYPE_CRC32) {
                    allChunks.add(PlannedChunk(cType, cBlocks, cTotalSz, dataOffset, dataLen))
                }

                var skipped = 0L
                while (skipped < dataLen) {
                    val s = bis.skip(dataLen - skipped)
                    if (s <= 0) {
                        val discard = ByteArray(minOf(64 * 1024L, dataLen - skipped).toInt())
                        val d = bis.read(discard)
                        if (d <= 0) break
                        skipped += d
                    } else {
                        skipped += s
                    }
                }
                currentStreamPos += cTotalSz
            }

            // Group into slices <= maxSliceBytes
            val slices = mutableListOf<SparseSlicePlan>()
            var currentSliceChunks = mutableListOf<PlannedChunk>()
            var startBlockOfCurrentSlice = 0L
            var currentSliceWire = 28L
            var totalBlocksCovered = 0L

            for (chunk in allChunks) {
                val wireIfAdded = currentSliceWire + chunk.totalSz
                if (wireIfAdded > maxSliceBytes && currentSliceChunks.isNotEmpty()) {
                    val sliceBlocks = currentSliceChunks.sumOf { it.chunkBlocks }
                    slices.add(
                        SparseSlicePlan(
                            sliceIndex = slices.size,
                            startBlock = startBlockOfCurrentSlice,
                            sliceBlocks = sliceBlocks,
                            blkSz = blkSz,
                            chunks = ArrayList(currentSliceChunks),
                            wireBytes = currentSliceWire
                        )
                    )
                    startBlockOfCurrentSlice = totalBlocksCovered
                    currentSliceChunks = mutableListOf()
                    currentSliceWire = 28L + (if (startBlockOfCurrentSlice > 0L) 12L else 0L)
                }

                if (chunk.chunkType == CHUNK_TYPE_RAW && chunk.totalSz > maxSliceBytes) {
                    val maxChunkData = ((maxSliceBytes - 64) / blkSz) * blkSz
                    var chunkOffset = 0L
                    var remainingBlocks = chunk.chunkBlocks
                    while (remainingBlocks > 0) {
                        val blocksThisPart = minOf(remainingBlocks, maxChunkData / blkSz)
                        val dataBytesThisPart = blocksThisPart * blkSz
                        val partWire = 12L + dataBytesThisPart

                        if (currentSliceWire + partWire > maxSliceBytes && currentSliceChunks.isNotEmpty()) {
                            val sliceBlocks = currentSliceChunks.sumOf { it.chunkBlocks }
                            slices.add(
                                SparseSlicePlan(
                                    sliceIndex = slices.size,
                                    startBlock = startBlockOfCurrentSlice,
                                    sliceBlocks = sliceBlocks,
                                    blkSz = blkSz,
                                    chunks = ArrayList(currentSliceChunks),
                                    wireBytes = currentSliceWire
                                )
                            )
                            startBlockOfCurrentSlice = totalBlocksCovered
                            currentSliceChunks = mutableListOf()
                            currentSliceWire = 28L + (if (startBlockOfCurrentSlice > 0L) 12L else 0L)
                        }

                        currentSliceChunks.add(
                            PlannedChunk(
                                chunkType = CHUNK_TYPE_RAW,
                                chunkBlocks = blocksThisPart,
                                totalSz = partWire,
                                dataStreamOffset = chunk.dataStreamOffset + chunkOffset,
                                dataLength = dataBytesThisPart
                            )
                        )
                        currentSliceWire += partWire
                        totalBlocksCovered += blocksThisPart
                        chunkOffset += dataBytesThisPart
                        remainingBlocks -= blocksThisPart
                    }
                } else {
                    currentSliceChunks.add(chunk)
                    currentSliceWire += chunk.totalSz
                    totalBlocksCovered += chunk.chunkBlocks
                }
            }

            if (currentSliceChunks.isNotEmpty()) {
                val sliceBlocks = currentSliceChunks.sumOf { it.chunkBlocks }
                slices.add(
                    SparseSlicePlan(
                        sliceIndex = slices.size,
                        startBlock = startBlockOfCurrentSlice,
                        sliceBlocks = sliceBlocks,
                        blkSz = blkSz,
                        chunks = ArrayList(currentSliceChunks),
                        wireBytes = currentSliceWire
                    )
                )
            }

            return slices
        } catch (_: Exception) {
            return null
        } finally {
            try { stream.close() } catch (_: Exception) {}
        }
    }
}
