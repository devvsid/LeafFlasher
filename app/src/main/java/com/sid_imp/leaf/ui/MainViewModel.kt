package com.sid_imp.leaf.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sid_imp.leaf.fastboot.*
import com.sid_imp.leaf.script.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID

enum class RomWizardStep {
    BOOT_SELECTION,
    WIPE_DATA,
    CUSTOM_PARTITIONS,
    VBMETA_VERITY,
    SUMMARY
}

data class PartitionFileOverride(
    val id: String,
    val targetStem: String,
    val fileName: String,
    val fileSize: Long,
    val cachedFile: File,
    val uri: Uri
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val uiPrefs = context.getSharedPreferences("leaf_ui_prefs", Context.MODE_PRIVATE)
    val usbManager = FastbootUsbManager(context)
    val flashExecutor = FlashExecutor(context, usbManager)

    private val _showOnboardingTutor = MutableStateFlow(!uiPrefs.getBoolean("tutor_seen_v1", false))
    val showOnboardingTutor: StateFlow<Boolean> = _showOnboardingTutor.asStateFlow()

    val fastbootMode: StateFlow<FastbootMode> = usbManager.modeFlow
    val deviceInfo: StateFlow<DeviceInfo?> = usbManager.deviceInfoFlow
    val adbDevice: StateFlow<AdbUsbDevice?> = usbManager.adbDeviceFlow
    val adbAwaitingAuth: StateFlow<Boolean> = usbManager.adbAwaitingAuthFlow

    val executionProgress: StateFlow<ExecutionProgress> = flashExecutor.progressFlow
    val flashExecutionState: StateFlow<FlashExecutionState> = flashExecutor.progressFlow
        .map { it.state }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, FlashExecutionState.IDLE)
    val isFlashingActive: StateFlow<Boolean> = flashExecutor.progressFlow
        .map {
            it.state == FlashExecutionState.RUNNING ||
                it.state == FlashExecutionState.WAITING_FOR_RECONNECT ||
                it.state == FlashExecutionState.PAUSED
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val logs: StateFlow<List<String>> = flashExecutor.logFlow

    private val _parsedTasks = MutableStateFlow<List<FlashTask>>(emptyList())
    val parsedTasks: StateFlow<List<FlashTask>> = _parsedTasks.asStateFlow()

    private val _extractedPartitions = MutableStateFlow<List<ExtractedPartitionItem>>(emptyList())
    val extractedPartitions: StateFlow<List<ExtractedPartitionItem>> = _extractedPartitions.asStateFlow()

    private val _uniquePartitionCount = MutableStateFlow(0)
    val uniquePartitionCount: StateFlow<Int> = _uniquePartitionCount.asStateFlow()

    private val _loadedScriptName = MutableStateFlow("")
    val loadedScriptName: StateFlow<String> = _loadedScriptName.asStateFlow()

    private val _loadedSourceKind = MutableStateFlow("") // "zip", "folder", "script", ""
    val loadedSourceKind: StateFlow<String> = _loadedSourceKind.asStateFlow()

    private val _selectedRomSlot = MutableStateFlow("a") // "a", "b", "both"
    val selectedRomSlot: StateFlow<String> = _selectedRomSlot.asStateFlow()

    private val _romWizardStep = MutableStateFlow(RomWizardStep.BOOT_SELECTION)
    val romWizardStep: StateFlow<RomWizardStep> = _romWizardStep.asStateFlow()

    private val _wipeDataEnabled = MutableStateFlow(false)
    val wipeDataEnabled: StateFlow<Boolean> = _wipeDataEnabled.asStateFlow()

    private val _disableVbmetaVerity = MutableStateFlow(false)
    val disableVbmetaVerity: StateFlow<Boolean> = _disableVbmetaVerity.asStateFlow()

    private val _hasVbmetaPartition = MutableStateFlow(false)
    val hasVbmetaPartition: StateFlow<Boolean> = _hasVbmetaPartition.asStateFlow()

    private val _rebootAfterFlash = MutableStateFlow(true)
    val rebootAfterFlash: StateFlow<Boolean> = _rebootAfterFlash.asStateFlow()

    private val _postFlashRebootTarget = MutableStateFlow("system") // "system", "bootloader"
    val postFlashRebootTarget: StateFlow<String> = _postFlashRebootTarget.asStateFlow()

    private val _partitionOverrides = MutableStateFlow<List<PartitionFileOverride>>(emptyList())
    val partitionOverrides: StateFlow<List<PartitionFileOverride>> = _partitionOverrides.asStateFlow()

    private val _zipProgress = MutableStateFlow(ZipExtractionProgress())
    val zipProgress: StateFlow<ZipExtractionProgress> = _zipProgress.asStateFlow()
    val isZipExtracting: StateFlow<Boolean> = _zipProgress
        .map { it.isExtracting }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isLogExpanded = MutableStateFlow(false)
    val isLogExpanded: StateFlow<Boolean> = _isLogExpanded.asStateFlow()

    // Single partition auto-detected state (no keyboard input required)
    private val _singlePartitionName = MutableStateFlow("")
    val singlePartitionName: StateFlow<String> = _singlePartitionName.asStateFlow()

    private val _singleSelectedFileUri = MutableStateFlow<Uri?>(null)
    val singleSelectedFileUri: StateFlow<Uri?> = _singleSelectedFileUri.asStateFlow()

    private val _singleSelectedFileName = MutableStateFlow("")
    val singleSelectedFileName: StateFlow<String> = _singleSelectedFileName.asStateFlow()

    private val _singleSelectedFileSize = MutableStateFlow(0L)
    val singleSelectedFileSize: StateFlow<Long> = _singleSelectedFileSize.asStateFlow()

    private val _singleDisableVerity = MutableStateFlow(false)
    val singleDisableVerity: StateFlow<Boolean> = _singleDisableVerity.asStateFlow()

    private val _isSinglePartitionFlashing = MutableStateFlow(false)
    val isSinglePartitionFlashing: StateFlow<Boolean> = _isSinglePartitionFlashing.asStateFlow()

    private val _singlePartitionProgressPercent = MutableStateFlow(0)
    val singlePartitionProgressPercent: StateFlow<Int> = _singlePartitionProgressPercent.asStateFlow()

    private val _singlePartitionStatusText = MutableStateFlow("")
    val singlePartitionStatusText: StateFlow<String> = _singlePartitionStatusText.asStateFlow()

    private val _singlePartitionSpeedMb = MutableStateFlow(0.0)
    val singlePartitionSpeedMb: StateFlow<Double> = _singlePartitionSpeedMb.asStateFlow()

    private val _isFormattingData = MutableStateFlow(false)
    val isFormattingData: StateFlow<Boolean> = _isFormattingData.asStateFlow()

    // Selective image extraction from ROM ZIP (Partition tab -> Extract 1 or more img from zip)
    private val _showZipPartitionExtractDialog = MutableStateFlow(false)
    val showZipPartitionExtractDialog: StateFlow<Boolean> = _showZipPartitionExtractDialog.asStateFlow()

    private val _isInspectingZipPartitions = MutableStateFlow(false)
    val isInspectingZipPartitions: StateFlow<Boolean> = _isInspectingZipPartitions.asStateFlow()

    private val _inspectedZipName = MutableStateFlow("")
    val inspectedZipName: StateFlow<String> = _inspectedZipName.asStateFlow()

    private val _inspectedZipPartitions = MutableStateFlow<List<ZipPartitionEntry>>(emptyList())
    val inspectedZipPartitions: StateFlow<List<ZipPartitionEntry>> = _inspectedZipPartitions.asStateFlow()

    private val _zipInspectionError = MutableStateFlow<String?>(null)
    val zipInspectionError: StateFlow<String?> = _zipInspectionError.asStateFlow()

    private val _selectiveOutputTreeUri = MutableStateFlow<Uri?>(null)
    val selectiveOutputTreeUri: StateFlow<Uri?> = _selectiveOutputTreeUri.asStateFlow()

    private val _selectiveOutputFolderLabel = MutableStateFlow("Downloads/LeafFlasher")
    val selectiveOutputFolderLabel: StateFlow<String> = _selectiveOutputFolderLabel.asStateFlow()

    private val _selectiveExtractProgress = MutableStateFlow(ZipExtractionProgress())
    val selectiveExtractProgress: StateFlow<ZipExtractionProgress> = _selectiveExtractProgress.asStateFlow()

    val isSelectiveExtracting: StateFlow<Boolean> = _selectiveExtractProgress
        .map { it.isExtracting }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _lastSelectiveExtractResult = MutableStateFlow<SelectiveExtractionResult?>(null)
    val lastSelectiveExtractResult: StateFlow<SelectiveExtractionResult?> = _lastSelectiveExtractResult.asStateFlow()

    private var inspectedZipUri: Uri? = null
    private var inspectZipJob: Job? = null
    private var selectiveExtractJob: Job? = null
    @Volatile private var isSelectiveCancelled = false

    // ADB Sideload states (GApps, Magisk, OTA ROMs, ZIP mods)
    private val _sideloadZipUri = MutableStateFlow<Uri?>(null)
    val sideloadZipUri: StateFlow<Uri?> = _sideloadZipUri.asStateFlow()

    private val _sideloadZipName = MutableStateFlow("")
    val sideloadZipName: StateFlow<String> = _sideloadZipName.asStateFlow()

    private val _sideloadZipSize = MutableStateFlow(0L)
    val sideloadZipSize: StateFlow<Long> = _sideloadZipSize.asStateFlow()

    private val _sideloadProgress = MutableStateFlow(AdbSideloadProgress())
    val sideloadProgress: StateFlow<AdbSideloadProgress> = _sideloadProgress.asStateFlow()

    val isSideloading: StateFlow<Boolean> = _sideloadProgress
        .map {
            it.state == AdbSideloadState.SENDING ||
                it.state == AdbSideloadState.INSTALLING_ON_DEVICE
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    @Volatile private var isSideloadCancelled = false
    private var sideloadJob: Job? = null

    private val baseFileMap = mutableMapOf<String, Uri>()
    private val activeFileMap = mutableMapOf<String, Uri>()
    private var basePartitionImages: List<PartitionImageSource> = emptyList()
    private var baseScriptTasks: List<FlashTask>? = null

    init {
        usbManager.onDiagnosticLog = { msg -> flashExecutor.addLog(msg) }
        usbManager.start()
        flashExecutor.addLog("Leaf Flasher ready")
    }

    override fun onCleared() {
        super.onCleared()
        usbManager.stop()
    }

    fun setRomWizardStep(step: RomWizardStep) {
        _romWizardStep.value = step
    }

    fun setWipeDataEnabled(enabled: Boolean) {
        _wipeDataEnabled.value = enabled
        rebuildActiveTasks()
    }

    fun setDisableVbmetaVerity(disabled: Boolean) {
        _disableVbmetaVerity.value = disabled
        if (basePartitionImages.isNotEmpty() || baseScriptTasks != null) {
            rebuildActiveTasks()
        }
    }

    fun resetFlashStateToWizard() {
        flashExecutor.resetProgress()
        _romWizardStep.value = RomWizardStep.SUMMARY
    }

    private fun resetWizardForNewRom() {
        _romWizardStep.value = RomWizardStep.BOOT_SELECTION
        _selectedRomSlot.value = "a"
        _rebootAfterFlash.value = true
        _postFlashRebootTarget.value = "system"
        _wipeDataEnabled.value = false
        _disableVbmetaVerity.value = false
    }

    fun setPostFlashRebootOption(enabled: Boolean, target: String = _postFlashRebootTarget.value) {
        _rebootAfterFlash.value = enabled
        _postFlashRebootTarget.value =
            if (target.equals("bootloader", ignoreCase = true)) "bootloader" else "system"
        if (basePartitionImages.isNotEmpty() || baseScriptTasks != null) {
            rebuildActiveTasks()
        }
    }

    fun setSelectedRomSlot(slot: String) {
        _selectedRomSlot.value = slot
        if (basePartitionImages.isNotEmpty() || baseScriptTasks != null) {
            rebuildActiveTasks()
        }
    }

    fun getSuggestedPartitionTargets(): List<String> {
        val defaults = listOf(
            "super_empty", "lk", "boot", "init_boot", "vendor_boot", "dtbo",
            "vbmeta", "vbmeta_system", "vbmeta_vendor", "preloader", "logo",
            "modem", "tee", "scp", "sspm", "spmfw", "gz", "dpm", "mcupm",
            "mcf_ota", "apusys", "ccu", "gpueb", "vcp", "pi_img",
            "connsys_bt", "connsys_gnss", "connsys_wifi",
            "odm_dlkm", "odm", "vendor_dlkm", "product", "vendor",
            "system_dlkm", "system_ext", "system", "super", "recovery"
        )
        val loadedStems = mutableListOf<String>()
        for (img in basePartitionImages) {
            if (img.stem.isNotBlank()) loadedStems.add(img.stem)
        }
        for (task in _parsedTasks.value) {
            if (task.type == TaskType.FLASH && !FlashScriptParser.isWipeSuperTask(task)) {
                val stem = FlashScriptParser.normalizePartitionStem(task.partition)
                if (stem.isNotBlank()) loadedStems.add(stem)
            }
        }
        return (loadedStems + defaults).distinct()
    }

    fun addOrReplacePartitionFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val overrideDir = File(context.cacheDir, "custom_overrides").apply {
                    if (!exists()) mkdirs()
                }
                val current = _partitionOverrides.value.toMutableList()
                var addedCount = 0

                for (uri in uris) {
                    val (rawName, _) = resolveFileNameAndSize(uri)
                    val safeName = if (rawName.isNotBlank() && rawName != "unknown.img") rawName else "partition.img"
                    val targetStem = FlashScriptParser.normalizePartitionStem(safeName)
                    val id = UUID.randomUUID().toString()
                    val destFile = File(overrideDir, "${id.take(8)}_$safeName")

                    val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output, 256 * 1024)
                        }
                        true
                    } ?: false

                    if (!copied || !destFile.exists() || destFile.length() == 0L) {
                        destFile.delete()
                        flashExecutor.addLog("✗ Cannot read $safeName")
                        continue
                    }

                    // Remove any previous override targeting the same partition stem
                    val existing = current.filter { it.targetStem == targetStem }
                    existing.forEach { old ->
                        try { old.cachedFile.delete() } catch (_: Exception) {}
                    }
                    current.removeAll { it.targetStem == targetStem }

                    current.add(
                        PartitionFileOverride(
                            id = id,
                            targetStem = targetStem,
                            fileName = safeName,
                            fileSize = destFile.length(),
                            cachedFile = destFile,
                            uri = Uri.fromFile(destFile)
                        )
                    )
                    addedCount++
                }

                if (addedCount > 0) {
                    _partitionOverrides.value = current
                    syncOverridesToWorkspaceAndMap()
                    rebuildActiveTasks()
                }
            } catch (e: Exception) {
                flashExecutor.addLog("✗ File override error: ${e.message}")
            }
        }
    }

    fun remapPartitionOverride(overrideId: String, newTargetStem: String) {
        val cleanStem = FlashScriptParser.normalizePartitionStem(newTargetStem)
        if (cleanStem.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = _partitionOverrides.value.toMutableList()
            val targetIdx = current.indexOfFirst { it.id == overrideId }
            if (targetIdx < 0) return@launch

            val targetItem = current[targetIdx].copy(targetStem = cleanStem)
            // Remove any other entry already targeting cleanStem
            current.forEachIndexed { index, item ->
                if (index != targetIdx && item.targetStem == cleanStem) {
                    try { item.cachedFile.delete() } catch (_: Exception) {}
                }
            }
            current.removeAll { it.id != overrideId && it.targetStem == cleanStem }
            val updatedIdx = current.indexOfFirst { it.id == overrideId }
            if (updatedIdx >= 0) {
                current[updatedIdx] = targetItem
            }

            _partitionOverrides.value = current
            syncOverridesToWorkspaceAndMap()
            rebuildActiveTasks()
        }
    }

    fun removePartitionOverride(overrideId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _partitionOverrides.value.toMutableList()
            val removed = current.firstOrNull { it.id == overrideId }
            if (removed != null) {
                try { removed.cachedFile.delete() } catch (_: Exception) {}
                current.removeAll { it.id == overrideId }
                _partitionOverrides.value = current
                syncOverridesToWorkspaceAndMap()
                rebuildActiveTasks()
            }
        }
    }

    fun clearPartitionOverrides() {
        viewModelScope.launch(Dispatchers.IO) {
            _partitionOverrides.value.forEach {
                try { it.cachedFile.delete() } catch (_: Exception) {}
            }
            _partitionOverrides.value = emptyList()
            File(context.cacheDir, "custom_overrides").deleteRecursively()
            syncOverridesToWorkspaceAndMap()
            rebuildActiveTasks()
        }
    }

    private fun syncOverridesToWorkspaceAndMap() {
        val extractedDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "leaf_extracted_rom")
        val backupDir = File(context.cacheDir, "custom_overrides_backup")

        if (extractedDir.exists() && extractedDir.isDirectory && backupDir.exists()) {
            // Restore original files first before re-applying current overrides
            backupDir.listFiles()?.forEach { bak ->
                val dest = File(extractedDir, bak.name)
                try {
                    val stem = FlashScriptParser.normalizePartitionStem(bak.name)
                    extractedDir.listFiles()?.forEach { cur ->
                        if (cur.isFile && FlashScriptParser.normalizePartitionStem(cur.name) == stem) {
                            cur.delete()
                        }
                    }
                    bak.copyTo(dest, overwrite = true)
                } catch (_: Exception) {}
            }
            backupDir.deleteRecursively()
        }

        activeFileMap.clear()
        activeFileMap.putAll(baseFileMap)

        val overrides = _partitionOverrides.value
        for (ov in overrides) {
            val stem = ov.targetStem
            var effectiveUri = ov.uri

            if (extractedDir.exists() && extractedDir.isDirectory) {
                val existingMatches = extractedDir.listFiles()?.filter { f ->
                    f.isFile && FlashScriptParser.normalizePartitionStem(f.name) == stem
                }.orEmpty()

                if (existingMatches.isNotEmpty()) {
                    if (!backupDir.exists()) backupDir.mkdirs()
                    existingMatches.forEach { orig ->
                        val bakDest = File(backupDir, orig.name)
                        if (!bakDest.exists()) {
                            try { orig.copyTo(bakDest, overwrite = false) } catch (_: Exception) {}
                        }
                        orig.delete()
                    }
                }

                val ext = ov.fileName.substringAfterLast('.', "img").lowercase()
                    .takeIf { it in setOf("img", "bin", "raw") } ?: "img"
                val destName = if (stem == "super_empty") "super_empty.img" else "$stem.$ext"
                val destFile = File(extractedDir, destName)
                try {
                    ov.cachedFile.copyTo(destFile, overwrite = true)
                    effectiveUri = Uri.fromFile(destFile)
                } catch (_: Exception) {}
            }

            val keys = buildList {
                add(stem)
                add("$stem.img")
                add("$stem.bin")
                add("$stem.raw")
                add("${stem}_a")
                add("${stem}_b")
                add("${stem}_a.img")
                add("${stem}_b.img")
                add(ov.fileName)
                add(ov.fileName.lowercase())
                if (stem == "preloader") {
                    add("preloader_raw")
                    add("preloader_raw.img")
                }
                if (stem == "super_empty") {
                    add("super_empty.img")
                }
            }
            keys.forEach { k -> activeFileMap[k] = effectiveUri }
        }

        flashExecutor.setFileMap(activeFileMap)
    }

    private fun buildEffectiveImages(): Pair<List<PartitionImageSource>, Set<String>> {
        val overrides = _partitionOverrides.value
        val overrideStems = overrides.map { it.targetStem }.toSet()
        val filteredBase = basePartitionImages.filterNot { it.stem in overrideStems }
        val overrideImages = overrides.map { ov ->
            PartitionImageSource(
                name = ov.fileName,
                uri = ov.uri,
                size = ov.fileSize,
                stemOverride = ov.targetStem
            )
        }
        return (filteredBase + overrideImages) to overrideStems
    }

    private fun rebuildActiveTasks() {
        val (effectiveImages, overrideStems) = buildEffectiveImages()

        val scriptBase = baseScriptTasks
        if (scriptBase != null) {
            val optionsApplied = applyUserOptionsToScriptTasks(scriptBase)
            val finalTasks = FlashScriptParser.applyVbmetaVerityOption(
                tasks = optionsApplied,
                disableVerityAndVerification = _disableVbmetaVerity.value
            )
            _parsedTasks.value = finalTasks
            updateAnalysis(finalTasks)
            _hasVbmetaPartition.value = computeHasVbmetaPartition(effectiveImages, finalTasks)
            _extractedPartitions.value = RomZipExtractor.buildExtractedPartitionInventory(
                images = effectiveImages,
                plannedTasks = finalTasks,
                customOverrideStems = overrideStems
            )
            return
        }

        if (basePartitionImages.isNotEmpty()) {
            val hasSuperEmpty = effectiveImages.any { it.stem == "super_empty" }
            val generatedTasks = RomZipExtractor.generateSequenceFromImages(
                images = effectiveImages,
                targetSlot = _selectedRomSlot.value,
                wipeSuper = hasSuperEmpty,
                wipeData = _wipeDataEnabled.value,
                rebootAfterFlash = _rebootAfterFlash.value,
                rebootTarget = _postFlashRebootTarget.value
            )
            val finalTasks = FlashScriptParser.applyVbmetaVerityOption(
                tasks = generatedTasks,
                disableVerityAndVerification = _disableVbmetaVerity.value
            )
            _parsedTasks.value = finalTasks
            updateAnalysis(finalTasks)
            _hasVbmetaPartition.value = computeHasVbmetaPartition(effectiveImages, finalTasks)
            _extractedPartitions.value = RomZipExtractor.buildExtractedPartitionInventory(
                images = effectiveImages,
                plannedTasks = finalTasks,
                customOverrideStems = overrideStems
            )
            return
        }

        _parsedTasks.value = emptyList()
        _extractedPartitions.value = emptyList()
        _uniquePartitionCount.value = 0
        _hasVbmetaPartition.value = false
    }

    private fun computeHasVbmetaPartition(
        images: List<PartitionImageSource>,
        tasks: List<FlashTask>
    ): Boolean {
        return images.any { it.stem == "vbmeta" || it.stem.startsWith("vbmeta_") } ||
            tasks.any { t ->
                t.type == TaskType.FLASH &&
                    FlashScriptParser.normalizePartitionStem(t.partition.ifBlank { t.fileName }).let {
                        it == "vbmeta" || it.startsWith("vbmeta_")
                    }
            }
    }

    private fun applyUserOptionsToScriptTasks(baseTasks: List<FlashTask>): List<FlashTask> {
        val mutable = baseTasks.map {
            it.copy(
                status = TaskStatus.PENDING,
                errorMessage = null,
                durationMs = 0L
            )
        }.toMutableList()

        // 1. Strip any hardcoded trailing reboot so user's "Reboot After Flash" toggle governs reboot
        while (mutable.isNotEmpty()) {
            val last = mutable.last()
            val isTrailingReboot = when (last.type) {
                TaskType.REBOOT, TaskType.REBOOT_RECOVERY -> true
                TaskType.REBOOT_BOOTLOADER -> {
                    mutable.size > 1 && mutable.dropLast(1).any { prev ->
                        prev.type == TaskType.FLASH ||
                        prev.type == TaskType.ERASE ||
                        prev.type == TaskType.SET_ACTIVE ||
                        prev.type == TaskType.WIPE
                    }
                }
                else -> false
            }
            if (isTrailingReboot) {
                mutable.removeAt(mutable.lastIndex)
            } else {
                break
            }
        }

        // 2. Deduplicate A+B logical partition flashes in fastbootd: flash active slot only
        val activeLogicalSlot = if (_selectedRomSlot.value.equals("b", ignoreCase = true)) "b" else "a"
        val logicalSlotsByStem = mutableMapOf<String, MutableSet<String>>()
        for (t in mutable) {
            if (t.type == TaskType.FLASH &&
                !FlashScriptParser.isWipeSuperTask(t) &&
                (t.phase == FlashingPhase.LOGICAL_DYNAMIC || FlashExecutor.isLogicalPartitionTarget(t.partition))) {
                val pLow = t.partition.lowercase()
                val slotSuffix = when {
                    pLow.endsWith("_a") -> "a"
                    pLow.endsWith("_b") -> "b"
                    else -> null
                }
                if (slotSuffix != null) {
                    val stem = pLow.removeSuffix("_a").removeSuffix("_b")
                    logicalSlotsByStem.getOrPut(stem) { mutableSetOf() }.add(slotSuffix)
                }
            }
        }
        mutable.removeAll { t ->
            if (t.type != TaskType.FLASH || FlashScriptParser.isWipeSuperTask(t)) return@removeAll false
            val isLogical = t.phase == FlashingPhase.LOGICAL_DYNAMIC || FlashExecutor.isLogicalPartitionTarget(t.partition)
            if (!isLogical) return@removeAll false
            val pLow = t.partition.lowercase()
            val stem = pLow.removeSuffix("_a").removeSuffix("_b")
            val present = logicalSlotsByStem[stem]
            present != null && present.size > 1 && !pLow.endsWith("_$activeLogicalSlot")
        }

        // 3. Synchronize wipe-super: ONLY run when a device-specific super_empty exists in ROM or overrides
        val overrides = _partitionOverrides.value
        val superOverride = overrides.firstOrNull { it.targetStem == "super_empty" }
        val superFromRom = basePartitionImages.firstOrNull { it.stem == "super_empty" }
        val superFromMap = baseFileMap["super_empty.img"] ?: baseFileMap["super_empty"]
        val hasSuperEmptyAvailable = superOverride != null || superFromRom != null || superFromMap != null

        val hasMonolithicSuperFlash = mutable.any {
            it.type == TaskType.FLASH &&
            it.partition.equals("super", ignoreCase = true) &&
            !FlashScriptParser.isWipeSuperTask(it)
        }

        if (!hasSuperEmptyAvailable || hasMonolithicSuperFlash) {
            mutable.removeAll { FlashScriptParser.isWipeSuperTask(it) }
        } else {
            val effectiveFileName = superOverride?.fileName ?: superFromRom?.name ?: "super_empty.img"
            val effectiveUri = superOverride?.uri ?: superFromRom?.uri ?: superFromMap
            val effectiveSize = superOverride?.fileSize ?: superFromRom?.size ?: 0L
            var replacedSuperWipe = false
            for (i in mutable.indices) {
                if (FlashScriptParser.isWipeSuperTask(mutable[i])) {
                    replacedSuperWipe = true
                    mutable[i] = mutable[i].copy(
                        fileName = effectiveFileName,
                        fileUri = effectiveUri,
                        fileSize = effectiveSize,
                        isFilePresent = true
                    )
                }
            }
            if (!replacedSuperWipe && superOverride != null) {
                val rebootFastbootIdx = mutable.indexOfFirst { it.type == TaskType.REBOOT_FASTBOOT }
                val firstLogicalIdx = mutable.indexOfFirst {
                    it.type == TaskType.FLASH && FlashExecutor.isLogicalPartitionTarget(it.partition)
                }
                val insertIdx = when {
                    rebootFastbootIdx >= 0 -> rebootFastbootIdx + 1
                    firstLogicalIdx >= 0 -> firstLogicalIdx
                    else -> mutable.size
                }
                mutable.add(
                    insertIdx,
                    FlashTask(
                        id = 0,
                        type = TaskType.FLASH,
                        rawCommandLine = "fastboot wipe-super $effectiveFileName",
                        partition = "super",
                        fileName = effectiveFileName,
                        fileUri = effectiveUri,
                        fileSize = effectiveSize,
                        phase = FlashingPhase.LOGICAL_DYNAMIC,
                        isFilePresent = true
                    )
                )
            }
        }

        // 4. Apply user-added / replaced partition files
        val slotsToFlash = when (_selectedRomSlot.value.lowercase()) {
            "both" -> listOf("a", "b")
            "b" -> listOf("b")
            else -> listOf("a")
        }
        for (ov in overrides) {
            if (ov.targetStem == "super_empty") continue
            val stem = ov.targetStem
            var replacedExisting = false
            for (i in mutable.indices) {
                val t = mutable[i]
                if (t.type == TaskType.FLASH && !FlashScriptParser.isWipeSuperTask(t)) {
                    val taskStem = FlashScriptParser.normalizePartitionStem(t.partition)
                    if (taskStem == stem) {
                        replacedExisting = true
                        mutable[i] = t.copy(
                            fileName = ov.fileName,
                            fileUri = ov.uri,
                            fileSize = ov.fileSize,
                            isFilePresent = true
                        )
                    }
                }
            }
            if (!replacedExisting && mutable.any { it.type == TaskType.FLASH }) {
                val isLogical = FlashExecutor.isLogicalPartitionTarget(stem) || stem == "super"
                if (stem == "super") {
                    val insertPos = mutable.indexOfFirst { it.type == TaskType.REBOOT_FASTBOOT }
                        .let { if (it >= 0) it + 1 else mutable.size }
                    mutable.add(
                        insertPos,
                        FlashTask(
                            id = 0,
                            type = TaskType.FLASH,
                            rawCommandLine = "fastboot flash super ${ov.fileName}",
                            partition = "super",
                            fileName = ov.fileName,
                            fileUri = ov.uri,
                            fileSize = ov.fileSize,
                            phase = FlashingPhase.LOGICAL_DYNAMIC,
                            isFilePresent = true
                        )
                    )
                } else if (isLogical) {
                    val targetPart = "${stem}_$activeLogicalSlot"
                    val insertPos = mutable.indexOfFirst { it.type == TaskType.SET_ACTIVE && it.phase == FlashingPhase.FINALIZATION }
                        .let { if (it >= 0) it else mutable.size }
                    mutable.add(
                        insertPos,
                        FlashTask(
                            id = 0,
                            type = TaskType.FLASH,
                            rawCommandLine = "fastboot flash $targetPart ${ov.fileName}",
                            partition = targetPart,
                            fileName = ov.fileName,
                            fileUri = ov.uri,
                            fileSize = ov.fileSize,
                            phase = FlashingPhase.LOGICAL_DYNAMIC,
                            isFilePresent = true
                        )
                    )
                } else {
                    val targetSlots = if (stem == "preloader") listOf("a") else slotsToFlash
                    val insertPos = mutable.indexOfFirst { it.type == TaskType.REBOOT_FASTBOOT }
                        .let { if (it >= 0) it else mutable.indexOfFirst { t -> t.type == TaskType.FLASH && t.phase != FlashingPhase.FIRMWARE_BOOTLOADER } }
                        .let { if (it >= 0) it else mutable.size }
                    val newTasks = targetSlots.map { sl ->
                        FlashTask(
                            id = 0,
                            type = TaskType.FLASH,
                            rawCommandLine = "fastboot flash ${stem}_$sl ${ov.fileName}",
                            partition = "${stem}_$sl",
                            fileName = ov.fileName,
                            fileUri = ov.uri,
                            fileSize = ov.fileSize,
                            phase = FlashingPhase.FIRMWARE_BOOTLOADER,
                            isFilePresent = true
                        )
                    }
                    mutable.addAll(insertPos, newTasks)
                }
            }
        }

        // 5. Normalize Wipe Data tasks according to user's Wipe Data checkbox
        mutable.removeAll { task ->
            task.type == TaskType.WIPE ||
            (task.type == TaskType.ERASE && task.partition.lowercase() in setOf("userdata", "metadata", "cache"))
        }
        if (_wipeDataEnabled.value) {
            mutable.add(
                FlashTask(
                    id = 0,
                    type = TaskType.WIPE,
                    rawCommandLine = "fastboot -w",
                    phase = FlashingPhase.FINALIZATION
                )
            )
        }

        // 6. Append Post-Flash Reboot ONLY if user toggled Reboot After Flash ON
        if (_rebootAfterFlash.value) {
            val isBootloaderTarget = _postFlashRebootTarget.value.equals("bootloader", ignoreCase = true)
            mutable.add(
                FlashTask(
                    id = 0,
                    type = if (isBootloaderTarget) TaskType.REBOOT_BOOTLOADER else TaskType.REBOOT,
                    rawCommandLine = if (isBootloaderTarget) "fastboot reboot bootloader" else "fastboot reboot",
                    phase = FlashingPhase.FINALIZATION
                )
            )
        }

        return mutable.mapIndexed { idx, t -> t.copy(id = idx + 1) }
    }

    fun clearLoadedRom() {
        _loadedScriptName.value = ""
        _loadedSourceKind.value = ""
        _parsedTasks.value = emptyList()
        _extractedPartitions.value = emptyList()
        _uniquePartitionCount.value = 0
        _zipProgress.value = ZipExtractionProgress()
        _hasVbmetaPartition.value = false
        resetWizardForNewRom()
        basePartitionImages = emptyList()
        baseScriptTasks = null
        baseFileMap.clear()
        File(context.cacheDir, "custom_overrides_backup").deleteRecursively()
        syncOverridesToWorkspaceAndMap()
        flashExecutor.resetProgress()
    }

    fun handleUsbDeviceAttached(device: android.hardware.usb.UsbDevice) {
        usbManager.onDeviceAttached(device)
    }

    fun toggleLogExpanded() {
        _isLogExpanded.value = !_isLogExpanded.value
    }

    fun openOnboardingTutor() {
        _showOnboardingTutor.value = true
    }

    fun completeOnboardingTutor() {
        uiPrefs.edit().putBoolean("tutor_seen_v1", true).apply()
        _showOnboardingTutor.value = false
    }

    fun attachHostActivity(activity: Activity) {
        usbManager.attachActivity(activity)
    }

    fun scanDevices(force: Boolean = false) {
        usbManager.scanForConnectedDevices(userTriggered = force)
    }

    fun extractAndFlashZip(zipUri: Uri) {
        _isLogExpanded.value = true
        _zipProgress.value = ZipExtractionProgress(
            isExtracting = true,
            statusMessage = "Reading ROM package..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            flashExecutor.resetProgress()
            File(context.cacheDir, "custom_overrides_backup").deleteRecursively()
            val fileName = getFileNameFromUri(zipUri)

            val result = RomZipExtractor.extractRomZip(
                context = context,
                zipUri = zipUri,
                onLog = { line -> flashExecutor.addLog(line) }
            ) { progress ->
                _zipProgress.value = progress
            }

            if (result.success) {
                baseFileMap.clear()
                baseFileMap.putAll(result.fileMap)

                basePartitionImages = result.extractedImages.map {
                    PartitionImageSource(it.name, Uri.fromFile(it), it.length())
                }

                resetWizardForNewRom()
                syncOverridesToWorkspaceAndMap()

                if (result.detectedScriptFile != null) {
                    _loadedSourceKind.value = "zip"
                    _loadedScriptName.value = "${result.detectedScriptFile.name} (from $fileName)"
                    val tasks = FileInputStream(result.detectedScriptFile).use { scriptStream ->
                        FlashScriptParser.parseStream(scriptStream)
                    }
                    baseScriptTasks = tasks
                    rebuildActiveTasks()
                } else {
                    _loadedSourceKind.value = "zip"
                    _loadedScriptName.value = "Direct ZIP: $fileName"
                    baseScriptTasks = null
                    rebuildActiveTasks()
                }

                _zipProgress.value = ZipExtractionProgress()
                flashExecutor.addLog("✓ Loaded $fileName (${_uniquePartitionCount.value} partitions)")
            } else {
                _zipProgress.value = ZipExtractionProgress()
                flashExecutor.addLog("✗ Extraction failed: ${result.errorMessage}")
            }
        }
    }

    fun loadRomZipFile(file: File) {
        if (!file.exists() || !file.isFile) {
            flashExecutor.addLog("✗ Invalid ZIP file selected")
            return
        }
        extractAndFlashZip(Uri.fromFile(file))
    }

    fun loadRomFolder(uri: Uri) {
        flashExecutor.resetProgress()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val folder = DocumentFile.fromTreeUri(context, uri)
                if (folder == null || !folder.isDirectory) {
                    flashExecutor.addLog("✗ Invalid ROM folder selected")
                    return@launch
                }

                val folderName = folder.name ?: "Extracted ROM"
                val fileMap = mutableMapOf<String, Uri>()
                val imageSources = mutableListOf<PartitionImageSource>()
                var detectedScript: DocumentFile? = null

                fun scanDir(dir: DocumentFile) {
                    for (file in dir.listFiles()) {
                        if (file.isDirectory) {
                            scanDir(file)
                        } else {
                            val name = file.name ?: continue
                            val fileUri = file.uri
                            val noExt = if (name.contains('.')) name.substringBeforeLast('.') else name
                            val clean = noExt.lowercase().removeSuffix("_a").removeSuffix("_b")

                            fileMap[name] = fileUri
                            fileMap[name.lowercase()] = fileUri
                            fileMap[noExt] = fileUri
                            fileMap[noExt.lowercase()] = fileUri
                            fileMap[clean] = fileUri
                            fileMap["${clean}.img"] = fileUri
                            fileMap["${clean}.bin"] = fileUri
                            fileMap["${clean}_a"] = fileUri
                            fileMap["${clean}_b"] = fileUri
                            fileMap["${clean}_a.img"] = fileUri
                            fileMap["${clean}_b.img"] = fileUri

                            if (name.endsWith(".img", ignoreCase = true) ||
                                name.endsWith(".bin", ignoreCase = true) ||
                                name.endsWith(".raw", ignoreCase = true)) {
                                imageSources.add(
                                    PartitionImageSource(
                                        name = name,
                                        uri = fileUri,
                                        size = file.length()
                                    )
                                )
                            }

                            if (name.endsWith(".bat", ignoreCase = true) || name.endsWith(".sh", ignoreCase = true)) {
                                if (detectedScript == null || name.contains("flash", ignoreCase = true)) {
                                    detectedScript = file
                                }
                            }
                        }
                    }
                }

                scanDir(folder)

                baseFileMap.clear()
                baseFileMap.putAll(fileMap)
                basePartitionImages = imageSources

                resetWizardForNewRom()
                syncOverridesToWorkspaceAndMap()

                if (detectedScript != null) {
                    _loadedSourceKind.value = "folder"
                    _loadedScriptName.value = "${detectedScript!!.name} (from $folderName)"
                    context.contentResolver.openInputStream(detectedScript!!.uri)?.use { stream ->
                        val tasks = FlashScriptParser.parseStream(stream)
                        baseScriptTasks = tasks
                        rebuildActiveTasks()
                    }
                } else {
                    _loadedSourceKind.value = "folder"
                    _loadedScriptName.value = "Folder: $folderName"
                    baseScriptTasks = null
                    rebuildActiveTasks()
                }

                flashExecutor.addLog("✓ Loaded $folderName (${_uniquePartitionCount.value} partitions)")
            } catch (e: Exception) {
                flashExecutor.addLog("✗ Error indexing folder: ${e.message}")
            }
        }
    }

    fun loadRomFromDirectory(dir: File) {
        flashExecutor.resetProgress()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!dir.exists() || !dir.isDirectory) {
                    flashExecutor.addLog("✗ Invalid ROM folder path")
                    return@launch
                }

                val folderName = dir.name
                val fileMap = mutableMapOf<String, Uri>()
                val imageSources = mutableListOf<PartitionImageSource>()
                var detectedScript: File? = null

                fun scanDir(current: File) {
                    val files = current.listFiles() ?: return
                    for (file in files) {
                        if (file.isDirectory) {
                            scanDir(file)
                        } else {
                            val name = file.name
                            val fileUri = Uri.fromFile(file)
                            val noExt = if (name.contains('.')) name.substringBeforeLast('.') else name
                            val clean = noExt.lowercase().removeSuffix("_a").removeSuffix("_b")

                            fileMap[name] = fileUri
                            fileMap[name.lowercase()] = fileUri
                            fileMap[noExt] = fileUri
                            fileMap[noExt.lowercase()] = fileUri
                            fileMap[clean] = fileUri
                            fileMap["${clean}.img"] = fileUri
                            fileMap["${clean}.bin"] = fileUri
                            fileMap["${clean}_a"] = fileUri
                            fileMap["${clean}_b"] = fileUri
                            fileMap["${clean}_a.img"] = fileUri
                            fileMap["${clean}_b.img"] = fileUri

                            if (name.endsWith(".img", ignoreCase = true) ||
                                name.endsWith(".bin", ignoreCase = true) ||
                                name.endsWith(".raw", ignoreCase = true)) {
                                imageSources.add(
                                    PartitionImageSource(
                                        name = name,
                                        uri = fileUri,
                                        size = file.length()
                                    )
                                )
                            }

                            if (name.endsWith(".bat", ignoreCase = true) || name.endsWith(".sh", ignoreCase = true)) {
                                if (detectedScript == null || name.contains("flash", ignoreCase = true)) {
                                    detectedScript = file
                                }
                            }
                        }
                    }
                }

                scanDir(dir)

                baseFileMap.clear()
                baseFileMap.putAll(fileMap)
                basePartitionImages = imageSources

                resetWizardForNewRom()
                syncOverridesToWorkspaceAndMap()

                if (detectedScript != null) {
                    _loadedSourceKind.value = "folder"
                    _loadedScriptName.value = "${detectedScript!!.name} (from $folderName)"
                    val tasks = FileInputStream(detectedScript!!).use { stream ->
                        FlashScriptParser.parseStream(stream)
                    }
                    baseScriptTasks = tasks
                    rebuildActiveTasks()
                } else {
                    _loadedSourceKind.value = "folder"
                    _loadedScriptName.value = "Folder: $folderName"
                    baseScriptTasks = null
                    rebuildActiveTasks()
                }

                flashExecutor.addLog("✓ Loaded $folderName (${_uniquePartitionCount.value} partitions)")
            } catch (e: Exception) {
                flashExecutor.addLog("✗ Error reading folder: ${e.message}")
            }
        }
    }

    fun loadRomFromUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                flashExecutor.resetProgress()
                val fileMap = mutableMapOf<String, Uri>()
                val imageSources = mutableListOf<PartitionImageSource>()
                var detectedScriptUri: Uri? = null
                var detectedScriptName: String? = null

                for (uri in uris) {
                    val (name, size) = resolveFileNameAndSize(uri)
                    val noExt = if (name.contains('.')) name.substringBeforeLast('.') else name
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

                    if (name.endsWith(".img", ignoreCase = true) ||
                        name.endsWith(".bin", ignoreCase = true) ||
                        name.endsWith(".raw", ignoreCase = true)) {
                        imageSources.add(
                            PartitionImageSource(
                                name = name,
                                uri = uri,
                                size = size
                            )
                        )
                    }

                    if (name.endsWith(".bat", ignoreCase = true) || name.endsWith(".sh", ignoreCase = true)) {
                        if (detectedScriptUri == null || name.contains("flash", ignoreCase = true)) {
                            detectedScriptUri = uri
                            detectedScriptName = name
                        }
                    }
                }

                baseFileMap.clear()
                baseFileMap.putAll(fileMap)
                basePartitionImages = imageSources

                resetWizardForNewRom()
                syncOverridesToWorkspaceAndMap()

                if (detectedScriptUri != null) {
                    context.contentResolver.openInputStream(detectedScriptUri)?.use { stream ->
                        val tasks = FlashScriptParser.parseStream(stream)
                        baseScriptTasks = tasks
                        rebuildActiveTasks()
                    }
                    _loadedSourceKind.value = "folder"
                    _loadedScriptName.value = "$detectedScriptName (${_uniquePartitionCount.value} partitions)"
                } else {
                    baseScriptTasks = null
                    rebuildActiveTasks()
                    _loadedSourceKind.value = "folder"
                    _loadedScriptName.value = "Selected (${_uniquePartitionCount.value} partitions)"
                }

                flashExecutor.addLog("✓ Loaded (${_uniquePartitionCount.value} partitions)")
            } catch (e: Exception) {
                flashExecutor.addLog("✗ Error loading files: ${e.message}")
            }
        }
    }

    private fun updateAnalysis(tasks: List<FlashTask>) {
        if (tasks.isEmpty()) {
            _uniquePartitionCount.value = 0
            return
        }
        val analysis = FlashScriptParser.analyzeScript(tasks, activeFileMap)
        _uniquePartitionCount.value = analysis.uniquePartitionsCount
    }

    private fun requireFastbootDevice(actionLabel: String): FastbootDevice? {
        val dev = usbManager.deviceFlow.value
        val mode = usbManager.modeFlow.value
        if (dev == null || !dev.isConnected || !mode.isBootloaderOrFastbootd) {
            flashExecutor.addLog("✗ $actionLabel blocked: Bootloader or Fastbootd not detected")
            return null
        }
        return dev
    }

    fun startFlashingScript() {
        requireFastbootDevice("Flash") ?: return
        _isLogExpanded.value = true
        val wipeLabel = if (_wipeDataEnabled.value) "Clean Flash (Wipe Data)" else "Dirty Flash (Keep Data)"
        val avbLabel = if (_disableVbmetaVerity.value) "Disabled (Port ROM)" else "Default (Verified)"
        flashExecutor.addLog("Plan: $wipeLabel • Slot ${_selectedRomSlot.value.uppercase()} • dm-verity: $avbLabel")
        val tasks = _parsedTasks.value
        flashExecutor.executeTasks(tasks, viewModelScope) { success ->
            if (success) {
                RomZipExtractor.cleanupExtractedDirectory(context)
                File(context.cacheDir, "custom_overrides_backup").deleteRecursively()
            }
        }
    }

    fun pauseExecution() = flashExecutor.pause()
    fun resumeExecution() = flashExecutor.resume()
    fun cancelExecution() = flashExecutor.cancel()
    fun clearLogs() = flashExecutor.clearLogs()

    // Partition page handlers (zero keyboard required)
    fun setSingleSelectedFile(uri: Uri) {
        _singleSelectedFileUri.value = uri
        val (name, size) = resolveFileNameAndSize(uri)
        _singleSelectedFileName.value = name
        _singleSelectedFileSize.value = size
        val detectedStem = FlashScriptParser.normalizePartitionStem(name)
        _singlePartitionName.value = detectedStem
        if (!detectedStem.startsWith("vbmeta")) {
            _singleDisableVerity.value = false
        }
    }

    fun setSinglePartitionTarget(stem: String) {
        val clean = FlashScriptParser.normalizePartitionStem(stem)
        _singlePartitionName.value = clean
        if (!clean.startsWith("vbmeta")) {
            _singleDisableVerity.value = false
        }
    }

    fun setSingleDisableVerity(enabled: Boolean) {
        _singleDisableVerity.value = enabled
    }

    fun inspectZipFileForSingleExtract(file: File) {
        if (!file.exists() || !file.isFile) {
            flashExecutor.addLog("✗ Invalid ZIP file selected")
            return
        }
        inspectZipForSingleExtract(Uri.fromFile(file))
    }

    fun inspectZipForSingleExtract(zipUri: Uri) {
        if (isSelectiveExtracting.value) return
        inspectZipJob?.cancel()
        inspectedZipUri = zipUri
        _showZipPartitionExtractDialog.value = true
        _isInspectingZipPartitions.value = true
        _zipInspectionError.value = null
        _inspectedZipPartitions.value = emptyList()

        val initialName = getFileNameFromUri(zipUri).takeIf { it.isNotBlank() && it != "unknown.img" }
            ?: File(zipUri.path ?: "rom_package.zip").name.ifBlank { "rom_package.zip" }
        _inspectedZipName.value = initialName

        inspectZipJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val (resolvedName, _) = resolveFileNameAndSize(zipUri)
                val archiveName = resolvedName.takeIf { it.isNotBlank() && it != "unknown.img" } ?: initialName
                _inspectedZipName.value = archiveName
                if (_selectiveOutputTreeUri.value == null) {
                    val folderSuffix = RomZipExtractor.sanitizeArchiveFolderName(archiveName)
                    _selectiveOutputFolderLabel.value = "Downloads/LeafFlasher/$folderSuffix"
                }

                val inspection = RomZipExtractor.inspectRomZipPartitions(
                    context = context,
                    zipUri = zipUri,
                    archiveDisplayName = archiveName
                )
                _isInspectingZipPartitions.value = false
                if (inspection.success && inspection.partitions.isNotEmpty()) {
                    _inspectedZipPartitions.value = inspection.partitions
                    _zipInspectionError.value = null
                    flashExecutor.addLog("✓ Indexed ${inspection.partitions.size} partitions in $archiveName")
                } else {
                    _inspectedZipPartitions.value = emptyList()
                    val err = inspection.errorMessage ?: "No extractable partition images found"
                    _zipInspectionError.value = err
                    flashExecutor.addLog("✗ Cannot read $archiveName: $err")
                }
            } catch (e: Exception) {
                _isInspectingZipPartitions.value = false
                val err = e.message ?: "Failed to inspect ZIP archive"
                _zipInspectionError.value = err
                flashExecutor.addLog("✗ Cannot read ZIP: $err")
            }
        }
    }

    fun dismissZipPartitionExtractDialog() {
        inspectZipJob?.cancel()
        _isInspectingZipPartitions.value = false
        _showZipPartitionExtractDialog.value = false
    }

    fun setSelectiveOutputTreeUri(treeUri: Uri?) {
        if (treeUri == null) {
            _selectiveOutputTreeUri.value = null
            val archiveName = _inspectedZipName.value
            _selectiveOutputFolderLabel.value = if (archiveName.isNotBlank()) {
                "Downloads/LeafFlasher/${RomZipExtractor.sanitizeArchiveFolderName(archiveName)}"
            } else {
                "Downloads/LeafFlasher"
            }
            return
        }
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: Exception) {}
        val docName = DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() } ?: "Selected Folder"
        _selectiveOutputTreeUri.value = treeUri
        _selectiveOutputFolderLabel.value = docName
    }

    fun startSelectiveZipExtraction(selected: List<ZipPartitionEntry>) {
        if (selected.isEmpty() || isSelectiveExtracting.value) return
        val zipUri = inspectedZipUri ?: return
        val archiveName = _inspectedZipName.value.ifBlank { "rom_package.zip" }
        val outputTree = _selectiveOutputTreeUri.value

        _showZipPartitionExtractDialog.value = false
        _lastSelectiveExtractResult.value = null
        _isLogExpanded.value = true
        isSelectiveCancelled = false
        _selectiveExtractProgress.value = ZipExtractionProgress(
            isExtracting = true,
            totalFiles = selected.size,
            statusMessage = "Preparing ${selected.size} partition(s)...",
            percent = 1
        )

        selectiveExtractJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                flashExecutor.addLog("Extracting ${selected.size} image(s) from $archiveName...")
                val result = RomZipExtractor.extractSelectedPartitionsFromZip(
                    context = context,
                    zipUri = zipUri,
                    archiveDisplayName = archiveName,
                    selectedEntries = selected,
                    outputTreeUri = outputTree,
                    isCancelled = { isSelectiveCancelled },
                    onLog = { line -> flashExecutor.addLog(line) },
                    onProgress = { progress -> _selectiveExtractProgress.value = progress }
                )
                _selectiveExtractProgress.value = ZipExtractionProgress()
                when {
                    result.cancelled -> {
                        flashExecutor.addLog("✗ Extraction cancelled")
                    }
                    result.success -> {
                        _lastSelectiveExtractResult.value = result
                        flashExecutor.addLog("✓ Saved ${result.savedFiles.size} image(s) to ${result.destinationDisplayPath}")
                    }
                    else -> {
                        flashExecutor.addLog("✗ Extraction failed: ${result.errorMessage}")
                    }
                }
            } catch (e: Exception) {
                _selectiveExtractProgress.value = ZipExtractionProgress()
                flashExecutor.addLog("✗ Extraction error: ${e.message}")
            }
        }
    }

    fun cancelSelectiveZipExtraction() {
        isSelectiveCancelled = true
        _selectiveExtractProgress.value = _selectiveExtractProgress.value.copy(
            statusMessage = "Cancelling extraction..."
        )
        flashExecutor.addLog("Cancelling image extraction...")
    }

    fun clearLastSelectiveExtractResult() {
        _lastSelectiveExtractResult.value = null
    }

    fun loadExtractedImageForSingleFlash(saved: SavedExtractedFile) {
        _singleSelectedFileUri.value = saved.uri
        _singleSelectedFileName.value = saved.fileName
        _singleSelectedFileSize.value = saved.sizeBytes
        val detectedStem = FlashScriptParser.normalizePartitionStem(saved.fileName)
        _singlePartitionName.value = detectedStem
        if (!detectedStem.startsWith("vbmeta")) {
            _singleDisableVerity.value = false
        }
        flashExecutor.addLog("✓ Loaded ${saved.fileName} -> $detectedStem for flashing")
    }

    fun flashSinglePartition() {
        if (_isSinglePartitionFlashing.value) return
        val fileUri = _singleSelectedFileUri.value ?: return
        val fileName = _singleSelectedFileName.value
        val rawPartition = _singlePartitionName.value.ifBlank {
            FlashScriptParser.normalizePartitionStem(fileName)
        }
        val partitionStem = FlashScriptParser.normalizePartitionStem(rawPartition)
        val disableVerity = _singleDisableVerity.value && partitionStem.startsWith("vbmeta")

        viewModelScope.launch(Dispatchers.IO) {
            var activeDevice = requireFastbootDevice("Flash") ?: return@launch

            _isLogExpanded.value = true
            _isSinglePartitionFlashing.value = true
            _singlePartitionProgressPercent.value = 0
            _singlePartitionSpeedMb.value = 0.0

            var succeeded = true
            try {
                val pfdSize = context.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                    pfd.statSize
                } ?: -1L
                val fileSize = if (pfdSize > 0L) {
                    pfdSize
                } else {
                    resolveFileNameAndSize(fileUri).second.takeIf { it > 0L }
                        ?: _singleSelectedFileSize.value.coerceAtLeast(0L)
                }

                val isSuperWipe = partitionStem == "super_empty" ||
                    fileName.contains("super_empty", ignoreCase = true)

                if (isSuperWipe) {
                    _singlePartitionStatusText.value = "Wiping super partition..."
                    flashExecutor.addLog("Wiping super partition...")
                    activeDevice.sendCommand("snapshot-update:cancel")

                    val stream = context.contentResolver.openInputStream(fileUri)
                    if (stream != null) {
                        val dlResp = stream.use { s ->
                            activeDevice.downloadData(
                                stream = s,
                                totalBytes = fileSize,
                                progressCallback = { sent, total ->
                                    val pct = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                                    _singlePartitionProgressPercent.value = pct
                                }
                            )
                        }
                        if (dlResp.isSuccess) {
                            var wipeResp = activeDevice.sendCommand("update-super:super:wipe")
                            if (!wipeResp.isSuccess && !wipeResp.payload.contains("lock", ignoreCase = true)) {
                                wipeResp = activeDevice.sendCommand("update-super:super")
                            }
                            if (!wipeResp.isSuccess && !wipeResp.payload.contains("lock", ignoreCase = true)) {
                                wipeResp = activeDevice.sendCommand("flash:super")
                            }
                            if (wipeResp.isSuccess) {
                                flashExecutor.addLog("✓ Super partition wiped")
                            } else if (wipeResp.payload.contains("lock", ignoreCase = true) ||
                                wipeResp.payload.contains("not allowed", ignoreCase = true)
                            ) {
                                succeeded = false
                                flashExecutor.addLog("✗ Super wipe blocked (${wipeResp.payload})")
                            } else {
                                flashExecutor.addLog("Note: wipe-super (${wipeResp.payload})")
                            }
                        } else {
                            succeeded = false
                            flashExecutor.addLog("✗ Wipe super failed: ${dlResp.payload}")
                        }
                    } else {
                        succeeded = false
                        flashExecutor.addLog("✗ Cannot open $fileName")
                    }
                    return@launch
                }

                // Switch Bootloader -> Fastbootd automatically if flashing a logical dynamic partition
                val isLogical = FlashExecutor.isLogicalPartitionTarget(partitionStem)
                if (isLogical && usbManager.modeFlow.value == FastbootMode.BOOTLOADER) {
                    _singlePartitionStatusText.value = "Rebooting to Fastbootd..."
                    flashExecutor.addLog("Rebooting to Fastbootd...")
                    activeDevice.reboot("fastboot")
                    val fdDev = usbManager.waitForReconnection(expectedMode = FastbootMode.FASTBOOTD)
                    if (fdDev == null) {
                        succeeded = false
                        flashExecutor.addLog("✗ Fastbootd reconnect timed out")
                        return@launch
                    }
                    activeDevice = fdDev
                    flashExecutor.addLog("✓ Connected to Fastbootd")
                }

                // Resolve current active slot target automatically
                val currentSlot = usbManager.deviceInfoFlow.value?.currentSlot
                    ?.takeIf { it.equals("a", true) || it.equals("b", true) }
                    ?.lowercase()
                    ?: activeDevice.getVariable("current-slot")
                        ?.takeIf { it.equals("a", true) || it.equals("b", true) }
                        ?.lowercase()

                val targetPart: String
                var fallbackPart: String? = null
                if (partitionStem == "super" || rawPartition.endsWith("_a", true) || rawPartition.endsWith("_b", true)) {
                    targetPart = rawPartition.lowercase()
                } else {
                    val hasSlotResp = activeDevice.getVariable("has-slot:$partitionStem")?.lowercase()
                    when {
                        hasSlotResp == "yes" -> {
                            val slot = currentSlot ?: "a"
                            targetPart = "${partitionStem}_$slot"
                        }
                        hasSlotResp == "no" -> {
                            targetPart = partitionStem
                        }
                        currentSlot != null -> {
                            targetPart = "${partitionStem}_$currentSlot"
                            fallbackPart = partitionStem
                        }
                        else -> {
                            targetPart = partitionStem
                            fallbackPart = "${partitionStem}_a"
                        }
                    }
                }

                var activeTarget = targetPart
                _singlePartitionStatusText.value = "Flashing $activeTarget..."
                flashExecutor.addLog("Flashing $activeTarget...")

                suspend fun prepareAndFlash(partName: String): FastbootResponse {
                    val logicalTarget = FlashExecutor.isLogicalPartitionTarget(partName)
                    var targetPartSize = fileSize
                    if (logicalTarget) {
                        activeDevice.sendCommand("snapshot-update:cancel")
                        val headerBytes = ByteArray(28)
                        context.contentResolver.openInputStream(fileUri)?.use { s ->
                            var offset = 0
                            while (offset < 28) {
                                val r = s.read(headerBytes, offset, 28 - offset)
                                if (r <= 0) break
                                offset += r
                            }
                        }
                        targetPartSize = SparseChunkHelper.computeTargetPartitionSize(headerBytes, fileSize)

                        val resizeResp = activeDevice.sendCommand("resize-logical-partition:$partName:$targetPartSize")
                        if (!resizeResp.isSuccess) {
                            val payload = resizeResp.payload.lowercase()
                            if (payload.contains("lock") || payload.contains("not allowed")) {
                                return resizeResp
                            } else if (payload.contains("not found") ||
                                payload.contains("does not exist") ||
                                payload.contains("no such") ||
                                payload.contains("unknown partition") ||
                                payload.contains("failed to find")
                            ) {
                                val createResp = activeDevice.sendCommand("create-logical-partition:$partName:$targetPartSize")
                                if (!createResp.isSuccess &&
                                    (createResp.payload.contains("lock", true) || createResp.payload.contains("not allowed", true))
                                ) {
                                    return createResp
                                }
                            } else if (payload.contains("no space") || payload.contains("not enough space")) {
                                val oppSlot = if (partName.endsWith("_a")) "b" else "a"
                                for (stem in listOf("product", "system_ext", "system", "vendor", "vendor_dlkm", "odm_dlkm", "odm")) {
                                    activeDevice.sendCommand("delete-logical-partition:${stem}_$oppSlot")
                                }
                                activeDevice.sendCommand("resize-logical-partition:$partName:$targetPartSize")
                            }
                        }
                    }

                    var lastBytes = 0L
                    var lastTime = System.currentTimeMillis()
                    var lastSingleUiMs = 0L
                    val singleInfos = mutableListOf<String>()

                    val baseStreamLambda: () -> java.io.InputStream? = {
                        context.contentResolver.openInputStream(fileUri)
                    }
                    val openStreamLambda = if (disableVerity) {
                        FlashScriptParser.wrapStreamWithAvbPatch(
                            openStream = baseStreamLambda,
                            disableVerity = true,
                            disableVerification = true
                        )
                    } else {
                        baseStreamLambda
                    }

                    var resp = activeDevice.flashPartitionChunked(
                        partition = partName,
                        totalBytes = fileSize,
                        openStream = openStreamLambda,
                        progressCallback = { sent, total ->
                            val now = System.currentTimeMillis()
                            val timeDelta = (now - lastTime) / 1000.0
                            if (timeDelta >= 0.4) {
                                val bytesDelta = sent - lastBytes
                                if (bytesDelta >= 0) {
                                    _singlePartitionSpeedMb.value = (bytesDelta / (1024.0 * 1024.0)) / timeDelta
                                }
                                lastBytes = sent
                                lastTime = now
                            }
                            val pct = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                            if (pct >= 100 || sent >= total || now - lastSingleUiMs >= 65L) {
                                lastSingleUiMs = now
                                _singlePartitionProgressPercent.value = pct
                            }
                        },
                        infoCallback = { info ->
                            if (info.isNotBlank()) singleInfos.add(info.trim())
                        }
                    )

                    val noSpaceFailure = !resp.isSuccess && logicalTarget && (
                        resp.payload.contains("no space", ignoreCase = true) ||
                        resp.payload.contains("not enough space", ignoreCase = true)
                    )
                    if (noSpaceFailure) {
                        val oppSlot = if (partName.endsWith("_a")) "b" else "a"
                        for (stem in listOf("product", "system_ext", "system", "vendor", "vendor_dlkm", "odm_dlkm", "odm")) {
                            activeDevice.sendCommand("delete-logical-partition:${stem}_$oppSlot")
                        }
                        activeDevice.sendCommand("resize-logical-partition:$partName:$targetPartSize")
                    }

                    val shouldRetry = !resp.isSuccess && (
                        noSpaceFailure ||
                        resp.type == ResponseType.TIMEOUT ||
                        resp.type == ResponseType.ERROR
                    )
                    if (shouldRetry) {
                        val detail = resp.payload.ifBlank { resp.type.name }
                        flashExecutor.addLog("Retrying $partName ($detail)...")
                        var lastSingleRetryMs = 0L
                        resp = activeDevice.flashPartitionChunked(
                            partition = partName,
                            totalBytes = fileSize,
                            openStream = openStreamLambda,
                            progressCallback = { sent, total ->
                                val pct = if (total > 0) ((sent * 100) / total).toInt().coerceIn(0, 100) else 0
                                val now = System.currentTimeMillis()
                                if (pct >= 100 || sent >= total || now - lastSingleRetryMs >= 65L) {
                                    lastSingleRetryMs = now
                                    _singlePartitionProgressPercent.value = pct
                                }
                            },
                            infoCallback = { info ->
                                if (info.isNotBlank()) singleInfos.add(info.trim())
                            }
                        )
                    }

                    return if (!resp.isSuccess && resp.payload.isBlank() && singleInfos.isNotEmpty()) {
                        resp.copy(payload = singleInfos.last())
                    } else {
                        resp
                    }
                }

                var resp = prepareAndFlash(activeTarget)
                val fbTarget = fallbackPart
                if (!resp.isSuccess && fbTarget != null) {
                    val errLow = resp.payload.lowercase()
                    if (errLow.contains("not found") ||
                        errLow.contains("does not exist") ||
                        errLow.contains("no such") ||
                        errLow.contains("partition table")
                    ) {
                        activeTarget = fbTarget
                        _singlePartitionStatusText.value = "Flashing $activeTarget..."
                        flashExecutor.addLog("Flashing $activeTarget...")
                        resp = prepareAndFlash(activeTarget)
                    }
                }

                if (resp.isSuccess) {
                    flashExecutor.addLog("✓ Flashed $activeTarget")
                } else {
                    succeeded = false
                    val detail = resp.payload.ifBlank { resp.type.name }
                    flashExecutor.addLog("✗ Failed $activeTarget: $detail")
                }
            } catch (e: Exception) {
                succeeded = false
                flashExecutor.addLog("✗ Error: ${e.message}")
            } finally {
                _isSinglePartitionFlashing.value = false
                if (succeeded) {
                    _singlePartitionProgressPercent.value = 100
                    _singlePartitionStatusText.value = "Flashed successfully"
                } else {
                    _singlePartitionStatusText.value = "Flash failed"
                }
            }
        }
    }

    fun formatData() {
        if (_isFormattingData.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val dev = requireFastbootDevice("Format data") ?: return@launch
            _isFormattingData.value = true
            try {
                flashExecutor.addLog("Formatting user data...")
                val udResp = dev.erasePartition("userdata")
                dev.erasePartition("metadata")
                dev.erasePartition("cache")

                if (udResp.isSuccess) {
                    flashExecutor.addLog("✓ Data formatted")
                } else {
                    val detail = udResp.payload.ifBlank { "Requires Bootloader mode" }
                    flashExecutor.addLog("✗ Format data failed: $detail")
                }
            } catch (e: Exception) {
                flashExecutor.addLog("✗ Format data error: ${e.message}")
            } finally {
                _isFormattingData.value = false
            }
        }
    }

    // ADB Sideload Handlers
    fun setSideloadZip(uri: Uri) {
        if (isSideloading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val (rawName, size) = resolveFileNameAndSize(uri)
            val safeName = if (rawName.isNotBlank() && rawName != "unknown.img") rawName else "sideload_package.zip"
            _sideloadZipUri.value = uri
            _sideloadZipName.value = safeName
            _sideloadZipSize.value = size
            _sideloadProgress.value = AdbSideloadProgress(
                state = AdbSideloadState.IDLE,
                fileName = safeName,
                percent = 0,
                uniqueBytesSent = 0L,
                totalBytes = size,
                speedMbPerSec = 0.0,
                statusMessage = "Ready to sideload"
            )
            val sizeTag = if (size > 0L) " (${formatBytes(size)})" else ""
            flashExecutor.addLog("✓ Selected ZIP: $safeName$sizeTag")
        }
    }

    fun clearSideloadZip() {
        if (isSideloading.value) return
        _sideloadZipUri.value = null
        _sideloadZipName.value = ""
        _sideloadZipSize.value = 0L
        _sideloadProgress.value = AdbSideloadProgress()
    }

    fun cancelAdbSideload() {
        isSideloadCancelled = true
        _sideloadProgress.value = _sideloadProgress.value.copy(
            statusMessage = "Cancelling ADB sideload..."
        )
        flashExecutor.addLog("Cancelling ADB sideload...")
    }

    fun enterAdbSideloadMode() {
        if (isSideloading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val mode = usbManager.modeFlow.value
            val adb = usbManager.adbDeviceFlow.value
            if (mode == FastbootMode.ADB_SIDELOAD) {
                flashExecutor.addLog("✓ Already in Recovery ADB Sideload mode")
                return@launch
            }
            if (mode.isAdbConnected && adb != null) {
                flashExecutor.addLog("Switching device to ADB Sideload...")
                val ok = adb.rebootToSideload()
                if (!ok) {
                    flashExecutor.addLog("Note: Enable Apply update -> Apply from ADB on target recovery")
                    return@launch
                }
                val newMode = usbManager.waitForAnyReconnection(expectedMode = FastbootMode.ADB_SIDELOAD)
                when (newMode) {
                    FastbootMode.ADB_SIDELOAD -> flashExecutor.addLog("✓ Connected in Recovery ADB Sideload mode")
                    FastbootMode.ADB_RECOVERY -> flashExecutor.addLog("Note: In Recovery — tap Apply update -> Apply from ADB")
                    else -> flashExecutor.addLog("Note: Reconnect device in Recovery -> Apply from ADB")
                }
            } else if (mode.isBootloaderOrFastbootd) {
                val fb = usbManager.deviceFlow.value ?: return@launch
                flashExecutor.addLog("Rebooting to Recovery...")
                fb.reboot("recovery")
                val newMode = usbManager.waitForAnyReconnection(expectedMode = FastbootMode.ADB_RECOVERY)
                when (newMode) {
                    FastbootMode.ADB_SIDELOAD -> flashExecutor.addLog("✓ Connected in Recovery ADB Sideload mode")
                    FastbootMode.ADB_RECOVERY -> flashExecutor.addLog("✓ Connected in Recovery — enable ADB Sideload on phone")
                    else -> flashExecutor.addLog("Note: Enable ADB Sideload inside Recovery on device")
                }
            } else {
                flashExecutor.addLog("✗ Connect device in ADB, Recovery, or Fastboot first")
            }
        }
    }

    fun startAdbSideload() {
        if (isSideloading.value) return
        val zipUri = _sideloadZipUri.value ?: return
        val fileName = _sideloadZipName.value.ifBlank { "package.zip" }

        val mode = usbManager.modeFlow.value
        val adb = usbManager.adbDeviceFlow.value
        if (mode != FastbootMode.ADB_SIDELOAD || adb == null || !adb.isConnected) {
            flashExecutor.addLog("✗ Device must be in Recovery ADB Sideload mode")
            return
        }

        _isLogExpanded.value = true
        isSideloadCancelled = false
        sideloadJob = viewModelScope.launch(Dispatchers.IO) {
            var tempSpoolFile: File? = null
            var effectiveBytes = _sideloadZipSize.value
            var useDirectSeekablePfd = false

            _sideloadProgress.value = AdbSideloadProgress(
                state = AdbSideloadState.SENDING,
                fileName = fileName,
                percent = 0,
                uniqueBytesSent = 0L,
                totalBytes = effectiveBytes,
                speedMbPerSec = 0.0,
                statusMessage = "Preparing $fileName..."
            )

            try {
                // Probe direct seekable FileChannel first (zero-copy, instant start on local storage)
                try {
                    context.contentResolver.openFileDescriptor(zipUri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { fis ->
                            val probed = try { fis.channel.size() } catch (_: Exception) { -1L }
                            if (probed > 0L) {
                                effectiveBytes = probed
                                useDirectSeekablePfd = true
                            }
                        }
                    }
                } catch (_: Exception) {}

                // Fallback for non-seekable SAF streams: buffer to temp file so recovery can random-seek ZIP EOCD
                if (!useDirectSeekablePfd) {
                    _sideloadProgress.value = _sideloadProgress.value.copy(
                        statusMessage = "Buffering package for sideload..."
                    )
                    val cacheDir = File(context.cacheDir, "sideload_tmp").apply { if (!exists()) mkdirs() }
                    val tmp = File(cacheDir, "sideload_pkg.zip")
                    context.contentResolver.openInputStream(zipUri)?.use { input ->
                        tmp.outputStream().use { output ->
                            input.copyTo(output, 256 * 1024)
                        }
                    }
                    if (tmp.exists() && tmp.length() > 0L) {
                        tempSpoolFile = tmp
                        effectiveBytes = tmp.length()
                    }
                }

                if (effectiveBytes <= 0L) {
                    _sideloadProgress.value = AdbSideloadProgress(
                        state = AdbSideloadState.FAILED,
                        fileName = fileName,
                        statusMessage = "ZIP file is empty or unreadable"
                    )
                    flashExecutor.addLog("✗ Cannot read $fileName")
                    return@launch
                }

                flashExecutor.addLog("Sideloading $fileName (${formatBytes(effectiveBytes)})...")

                val openChannel: () -> Pair<Closeable?, FileChannel?> = {
                    val spool = tempSpoolFile
                    if (spool != null && spool.exists()) {
                        val raf = RandomAccessFile(spool, "r")
                        Pair(raf, raf.channel)
                    } else {
                        val pfd = context.contentResolver.openFileDescriptor(zipUri, "r")
                        if (pfd != null) {
                            val fis = FileInputStream(pfd.fileDescriptor)
                            val ch = fis.channel
                            val owner = Closeable {
                                try { ch.close() } catch (_: Exception) {}
                                try { fis.close() } catch (_: Exception) {}
                                try { pfd.close() } catch (_: Exception) {}
                            }
                            Pair(owner, ch)
                        } else {
                            Pair(null, null)
                        }
                    }
                }

                val (ok, msg) = adb.sideloadZip(
                    fileName = fileName,
                    totalBytes = effectiveBytes,
                    openSeekableChannel = openChannel,
                    isCancelled = { isSideloadCancelled },
                    onProgress = { prog ->
                        _sideloadProgress.value = prog
                    }
                )

                if (isSideloadCancelled) {
                    _sideloadProgress.value = _sideloadProgress.value.copy(
                        state = AdbSideloadState.CANCELLED,
                        statusMessage = "Sideload cancelled"
                    )
                    flashExecutor.addLog("✗ ADB Sideload cancelled")
                } else if (ok) {
                    _sideloadProgress.value = _sideloadProgress.value.copy(
                        state = AdbSideloadState.COMPLETED,
                        percent = 100,
                        uniqueBytesSent = effectiveBytes,
                        totalBytes = effectiveBytes,
                        statusMessage = msg
                    )
                    flashExecutor.addLog("✓ $msg ($fileName)")
                } else {
                    _sideloadProgress.value = _sideloadProgress.value.copy(
                        state = AdbSideloadState.FAILED,
                        statusMessage = msg
                    )
                    flashExecutor.addLog("✗ Sideload failed: $msg")
                }
            } catch (e: Exception) {
                val err = e.message ?: "Sideload error"
                _sideloadProgress.value = _sideloadProgress.value.copy(
                    state = AdbSideloadState.FAILED,
                    statusMessage = err
                )
                flashExecutor.addLog("✗ Sideload error: $err")
            } finally {
                try {
                    tempSpoolFile?.delete()
                    File(context.cacheDir, "sideload_tmp").deleteRecursively()
                } catch (_: Exception) {}
            }
        }
    }

    fun rebootDevice(target: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val mode = usbManager.modeFlow.value
            val label = target.ifBlank { "system" }
            val targetLower = target.lowercase().trim()

            if (mode.isAdbConnected) {
                val adb = usbManager.adbDeviceFlow.value
                if (adb == null || !adb.isConnected) {
                    flashExecutor.addLog("✗ Reboot blocked: ADB device disconnected")
                    return@launch
                }
                flashExecutor.addLog("Rebooting to $label via ADB...")
                val ok = adb.reboot(target)
                if (!ok) {
                    flashExecutor.addLog("Note: Device may not allow ADB reboot from ${mode.name} — use recovery menu")
                    return@launch
                }
                if (targetLower == "bootloader" || targetLower == "fastboot" || targetLower == "fastbootd" ||
                    targetLower == "recovery" || targetLower == "sideload"
                ) {
                    val expectedMode = when (targetLower) {
                        "fastboot", "fastbootd" -> FastbootMode.FASTBOOTD
                        "bootloader" -> FastbootMode.BOOTLOADER
                        "sideload" -> FastbootMode.ADB_SIDELOAD
                        "recovery" -> FastbootMode.ADB_RECOVERY
                        else -> null
                    }
                    val reconnectedMode = usbManager.waitForAnyReconnection(expectedMode = expectedMode)
                    if (reconnectedMode != FastbootMode.DISCONNECTED) {
                        flashExecutor.addLog("✓ Connected ($label)")
                    }
                }
                return@launch
            }

            val dev = requireFastbootDevice("Reboot") ?: return@launch
            flashExecutor.addLog("Rebooting to $label...")
            dev.reboot(target)

            if (targetLower == "fastboot" || targetLower == "fastbootd" || targetLower == "bootloader" || targetLower == "recovery") {
                val expectedMode = when (targetLower) {
                    "fastboot", "fastbootd" -> FastbootMode.FASTBOOTD
                    "bootloader" -> FastbootMode.BOOTLOADER
                    "recovery" -> FastbootMode.ADB_RECOVERY
                    else -> null
                }
                val reconnectedMode = usbManager.waitForAnyReconnection(expectedMode = expectedMode)
                if (reconnectedMode != FastbootMode.DISCONNECTED) {
                    flashExecutor.addLog("✓ Connected ($label)")
                }
            }
        }
    }

    fun setActiveSlot(slot: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dev = requireFastbootDevice("Slot switch") ?: return@launch
            flashExecutor.addLog("Setting active slot ${slot.uppercase()}...")
            val resp = dev.setActiveSlot(slot)
            if (resp.isSuccess) {
                flashExecutor.addLog("✓ Active slot: ${slot.uppercase()}")
                usbManager.refreshDeviceInfo()
            } else {
                flashExecutor.addLog("✗ Slot switch failed: ${resp.payload}")
            }
        }
    }

    fun executeCustomCommand(raw: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val mode = usbManager.modeFlow.value
            val rawTrim = raw.trim()
            if (rawTrim.isEmpty()) return@launch

            if (mode.isAdbConnected) {
                val adb = usbManager.adbDeviceFlow.value
                if (adb == null || !adb.isConnected) {
                    flashExecutor.addLog("✗ ADB device disconnected")
                    return@launch
                }
                val cleanAdb = if (rawTrim.startsWith("adb ", ignoreCase = true)) {
                    rawTrim.substring(4).trim()
                } else {
                    rawTrim
                }
                if (cleanAdb.isEmpty()) return@launch
                flashExecutor.addLog("> adb $cleanAdb")

                val (ok, output) = adb.executeCommand(cleanAdb)
                val lines = output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
                if (lines.isEmpty()) {
                    flashExecutor.addLog(if (ok) "✓ OK" else "✗ Command failed")
                } else {
                    val prefix = if (ok) "✓ " else "✗ "
                    lines.take(30).forEachIndexed { idx, line ->
                        flashExecutor.addLog(if (idx == 0) "$prefix$line" else line)
                    }
                    if (lines.size > 30) {
                        flashExecutor.addLog("... (${lines.size - 30} more lines)")
                    }
                }
                return@launch
            }

            val dev = requireFastbootDevice("Command") ?: return@launch
            var clean = rawTrim
            if (clean.startsWith("fastboot", ignoreCase = true)) {
                clean = clean.substring("fastboot".length).trim()
            }
            if (clean.isEmpty()) return@launch
            flashExecutor.addLog("> fastboot $clean")

            val wireCmd = when {
                clean.startsWith("--set-active=", ignoreCase = true) -> "set_active:" + clean.substring("--set-active=".length).trim()
                clean.startsWith("set_active ", ignoreCase = true) -> "set_active:" + clean.substring("set_active ".length).trim()
                clean.startsWith("getvar ", ignoreCase = true) -> "getvar:" + clean.substring("getvar ".length).trim()
                clean.startsWith("erase ", ignoreCase = true) -> "erase:" + clean.substring("erase ".length).trim()
                clean.equals("reboot bootloader", ignoreCase = true) -> "reboot-bootloader"
                clean.equals("reboot fastboot", ignoreCase = true) -> "reboot-fastboot"
                clean.equals("reboot recovery", ignoreCase = true) -> "reboot-recovery"
                clean.startsWith("wipe-super", ignoreCase = true) -> "update-super:super:wipe"
                clean.equals("snapshot-update cancel", ignoreCase = true) || clean.equals("snapshot-update:cancel", ignoreCase = true) -> "snapshot-update:cancel"
                clean.startsWith("delete-logical-partition ", ignoreCase = true) -> "delete-logical-partition:" + clean.substring("delete-logical-partition ".length).trim()
                clean.startsWith("resize-logical-partition ", ignoreCase = true) -> "resize-logical-partition:" + clean.substring("resize-logical-partition ".length).trim().replace(" ", ":")
                clean.startsWith("create-logical-partition ", ignoreCase = true) -> "create-logical-partition:" + clean.substring("create-logical-partition ".length).trim().replace(" ", ":")
                clean.startsWith("oem ", ignoreCase = true) -> clean
                clean.startsWith("flashing ", ignoreCase = true) -> clean
                else -> clean
            }

            val resp = dev.sendCommand(wireCmd) { info ->
                if (info.isNotBlank()) flashExecutor.addLog(info)
            }
            if (resp.isSuccess) {
                val out = resp.payload.ifBlank { "OK" }
                flashExecutor.addLog("✓ $out")
            } else {
                flashExecutor.addLog("✗ ${resp.payload}")
            }
        }
    }

    fun resolveFileNameAndSize(uri: Uri): Pair<String, Long> {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val filePath = uri.path
            if (!filePath.isNullOrBlank()) {
                val f = File(filePath)
                if (f.exists()) {
                    return Pair(f.name, f.length())
                }
            }
        }
        var name = "unknown.img"
        var size = 0L
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
                }
            }
            if (size <= 0L) {
                size = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    pfd.statSize.coerceAtLeast(0L)
                } ?: 0L
            }
        } catch (_: Exception) {}
        return Pair(name, size)
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return resolveFileNameAndSize(uri).first
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.2f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }
}
