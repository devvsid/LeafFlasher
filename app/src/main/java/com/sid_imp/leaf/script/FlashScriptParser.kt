package com.sid_imp.leaf.script

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.SequenceInputStream

object FlashScriptParser {

    /**
     * Normalizes a filename or partition name into its canonical partition stem.
     * Examples: "boot.img" -> "boot", "vendor_boot_a.img" -> "vendor_boot",
     * "super_empty.img" -> "super_empty", "preloader_raw.img" -> "preloader".
     */
    fun normalizePartitionStem(name: String): String {
        val noExt = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.')
            .lowercase()
            .trim()
        val stem = noExt.removeSuffix("_a").removeSuffix("_b")
        return when {
            stem == "super_empty" || stem.startsWith("super_empty") || stem.endsWith("_super_empty") -> "super_empty"
            stem == "preloader_raw" || stem.startsWith("preloader") -> "preloader"
            stem in setOf("stock_lk", "lk_stock", "patched_lk", "lk_patched") -> "lk"
            else -> stem
        }
    }

    fun isWipeSuperTask(task: FlashTask): Boolean {
        return task.rawCommandLine.contains("wipe-super", ignoreCase = true) ||
               task.fileName.contains("super_empty", ignoreCase = true)
    }

    /**
     * Counts distinct target partitions being flashed (excludes wipe-super meta commands and avoids
     * doubling physical A+B slot targets such as boot_a + boot_b).
     */
    fun countUniquePartitions(tasks: List<FlashTask>): Int {
        return tasks
            .asSequence()
            .filter { it.type == TaskType.FLASH && !isWipeSuperTask(it) && it.partition.isNotBlank() }
            .map { normalizePartitionStem(it.partition) }
            .filter { it.isNotEmpty() }
            .distinct()
            .count()
    }

    /**
     * Patches Android Verified Boot (AVB 2.0 "AVB0") header byte 123 (flags LSB) in-place on a copy:
     * bit 0 (0x01) = HASHTREE_DISABLED (--disable-verity)
     * bit 1 (0x02) = VERIFICATION_DISABLED (--disable-verification)
     */
    fun patchVbmetaHeaderBytes(
        data: ByteArray,
        disableVerity: Boolean = true,
        disableVerification: Boolean = true
    ): ByteArray {
        if ((!disableVerity && !disableVerification) || data.size < 124) return data
        if (data[0] != 'A'.code.toByte() ||
            data[1] != 'V'.code.toByte() ||
            data[2] != 'B'.code.toByte() ||
            data[3] != '0'.code.toByte()
        ) {
            return data
        }
        var bits = 0
        if (disableVerity) bits = bits or 0x01
        if (disableVerification) bits = bits or 0x02
        val copy = data.copyOf()
        copy[123] = (copy[123].toInt() or bits).toByte()
        return copy
    }

    /**
     * Enables AVB disable-verity & disable-verification flags on vbmeta flash tasks
     * when the user opts to disable dm-verity verification (e.g. for Port ROMs).
     */
    fun applyVbmetaVerityOption(
        tasks: List<FlashTask>,
        disableVerityAndVerification: Boolean
    ): List<FlashTask> {
        if (!disableVerityAndVerification) return tasks
        return tasks.map { task ->
            if (task.type == TaskType.FLASH) {
                val stem = normalizePartitionStem(task.partition.ifBlank { task.fileName })
                if (stem == "vbmeta" || stem.startsWith("vbmeta_")) {
                    task.copy(disableVerity = true, disableVerification = true)
                } else {
                    task
                }
            } else {
                task
            }
        }
    }

