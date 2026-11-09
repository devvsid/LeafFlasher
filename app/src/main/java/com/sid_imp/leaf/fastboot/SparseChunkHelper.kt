package com.sid_imp.leaf.fastboot

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
        buffer.putShort(CHUNK_TYPE_RAW)
        buffer.putShort(0.toShort()) // reserved1
        buffer.putInt(chunkBlocks.toInt()) // chunk_sz (blocks)
        buffer.putInt((12L + sliceDataBytes).toInt()) // total_sz (header + data)

        return buffer.array()
    }
}
