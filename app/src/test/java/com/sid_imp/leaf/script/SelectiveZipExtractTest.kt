package com.sid_imp.leaf.script

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SelectiveZipExtractTest {

    private fun encodeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun writeFieldBytes(out: ByteArrayOutputStream, fieldNum: Int, bytes: ByteArray) {
        encodeVarint(out, ((fieldNum shl 3) or 2).toLong())
        encodeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeFieldVarint(out: ByteArrayOutputStream, fieldNum: Int, value: Long) {
        encodeVarint(out, ((fieldNum shl 3) or 0).toLong())
        encodeVarint(out, value)
    }

    private fun buildSyntheticPayload(specs: List<Pair<String, ByteArray>>): ByteArray {
        val blockSize = 4096L
        val dataStream = ByteArrayOutputStream()
        val manifestOut = ByteArrayOutputStream()
        writeFieldVarint(manifestOut, 3, blockSize)

        var currentDataOffset = 0L
        for ((name, rawBytes) in specs) {
            dataStream.write(rawBytes)
            val blocks = (rawBytes.size.toLong() + blockSize - 1L) / blockSize

            val extOut = ByteArrayOutputStream().also {
                writeFieldVarint(it, 1, 0L)
                writeFieldVarint(it, 2, blocks)
            }.toByteArray()

            val opOut = ByteArrayOutputStream().also {
                writeFieldVarint(it, 1, 0L) // REPLACE
                writeFieldVarint(it, 2, currentDataOffset)
                writeFieldVarint(it, 3, rawBytes.size.toLong())
                writeFieldBytes(it, 6, extOut)
            }.toByteArray()

            val infoOut = ByteArrayOutputStream().also {
                writeFieldVarint(it, 1, rawBytes.size.toLong())
            }.toByteArray()

            val partOut = ByteArrayOutputStream().also {
                writeFieldBytes(it, 1, name.toByteArray(Charsets.UTF_8))
                writeFieldBytes(it, 7, infoOut)
                writeFieldBytes(it, 8, opOut)
            }.toByteArray()

            writeFieldBytes(manifestOut, 13, partOut)
            currentDataOffset += rawBytes.size
        }

        val manifestBytes = manifestOut.toByteArray()
        val dataBytes = dataStream.toByteArray()
        val hdr = ByteBuffer.allocate(24).apply {
            putInt(0x43724155) // CrAU
            putLong(2L)
            putLong(manifestBytes.size.toLong())
            putInt(0)
        }.array()

        return ByteArrayOutputStream().apply {
            write(hdr)
            write(manifestBytes)
            write(dataBytes)
        }.toByteArray()
    }

    private fun putZipEntry(zos: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        if (stored) {
            val crc = CRC32().apply { update(data) }.value
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            entry.crc = crc
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    @Test
    fun testInspectAndSelectiveExtractPayloadFromStoredAndDeflatedZips() = runBlocking {
        val specs = listOf(
            "system" to ByteArray(8192) { idx -> (idx % 251).toByte() },
            "vendor_boot" to ByteArray(4096) { 0x33 },
            "boot" to ("ANDROID!BOOT".toByteArray() + ByteArray(4096 - 12) { 0x44 }),
            "init_boot" to ("ANDROID!INIT".toByteArray() + ByteArray(4096 - 12) { 0x55 })
        )
        val payloadBytes = buildSyntheticPayload(specs)

        val tempRoot = File(System.getProperty("java.io.tmpdir"), "leaf_selective_payload_test")
        tempRoot.deleteRecursively()
        tempRoot.mkdirs()

        try {
            val storedZip = File(tempRoot, "ota_stored.zip")
            ZipOutputStream(FileOutputStream(storedZip)).use { zos ->
                putZipEntry(zos, "META-INF/com/android/metadata", "post-build=1".toByteArray(), stored = false)
                putZipEntry(zos, "payload.bin", payloadBytes, stored = true)
            }

            val deflatedZip = File(tempRoot, "ota_deflated.zip")
            ZipOutputStream(FileOutputStream(deflatedZip)).use { zos ->
                putZipEntry(zos, "payload.bin", payloadBytes, stored = false)
            }

            for ((label, zipFile) in listOf("stored" to storedZip, "deflated" to deflatedZip)) {
                val inspection = RomZipExtractor.inspectZipFilePartitions(zipFile)
                assertTrue("[$label] inspection failed: ${inspection.errorMessage}", inspection.success)
                assertTrue(inspection.hasPayloadBin)
                assertEquals(4, inspection.partitions.size)

                // Root/recovery images are prioritized at top: init_boot, boot, vendor_boot, system
                assertEquals(
                    listOf("init_boot", "boot", "vendor_boot", "system"),
                    inspection.partitions.map { it.name }
                )
                assertEquals("init_boot.img", inspection.partitions[0].outputFileName)
                assertEquals(4096L, inspection.partitions[0].sizeBytes)
                assertEquals(ZipPartitionOrigin.PAYLOAD_BIN, inspection.partitions[0].origin)

                val selected = inspection.partitions.filter { it.name == "boot" || it.name == "init_boot" }
                val outDir = File(tempRoot, "out_$label")
                val extracted = RomZipExtractor.extractSelectedPartitionsToDir(
                    zipFile = zipFile,
                    selectedEntries = selected,
                    outputDir = outDir
                )

                assertEquals(2, extracted.size)
                assertTrue(File(outDir, "boot.img").exists())
                assertTrue(File(outDir, "init_boot.img").exists())
                assertFalse("Unselected partition must not be extracted", File(outDir, "system.img").exists())
                assertFalse("Unselected partition must not be extracted", File(outDir, "vendor_boot.img").exists())

                assertArrayEquals(specs.first { it.first == "boot" }.second, File(outDir, "boot.img").readBytes())
                assertArrayEquals(specs.first { it.first == "init_boot" }.second, File(outDir, "init_boot.img").readBytes())
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testInspectAndSelectiveExtractFastbootArchiveEntries() = runBlocking {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "leaf_selective_fastboot_test")
        tempRoot.deleteRecursively()
        tempRoot.mkdirs()

        try {
            val bootData = ByteArray(16384) { (it * 7).toByte() }
            val initBootData = ByteArray(12288) { (it * 11 + 3).toByte() }
            val vendorBootData = ByteArray(8192) { 0x6A }

            val fbZip = File(tempRoot, "fastboot_rom.zip")
            ZipOutputStream(FileOutputStream(fbZip)).use { zos ->
                putZipEntry(zos, "rom/flash_all.sh", "fastboot flash boot images/boot.img".toByteArray(), stored = false)
                putZipEntry(zos, "rom/images/vendor_boot.img", vendorBootData, stored = false)
                putZipEntry(zos, "rom/images/boot.img", bootData, stored = true)
                putZipEntry(zos, "rom/images/init_boot.img", initBootData, stored = false)
            }

            val inspection = RomZipExtractor.inspectZipFilePartitions(fbZip)
            assertTrue("Inspection failed: ${inspection.errorMessage}", inspection.success)
            assertFalse(inspection.hasPayloadBin)
            assertEquals(3, inspection.partitions.size)
            assertEquals(listOf("init_boot", "boot", "vendor_boot"), inspection.partitions.map { it.name })
            assertTrue(inspection.partitions.all { it.origin == ZipPartitionOrigin.DIRECT_ZIP_ENTRY })

            val onlyInitBoot = inspection.partitions.filter { it.name == "init_boot" }
            val outDir = File(tempRoot, "out_fb")
            val extracted = RomZipExtractor.extractSelectedPartitionsToDir(
                zipFile = fbZip,
                selectedEntries = onlyInitBoot,
                outputDir = outDir
            )
            assertEquals(1, extracted.size)
            assertTrue(File(outDir, "init_boot.img").exists())
            assertFalse(File(outDir, "boot.img").exists())
            assertFalse(File(outDir, "flash_all.sh").exists())
            assertArrayEquals(initBootData, File(outDir, "init_boot.img").readBytes())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testSanitizeArchiveFolderNameAndCancellation() = runBlocking {
        assertEquals(
            "Nothing_Phone_2-OTA_(2026)",
            RomZipExtractor.sanitizeArchiveFolderName("Nothing Phone 2-OTA (2026).zip")
        )
        assertEquals(
            "extracted_rom",
            RomZipExtractor.sanitizeArchiveFolderName("   .zip ")
        )
        assertEquals(
            "My_ROM_v1",
            RomZipExtractor.sanitizeArchiveFolderName("My:ROM*v1?.ZIP")
        )

        val tempRoot = File(System.getProperty("java.io.tmpdir"), "leaf_cancel_extract_test")
        tempRoot.deleteRecursively()
        tempRoot.mkdirs()
        try {
            val zipFile = File(tempRoot, "cancel.zip")
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                putZipEntry(zos, "boot.img", ByteArray(65536) { 0x19 }, stored = false)
            }
            val inspection = RomZipExtractor.inspectZipFilePartitions(zipFile)
            assertTrue(inspection.success)

            var cancelledThrown = false
            val outDir = File(tempRoot, "out_cancel")
            try {
                RomZipExtractor.extractSelectedPartitionsToDir(
                    zipFile = zipFile,
                    selectedEntries = inspection.partitions,
                    outputDir = outDir,
                    isCancelled = { true }
                )
            } catch (_: CancellationException) {
                cancelledThrown = true
            }
            assertTrue("Expected CancellationException when isCancelled is true", cancelledThrown)
            assertFalse("Partial boot.img must not remain after cancellation", File(outDir, "boot.img").exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
