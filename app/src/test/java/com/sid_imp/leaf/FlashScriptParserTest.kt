package com.sid_imp.leaf

import android.net.Uri
import com.sid_imp.leaf.fastboot.*
import com.sid_imp.leaf.script.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class FlashScriptParserTest {

    @Test
    fun testWindowsBatchParsing() {
        val batScript = """
            @echo off
            :: Nothing Phone Flash Script
            echo Flashing kernel...
            fastboot --set-active=a
            fastboot flash boot_a %~dp0\images\boot.img
            fastboot --disable-verity --disable-verification flash vbmeta_a vbmeta.img
            fastboot reboot fastboot
            echo Flashing system in fastbootd...
            fastboot flash system system.img
            fastboot erase userdata
            fastboot reboot
            pause
        """.trimIndent()

        val tasks = FlashScriptParser.parseScript(batScript)
        assertEquals(7, tasks.size)

        // 1. set active slot a
        assertEquals(TaskType.SET_ACTIVE, tasks[0].type)
        assertEquals("a", tasks[0].slot)

        // 2. flash boot_a with extracted file boot.img
        assertEquals(TaskType.FLASH, tasks[1].type)
        assertEquals("boot_a", tasks[1].partition)
        assertEquals("boot.img", tasks[1].fileName)
        assertEquals(FlashingPhase.FIRMWARE_BOOTLOADER, tasks[1].phase)

        // 3. flash vbmeta with verity flags disabled
        assertEquals(TaskType.FLASH, tasks[2].type)
        assertEquals("vbmeta_a", tasks[2].partition)
        assertEquals("vbmeta.img", tasks[2].fileName)
        assertTrue(tasks[2].disableVerity)
        assertTrue(tasks[2].disableVerification)
        assertEquals(FlashingPhase.FIRMWARE_BOOTLOADER, tasks[2].phase)

        // 4. reboot fastboot (switches to fastbootd!)
        assertEquals(TaskType.REBOOT_FASTBOOT, tasks[3].type)
        assertTrue(tasks[3].isRebootTask)
        assertEquals(FlashingPhase.FASTBOOTD_TRANSITION, tasks[3].phase)

        // 5. flash system (logical partition)
        assertEquals(TaskType.FLASH, tasks[4].type)
        assertEquals("system", tasks[4].partition)
        assertEquals("system.img", tasks[4].fileName)
        assertEquals(FlashingPhase.LOGICAL_DYNAMIC, tasks[4].phase)

        // 6. erase userdata
        assertEquals(TaskType.ERASE, tasks[5].type)
        assertEquals("userdata", tasks[5].partition)
        assertEquals(FlashingPhase.FINALIZATION, tasks[5].phase)

        // 7. reboot to system
        assertEquals(TaskType.REBOOT, tasks[6].type)
        assertEquals(FlashingPhase.FINALIZATION, tasks[6].phase)
    }

    @Test
    fun testBatWithArgumentsAndSlots() {
        val batScript = """
            @echo off
            fastboot %* flash --slot=all boot images\boot.img
            fastboot %* flash --slot=all dtbo images\dtbo.img
            fastboot %* flash --disable-verity --disable-verification vbmeta vbmeta.img
            fastboot %* reboot fastboot
            fastboot %* flash --slot=all system images\system.img
            fastboot %* reboot
        """.trimIndent()

        val tasks = FlashScriptParser.parseScript(batScript)
        assertEquals(6, tasks.size)
        assertEquals("boot", tasks[0].partition)
        assertEquals("boot.img", tasks[0].fileName)
        assertEquals("all", tasks[0].slot)
        assertEquals(FlashingPhase.FIRMWARE_BOOTLOADER, tasks[0].phase)

        assertEquals(TaskType.REBOOT_FASTBOOT, tasks[3].type)
        assertEquals(FlashingPhase.FASTBOOTD_TRANSITION, tasks[3].phase)

        assertEquals("system", tasks[4].partition)
        assertEquals("system.img", tasks[4].fileName)
        assertEquals(FlashingPhase.LOGICAL_DYNAMIC, tasks[4].phase)
    }

    @Test
    fun testTermuxFlashShParsing() {
        val shScript = """
            #!/bin/bash
            FASTBOOT_CMD="termux-fastboot"
            core_partitions="boot dtbo init_boot vendor_boot"
            vbmeta_partitions="vbmeta vbmeta_system"
            logical_partitions="product vendor system"

            FlashImageSilent() {
                "${'$'}FASTBOOT_CMD" flash "${'$'}1" "${'$'}2"
            }

            "${'$'}FASTBOOT_CMD" --set-active=a
            "${'$'}FASTBOOT_CMD" erase userdata

            for i in ${'$'}core_partitions; do FlashImageSilent "${'$'}{i}_a" "${'$'}{i}.img"; done
            FlashImageSilent "preloader_a" "preloader_raw.img"
            for i in ${'$'}vbmeta_partitions; do FlashImageSilent "${'$'}{i}_a" "${'$'}{i}.img"; done

            RebootFastbootD

            for i in ${'$'}logical_partitions; do FlashImageSilent "${'$'}{i}_a" "${'$'}{i}.img"; done

            "${'$'}FASTBOOT_CMD" reboot
        """.trimIndent()

        val tasks = FlashScriptParser.parseScript(shScript)
        assertTrue(tasks.isNotEmpty())

        val bootTask = tasks.find { it.partition == "boot_a" }
        assertNotNull(bootTask)
        assertEquals("boot.img", bootTask!!.fileName)
        assertEquals(FlashingPhase.FIRMWARE_BOOTLOADER, bootTask.phase)

        val preloaderTask = tasks.find { it.partition == "preloader" || it.partition == "preloader_a" }
        assertNotNull(preloaderTask)
        assertEquals("preloader_raw.img", preloaderTask!!.fileName)

        val rebootFastbootTask = tasks.find { it.type == TaskType.REBOOT_FASTBOOT }
        assertNotNull(rebootFastbootTask)
        assertEquals(FlashingPhase.FASTBOOTD_TRANSITION, rebootFastbootTask!!.phase)

        val systemTask = tasks.find { it.partition == "system_a" }
        assertNotNull(systemTask)
        assertEquals("system.img", systemTask!!.fileName)
        assertEquals(FlashingPhase.LOGICAL_DYNAMIC, systemTask.phase)
    }

    @Test
    fun testScriptAnalysisAndFileVerification() {
        val batScript = """
            fastboot flash boot boot.img
            fastboot flash vbmeta vbmeta.img
            fastboot reboot fastboot
            fastboot flash system system.img
            fastboot flash vendor vendor.img
            fastboot reboot
        """.trimIndent()

        val tasks = FlashScriptParser.parseScript(batScript)
        val mockFileMap = mapOf(
            "boot.img" to "file://mock/boot.img",
            "system.img" to "file://mock/system.img"
        )

        val analysis = FlashScriptParser.analyzeScript(tasks, mockFileMap)
        assertEquals(4, analysis.totalPartitionsCount)
        assertEquals(2, analysis.presentPartitionsCount) // boot.img and system.img present
        assertEquals(2, analysis.firmwareTasks.size) // boot, vbmeta
        assertNotNull(analysis.rebootTransitionTask)
        assertEquals(2, analysis.logicalTasks.size) // system, vendor
        assertEquals(1, analysis.finalTasks.size) // reboot
    }

    @Test
    fun testNothingPhonePresetScript() {
        val tasks = FlashScriptParser.parseScript(SampleScripts.NOTHING_PHONE_2_FASTBOOT_SCRIPT)
        assertTrue(tasks.isNotEmpty())
        assertEquals(24, tasks.size)

        val rebootFastbootTask = tasks.find { it.type == TaskType.REBOOT_FASTBOOT }
        assertNotNull("Reboot to fastbootd task must exist in Nothing Phone script", rebootFastbootTask)

        val rebootIdx = tasks.indexOf(rebootFastbootTask)
        val systemTask = tasks.find { it.partition == "system_a" }
        assertNotNull(systemTask)
        val systemIdx = tasks.indexOf(systemTask)
        assertTrue("Dynamic system partition must be flashed after fastbootd reboot", systemIdx > rebootIdx)
        assertEquals(FlashingPhase.LOGICAL_DYNAMIC, systemTask!!.phase)
        assertFalse(
            "Logical partitions in fastbootd must target active slot only",
            tasks.any { it.phase == FlashingPhase.LOGICAL_DYNAMIC && it.partition.endsWith("_b") }
        )
    }

    @Test
    fun testApusysPartitionMatching() {
        val script = """
            fastboot flash apusys_a apusys.img
            fastboot flash ccu_a ccu.img
        """.trimIndent()

        val tasks = FlashScriptParser.parseScript(script)
        assertEquals(2, tasks.size)

        // Case 1: apusys_1.img in folder
        val mockFileMap1 = mapOf("apusys_1.img" to "uri://apusys_1.img")
        FlashScriptParser.analyzeScript(tasks, mockFileMap1)
        assertTrue("apusys partition must match apusys_1.img", tasks[0].isFilePresent)

        // Case 2: apusys.bin in folder
        val mockFileMap2 = mapOf("apusys.bin" to "uri://apusys.bin")
        FlashScriptParser.analyzeScript(tasks, mockFileMap2)
        assertTrue("apusys partition must match apusys.bin", tasks[0].isFilePresent)
    }

    @Test
    fun testSimulatedFastbootDeviceProtocol() = runBlocking {
        val device = FastbootSimulatedDevice()
        assertTrue(device.isConnected)
        assertEquals(FastbootMode.BOOTLOADER, device.currentMode)

        val getvarResp = device.sendCommand("getvar:product")
        assertTrue(getvarResp.isSuccess)
        assertEquals("Universal Android Device", getvarResp.payload)

        val dummyData = "Android Fastboot Dummy Kernel Content".toByteArray()
        val flashResp = device.flashPartition(
            partition = "boot_a",
            stream = ByteArrayInputStream(dummyData),
            totalBytes = dummyData.size.toLong()
        )
        assertTrue(flashResp.isSuccess)

        val rebootFastbootResp = device.reboot("fastboot")
        assertTrue(rebootFastbootResp.isSuccess)
        assertEquals(FastbootMode.FASTBOOTD, device.currentMode)

        val flashSystemResp = device.flashPartition(
            partition = "system_a",
            stream = ByteArrayInputStream(dummyData),
            totalBytes = dummyData.size.toLong()
        )
        assertTrue(flashSystemResp.isSuccess)

        val eraseResp = device.erasePartition("userdata")
        assertTrue(eraseResp.isSuccess)

        device.close()
    }

    @Test
    fun testAdbPacketCreation() {
        val payload = "host::LeafFlasher\u0000".toByteArray()
        val connectMsg = AdbMessage.create(AdbConstants.A_CNXN, AdbConstants.A_VERSION, AdbConstants.MAX_PAYLOAD, payload)
        val packet = connectMsg.toBytes()
        assertNotNull(packet)
        assertEquals(24 + payload.size, packet.size)

        val rebootMsg = AdbMessage.create(AdbConstants.A_OPEN, 1, 0, "reboot:bootloader\u0000".toByteArray())
        val rebootPacket = rebootMsg.toBytes()
        assertNotNull(rebootPacket)
        assertEquals(24 + "reboot:bootloader\u0000".toByteArray().size, rebootPacket.size)
    }

    @Test
    fun testCmfPhone1TetrisFlashShScript() {
        val tasks = FlashScriptParser.parseScript(SampleScripts.CMF_PHONE_1_FLASH_SH)
        println("DEBUG: tasks.size = ${tasks.size}, partitions = ${tasks.map { it.partition }}")
        assertTrue("Expected >= 30 tasks but got ${tasks.size}", tasks.size >= 30)

        // Verify apusys_a is parsed
        val apusysTask = tasks.find { it.partition == "apusys_a" }
        assertNotNull(apusysTask)
        assertEquals("apusys.img", apusysTask?.fileName)

        // Verify preloader is parsed
        val preloaderTask = tasks.find { it.partition == "preloader" || it.partition == "preloader_a" }
        assertNotNull(preloaderTask)
        assertEquals("preloader_raw.img", preloaderTask?.fileName)

        // Verify fastbootd transition exists
        val rebootFastbootTask = tasks.find { it.type == TaskType.REBOOT_FASTBOOT }
        assertNotNull(rebootFastbootTask)

        // Verify system_a is parsed in logical partitions
        val systemTask = tasks.find { it.partition == "system_a" }
        assertNotNull(systemTask)
        assertEquals("system.img", systemTask?.fileName)
    }

    @Test
    fun testRealRomZipPayloadExtractionSpeed() = runBlocking {
        val envZipPath = System.getenv("LEAF_TEST_ROM_ZIP")
        if (envZipPath.isNullOrBlank()) {
            println("LEAF_TEST_ROM_ZIP not set, skipping optional real-archive benchmark test.")
            return@runBlocking
        }
        val zipFile = java.io.File(envZipPath)
        if (!zipFile.exists()) {
            println("Test ROM zip not found at ${zipFile.absolutePath}, skipping.")
            return@runBlocking
        }

        val testOutDir = java.io.File(System.getProperty("java.io.tmpdir"), "leaf_otaripper_test")
        testOutDir.deleteRecursively()
        testOutDir.mkdirs()

        val fis = java.io.FileInputStream(zipFile)
        val channel = fis.channel
        val t0 = System.currentTimeMillis()

        // 1. Direct ZIP Central Directory Lookup
        val entry = RomZipExtractor.findZipEntry(channel, "payload.bin")
        assertNotNull("payload.bin must be found in ROM zip", entry)
        assertEquals(0, entry!!.compressionMethod)
        println("Direct ZIP lookup took ${System.currentTimeMillis() - t0}ms. payload.bin offset = ${entry.dataOffset}, size = ${entry.uncompressedSize}")

        // 2. Extract using Otaripper-optimized PayloadDumper
        val tExtractStart = System.currentTimeMillis()
        val dumped = PayloadDumper.extractPayloadFromChannel(
            channel = channel,
            baseOffset = entry.dataOffset,
            totalSize = entry.uncompressedSize,
            outputDir = testOutDir,
            onProgress = { part, idx, total, pct ->
                println("  [OTARIPPER DUMP] $part ($idx/$total) $pct%")
            }
        )

        val totalTimeSec = (System.currentTimeMillis() - tExtractStart) / 1000.0
        println("Extraction completed in $totalTimeSec seconds! Total partitions: ${dumped.size}")
        assertTrue(dumped.isNotEmpty())

        val bootImg = java.io.File(testOutDir, "boot.img")
        assertTrue(bootImg.exists() && bootImg.length() > 0)

        val vcpImg = java.io.File(testOutDir, "vcp.img")
        assertTrue(vcpImg.exists() && vcpImg.length() > 0)

        // Verify boot.img magic is "ANDROID!"
        val bootMagic = ByteArray(8)
        java.io.FileInputStream(bootImg).use { it.read(bootMagic) }
        assertEquals("ANDROID!", String(bootMagic).trim())

        fis.close()
        testOutDir.deleteRecursively()
    }

    @Test
    fun testStrictUsbInterfaceFiltering() {
        // Bootloader & Fastbootd interface (Class 255, Subclass 66, Protocol 3)
        assertTrue(FastbootUsbManager.isStrictFastbootInterface(0x18D1, 0xD00D, 255, 66, 3))
        assertTrue(FastbootUsbManager.isStrictFastbootInterface(0x0E8D, 0x201C, 255, 66, 3))

        // ADB (Class 255, Subclass 66, Protocol 1) must be rejected
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x18D1, 0x4EE7, 255, 66, 1))
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x0E8D, 0x201C, 255, 66, 1))

        // MediaTek BROM & Preloader PIDs must always be rejected
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x0E8D, 0x0003, 255, 66, 3))
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x0E8D, 0x2000, 255, 66, 3))
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x0E8D, 0x2001, 2, 2, 1))
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x0E8D, 0x2000, 10, 0, 0))

        // Generic Class 255 or MTP/CDC must be rejected
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x2717, 0xFF08, 255, 0, 0))
        assertFalse(FastbootUsbManager.isStrictFastbootInterface(0x2717, 0xFF08, 6, 1, 1))
    }

    @Test
    fun testFastbootModeFlashGuards() {
        assertTrue(FastbootMode.BOOTLOADER.isBootloaderOrFastbootd)
        assertTrue(FastbootMode.FASTBOOTD.isBootloaderOrFastbootd)
        assertFalse(FastbootMode.ADB.isBootloaderOrFastbootd)
        assertFalse(FastbootMode.RECONNECTING.isBootloaderOrFastbootd)
        assertFalse(FastbootMode.DISCONNECTED.isBootloaderOrFastbootd)

        val unverifiedDevice = DeviceInfo(isVerifiedFastboot = false, isUserspace = false, isAdbMode = false)
        assertEquals(FastbootMode.DISCONNECTED, unverifiedDevice.mode)

        val adbReportedDevice = DeviceInfo(isVerifiedFastboot = true, isUserspace = false, isAdbMode = true)
        assertEquals(FastbootMode.DISCONNECTED, adbReportedDevice.mode)

        val bootloaderDevice = DeviceInfo(isVerifiedFastboot = true, isUserspace = false, isAdbMode = false)
        assertEquals(FastbootMode.BOOTLOADER, bootloaderDevice.mode)

        val fastbootdDevice = DeviceInfo(isVerifiedFastboot = true, isUserspace = true, isAdbMode = false)
        assertEquals(FastbootMode.FASTBOOTD, fastbootdDevice.mode)
    }

    @Test
    fun testFastbootdLogicalPartitionsFlashActiveSlotOnly() {
        val mockFiles = listOf(
            "boot.img", "lk.img", "vbmeta.img",
            "odm_dlkm.img", "odm.img", "vendor_dlkm.img", "product.img", "vendor.img", "system.img"
        ).map { name ->
            PartitionImageSource(name = name, uri = null, size = 1024L * 1024L)
        }

        // Even when targetSlot == "both" for Bootloader partitions, fastbootd dynamic partitions
        // inside super must target ONLY active slot (_a) and never duplicate _b.
        val tasksBoth = RomZipExtractor.generateSequenceFromImages(
            images = mockFiles,
            targetSlot = "both",
            wipeSuper = true
        )

        val bootTargets = tasksBoth.filter { it.partition.startsWith("boot_") }.map { it.partition }
        assertEquals(listOf("boot_a", "boot_b"), bootTargets)

        val lkTargets = tasksBoth.filter { it.partition.startsWith("lk_") }.map { it.partition }
        assertEquals(listOf("lk_a", "lk_b"), lkTargets)

        val logicalTargets = tasksBoth
            .filter { it.phase == FlashingPhase.LOGICAL_DYNAMIC && it.type == TaskType.FLASH && it.partition != "super" }
            .map { it.partition }
        assertEquals(
            listOf("odm_dlkm_a", "odm_a", "vendor_dlkm_a", "product_a", "vendor_a", "system_a"),
            logicalTargets
        )
        assertFalse(logicalTargets.any { it.endsWith("_b") })

        // When targetSlot == "b", fastbootd logical partitions target slot _b only
        val tasksSlotB = RomZipExtractor.generateSequenceFromImages(
            images = mockFiles,
            targetSlot = "b",
            wipeSuper = true
        )
        val logicalTargetsB = tasksSlotB
            .filter { it.phase == FlashingPhase.LOGICAL_DYNAMIC && it.type == TaskType.FLASH && it.partition != "super" }
            .map { it.partition }
        assertTrue(logicalTargetsB.all { it.endsWith("_b") })
        assertFalse(logicalTargetsB.any { it.endsWith("_a") })
    }

    @Test
    fun testNormalizePartitionStem() {
        assertEquals("boot", FlashScriptParser.normalizePartitionStem("boot.img"))
        assertEquals("vendor_boot", FlashScriptParser.normalizePartitionStem("vendor_boot_a.img"))
        assertEquals("vbmeta_system", FlashScriptParser.normalizePartitionStem("vbmeta_system_b.img"))
        assertEquals("super_empty", FlashScriptParser.normalizePartitionStem("super_empty.img"))
        assertEquals("super_empty", FlashScriptParser.normalizePartitionStem("device_super_empty.img"))
        assertEquals("lk", FlashScriptParser.normalizePartitionStem("stock_lk.bin"))
        assertEquals("lk", FlashScriptParser.normalizePartitionStem("patched_lk.img"))
        assertEquals("preloader", FlashScriptParser.normalizePartitionStem("preloader_raw.img"))
    }

    @Test
    fun testCountUniquePartitionsAcrossSlotOptions() {
        val thirtyFiveNames = listOf(
            "boot.img", "dtbo.img", "init_boot.img", "vendor_boot.img", "apusys.img", "ccu.img",
            "connsys_bt.img", "connsys_gnss.img", "connsys_wifi.img", "dpm.img", "gpueb.img",
            "gz.img", "lk.img", "logo.img", "mcf_ota.img", "mcupm.img", "modem.img", "pi_img.img",
            "scp.img", "spmfw.img", "sspm.img", "tee.img", "vcp.img", "preloader_raw.img",
            "vbmeta.img", "vbmeta_system.img", "vbmeta_vendor.img",
            "odm_dlkm.img", "odm.img", "vendor_dlkm.img", "product.img", "vendor.img",
            "system_dlkm.img", "system_ext.img", "system.img"
        )
        assertEquals(35, thirtyFiveNames.size)

        val imagesWithoutSuperEmpty = thirtyFiveNames.map {
            PartitionImageSource(name = it, uri = null, size = 4096L)
        }
        for (slot in listOf("a", "b", "both")) {
            val tasks = RomZipExtractor.generateSequenceFromImages(
                images = imagesWithoutSuperEmpty,
                targetSlot = slot,
                wipeSuper = false
            )
            assertEquals("Expected 35 unique partitions for slot=$slot", 35, FlashScriptParser.countUniquePartitions(tasks))
            assertFalse("wipe-super must not be emitted without super_empty.img", tasks.any { FlashScriptParser.isWipeSuperTask(it) })
        }

        // Including super_empty.img (wipe-super) must still report 35 real partitions
        val imagesWithSuperEmpty = imagesWithoutSuperEmpty + PartitionImageSource("super_empty.img", null, 5392L)
        val tasksBothWithSuperEmpty = RomZipExtractor.generateSequenceFromImages(
            images = imagesWithSuperEmpty,
            targetSlot = "both",
            wipeSuper = true
        )
        assertTrue(tasksBothWithSuperEmpty.any { FlashScriptParser.isWipeSuperTask(it) })
        assertEquals(35, FlashScriptParser.countUniquePartitions(tasksBothWithSuperEmpty))
    }

    @Test
    fun testPatchVbmetaHeaderBytes() {
        val avbHeader = ByteArray(256)
        avbHeader[0] = 'A'.code.toByte()
        avbHeader[1] = 'V'.code.toByte()
        avbHeader[2] = 'B'.code.toByte()
        avbHeader[3] = '0'.code.toByte()
        avbHeader[123] = 0x00

        val verityOnly = FlashScriptParser.patchVbmetaHeaderBytes(avbHeader, disableVerity = true, disableVerification = false)
        assertEquals(0x01.toByte(), verityOnly[123])
        assertEquals(0x00.toByte(), avbHeader[123]) // Original unchanged

        val bothFlags = FlashScriptParser.patchVbmetaHeaderBytes(avbHeader, disableVerity = true, disableVerification = true)
        assertEquals(0x03.toByte(), bothFlags[123])

        // Non-AVB payload (e.g. boot.img ANDROID!) must never be altered
        val bootHeader = ByteArray(256)
        "ANDROID!".toByteArray().copyInto(bootHeader)
        bootHeader[123] = 0x2A
        val untouched = FlashScriptParser.patchVbmetaHeaderBytes(bootHeader, disableVerity = true, disableVerification = true)
        assertEquals(0x2A.toByte(), untouched[123])
    }

    @Test
    fun testPostFlashRebootSystemSequence() {
        val mockFiles = listOf("boot.img", "system.img").map { name ->
            PartitionImageSource(name = name, uri = null, size = 1024L * 1024L)
        }
        val tasksRebootSystem = RomZipExtractor.generateSequenceFromImages(
            images = mockFiles,
            targetSlot = "a",
            rebootAfterFlash = true,
            rebootTarget = "system"
        )
        val lastTask = tasksRebootSystem.last()
        assertEquals(TaskType.REBOOT, lastTask.type)
        assertEquals("fastboot reboot", lastTask.rawCommandLine)
        assertEquals(FlashingPhase.FINALIZATION, lastTask.phase)

        val tasksRebootBootloader = RomZipExtractor.generateSequenceFromImages(
            images = mockFiles,
            targetSlot = "a",
            rebootAfterFlash = true,
            rebootTarget = "bootloader"
        )
        val lastBlTask = tasksRebootBootloader.last()
        assertEquals(TaskType.REBOOT_BOOTLOADER, lastBlTask.type)
        assertEquals("fastboot reboot bootloader", lastBlTask.rawCommandLine)
    }

    @Test
    fun testAdaptiveBulkChunkSchedule() {
        assertEquals(4096, FastbootUsbDevice.nextFallbackChunkSize(16384, 512))
        assertEquals(512, FastbootUsbDevice.nextFallbackChunkSize(4096, 512))
        assertEquals(1024, FastbootUsbDevice.nextFallbackChunkSize(4096, 1024))
        assertNull(FastbootUsbDevice.nextFallbackChunkSize(512, 512))
        assertNull(FastbootUsbDevice.nextFallbackChunkSize(1024, 1024))
    }

    @Test
    fun testBuildExtractedPartitionInventory() {
        val baseImages = listOf(
            PartitionImageSource("boot.img", null, 64L * 1024 * 1024),
            PartitionImageSource("boot_b.img", null, 64L * 1024 * 1024), // duplicate stem "boot"
            PartitionImageSource("lk.img", null, 4L * 1024 * 1024),
            PartitionImageSource("vbmeta.img", null, 8192L),
            PartitionImageSource("odm_dlkm.img", null, 12L * 1024 * 1024),
            PartitionImageSource("system.img", null, 1200L * 1024 * 1024),
            PartitionImageSource("super_empty.img", null, 5392L)
        )

        // Check slot = "both": Bootloader targets A + B, Fastbootd targets active slot A only, Super Wipe flagged
        val tasksBoth = RomZipExtractor.generateSequenceFromImages(
            images = baseImages,
            targetSlot = "both",
            wipeSuper = true
        )
        val invBoth = RomZipExtractor.buildExtractedPartitionInventory(
            images = baseImages,
            plannedTasks = tasksBoth
        )

        // boot.img + boot_b.img deduplicate to single stem "boot" => 6 unique stems
        assertEquals(6, invBoth.size)
        val bootItem = invBoth.first { it.stem == "boot" }
        assertEquals("Core", bootItem.category)
        assertEquals("Bootloader (Fastboot)", bootItem.flashMode)
        assertFalse(bootItem.isFastbootd)
        assertEquals("Bootloader (Fastboot)", bootItem.phaseLabel)
        assertEquals(listOf("boot_a", "boot_b"), bootItem.targetPartitions)
        assertTrue(bootItem.isIncludedInFlashPlan)
        assertEquals(64L * 1024 * 1024, bootItem.fileSize)

        val lkItem = invBoth.first { it.stem == "lk" }
        assertEquals("Firmware", lkItem.category)
        assertEquals("Bootloader (Fastboot)", lkItem.flashMode)
        assertFalse(lkItem.isFastbootd)

        val vbmetaItem = invBoth.first { it.stem == "vbmeta" }
        assertEquals("VBMeta", vbmetaItem.category)
        assertEquals("Bootloader (Fastboot)", vbmetaItem.flashMode)
        assertFalse(vbmetaItem.isFastbootd)

        val systemItem = invBoth.first { it.stem == "system" }
        assertEquals("Logical", systemItem.category)
        assertEquals("FastbootD", systemItem.flashMode)
        assertTrue(systemItem.isFastbootd)
        assertEquals("FastbootD", systemItem.phaseLabel)
        assertEquals(listOf("system_a"), systemItem.targetPartitions)
        assertTrue(systemItem.isIncludedInFlashPlan)

        val superEmptyItem = invBoth.first { it.stem == "super_empty" }
        assertEquals("Super Wipe", superEmptyItem.category)
        assertEquals("FastbootD", superEmptyItem.flashMode)
        assertTrue(superEmptyItem.isFastbootd)
        assertEquals("Super Wipe", superEmptyItem.phaseLabel)
        assertEquals(listOf("super (wipe)"), superEmptyItem.targetPartitions)
        assertTrue(superEmptyItem.isIncludedInFlashPlan)

        // Check slot = "b": Bootloader and Fastbootd both target slot _b only
        val tasksB = RomZipExtractor.generateSequenceFromImages(
            images = baseImages,
            targetSlot = "b",
            wipeSuper = false
        )
        val invB = RomZipExtractor.buildExtractedPartitionInventory(
            images = baseImages,
            plannedTasks = tasksB
        )
        assertEquals(listOf("boot_b"), invB.first { it.stem == "boot" }.targetPartitions)
        assertEquals(listOf("system_b"), invB.first { it.stem == "system" }.targetPartitions)

        // Check custom override replacement (lk replaced by custom patched_lk.img + extra init_boot)
        // plus an excluded extra image not present in plannedTasks
        val overriddenImages = baseImages.filterNot { it.stem == "lk" } + listOf(
            PartitionImageSource("patched_lk_v2.img", null, 4194304L, stemOverride = "lk"),
            PartitionImageSource("custom_init_boot.img", null, 8L * 1024 * 1024, stemOverride = "init_boot"),
            PartitionImageSource("unplanned_misc.img", null, 1024L)
        )
        val plannedWithoutMisc = RomZipExtractor.generateSequenceFromImages(
            images = overriddenImages.filterNot { it.stem == "unplanned_misc" },
            targetSlot = "a",
            wipeSuper = true
        )
        val invOverride = RomZipExtractor.buildExtractedPartitionInventory(
            images = overriddenImages,
            plannedTasks = plannedWithoutMisc,
            customOverrideStems = setOf("lk", "init_boot")
        )

        val lkOverridden = invOverride.first { it.stem == "lk" }
        assertEquals("patched_lk_v2.img", lkOverridden.fileName)
        assertEquals(4194304L, lkOverridden.fileSize)
        assertTrue(lkOverridden.isCustomOverride)
        assertTrue(lkOverridden.isIncludedInFlashPlan)

        val initBootCustom = invOverride.first { it.stem == "init_boot" }
        assertEquals("custom_init_boot.img", initBootCustom.fileName)
        assertTrue(initBootCustom.isCustomOverride)
        assertEquals(listOf("init_boot_a"), initBootCustom.targetPartitions)

        // Unplanned images sort after planned items and mark isIncludedInFlashPlan = false
        val unplanned = invOverride.last()
        assertEquals("unplanned_misc", unplanned.stem)
        assertEquals("Extra", unplanned.category)
        assertFalse(unplanned.isIncludedInFlashPlan)
        assertTrue(unplanned.targetPartitions.isEmpty())
        assertTrue(invOverride.indexOf(invOverride.first { o -> o.stem == "boot" }) < invOverride.indexOf(invOverride.first { o -> o.stem == "system" }))
    }

    @Test
    fun testStrictAdbInterfaceFiltering() {
        assertTrue(FastbootUsbManager.isStrictAdbInterface(0x18D1, 0x4EE7, 255, 66, 1))
        assertTrue(FastbootUsbManager.isStrictAdbInterface(0x0E8D, 0x201C, 255, 66, 1))

        // Fastboot (protocol 3) must NOT match ADB matcher
        assertFalse(FastbootUsbManager.isStrictAdbInterface(0x18D1, 0xD00D, 255, 66, 3))
        // MediaTek BROM/Preloader PIDs must be rejected
        assertFalse(FastbootUsbManager.isStrictAdbInterface(0x0E8D, 0x0003, 255, 66, 1))
        assertFalse(FastbootUsbManager.isStrictAdbInterface(0x0E8D, 0x2000, 255, 66, 1))
        // MTP / CDC / generic class must be rejected
        assertFalse(FastbootUsbManager.isStrictAdbInterface(0x2717, 0xFF08, 6, 1, 1))
        assertFalse(FastbootUsbManager.isStrictAdbInterface(0x2717, 0xFF08, 255, 0, 1))
    }

    @Test
    fun testAdbModeAndDeviceInfoMapping() {
        assertTrue(FastbootMode.ADB.isAdbConnected)
        assertTrue(FastbootMode.ADB_RECOVERY.isAdbConnected)
        assertTrue(FastbootMode.ADB_SIDELOAD.isAdbConnected)
        assertFalse(FastbootMode.ADB.isBootloaderOrFastbootd)
        assertFalse(FastbootMode.ADB_RECOVERY.isBootloaderOrFastbootd)
        assertFalse(FastbootMode.ADB_SIDELOAD.isBootloaderOrFastbootd)
        assertFalse(FastbootMode.BOOTLOADER.isAdbConnected)
        assertFalse(FastbootMode.FASTBOOTD.isAdbConnected)
        assertFalse(FastbootMode.DISCONNECTED.isAdbConnected)

        val sideloadInfo = DeviceInfo(
            productName = "Pong",
            isVerifiedFastboot = false,
            isAdbMode = true,
            adbSubMode = FastbootMode.ADB_SIDELOAD,
            isAdbAuthorized = true
        )
        assertEquals(FastbootMode.ADB_SIDELOAD, sideloadInfo.mode)

        val recoveryInfo = DeviceInfo(
            productName = "Tetris",
            isVerifiedFastboot = false,
            isAdbMode = true,
            adbSubMode = FastbootMode.ADB_RECOVERY,
            isAdbAuthorized = true
        )
        assertEquals(FastbootMode.ADB_RECOVERY, recoveryInfo.mode)

        val adbOsInfo = DeviceInfo(
            productName = "OnePlus",
            isVerifiedFastboot = false,
            isAdbMode = true,
            adbSubMode = FastbootMode.ADB,
            isAdbAuthorized = true
        )
        assertEquals(FastbootMode.ADB, adbOsInfo.mode)

        // Guard: isAdbMode + isVerifiedFastboot simultaneously true must collapse safely
        val anomalous = DeviceInfo(
            isVerifiedFastboot = true,
            isAdbMode = true,
            adbSubMode = FastbootMode.ADB
        )
        assertEquals(FastbootMode.DISCONNECTED, anomalous.mode)
    }

    @Test
    fun testAdbBannerParsingAndHeaderFraming() {
        val sideload = AdbMessage.parseBanner("sideload::\u0000")
        assertEquals(FastbootMode.ADB_SIDELOAD, sideload.mode)
        assertEquals("Recovery Sideload", sideload.productName)

        val recovery = AdbMessage.parseBanner("recovery::ro.product.model=Nothing Phone (2);ro.product.device=Pong;\u0000")
        assertEquals(FastbootMode.ADB_RECOVERY, recovery.mode)
        assertEquals("Nothing Phone (2)", recovery.productName)

        val osAdb = AdbMessage.parseBanner("device::ro.product.device=tetris;ro.product.name=tetris;\u0000")
        assertEquals(FastbootMode.ADB, osAdb.mode)
        assertEquals("tetris", osAdb.productName)

        val msg = AdbMessage.create(AdbConstants.A_OPEN, 7, 0, "sideload-host:1048576:65536\u0000".toByteArray())
        val hdr = msg.headerBytes()
        assertEquals(24, hdr.size)
        val parsed = AdbMessage.parseHeader(hdr)
        assertNotNull(parsed)
        assertEquals(AdbConstants.A_OPEN, parsed!!.command)
        assertEquals(7, parsed.arg0)
        assertEquals(msg.dataLength, parsed.dataLength)
        assertEquals(msg.dataCheck, parsed.dataCheck)
        assertArrayEquals(hdr + msg.payload, msg.toBytes())
    }

    @Test
    fun testAdbRsaKeyManagerAndTokenSigning() {
        val tempKeyDir = java.io.File(System.getProperty("java.io.tmpdir"), "leaf_adb_key_test")
        tempKeyDir.deleteRecursively()
        try {
            val kp1 = AdbRsaKeyManager.loadOrCreateKeyPair(tempKeyDir)
            val kp2 = AdbRsaKeyManager.loadOrCreateKeyPair(tempKeyDir)
            assertArrayEquals(kp1.public.encoded, kp2.public.encoded)
            assertArrayEquals(kp1.private.encoded, kp2.private.encoded)

            val rsaPub = kp1.public as java.security.interfaces.RSAPublicKey
            val struct = AdbRsaKeyManager.encodeAndroidPublicKeyStruct(rsaPub)
            assertEquals(524, struct.size)

            val buf = java.nio.ByteBuffer.wrap(struct).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(64, buf.getInt(0))
            assertEquals(rsaPub.publicExponent.toInt(), buf.getInt(520))

            val payloadStr = String(AdbRsaKeyManager.encodeAndroidPublicKeyPayload(rsaPub), Charsets.UTF_8)
            assertTrue(payloadStr.endsWith(" sid_imp@leafflasher\u0000"))
            val b64Part = payloadStr.substringBefore(' ')
            assertEquals(700, b64Part.length)

            val token20B = ByteArray(20) { (it * 13 + 5).toByte() }
            val sigBytes = AdbRsaKeyManager.signAuthToken(kp1.private, token20B)
            assertEquals(256, sigBytes.size)

            val expectedDigestInfo = AdbRsaKeyManager.SHA1_DIGEST_INFO_PREFIX + token20B
            val verifier = java.security.Signature.getInstance("NONEwithRSA")
            verifier.initVerify(kp1.public)
            verifier.update(expectedDigestInfo)
            assertTrue("ADB RSA token signature must verify against DigestInfo+token", verifier.verify(sigBytes))

            val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kp1.public)
            assertArrayEquals(expectedDigestInfo, cipher.doFinal(sigBytes))
        } finally {
            tempKeyDir.deleteRecursively()
        }
    }

    @Test
    fun testFeatureGuideAndTutorCompleteness() {
        val entries = com.sid_imp.leaf.ui.FeatureInfoKey.entries
        assertEquals(13, entries.size)
        assertEquals(com.sid_imp.leaf.ui.RomWizardStep.BOOT_SELECTION, com.sid_imp.leaf.ui.RomWizardStep.entries.first())
        entries.forEach { key ->
            val spec = com.sid_imp.leaf.ui.FeatureGuideCatalog.getFeatureInfo(key)
            assertEquals(key, spec.key)
            assertTrue("Title must not be blank for $key", spec.title.isNotBlank())
            assertTrue("whatItDoes must not be blank for $key", spec.whatItDoes.isNotBlank())
            assertTrue("whenToUse must not be blank for $key", spec.whenToUse.isNotBlank())
            assertTrue("tip must not be blank for $key", spec.tip.isNotBlank())
        }

        val steps = com.sid_imp.leaf.ui.FeatureGuideCatalog.tutorSteps
        assertEquals(4, steps.size)
        steps.forEachIndexed { idx, step ->
            assertEquals(idx + 1, step.stepNumber)
            assertTrue(step.badge.isNotBlank())
            assertTrue(step.title.isNotBlank())
            assertTrue(step.tileFocus.isNotBlank())
            assertTrue(step.explanation.isNotBlank())
            assertTrue(step.proTip.isNotBlank())
        }
    }

    @Test
    fun testApplyVbmetaVerityOption() {
        val baseImages = listOf(
            PartitionImageSource("boot.img", null, 64L * 1024 * 1024),
            PartitionImageSource("vbmeta.img", null, 8192L),
            PartitionImageSource("vbmeta_system.img", null, 4096L),
            PartitionImageSource("vbmeta_vendor.img", null, 4096L),
            PartitionImageSource("system.img", null, 512L * 1024 * 1024)
        )
        val generated = RomZipExtractor.generateSequenceFromImages(
            images = baseImages,
            targetSlot = "a"
        )

        val defaultTasks = FlashScriptParser.applyVbmetaVerityOption(generated, disableVerityAndVerification = false)
        assertTrue(defaultTasks.filter { it.partition.startsWith("vbmeta") }.all { !it.disableVerity && !it.disableVerification })

        val portRomTasks = FlashScriptParser.applyVbmetaVerityOption(generated, disableVerityAndVerification = true)
        val vbmetaTasks = portRomTasks.filter { it.type == TaskType.FLASH && it.partition.startsWith("vbmeta") }
        assertEquals(3, vbmetaTasks.size)
        assertTrue(vbmetaTasks.all { it.disableVerity && it.disableVerification })

        val nonVbmetaTasks = portRomTasks.filter { it.type == TaskType.FLASH && !it.partition.startsWith("vbmeta") }
        assertTrue(nonVbmetaTasks.isNotEmpty())
        assertTrue(nonVbmetaTasks.all { !it.disableVerity && !it.disableVerification })
    }

    @Test
    fun testSyntheticPayloadParallelExtractionSnapshots() = runBlocking {
        fun encodeVarint(out: java.io.ByteArrayOutputStream, value: Long) {
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

        fun writeFieldBytes(out: java.io.ByteArrayOutputStream, fieldNum: Int, bytes: ByteArray) {
            encodeVarint(out, ((fieldNum shl 3) or 2).toLong())
            encodeVarint(out, bytes.size.toLong())
            out.write(bytes)
        }

        fun writeFieldVarint(out: java.io.ByteArrayOutputStream, fieldNum: Int, value: Long) {
            encodeVarint(out, ((fieldNum shl 3) or 0).toLong())
            encodeVarint(out, value)
        }

        val blockSize = 4096L
        val specs = listOf(
            "boot" to ByteArray(4096) { 0x42 },
            "dtbo" to ByteArray(4096) { 0x2A },
            "vbmeta" to ByteArray(4096) { 0x56 }
        )

        val dataStream = java.io.ByteArrayOutputStream()
        val manifestOut = java.io.ByteArrayOutputStream()
        writeFieldVarint(manifestOut, 3, blockSize)

        var currentDataOffset = 0L
        for ((name, rawBytes) in specs) {
            dataStream.write(rawBytes)

            val extOut = java.io.ByteArrayOutputStream().also {
                writeFieldVarint(it, 1, 0L)
                writeFieldVarint(it, 2, 1L)
            }.toByteArray()

            val opOut = java.io.ByteArrayOutputStream().also {
                writeFieldVarint(it, 1, 0L) // REPLACE
                writeFieldVarint(it, 2, currentDataOffset)
                writeFieldVarint(it, 3, rawBytes.size.toLong())
                writeFieldBytes(it, 6, extOut)
            }.toByteArray()

            val infoOut = java.io.ByteArrayOutputStream().also {
                writeFieldVarint(it, 1, rawBytes.size.toLong())
            }.toByteArray()

            val partOut = java.io.ByteArrayOutputStream().also {
                writeFieldBytes(it, 1, name.toByteArray(Charsets.UTF_8))
                writeFieldBytes(it, 7, infoOut)
                writeFieldBytes(it, 8, opOut)
            }.toByteArray()

            writeFieldBytes(manifestOut, 13, partOut)
            currentDataOffset += rawBytes.size
        }

        val manifestBytes = manifestOut.toByteArray()
        val dataBytes = dataStream.toByteArray()

        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "leaf_synthetic_payload_test")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        try {
            val payloadFile = java.io.File(tempDir, "payload.bin")
            val outDir = java.io.File(tempDir, "out")
            val hdr = java.nio.ByteBuffer.allocate(24).apply {
                putInt(0x43724155) // CrAU
                putLong(2L)
                putLong(manifestBytes.size.toLong())
                putInt(0)
            }.array()

            java.io.FileOutputStream(payloadFile).use { fos ->
                fos.write(hdr)
                fos.write(manifestBytes)
                fos.write(dataBytes)
            }

            val snapshots = java.util.Collections.synchronizedList(mutableListOf<PayloadDumper.PayloadProgressSnapshot>())
            val files = PayloadDumper.extractPayload(
                payloadFile = payloadFile,
                outputDir = outDir,
                onSnapshot = { snap -> snapshots.add(snap) }
            )

            assertEquals(3, files.size)
            assertTrue("Expected snapshots to be emitted", snapshots.isNotEmpty())
            val lastSnap = snapshots.last()
            assertEquals(3, lastSnap.completedFiles)
            assertEquals(3, lastSnap.totalFiles)
            assertEquals(100, lastSnap.overallPercent)
            assertEquals(12288L, lastSnap.bytesExtracted)
            assertTrue(lastSnap.activeWorkers.isEmpty())

            // Verify monotonic percentage and completedFiles invariant across all emitted snapshots
            var maxSeen = 0
            var maxDoneSeen = 0
            for (snap in snapshots) {
                assertTrue("Progress went backwards: ${snap.overallPercent} < $maxSeen", snap.overallPercent >= maxSeen)
                assertTrue("Completed files went backwards: ${snap.completedFiles} < $maxDoneSeen", snap.completedFiles >= maxDoneSeen)
                maxSeen = snap.overallPercent
                maxDoneSeen = snap.completedFiles
                snap.activeWorkers.forEach { w ->
                    assertTrue(w.percent in 0..99)
                    assertTrue(w.partitionName.isNotBlank())
                }
            }

            // Verify extracted partition files match input bytes identically
            for ((name, expectedBytes) in specs) {
                val img = java.io.File(outDir, "$name.img")
                assertTrue("${img.name} missing", img.exists())
                assertArrayEquals(expectedBytes, img.readBytes())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testLeafUiMetricsAdaptationAcrossDisplayConfigs() {
        val compact320 = com.sid_imp.leaf.ui.theme.computeLeafUiMetrics(
            screenWidthDp = 320,
            screenHeightDp = 568,
            smallestWidthDp = 320,
            fontScale = 1.0f
        )
        assertEquals(com.sid_imp.leaf.ui.theme.LeafWidthClass.COMPACT, compact320.widthClass)
        assertTrue(compact320.isCompactWidth)
        assertTrue(compact320.isNarrowOrZoomed)
        assertTrue(compact320.isShortHeight)
        assertTrue(compact320.maxDialogWidthDp <= 320f)
        assertTrue(compact320.dockMaxWidthDp <= 320f)

        val compactZoomed = com.sid_imp.leaf.ui.theme.computeLeafUiMetrics(
            screenWidthDp = 320,
            screenHeightDp = 568,
            smallestWidthDp = 320,
            fontScale = 1.35f
        )
        assertTrue(compactZoomed.isNarrowOrZoomed)
        assertTrue(compactZoomed.dockHeightDp > compact320.dockHeightDp)
        assertTrue(compactZoomed.dockLabelSp < compact320.dockLabelSp)
        assertTrue(compactZoomed.textScale < compact320.textScale)

        val standardPhone = com.sid_imp.leaf.ui.theme.computeLeafUiMetrics(
            screenWidthDp = 412,
            screenHeightDp = 915,
            smallestWidthDp = 412,
            fontScale = 1.0f
        )
        assertEquals(com.sid_imp.leaf.ui.theme.LeafWidthClass.STANDARD, standardPhone.widthClass)
        assertFalse(standardPhone.isCompactWidth)
        assertFalse(standardPhone.isShortHeight)
        assertFalse(standardPhone.isLandscape)
        assertEquals(412f, standardPhone.maxContentWidthDp, 0.01f)

        val landscapeShort = com.sid_imp.leaf.ui.theme.computeLeafUiMetrics(
            screenWidthDp = 780,
            screenHeightDp = 360,
            smallestWidthDp = 360,
            fontScale = 1.0f
        )
        assertTrue(landscapeShort.isLandscape)
        assertTrue(landscapeShort.isShortHeight)
        assertEquals(com.sid_imp.leaf.ui.theme.LeafWidthClass.EXPANDED, landscapeShort.widthClass)
        assertTrue(landscapeShort.maxMenuHeightDp <= 360f)
        assertTrue(landscapeShort.maxFocusContentWidthDp > landscapeShort.maxContentWidthDp)

        val tabletPortrait = com.sid_imp.leaf.ui.theme.computeLeafUiMetrics(
            screenWidthDp = 720,
            screenHeightDp = 1024,
            smallestWidthDp = 720,
            fontScale = 1.0f
        )
        assertEquals(com.sid_imp.leaf.ui.theme.LeafWidthClass.EXPANDED, tabletPortrait.widthClass)
        assertEquals(640f, tabletPortrait.maxContentWidthDp, 0.01f)
        assertTrue(tabletPortrait.maxFocusContentWidthDp >= tabletPortrait.maxContentWidthDp)

        val highCustomSwLandscape = com.sid_imp.leaf.ui.theme.computeLeafUiMetrics(
            screenWidthDp = 1080,
            screenHeightDp = 800,
            smallestWidthDp = 800,
            fontScale = 1.0f
        )
        assertEquals(com.sid_imp.leaf.ui.theme.LeafWidthClass.EXPANDED, highCustomSwLandscape.widthClass)
        assertTrue(highCustomSwLandscape.isLandscape)
        assertEquals(740f, highCustomSwLandscape.maxFocusContentWidthDp, 0.01f)
        assertEquals(480f, highCustomSwLandscape.dockMaxWidthDp, 0.01f)
    }

    @Test
    fun testStorageVolumeHelperPathsAndLabels() {
        assertEquals(
            "/storage/3A1F-9B0C",
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.extractStorageRootFromAppFilesDir(
                "/storage/3A1F-9B0C/Android/data/com.sid_imp.leaf/files"
            )
        )
        assertEquals(
            "/storage/emulated/0",
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.extractStorageRootFromAppFilesDir(
                "/storage/emulated/0/Android/data/com.sid_imp.leaf/files"
            )
        )
        assertNull(
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.extractStorageRootFromAppFilesDir(null)
        )
        assertNull(
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.extractStorageRootFromAppFilesDir("/storage/3A1F-9B0C")
        )

        val used = mutableSetOf<String>()
        val firstSd = com.sid_imp.leaf.ui.components.StorageVolumeHelper.formatRemovableVolumeLabel(
            rawDescription = "SanDisk SD card",
            existingLabels = used
        )
        assertEquals("SD Card", firstSd)
        used.add(firstSd)

        val secondSd = com.sid_imp.leaf.ui.components.StorageVolumeHelper.formatRemovableVolumeLabel(
            rawDescription = "Samsung microSD",
            existingLabels = used
        )
        assertEquals("SD Card 2", secondSd)
        used.add(secondSd)

        val usbDrive = com.sid_imp.leaf.ui.components.StorageVolumeHelper.formatRemovableVolumeLabel(
            rawDescription = "USB OTG Drive",
            existingLabels = used
        )
        assertEquals("USB Drive", usbDrive)

        assertTrue(
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.isPathInsideRoot(
                "/storage/3A1F-9B0C/ROMs/build",
                "/storage/3A1F-9B0C"
            )
        )
        assertFalse(
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.isPathInsideRoot(
                "/storage/3A1F-9B0C_other",
                "/storage/3A1F-9B0C"
            )
        )

        val removable = listOf("/storage/3A1F-9B0C" to "SD Card")
        assertEquals(
            "SD Card",
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.formatStorageBreadcrumb(
                currentPath = "/storage/3A1F-9B0C",
                phoneRootPath = "/storage/emulated/0",
                removableRoots = removable
            )
        )
        assertEquals(
            "SD Card/ROMs/Infinity",
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.formatStorageBreadcrumb(
                currentPath = "/storage/3A1F-9B0C/ROMs/Infinity",
                phoneRootPath = "/storage/emulated/0",
                removableRoots = removable
            )
        )
        assertEquals(
            "Phone Storage/Download",
            com.sid_imp.leaf.ui.components.StorageVolumeHelper.formatStorageBreadcrumb(
                currentPath = "/storage/emulated/0/Download",
                phoneRootPath = "/storage/emulated/0",
                removableRoots = removable
            )
        )
    }
}