    /**
     * Wraps a stream opener to apply AVB disable flags across the first 256 header bytes when
     * targeting a vbmeta image, without loading the whole file into memory.
     */
    fun wrapStreamWithAvbPatch(
        openStream: () -> InputStream?,
        disableVerity: Boolean,
        disableVerification: Boolean
    ): () -> InputStream? {
        if (!disableVerity && !disableVerification) return openStream
        return {
            val raw = openStream()
            if (raw == null) {
                null
            } else {
                val bis = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, 8192)
                val headerBuf = ByteArray(256)
                var readCount = 0
                while (readCount < headerBuf.size) {
                    val n = bis.read(headerBuf, readCount, headerBuf.size - readCount)
                    if (n <= 0) break
                    readCount += n
                }
                val actualHeader = if (readCount == headerBuf.size) headerBuf else headerBuf.copyOf(readCount)
                val patchedHeader = patchVbmetaHeaderBytes(actualHeader, disableVerity, disableVerification)
                SequenceInputStream(ByteArrayInputStream(patchedHeader), bis)
            }
        }
    }

    private val KNOWN_LOGICAL_PARTITIONS = setOf(
        "system", "system_a", "system_b",
        "vendor", "vendor_a", "vendor_b",
        "product", "product_a", "product_b",
        "system_ext", "system_ext_a", "system_ext_b",
        "odm", "odm_a", "odm_b",
        "odm_dlkm", "odm_dlkm_a", "odm_dlkm_b",
        "vendor_dlkm", "vendor_dlkm_a", "vendor_dlkm_b",
        "system_dlkm", "system_dlkm_a", "system_dlkm_b",
        "super"
    )

    /**
     * Parses Windows .bat, .cmd or Bash / Termux .sh script content into structured FlashTask list.
     * Expands script variables and loops, removes PC driver checks, and preserves exact flashing sequence.
     */
    fun parseScript(scriptContent: String): List<FlashTask> {
        val lines = scriptContent.lines()
        val expandedLines = preprocessAndExpand(lines)

        val tasks = mutableListOf<FlashTask>()
        var taskId = 1

        for (line in expandedLines) {
            val task = parseCommandLine(line, taskId)
            if (task != null) {
                tasks.add(task)
                taskId++
            }
        }

        classifyPhases(tasks)
        return tasks
    }

    fun parseStream(stream: InputStream): List<FlashTask> {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val lines = reader.readLines()
        return parseScript(lines.joinToString("\n"))
    }

    /**
     * Preprocesses script: extracts variable definitions, expands bash/bat loops,
     * strips functions definitions and ignores PC driver/management commands.
     */
    private fun preprocessAndExpand(lines: List<String>): List<String> {
        val variables = mutableMapOf<String, String>()
        val expanded = mutableListOf<String>()

        var inFunction = false

        // 1. First pass: extract variable definitions
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("::") || trimmed.startsWith("rem", ignoreCase = true)) {
                continue
            }

            // Bash variable assignment: name="val" or name=val
            val bashVarRegex = Regex("""^([a-zA-Z_][a-zA-Z0-9_]*)=["']?(.*?)["']?$""")
            val bashMatch = bashVarRegex.matchEntire(trimmed)
            if (bashMatch != null && !trimmed.startsWith("export ") && !trimmed.startsWith("if ")) {
                val varName = bashMatch.groupValues[1]
                var varVal = bashMatch.groupValues[2]
                for ((k, v) in variables) {
                    varVal = varVal.replace("\${$k}", v).replace("\$$k", v)
                }
                variables[varName] = varVal
                continue
            }

            // Batch variable assignment: set name=val
            val batVarRegex = Regex("""^set\s+([a-zA-Z_][a-zA-Z0-9_]*)=(.*)$""", RegexOption.IGNORE_CASE)
            val batMatch = batVarRegex.matchEntire(trimmed)
            if (batMatch != null) {
                val varName = batMatch.groupValues[1]
                var varVal = batMatch.groupValues[2]
                for ((k, v) in variables) {
                    varVal = varVal.replace("%$k%", v)
                }
                variables[varName] = varVal
                continue
            }
        }

        // 2. Second pass: expand loops and filter out functions & PC noise
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            // Track bash function block start/end
            if (trimmed.contains("() {") || trimmed.endsWith("()")) {
                inFunction = true
                continue
            }
            if (inFunction) {
                if (trimmed == "}") {
                    inFunction = false
                }
                continue
            }

            // Bash loop expansion: for i in $core_partitions; do FlashImageSilent "${i}_a" "${i}.img"; done
            val bashForRegex = Regex("""for\s+([a-zA-Z_][a-zA-Z0-9_]*)\s+in\s+([^;]+);\s*do\s+(.*?)(?:;\s*done|$)""")
            val bashForMatch = bashForRegex.find(trimmed)
            if (bashForMatch != null) {
                val iterVar = bashForMatch.groupValues[1]
                val rawList = bashForMatch.groupValues[2].trim()
                val body = bashForMatch.groupValues[3].trim()

                val items = if (rawList.startsWith("$")) {
                    val vName = rawList.trimStart('$', '{').trimEnd('}')
                    variables[vName]?.split("\\s+".toRegex()) ?: emptyList()
                } else {
                    rawList.split("\\s+".toRegex())
                }

                for (item in items) {
                    if (item.isNotBlank()) {
                        val cmdItem = body.replace("\${$iterVar}", item).replace("\$$iterVar", item)
                        expanded.add(cmdItem)
                    }
                }
                continue
            }

            // Batch loop expansion: for %%i in (...) do ...
            val batForRegex = Regex("""for\s+%%([a-zA-Z0-9_]+)\s+in\s+\((.*?)\)\s+do\s+(.*)""", RegexOption.IGNORE_CASE)
            val batForMatch = batForRegex.find(trimmed)
            if (batForMatch != null) {
                val iterVar = batForMatch.groupValues[1]
                var rawList = batForMatch.groupValues[2].trim()
                val body = batForMatch.groupValues[3].trim()

                for ((k, v) in variables) {
                    rawList = rawList.replace("%$k%", v)
                }
                val items = rawList.replace(',', ' ').split("\\s+".toRegex())
                for (item in items) {
                    if (item.isNotBlank()) {
                        val cmdItem = body.replace("%%$iterVar", item)
                        expanded.add(cmdItem)
                    }
                }
                continue
            }

            expanded.add(trimmed)
        }

        // 3. Third pass: variable substitution and function call resolution
        val cleaned = mutableListOf<String>()
        for (line in expanded) {
            var t = line.trim()
            for ((k, v) in variables) {
                t = t.replace("\${$k}", v).replace("\$$k", v).replace("%$k%", v)
            }

            // Convert Termux flash functions: FlashImageSilent "boot_a" "boot.img" -> fastboot flash boot_a boot.img
            val flashCallRegex = Regex("""FlashImage(?:Silent|Verbose)\s+["']?([^"'\s]+)["']?\s+["']?([^"'\s]+)["']?""")
            val flashMatch = flashCallRegex.find(t)
            if (flashMatch != null) {
                var part = flashMatch.groupValues[1]
                val img = flashMatch.groupValues[2]
                if (part.equals("preloader_a", ignoreCase = true) || part.equals("preloader_b", ignoreCase = true)) {
                    part = "preloader"
                }
                cleaned.add("fastboot flash $part $img")
                continue
            }

            if (t.equals("RebootFastbootD", ignoreCase = true) || t.equals("RebootFastbootD()", ignoreCase = true)) {
                cleaned.add("fastboot reboot fastboot")
                continue
            }

            cleaned.add(t)
        }

        return cleaned
    }

    fun parseCommandLine(rawLine: String, id: Int): FlashTask? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null

        // Strip comments
        if (trimmed.startsWith("#") ||
            trimmed.startsWith("::") ||
            trimmed.startsWith("rem", ignoreCase = true) ||
            trimmed.startsWith("@rem", ignoreCase = true) ||
            trimmed.startsWith("//")) {
            return null
        }

        // Strip shell noise, driver checks, pauses, pings, terminal echo
        val lower = trimmed.lowercase()
        if (lower.startsWith("@echo") ||
            lower.startsWith("echo.") ||
            lower.startsWith("echo ") ||
            lower == "echo" ||
            lower == "pause" ||
            lower.startsWith("pause ") ||
            lower == "cls" ||
            lower == "clear" ||
            lower.startsWith("title ") ||
            lower.startsWith("color ") ||
            lower.startsWith("pnputil") ||
            lower.startsWith("ping ") ||
            lower.startsWith("timeout ") ||
            lower.startsWith("sleep ") ||
            lower.startsWith("if ") ||
            lower.startsWith("goto ") ||
            lower.startsWith(":") ||
            lower.startsWith("exit") ||
            lower.startsWith("set ") ||
            lower.startsWith("setlocal") ||
            lower.startsWith("endlocal") ||
            lower.startsWith("cd ") ||
            lower.startsWith("path ") ||
            lower.startsWith("pkg ") ||
            lower.startsWith("apt ") ||
            lower.startsWith("git ") ||
            lower.startsWith("chmod ") ||
            (lower.startsWith("termux-") && !lower.startsWith("termux-fastboot")) ||
            lower.startsWith("read ") ||
            lower.startsWith("cat ") ||
            lower.startsWith("setupenvironment") ||
            lower.startsWith("updateprogress") ||
            lower.startsWith("checkdevice") ||
            lower.contains("fastboot devices") ||
            lower.contains("getvar max-download-size")) {
            return null
        }

        // Clean redirection and pipelines: e.g. >> log.txt 2>&1, | while ..., &
        var cleanLine = trimmed
        cleanLine = cleanLine.replace(">>", " ").replace(">", " ")
        if (cleanLine.contains("2>&1")) {
            cleanLine = cleanLine.replace("2>&1", " ")
        }
        val pipeIdx = cleanLine.indexOf('|')
        if (pipeIdx >= 0) {
            cleanLine = cleanLine.substring(0, pipeIdx)
        }
        val bgIdx = cleanLine.indexOf('&')
        if (bgIdx >= 0 && !cleanLine.contains("2>&1")) {
            cleanLine = cleanLine.substring(0, bgIdx)
        }
        cleanLine = cleanLine.trim()

        if (cleanLine.startsWith("@")) {
            cleanLine = cleanLine.substring(1).trim()
        }

        // Fastboot binary prefixes
        val fastbootPrefixes = listOf(
            "./platform-tools/fastboot",
            ".\\platform-tools\\fastboot",
            "platform-tools/fastboot",
            "platform-tools\\fastboot",
            "./termux-fastboot",
            "termux-fastboot",
            "./fastboot",
            ".\\fastboot",
            "fastboot.exe",
            "fastboot",
            "%FASTBOOT%",
            "\$FASTBOOT_CMD",
            "\$FASTBOOT"
        )

        var matched = false
        for (prefix in fastbootPrefixes) {
            if (cleanLine.startsWith(prefix, ignoreCase = true)) {
                cleanLine = cleanLine.substring(prefix.length).trim()
                matched = true
                break
            }
        }

        if (!matched) {
            val idx = cleanLine.indexOf("fastboot", ignoreCase = true)
            if (idx >= 0) {
                cleanLine = cleanLine.substring(idx + "fastboot".length).trim()
                matched = true
            }
        }

        if (!matched &&
            !cleanLine.startsWith("flash ", ignoreCase = true) &&
            !cleanLine.startsWith("erase ", ignoreCase = true) &&
            !cleanLine.startsWith("reboot", ignoreCase = true) &&
            !cleanLine.startsWith("wipe-super", ignoreCase = true) &&
            !cleanLine.startsWith("--set-active", ignoreCase = true) &&
            !cleanLine.startsWith("-w", ignoreCase = true)) {
            return null
        }

        return tokenizeAndBuildTask(id, trimmed, cleanLine)
    }

    private fun tokenizeAndBuildTask(id: Int, rawCommandLine: String, cmdLine: String): FlashTask? {
        val allTokens = tokenize(cmdLine)
        if (allTokens.isEmpty()) return null

        var disableVerity = false
        var disableVerification = false
        var slot: String? = null

        // Known fastboot action verbs
        val fastbootVerbs = setOf(
            "flash", "erase", "reboot", "reboot-fastboot", "reboot-bootloader",
            "reboot-recovery", "wipe-super", "set_active", "--set-active",
            "delete-logical-partition", "create-logical-partition", "resize-logical-partition",
            "-w"
        )

        // Find the action verb index
        var verbIndex = -1
        var verb = ""

        for (i in allTokens.indices) {
            val t = allTokens[i].lowercase()
            if (t in fastbootVerbs || t.startsWith("--set-active=")) {
                verbIndex = i
                verb = if (t.startsWith("--set-active=")) "--set-active" else t
                break
            }
        }

        if (verbIndex == -1) {
            return null
        }

        // Process options/flags anywhere before or after the verb
        val remainingArgs = mutableListOf<String>()
        var i = 0
        while (i < allTokens.size) {
            if (i == verbIndex) {
                i++
                continue
            }
            val tok = allTokens[i]
            when {
                tok == "--disable-verity" -> disableVerity = true
                tok == "--disable-verification" -> disableVerification = true
                tok.startsWith("--set-active=") -> {
                    slot = tok.removePrefix("--set-active=").trim()
                }
                tok.startsWith("--slot=") -> {
                    slot = tok.removePrefix("--slot=").trim()
                }
                tok == "--slot" && i + 1 < allTokens.size -> {
                    slot = allTokens[i + 1].trim()
                    i++
                }
                tok == "-s" && i + 1 < allTokens.size -> {
                    i++ // Skip serial argument
                }
                tok == "-S" && i + 1 < allTokens.size -> {
                    i++ // Skip max sparse size argument (e.g. -S 512M)
                }
                tok == "%*" || tok == "%1" || tok == "%2" || tok == "-u" || tok == "--skip-secondary" -> {
                    // Ignore batch argument pass-throughs
                }
                else -> {
                    remainingArgs.add(tok)
                }
            }
            i++
        }

        return when (verb) {
            "flash" -> {
                if (remainingArgs.isNotEmpty()) {
                    val partition = remainingArgs[0]
                    val fileToken = if (remainingArgs.size > 1) remainingArgs[1] else ""
                    val cleanFileName = extractFileName(fileToken)
                    FlashTask(
                        id = id,
                        type = TaskType.FLASH,
                        rawCommandLine = rawCommandLine,
                        partition = partition,
                        fileName = cleanFileName,
                        slot = slot,
                        disableVerity = disableVerity,
                        disableVerification = disableVerification
                    )
                } else null
            }

            "erase" -> {
                val partition = if (remainingArgs.isNotEmpty()) remainingArgs[0] else ""
                FlashTask(
                    id = id,
                    type = TaskType.ERASE,
                    rawCommandLine = rawCommandLine,
                    partition = partition
                )
            }

            "wipe-super" -> {
                val superFile = if (remainingArgs.isNotEmpty()) extractFileName(remainingArgs[0]) else "super_empty.img"
                FlashTask(
                    id = id,
                    type = TaskType.FLASH,
                    rawCommandLine = rawCommandLine,
                    partition = "super",
                    fileName = superFile
                )
            }

            "-w" -> {
                FlashTask(
                    id = id,
                    type = TaskType.WIPE,
                    rawCommandLine = rawCommandLine
                )
            }

            "reboot" -> {
                val target = if (remainingArgs.isNotEmpty()) remainingArgs[0].lowercase() else ""
                when (target) {
                    "fastboot", "fastbootd" -> FlashTask(
                        id = id,
                        type = TaskType.REBOOT_FASTBOOT,
                        rawCommandLine = rawCommandLine
                    )
                    "bootloader" -> FlashTask(
                        id = id,
                        type = TaskType.REBOOT_BOOTLOADER,
                        rawCommandLine = rawCommandLine
                    )
                    "recovery" -> FlashTask(
                        id = id,
                        type = TaskType.REBOOT_RECOVERY,
                        rawCommandLine = rawCommandLine
                    )
                    else -> FlashTask(
                        id = id,
                        type = TaskType.REBOOT,
                        rawCommandLine = rawCommandLine
                    )
                }
            }

            "reboot-fastboot" -> FlashTask(
                id = id,
                type = TaskType.REBOOT_FASTBOOT,
                rawCommandLine = rawCommandLine
            )

            "reboot-bootloader" -> FlashTask(
                id = id,
                type = TaskType.REBOOT_BOOTLOADER,
                rawCommandLine = rawCommandLine
            )

            "reboot-recovery" -> FlashTask(
                id = id,
                type = TaskType.REBOOT_RECOVERY,
                rawCommandLine = rawCommandLine
            )

            "set_active", "--set-active" -> {
                val targetSlot = if (remainingArgs.isNotEmpty()) remainingArgs[0] else slot ?: "a"
                FlashTask(
                    id = id,
                    type = TaskType.SET_ACTIVE,
                    rawCommandLine = rawCommandLine,
                    slot = targetSlot
                )
            }

            "delete-logical-partition" -> {
                val partition = if (remainingArgs.isNotEmpty()) remainingArgs[0] else ""
                FlashTask(
                    id = id,
                    type = TaskType.DELETE_LOGICAL,
                    rawCommandLine = rawCommandLine,
                    partition = partition
                )
            }

            "create-logical-partition" -> {
                val partition = if (remainingArgs.isNotEmpty()) remainingArgs[0] else ""
                val size = if (remainingArgs.size > 1) remainingArgs[1] else ""
                FlashTask(
                    id = id,
                    type = TaskType.CREATE_LOGICAL,
                    rawCommandLine = rawCommandLine,
                    partition = partition,
                    extraArgs = listOf(size)
                )
            }

            "resize-logical-partition" -> {
                val partition = if (remainingArgs.isNotEmpty()) remainingArgs[0] else ""
                val size = if (remainingArgs.size > 1) remainingArgs[1] else ""
                FlashTask(
                    id = id,
                    type = TaskType.RESIZE_LOGICAL,
                    rawCommandLine = rawCommandLine,
                    partition = partition,
                    extraArgs = listOf(size)
                )
            }

            else -> null
        }
    }

    private fun classifyPhases(tasks: List<FlashTask>) {
        val rebootIndex = tasks.indexOfFirst { it.type == TaskType.REBOOT_FASTBOOT }

        for (i in tasks.indices) {
            val t = tasks[i]
            when {
                t.type == TaskType.REBOOT_FASTBOOT -> {
                    t.phase = FlashingPhase.FASTBOOTD_TRANSITION
                }
                t.type == TaskType.REBOOT ||
                (t.type == TaskType.REBOOT_BOOTLOADER && i == tasks.lastIndex) ||
                t.type == TaskType.WIPE ||
                (t.type == TaskType.ERASE && (t.partition.contains("userdata") || t.partition.contains("metadata"))) -> {
                    t.phase = FlashingPhase.FINALIZATION
                }
                t.type == TaskType.FLASH -> {
                    if (rebootIndex >= 0) {
                        t.phase = if (i < rebootIndex) FlashingPhase.FIRMWARE_BOOTLOADER else FlashingPhase.LOGICAL_DYNAMIC
                    } else {
                        val cleanPart = t.partition.lowercase().removeSuffix("_a").removeSuffix("_b")
                        t.phase = if (cleanPart in KNOWN_LOGICAL_PARTITIONS) FlashingPhase.LOGICAL_DYNAMIC else FlashingPhase.FIRMWARE_BOOTLOADER
                    }
                }
                else -> {
                    t.phase = if (rebootIndex in 0..i) FlashingPhase.LOGICAL_DYNAMIC else FlashingPhase.FIRMWARE_BOOTLOADER
                }
            }
        }
    }

    fun analyzeScript(tasks: List<FlashTask>, fileMap: Map<String, *>): ScriptAnalysis {
        val firmware = mutableListOf<FlashTask>()
        var rebootTask: FlashTask? = null
        val logical = mutableListOf<FlashTask>()
        val final = mutableListOf<FlashTask>()

        var totalPartitions = 0
        var presentCount = 0

        val lowerKeys = HashSet<String>(fileMap.size * 2)
        for (k in fileMap.keys) {
            lowerKeys.add(k.lowercase())
        }

        for (task in tasks) {
            if (task.type == TaskType.FLASH) {
                totalPartitions++
                val cleanPart = task.partition.lowercase().removeSuffix("_a").removeSuffix("_b")
                val fileLow = task.fileName.lowercase()
                val partLow = task.partition.lowercase()
                val cleanFile = fileLow.removeSuffix(".img").removeSuffix(".bin").removeSuffix(".raw")
                val exists = fileMap.containsKey(task.fileName) ||
                        lowerKeys.contains(fileLow) ||
                        lowerKeys.contains(cleanFile) ||
                        fileMap.containsKey(task.partition) ||
                        lowerKeys.contains(partLow) ||
                        lowerKeys.contains(cleanPart) ||
                        lowerKeys.contains("${cleanPart}.img") ||
                        lowerKeys.contains("${cleanPart}_a.img") ||
                        lowerKeys.contains("${cleanPart}_b.img") ||
                        (fileLow.isNotEmpty() && (
                            lowerKeys.contains("${partLow}.img") ||
                            lowerKeys.contains("${cleanPart}.bin") ||
                            lowerKeys.contains("${cleanPart}.raw") ||
                            lowerKeys.any { it.startsWith(cleanPart) }
                        ))
                task.isFilePresent = exists
                if (exists) presentCount++
            }

            when (task.phase) {
                FlashingPhase.FIRMWARE_BOOTLOADER -> firmware.add(task)
                FlashingPhase.FASTBOOTD_TRANSITION -> rebootTask = task
                FlashingPhase.LOGICAL_DYNAMIC -> logical.add(task)
                FlashingPhase.FINALIZATION -> final.add(task)
            }
        }

        return ScriptAnalysis(
            tasks = tasks,
            firmwareTasks = firmware,
            rebootTransitionTask = rebootTask,
            logicalTasks = logical,
            finalTasks = final,
            totalPartitionsCount = totalPartitions,
            presentPartitionsCount = presentCount,
            uniquePartitionsCount = countUniquePartitions(tasks)
        )
    }

    private fun extractFileName(pathToken: String): String {
        var clean = pathToken.trim().removeSurrounding("\"").removeSurrounding("'")
        clean = clean.replace("%~dp0", "")
        clean = clean.replace("%CD%\\", "")
        clean = clean.replace("%CD%/", "")
        clean = clean.replace("\\", "/")
        val lastSlash = clean.lastIndexOf('/')
        return if (lastSlash >= 0) {
            clean.substring(lastSlash + 1)
        } else {
            clean
        }
    }

    private fun tokenize(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var quoteChar = ' '

        for (i in line.indices) {
            val c = line[i]
            when {
                (c == '"' || c == '\'') && !inQuote -> {
                    inQuote = true
                    quoteChar = c
                }
                c == quoteChar && inQuote -> {
                    inQuote = false
                }
                c.isWhitespace() && !inQuote -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                else -> {
                    current.append(c)
                }
            }
        }

        if (current.isNotEmpty()) {
            result.add(current.toString())
        }

        return result
    }
}
