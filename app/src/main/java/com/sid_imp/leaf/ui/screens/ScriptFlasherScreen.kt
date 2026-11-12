package com.sid_imp.leaf.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.fastboot.FastbootMode
import com.sid_imp.leaf.script.ActiveExtractionWorker
import com.sid_imp.leaf.script.FlashExecutionState
import com.sid_imp.leaf.script.ZipExtractionProgress
import com.sid_imp.leaf.ui.FeatureInfoKey
import com.sid_imp.leaf.ui.MainViewModel
import com.sid_imp.leaf.ui.PartitionFileOverride
import com.sid_imp.leaf.ui.RomWizardStep
import com.sid_imp.leaf.ui.components.*
import com.sid_imp.leaf.ui.theme.*
import kotlinx.coroutines.flow.StateFlow

private data class RomPickerRequest(val origin: Rect?)
private data class CustomMenuRequest(val origin: Rect?, val advanceOnConfirm: Boolean)
private data class OverrideEditRequest(val override: PartitionFileOverride, val origin: Rect?)
private data class FeatureInfoRequest(val key: FeatureInfoKey, val origin: Rect?)

@Composable
fun ScriptFlasherScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val tasks by viewModel.parsedTasks.collectAsState()
    val extractedPartitions by viewModel.extractedPartitions.collectAsState()
    val uniquePartitions by viewModel.uniquePartitionCount.collectAsState()
    val isFlashingActive by viewModel.isFlashingActive.collectAsState()
    val flashState by viewModel.flashExecutionState.collectAsState()
    val isZipExtracting by viewModel.isZipExtracting.collectAsState()
    val scriptName by viewModel.loadedScriptName.collectAsState()
    val loadedSourceKind by viewModel.loadedSourceKind.collectAsState()
    val isLogExpanded by viewModel.isLogExpanded.collectAsState()
    val wizardStep by viewModel.romWizardStep.collectAsState()
    val selectedSlot by viewModel.selectedRomSlot.collectAsState()
    val wipeDataEnabled by viewModel.wipeDataEnabled.collectAsState()
    val disableVbmetaVerity by viewModel.disableVbmetaVerity.collectAsState()
    val hasVbmetaPartition by viewModel.hasVbmetaPartition.collectAsState()
    val rebootAfterFlash by viewModel.rebootAfterFlash.collectAsState()
    val postFlashRebootTarget by viewModel.postFlashRebootTarget.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val fastbootMode by viewModel.fastbootMode.collectAsState()
    val partitionOverrides by viewModel.partitionOverrides.collectAsState()

    // Origin-anchored dialog & selection menu states
    var romPickerRequest by remember { mutableStateOf<RomPickerRequest?>(null) }
    var extractedDialogOrigin by remember { mutableStateOf<Rect?>(null) }
    var showExtractedDialog by remember { mutableStateOf(false) }

    var showSlotRebootMenu by remember { mutableStateOf(false) }
    var slotRebootOrigin by remember { mutableStateOf<Rect?>(null) }

    var showWipeMenu by remember { mutableStateOf(false) }
    var wipeMenuOrigin by remember { mutableStateOf<Rect?>(null) }

    var customMenuRequest by remember { mutableStateOf<CustomMenuRequest?>(null) }

    var showVbmetaMenu by remember { mutableStateOf(false) }
    var vbmetaMenuOrigin by remember { mutableStateOf<Rect?>(null) }

    var editingOverrideRequest by remember { mutableStateOf<OverrideEditRequest?>(null) }
    var featureInfoRequest by remember { mutableStateOf<FeatureInfoRequest?>(null) }

    // Anchor holders for cards, option tiles, summary tiles & pills
    val chooseRomAnchor = rememberAnchorBounds()
    val romBarAnchor = rememberAnchorBounds()
    val checkBtnAnchor = rememberAnchorBounds()
    val yesAttachAnchor = rememberAnchorBounds()
    val bootRowAnchor = rememberAnchorBounds()
    val wipeRowAnchor = rememberAnchorBounds()
    val customRowAnchor = rememberAnchorBounds()
    val vbmetaRowAnchor = rememberAnchorBounds()

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.extractAndFlashZip(it) }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadRomFolder(it) }
    }

    val multiFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.loadRomFromUris(uris)
        }
    }

    val addReplaceFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.addOrReplacePartitionFiles(uris)
        }
    }

    val slotSummary = when (selectedSlot.lowercase()) {
        "b" -> "Slot B"
        "both" -> "A + B"
        else -> "Slot A"
    }
    val rebootSummary = when {
        !rebootAfterFlash -> "Stay Here"
        postFlashRebootTarget.equals("bootloader", ignoreCase = true) -> "Bootloader"
        else -> "Restart Phone"
    }
    val detectedDeviceSlot = deviceInfo?.currentSlot
        ?.takeIf { it.equals("a", true) || it.equals("b", true) }
        ?.uppercase()

    val hasLoadedRom = scriptName.isNotEmpty() || extractedPartitions.isNotEmpty()
    val isExecutionUiActive = isFlashingActive ||
        flashState == FlashExecutionState.COMPLETED ||
        flashState == FlashExecutionState.FAILED
    val isAnySelectionMenuOpen = showSlotRebootMenu ||
        showWipeMenu ||
        customMenuRequest != null ||
        showVbmetaMenu ||
        editingOverrideRequest != null

    val metrics = LocalLeafUiMetrics.current

    // Close every open popup or selection menu as soon as flashing or extraction starts (Pure Focus Mode)
    LaunchedEffect(isFlashingActive, isZipExtracting) {
        if (isFlashingActive || isZipExtracting) {
            romPickerRequest = null
            showExtractedDialog = false
            showSlotRebootMenu = false
            showWipeMenu = false
            customMenuRequest = null
            showVbmetaMenu = false
            editingOverrideRequest = null
            featureInfoRequest = null
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val availableHeight = maxHeight
        val isTaskRunning = isZipExtracting || isFlashingActive
        val useFullScreenFocus = (isTaskRunning && availableHeight >= 360.dp) ||
            (isExecutionUiActive && !isFlashingActive && availableHeight >= 660.dp)
        val expandConsoleFull = useFullScreenFocus && isLogExpanded
        val maxWorkersListHeight = (availableHeight * 0.32f).coerceIn(96.dp, 240.dp)
        val maxColWidth = if (isTaskRunning) {
            metrics.maxFocusContentWidthDp.dp
        } else {
            metrics.maxContentWidthDp.dp
        }
        val sectionGap = metrics.sectionGapDp.dp

        Column(
            modifier = Modifier
                .widthIn(max = maxColWidth)
                .fillMaxWidth()
                .then(
                    if (expandConsoleFull) {
                        Modifier.fillMaxHeight()
                    } else {
                        Modifier.verticalScroll(rememberScrollState())
                    }
                )
                .padding(
                    horizontal = metrics.screenHorizontalPaddingDp.dp,
                    vertical = if (metrics.isShortHeight) 4.dp else 6.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 4.dp else 6.dp))

            // 1. Unified Choose ROM / Loaded ROM Bar (hidden during extraction or active flash for Focus Mode)
            OneUiAnimatedSection(visible = !isFlashingActive && !isZipExtracting) {
                AnimatedContent(
                    targetState = hasLoadedRom,
                    transitionSpec = {
                        (fadeIn(tween(180)) + scaleIn(initialScale = 0.97f, animationSpec = tween(180)))
                            .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.97f, animationSpec = tween(120)))
                    },
                    label = "unifiedRomCardState"
                ) { isLoaded ->
                    if (!isLoaded) {
                        OneUiCard(
                            modifier = Modifier
                                .trackAnchorBounds(chooseRomAnchor)
                                .oneUiBounceClick(
                                    enabled = !isFlashingActive && !isZipExtracting,
                                    pressedScale = 0.975f
                                ) {
                                    romPickerRequest = RomPickerRequest(chooseRomAnchor.bounds)
                                },
                            containerColor = OneUiCardSurface,
                            contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
                        ) {
                            val badgeSize = metrics.badgeLargeSizeDp.dp
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(badgeSize)
                                            .clip(CircleShape)
                                            .background(OneUiActiveSurface),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderZip,
                                            contentDescription = "Choose ROM",
                                            tint = OneUiEmerald,
                                            modifier = Modifier.size((metrics.badgeLargeSizeDp * 0.50f).dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 10.dp else 12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Choose ROM File or Folder",
                                            fontFamily = IosRoundedFontFamily,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = OneUiTextPrimary,
                                            fontSize = metrics.cardTitleSp.sp,
                                            lineHeight = (metrics.cardTitleSp + 4f).sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Pick a ROM .zip or an extracted folder",
                                            fontFamily = IosRoundedFontFamily,
                                            fontWeight = FontWeight.SemiBold,
                                            color = OneUiTextSecondary,
                                            fontSize = metrics.subtitleSp.sp,
                                            lineHeight = (metrics.subtitleSp + 3.5f).sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(OneUiEmerald)
                                        .padding(
                                            horizontal = if (metrics.isCompactWidth) 11.dp else 14.dp,
                                            vertical = if (metrics.isCompactWidth) 7.dp else 8.dp
                                        )
                                ) {
                                    Text(
                                        text = "Browse",
                                        fontFamily = IosRoundedFontFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = OneUiEmeraldDeep,
                                        fontSize = metrics.subtitleSp.sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(IntrinsicSize.Max),
                                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
                            ) {
                                OneUiLandingInfoTile(
                                    title = "ROM .zip File",
                                    subtitle = "Unpacks files automatically for you",
                                    icon = Icons.Default.FolderZip,
                                    accent = OneUiEmerald,
                                    badgeBg = OneUiActiveSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                                OneUiLandingInfoTile(
                                    title = "ROM Folder",
                                    subtitle = "Use a folder that already has .img files",
                                    icon = Icons.Default.FolderOpen,
                                    accent = OneUiMint,
                                    badgeBg = OneUiCardSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }

                            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                                    .background(OneUiInnerTile)
                                    .padding(
                                        horizontal = metrics.innerCardPaddingDp.dp,
                                        vertical = if (metrics.isShortHeight) 8.dp else 10.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = OneUiEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Next: 4 quick choices — Boot Slot, Wipe Data, Extra Files & dm-verity.",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.captionSp.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OneUiTextSecondary,
                                    lineHeight = (metrics.captionSp + 4.5f).sp
                                )
                            }
                        }
                    } else {
                        OneUiCard(
                            containerColor = OneUiActiveSurface,
                            contentPadding = PaddingValues(metrics.innerCardPaddingDp.dp.coerceAtLeast(11.dp))
                        ) {
                            val stackLoadedActions = metrics.screenWidthDp < 340 && metrics.fontScale > 1.25f
                            val countText = when {
                                extractedPartitions.isNotEmpty() -> "${extractedPartitions.size} images • ${tasks.size} steps"
                                uniquePartitions > 0 -> "$uniquePartitions partitions • ${tasks.size} steps"
                                else -> "${tasks.size} steps"
                            }

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .trackAnchorBounds(romBarAnchor)
                                            .oneUiBounceClick(
                                                enabled = !isFlashingActive && !isZipExtracting,
                                                pressedScale = 0.98f
                                            ) {
                                                romPickerRequest = RomPickerRequest(romBarAnchor.bounds)
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(metrics.badgeMediumSizeDp.dp)
                                                .clip(CircleShape)
                                                .background(OneUiEmerald),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (loadedSourceKind == "folder") Icons.Default.FolderOpen else Icons.Default.FolderZip,
                                                contentDescription = null,
                                                tint = OneUiEmeraldDeep,
                                                modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 8.dp else 10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = scriptName.removePrefix("Direct ZIP: ").ifBlank { "Loaded ROM" },
                                                fontFamily = IosRoundedFontFamily,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = OneUiTextPrimary,
                                                fontSize = metrics.bodySp.sp,
                                                maxLines = if (metrics.isNarrowOrZoomed) 2 else 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = countText,
                                                fontFamily = IosRoundedFontFamily,
                                                fontWeight = FontWeight.Bold,
                                                color = OneUiMint,
                                                fontSize = metrics.captionSp.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    if (!stackLoadedActions) {
                                        Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 6.dp else 8.dp))

                                        Row(
                                            modifier = Modifier
                                                .trackAnchorBounds(checkBtnAnchor)
                                                .clip(CircleShape)
                                                .background(OneUiEmerald)
                                                .oneUiBounceClick(pressedScale = 0.93f) {
                                                    extractedDialogOrigin = checkBtnAnchor.bounds
                                                    showExtractedDialog = true
                                                }
                                                .padding(
                                                    horizontal = if (metrics.isCompactWidth) 9.dp else 11.dp,
                                                    vertical = 6.dp
                                                ),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.FactCheck,
                                                contentDescription = "View Files",
                                                tint = OneUiEmeraldDeep,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Files",
                                                fontFamily = IosRoundedFontFamily,
                                                color = OneUiEmeraldDeep,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = metrics.captionSp.sp,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 5.dp else 6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(OneUiDangerBadge)
                                                .oneUiBounceClick(pressedScale = 0.93f) { viewModel.clearLoadedRom() }
                                                .padding(
                                                    horizontal = if (metrics.isCompactWidth) 9.dp else 11.dp,
                                                    vertical = 6.dp
                                                )
                                        ) {
                                            Text(
                                                text = "Clear",
                                                fontFamily = IosRoundedFontFamily,
                                                color = OneUiDangerRed,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = metrics.captionSp.sp,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                if (stackLoadedActions) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .trackAnchorBounds(checkBtnAnchor)
                                                .clip(CircleShape)
                                                .background(OneUiEmerald)
                                                .oneUiBounceClick(pressedScale = 0.95f) {
                                                    extractedDialogOrigin = checkBtnAnchor.bounds
                                                    showExtractedDialog = true
                                                }
                                                .padding(vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.FactCheck,
                                                contentDescription = "View Files",
                                                tint = OneUiEmeraldDeep,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Files",
                                                fontFamily = IosRoundedFontFamily,
                                                color = OneUiEmeraldDeep,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = metrics.captionSp.sp
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(CircleShape)
                                                .background(OneUiDangerBadge)
                                                .oneUiBounceClick(pressedScale = 0.95f) { viewModel.clearLoadedRom() }
                                                .padding(vertical = 7.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "Clear",
                                                fontFamily = IosRoundedFontFamily,
                                                color = OneUiDangerRed,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = metrics.captionSp.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Dynamic Parallel ZIP Unpacking Session (Focus Mode hides other cards and expands logs)
            OneUiAnimatedSection(visible = isZipExtracting) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OneUiZipProgressCard(
                        zipProgressFlow = viewModel.zipProgress,
                        formatBytes = viewModel::formatBytes,
                        maxWorkersListHeight = maxWorkersListHeight
                    )
                }
            }

            // 3. Step-by-Step Guided Setup + Persistent Continue -> Start Flashing Button
            OneUiAnimatedSection(
                visible = hasLoadedRom && !isZipExtracting && !isExecutionUiActive
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(sectionGap))

                    OneUiWizardStepProgressStrip(
                        currentStep = wizardStep,
                        onSelectStep = { target -> viewModel.setRomWizardStep(target) }
                    )

                    Spacer(modifier = Modifier.height(sectionGap))

                    AnimatedContent(
                        targetState = wizardStep,
                        transitionSpec = {
                            val forward = targetState.ordinal >= initialState.ordinal
                            (fadeIn(tween(190)) + slideInHorizontally(
                                animationSpec = tween(220, easing = FastOutSlowInEasing)
                            ) { w -> if (forward) w / 5 else -w / 5 }).togetherWith(
                                fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { w ->
                                    if (forward) -w / 5 else w / 5
                                }
                            )
                        },
                        label = "guidedWizardStep"
                    ) { step ->
                        when (step) {
                            RomWizardStep.BOOT_SELECTION -> {
                                OneUiBootSelectionStepCard(
                                    selectedSlot = selectedSlot,
                                    slotSummary = slotSummary,
                                    rebootSummary = rebootSummary,
                                    rebootAfterFlash = rebootAfterFlash,
                                    postFlashRebootTarget = postFlashRebootTarget,
                                    detectedDeviceSlot = detectedDeviceSlot,
                                    onInfoClick = { bounds ->
                                        featureInfoRequest = FeatureInfoRequest(FeatureInfoKey.BOOT_SELECTION, bounds)
                                    },
                                    onSlotSelected = { slot -> viewModel.setSelectedRomSlot(slot) },
                                    onRebootOptionSelected = { enabled, target ->
                                        viewModel.setPostFlashRebootOption(enabled, target)
                                    }
                                )
                            }

                            RomWizardStep.WIPE_DATA -> {
                                OneUiWipeDataStepCard(
                                    wipeDataEnabled = wipeDataEnabled,
                                    onBack = { viewModel.setRomWizardStep(RomWizardStep.BOOT_SELECTION) },
                                    onInfoClick = { bounds ->
                                        featureInfoRequest = FeatureInfoRequest(FeatureInfoKey.WIPE_DATA, bounds)
                                    },
                                    onSelectCleanFlash = { viewModel.setWipeDataEnabled(true) },
                                    onSelectDirtyFlash = { viewModel.setWipeDataEnabled(false) }
                                )
                            }

                            RomWizardStep.CUSTOM_PARTITIONS -> {
                                OneUiCustomPartitionsStepCard(
                                    overrideCount = partitionOverrides.size,
                                    yesAttachAnchor = yesAttachAnchor,
                                    onBack = { viewModel.setRomWizardStep(RomWizardStep.WIPE_DATA) },
                                    onInfoClick = { bounds ->
                                        featureInfoRequest = FeatureInfoRequest(
                                            FeatureInfoKey.ADVANCED_CUSTOM_PARTITIONS,
                                            bounds
                                        )
                                    },
                                    onSelectYesAttach = {
                                        customMenuRequest = CustomMenuRequest(
                                            origin = yesAttachAnchor.bounds,
                                            advanceOnConfirm = true
                                        )
                                    },
                                    onSelectDefault = { viewModel.clearPartitionOverrides() }
                                )
                            }

                            RomWizardStep.VBMETA_VERITY -> {
                                OneUiVbmetaStepCard(
                                    disableVbmeta = disableVbmetaVerity,
                                    hasVbmetaPartition = hasVbmetaPartition,
                                    onBack = { viewModel.setRomWizardStep(RomWizardStep.CUSTOM_PARTITIONS) },
                                    onInfoClick = { bounds ->
                                        featureInfoRequest = FeatureInfoRequest(
                                            FeatureInfoKey.VBMETA_VERITY,
                                            bounds
                                        )
                                    },
                                    onSelectDisable = { viewModel.setDisableVbmetaVerity(true) },
                                    onSelectKeepDefault = { viewModel.setDisableVbmetaVerity(false) }
                                )
                            }

                            RomWizardStep.SUMMARY -> {
                                OneUiFlashSummaryCard(
                                    slotSummary = slotSummary,
                                    rebootSummary = rebootSummary,
                                    wipeDataEnabled = wipeDataEnabled,
                                    customCount = partitionOverrides.size,
                                    disableVbmeta = disableVbmetaVerity,
                                    bootRowAnchor = bootRowAnchor,
                                    wipeRowAnchor = wipeRowAnchor,
                                    customRowAnchor = customRowAnchor,
                                    vbmetaRowAnchor = vbmetaRowAnchor,
                                    onBack = { viewModel.setRomWizardStep(RomWizardStep.VBMETA_VERITY) },
                                    onEditBootSlot = {
                                        slotRebootOrigin = bootRowAnchor.bounds
                                        showSlotRebootMenu = true
                                    },
                                    onEditWipe = {
                                        wipeMenuOrigin = wipeRowAnchor.bounds
                                        showWipeMenu = true
                                    },
                                    onEditCustomPartitions = {
                                        customMenuRequest = CustomMenuRequest(
                                            origin = customRowAnchor.bounds,
                                            advanceOnConfirm = false
                                        )
                                    },
                                    onEditVbmeta = {
                                        vbmetaMenuOrigin = vbmetaRowAnchor.bounds
                                        showVbmetaMenu = true
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(sectionGap))

                    // Persistent CTA: "Continue" while stepping through setup or while any selection menu is open;
                    // transitions to "Start Flashing" ONLY on final Summary with no menu open.
                    val isSummaryReadyMode = wizardStep == RomWizardStep.SUMMARY && !isAnySelectionMenuOpen
                    val hasRomTasks = tasks.isNotEmpty() && !isZipExtracting
                    val isDeviceReady = fastbootMode == FastbootMode.BOOTLOADER ||
                        fastbootMode == FastbootMode.FASTBOOTD

                    OneUiScriptHeroCta(
                        isContinueMode = !isSummaryReadyMode,
                        isDeviceReady = isDeviceReady,
                        isButtonEnabled = if (isSummaryReadyMode) (hasRomTasks && isDeviceReady) else true,
                        onClick = {
                            when {
                                isAnySelectionMenuOpen -> {
                                    showSlotRebootMenu = false
                                    showWipeMenu = false
                                    customMenuRequest = null
                                    showVbmetaMenu = false
                                    editingOverrideRequest = null
                                }
                                wizardStep == RomWizardStep.BOOT_SELECTION ->
                                    viewModel.setRomWizardStep(RomWizardStep.WIPE_DATA)
                                wizardStep == RomWizardStep.WIPE_DATA ->
                                    viewModel.setRomWizardStep(RomWizardStep.CUSTOM_PARTITIONS)
                                wizardStep == RomWizardStep.CUSTOM_PARTITIONS ->
                                    viewModel.setRomWizardStep(RomWizardStep.VBMETA_VERITY)
                                wizardStep == RomWizardStep.VBMETA_VERITY ->
                                    viewModel.setRomWizardStep(RomWizardStep.SUMMARY)
                                wizardStep == RomWizardStep.SUMMARY ->
                                    viewModel.startFlashingScript()
                            }
                        }
                    )
                }
            }

            // 4. Focus Flashing View (ONLY flashing progress + logs while running; Reboot/Retry controls after finish)
            OneUiAnimatedSection(visible = isExecutionUiActive) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isFlashingActive && wipeDataEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(OneUiDangerSurface)
                                .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = OneUiDangerRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Clean Flash active • Old phone data will erase at end",
                                fontFamily = IosRoundedFontFamily,
                                fontSize = metrics.captionSp.sp,
                                fontWeight = FontWeight.Bold,
                                color = OneUiDangerRed,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(sectionGap))
                    } else if (!isFlashingActive) {
                        Spacer(modifier = Modifier.height(sectionGap))
                    }

                    ProgressBarWithSpeed(
                        progressFlow = viewModel.executionProgress,
                        onPause = { viewModel.pauseExecution() },
                        onResume = { viewModel.resumeExecution() },
                        onCancel = { viewModel.cancelExecution() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            OneUiAnimatedSection(
                visible = flashState == FlashExecutionState.COMPLETED &&
                    (fastbootMode == FastbootMode.BOOTLOADER || fastbootMode == FastbootMode.FASTBOOTD)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(sectionGap))
                    OneUiCard(
                        containerColor = OneUiActiveSurface,
                        contentPadding = PaddingValues(metrics.innerCardPaddingDp.dp.coerceAtLeast(12.dp))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = OneUiEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "All Done • Restart Phone",
                                fontFamily = IosRoundedFontFamily,
                                color = OneUiEmerald,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = metrics.bodySp.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                                    .clip(CircleShape)
                                    .background(OneUiEmerald)
                                    .oneUiBounceClick(pressedScale = 0.96f) { viewModel.rebootDevice("") }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = "Restart Phone",
                                    tint = OneUiEmeraldDeep,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Restart Phone",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.subtitleSp.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = OneUiEmeraldDeep,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                                    .clip(CircleShape)
                                    .background(OneUiInnerTile)
                                    .oneUiBounceClick(pressedScale = 0.96f) { viewModel.rebootDevice("bootloader") }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.StayCurrentPortrait,
                                    contentDescription = "Bootloader",
                                    tint = OneUiMint,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Bootloader",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.subtitleSp.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            OneUiAnimatedSection(
                visible = !isFlashingActive &&
                    (flashState == FlashExecutionState.COMPLETED || flashState == FlashExecutionState.FAILED)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(sectionGap))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                            .clip(CircleShape)
                            .background(OneUiCardSurface)
                            .oneUiBounceClick(pressedScale = 0.97f) {
                                viewModel.resetFlashStateToWizard()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Back to Choices",
                            fontFamily = IosRoundedFontFamily,
                            fontSize = metrics.bodySp.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiMint
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(sectionGap))

            // 5. Leaves Log Card (stretches to use entire remaining viewport height during ongoing task)
            LeavesConsoleCard(
                logsFlow = viewModel.logs,
                isExpanded = isLogExpanded,
                onToggleExpanded = { viewModel.toggleLogExpanded() },
                onClearLogs = { viewModel.clearLogs() },
                consoleHeight = if (useFullScreenFocus) {
                    (availableHeight * 0.44f).coerceIn(140.dp, 340.dp)
                } else {
                    metrics.consoleDefaultHeightDp.dp
                },
                fillAvailableHeight = expandConsoleFull,
                modifier = if (expandConsoleFull) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier.fillMaxWidth()
                }
            )

            Spacer(
                modifier = Modifier.height(
                    if (expandConsoleFull) 8.dp else if (metrics.isShortHeight) 14.dp else 20.dp
                )
            )
        }
    }

    // Origin-Anchored Dialogs & Selection Menus
    romPickerRequest?.let { req ->
        UnifiedRomPickerDialog(
            originBounds = req.origin,
            onDismissRequest = { romPickerRequest = null },
            onFolderSelected = { folder -> viewModel.loadRomFromDirectory(folder) },
            onZipFileSelected = { zipFile -> viewModel.loadRomZipFile(zipFile) },
            onLaunchSystemZipPicker = {
                zipPickerLauncher.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                )
            },
            onLaunchSystemFolderPicker = {
                try {
                    folderPickerLauncher.launch(null)
                } catch (_: ActivityNotFoundException) {
                    romPickerRequest = RomPickerRequest(req.origin)
                } catch (_: Exception) {
                    romPickerRequest = RomPickerRequest(req.origin)
                }
            },
            onPickFilesAlternative = {
                multiFilePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            }
        )
    }

    if (showExtractedDialog) {
        ExtractedPartitionsDialog(
            romTitle = scriptName,
            extractedPartitions = extractedPartitions,
            plannedTasks = tasks,
            selectedSlotLabel = slotSummary,
            formatBytes = viewModel::formatBytes,
            originBounds = extractedDialogOrigin,
            onDismiss = { showExtractedDialog = false }
        )
    }

    if (showSlotRebootMenu) {
        AnchoredSlotRebootMenu(
            selectedSlot = selectedSlot,
            rebootAfterFlash = rebootAfterFlash,
            postFlashRebootTarget = postFlashRebootTarget,
            detectedDeviceSlot = detectedDeviceSlot,
            originBounds = slotRebootOrigin,
            onDismiss = { showSlotRebootMenu = false },
            onSlotSelected = { slot -> viewModel.setSelectedRomSlot(slot) },
            onRebootOptionSelected = { enabled, target ->
                viewModel.setPostFlashRebootOption(enabled, target)
            },
            onInfoClick = { key, bounds ->
                featureInfoRequest = FeatureInfoRequest(key, bounds)
            }
        )
    }

    if (showWipeMenu) {
        AnchoredWipeSelectionMenu(
            wipeDataEnabled = wipeDataEnabled,
            originBounds = wipeMenuOrigin,
            onDismiss = { showWipeMenu = false },
            onInfoClick = { bounds ->
                featureInfoRequest = FeatureInfoRequest(FeatureInfoKey.WIPE_DATA, bounds)
            },
            onSelect = { enableWipe ->
                viewModel.setWipeDataEnabled(enableWipe)
            }
        )
    }

    customMenuRequest?.let { req ->
        AnchoredCustomPartitionsMenu(
            overrides = partitionOverrides,
            formatBytes = viewModel::formatBytes,
            originBounds = req.origin,
            onDismiss = { customMenuRequest = null },
            onInfoClick = { bounds ->
                featureInfoRequest = FeatureInfoRequest(FeatureInfoKey.ADVANCED_CUSTOM_PARTITIONS, bounds)
            },
            onAddFiles = {
                addReplaceFilesLauncher.launch(arrayOf("*/*", "application/octet-stream"))
            },
            onRemapOverride = { ov, chipBounds ->
                editingOverrideRequest = OverrideEditRequest(ov, chipBounds)
            },
            onRemoveOverride = { id ->
                viewModel.removePartitionOverride(id)
            },
            onUseDefault = {
                viewModel.clearPartitionOverrides()
                if (req.advanceOnConfirm) {
                    viewModel.setRomWizardStep(RomWizardStep.VBMETA_VERITY)
                }
            },
            onConfirmDone = {
                if (req.advanceOnConfirm) {
                    viewModel.setRomWizardStep(RomWizardStep.VBMETA_VERITY)
                }
            }
        )
    }

    if (showVbmetaMenu) {
        AnchoredVbmetaSelectionMenu(
            disableVbmeta = disableVbmetaVerity,
            hasVbmetaPartition = hasVbmetaPartition,
            originBounds = vbmetaMenuOrigin,
            onDismiss = { showVbmetaMenu = false },
            onInfoClick = { bounds ->
                featureInfoRequest = FeatureInfoRequest(FeatureInfoKey.VBMETA_VERITY, bounds)
            },
            onSelect = { disable ->
                viewModel.setDisableVbmetaVerity(disable)
            }
        )
    }

    editingOverrideRequest?.let { editReq ->
        PartitionTargetPickerDialog(
            title = "Target Partition",
            currentTarget = editReq.override.targetStem,
            partitions = viewModel.getSuggestedPartitionTargets(),
            originBounds = editReq.origin,
            onDismiss = { editingOverrideRequest = null },
            onSelectPartition = { picked ->
                viewModel.remapPartitionOverride(editReq.override.id, picked)
            }
        )
    }

    featureInfoRequest?.let { req ->
        OneUiFeatureInfoDialog(
            featureKey = req.key,
            originBounds = req.origin,
            onDismiss = { featureInfoRequest = null }
        )
    }
}

@Composable
private fun OneUiLandingInfoTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    badgeBg: Color,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    val badgeSize = metrics.badgeSmallSizeDp.dp
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
            .background(OneUiInnerTile)
            .padding(metrics.innerCardPaddingDp.dp)
    ) {
        Box(
            modifier = Modifier
                .size(badgeSize)
                .clip(CircleShape)
                .background(badgeBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size((metrics.badgeSmallSizeDp * 0.52f).dp)
            )
        }
        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 6.dp else 8.dp))
        Text(
            text = title,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.bodySp.sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.captionSp.sp,
            fontWeight = FontWeight.Medium,
            color = OneUiTextSecondary,
            lineHeight = (metrics.captionSp + 4f).sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OneUiWizardStepProgressStrip(
    currentStep: RomWizardStep,
    onSelectStep: (RomWizardStep) -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val steps = listOf(
        RomWizardStep.BOOT_SELECTION to "Boot",
        RomWizardStep.WIPE_DATA to "Wipe",
        RomWizardStep.CUSTOM_PARTITIONS to "Files",
        RomWizardStep.VBMETA_VERITY to "Verity",
        RomWizardStep.SUMMARY to if (metrics.isNarrowOrZoomed) "Final" else "Summary"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(OneUiCardSurface)
            .padding(if (metrics.isCompactWidth) 3.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 3.dp else 4.dp)
    ) {
        steps.forEachIndexed { idx, (step, label) ->
            val isCurrent = currentStep == step
            val isDone = step.ordinal < currentStep.ordinal
            val bg by animateColorAsState(
                targetValue = when {
                    isCurrent -> OneUiEmerald
                    isDone -> OneUiActiveSurface
                    else -> OneUiInnerTile
                },
                animationSpec = tween(180),
                label = "wizardPillBg"
            )
            val fg by animateColorAsState(
                targetValue = when {
                    isCurrent -> OneUiEmeraldDeep
                    isDone -> OneUiMint
                    else -> OneUiTextMuted
                },
                animationSpec = tween(180),
                label = "wizardPillFg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(bg)
                    .oneUiBounceClick(pressedScale = 0.94f) { onSelectStep(step) }
                    .padding(vertical = if (metrics.isShortHeight) 6.dp else 8.dp, horizontal = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isDone) "✓ $label" else "${idx + 1} $label",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.microSp.sp,
                    letterSpacing = if (metrics.isNarrowOrZoomed) (-0.3).sp else (-0.15).sp,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OneUiBootSelectionStepCard(
    selectedSlot: String,
    slotSummary: String,
    rebootSummary: String,
    rebootAfterFlash: Boolean,
    postFlashRebootTarget: String,
    detectedDeviceSlot: String?,
    onInfoClick: (Rect?) -> Unit,
    onSlotSelected: (String) -> Unit,
    onRebootOptionSelected: (Boolean, String) -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    OneUiCard(
        containerColor = OneUiCardSurface,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OneUiStepBadge("STEP 1 OF 4 • BOOT")
                if (detectedDeviceSlot != null) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OneUiInnerTile)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (metrics.isNarrowOrZoomed) "Slot $detectedDeviceSlot" else "Phone: Slot $detectedDeviceSlot",
                            fontFamily = IosRoundedFontFamily,
                            fontSize = metrics.microSp.sp,
                            fontWeight = FontWeight.Bold,
                            color = OneUiMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            OneUiInfoButton(onClick = onInfoClick, badgeBg = OneUiInnerTile)
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        Text(
            text = "Where to install & restart",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.headlineSp.sp,
            lineHeight = (metrics.headlineSp + 5f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "Choose which slot gets the ROM and what your phone should do after flashing finishes.",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            fontWeight = FontWeight.Medium,
            color = OneUiTextSecondary,
            lineHeight = (metrics.subtitleSp + 4.5f).sp
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                .background(OneUiInnerTile)
                .padding(metrics.innerCardPaddingDp.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Target Slot",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.bodySp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OneUiTextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (selectedSlot.lowercase()) {
                        "b" -> "Install on Slot B"
                        "both" -> "Firmware on A & B"
                        else -> if (metrics.isNarrowOrZoomed) "Best • Slot A" else "Recommended • Slot A"
                    },
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.captionSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiMint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SlotSegmentedToggle(
                selectedSlot = selectedSlot,
                onSlotSelected = onSlotSelected,
                fullLabels = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

        Text(
            text = "When Flashing Finishes",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.bodySp.sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))

        OneUiRebootOptionGrid(
            rebootAfterFlash = rebootAfterFlash,
            postFlashRebootTarget = postFlashRebootTarget,
            onSelect = onRebootOptionSelected
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

        OneUiSelectionPreviewPill(
            text = "Selected right now: $slotSummary • $rebootSummary",
            accent = OneUiEmerald,
            surfaceBg = OneUiActiveSurface
        )
    }
}

@Composable
private fun OneUiRebootOptionGrid(
    rebootAfterFlash: Boolean,
    postFlashRebootTarget: String,
    onSelect: (Boolean, String) -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val isSystem = rebootAfterFlash && !postFlashRebootTarget.equals("bootloader", ignoreCase = true)
    val isBootloader = rebootAfterFlash && postFlashRebootTarget.equals("bootloader", ignoreCase = true)
    val isStay = !rebootAfterFlash

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
    ) {
        OneUiCompactRebootCard(
            title = "Restart Phone",
            subtitle = "Turn phone back on",
            badge = if (metrics.isNarrowOrZoomed) "Best" else "Recommended",
            icon = Icons.Default.PowerSettingsNew,
            accent = OneUiEmerald,
            iconBg = OneUiActiveSurface,
            isSelected = isSystem,
            onClick = { onSelect(true, "system") },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        OneUiCompactRebootCard(
            title = "Bootloader",
            subtitle = "Back to Fastboot",
            badge = "Manual",
            icon = Icons.Default.StayCurrentPortrait,
            accent = OneUiCyan,
            iconBg = OneUiCyanSurface,
            isSelected = isBootloader,
            onClick = { onSelect(true, "bootloader") },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        OneUiCompactRebootCard(
            title = "Stay Here",
            subtitle = "Do not auto restart",
            badge = "Wait",
            icon = Icons.Default.PauseCircleOutline,
            accent = OneUiAmber,
            iconBg = OneUiAmberSurface,
            isSelected = isStay,
            onClick = { onSelect(false, "system") },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
    }
}

@Composable
private fun OneUiCompactRebootCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    accent: Color,
    iconBg: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    val surfaceColor by animateColorAsState(
        targetValue = if (isSelected) iconBg else OneUiInnerTile,
        animationSpec = tween(180),
        label = "compactRebootBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.65f) else Color.Transparent,
        animationSpec = tween(180),
        label = "compactRebootBorder"
    )
    val cardShape = RoundedCornerShape(metrics.innerCornerRadiusDp.dp)
    val badgeSize = (metrics.badgeSmallSizeDp - 2f).coerceAtLeast(26f).dp
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = metrics.rebootCardMinHeightDp.dp)
            .clip(cardShape)
            .background(surfaceColor)
            .then(
                if (isSelected) {
                    Modifier.border(1.3.dp, borderColor, cardShape)
                } else {
                    Modifier
                }
            )
            .oneUiBounceClick(pressedScale = 0.96f, onClick = onClick)
            .padding(
                horizontal = if (metrics.isCompactWidth) 8.dp else 10.dp,
                vertical = if (metrics.isShortHeight) 9.dp else 11.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(if (isSelected) accent else OneUiCardSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isSelected) Color.Black else accent,
                    modifier = Modifier.size((badgeSize * 0.53f))
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 6.dp else 8.dp))
        Text(
            text = title,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            lineHeight = (metrics.subtitleSp + 3f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.microSp.sp,
            fontWeight = FontWeight.Medium,
            color = OneUiTextSecondary,
            lineHeight = (metrics.microSp + 3.5f).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = badge,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.microSp.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (isSelected) accent else OneUiTextMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OneUiWipeDataStepCard(
    wipeDataEnabled: Boolean,
    onBack: () -> Unit,
    onInfoClick: (Rect?) -> Unit,
    onSelectCleanFlash: () -> Unit,
    onSelectDirtyFlash: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    OneUiCard(
        containerColor = OneUiCardSurface,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
            ) {
                OneUiBackChip(onClick = onBack)
                OneUiStepBadge("STEP 2 OF 4 • WIPE DATA")
            }
            Spacer(modifier = Modifier.width(6.dp))
            OneUiInfoButton(
                onClick = onInfoClick,
                tint = OneUiDangerRed,
                badgeBg = OneUiDangerBadge
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        Text(
            text = "Erase your old apps & data?",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.headlineSp.sp,
            lineHeight = (metrics.headlineSp + 5f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Pick Yes when changing to a new ROM. Pick No if you are only updating the same ROM.",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            fontWeight = FontWeight.Medium,
            color = OneUiTextSecondary,
            lineHeight = (metrics.subtitleSp + 4.5f).sp
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
        ) {
            OneUiChoiceGridCard(
                title = "Yes • Clean Flash",
                subtitle = "Erases all apps, accounts, photos & internal storage",
                tagLabel = "New ROM",
                icon = Icons.Default.DeleteSweep,
                accent = OneUiDangerRed,
                iconBg = OneUiDangerBadge,
                surfaceBg = if (wipeDataEnabled) OneUiDangerActiveSurface else OneUiDangerSurface,
                isSelected = wipeDataEnabled,
                onClick = onSelectCleanFlash,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            OneUiChoiceGridCard(
                title = "No • Dirty Flash",
                subtitle = "Keeps existing apps, photos & user logins safe",
                tagLabel = "Same ROM Update",
                icon = Icons.Default.Save,
                accent = OneUiEmerald,
                iconBg = OneUiActiveSurface,
                surfaceBg = if (!wipeDataEnabled) OneUiActiveSurface else OneUiInnerTile,
                isSelected = !wipeDataEnabled,
                onClick = onSelectDirtyFlash,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

        OneUiSelectionPreviewPill(
            text = if (wipeDataEnabled) {
                "Selected right now: Yes • Clean Flash (Erases data)"
            } else {
                "Selected right now: No • Dirty Flash (Keeps data)"
            },
            accent = if (wipeDataEnabled) OneUiDangerRed else OneUiEmerald,
            surfaceBg = if (wipeDataEnabled) OneUiDangerSurface else OneUiActiveSurface
        )
    }
}

@Composable
private fun OneUiCustomPartitionsStepCard(
    overrideCount: Int,
    yesAttachAnchor: AnchorBoundsHolder,
    onBack: () -> Unit,
    onInfoClick: (Rect?) -> Unit,
    onSelectYesAttach: () -> Unit,
    onSelectDefault: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    OneUiCard(
        containerColor = OneUiCardSurface,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
            ) {
                OneUiBackChip(onClick = onBack)
                OneUiStepBadge("STEP 3 OF 4 • EXTRA FILES")
            }
            Spacer(modifier = Modifier.width(6.dp))
            OneUiInfoButton(onClick = onInfoClick, badgeBg = OneUiInnerTile)
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        Text(
            text = "Add or swap any .img files?",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.headlineSp.sp,
            lineHeight = (metrics.headlineSp + 5f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        OneUiNoteBanner(
            text = "Note: Optional — you can attach super_empty.img, or a Magisk / KernelSU patched boot.img or init_boot.img here.",
            accent = OneUiMint,
            surfaceBg = OneUiInnerTile
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
        ) {
            OneUiChoiceGridCard(
                title = if (overrideCount > 0) "Yes • $overrideCount Ready" else "Yes • Add Files",
                subtitle = "Attach super_empty.img, rooted boot / init_boot & more",
                tagLabel = "super_empty / root",
                icon = Icons.AutoMirrored.Filled.NoteAdd,
                accent = OneUiEmerald,
                iconBg = OneUiActiveSurface,
                surfaceBg = if (overrideCount > 0) OneUiActiveSurface else OneUiInnerTile,
                isSelected = overrideCount > 0,
                onClick = onSelectYesAttach,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .trackAnchorBounds(yesAttachAnchor)
            )

            OneUiChoiceGridCard(
                title = "No • Keep Default",
                subtitle = "Just install the normal files that came inside the ROM",
                tagLabel = "Standard ROM",
                icon = Icons.Default.CheckCircle,
                accent = OneUiMint,
                iconBg = OneUiCardSurface,
                surfaceBg = if (overrideCount == 0) OneUiActiveSurface else OneUiInnerTile,
                isSelected = overrideCount == 0,
                onClick = onSelectDefault,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

        OneUiSelectionPreviewPill(
            text = if (overrideCount > 0) {
                "Selected right now: Yes • $overrideCount extra file(s) attached"
            } else {
                "Selected right now: Keep Default (Standard ROM files)"
            },
            accent = OneUiEmerald,
            surfaceBg = OneUiActiveSurface
        )
    }
}

@Composable
private fun OneUiVbmetaStepCard(
    disableVbmeta: Boolean,
    hasVbmetaPartition: Boolean,
    onBack: () -> Unit,
    onInfoClick: (Rect?) -> Unit,
    onSelectDisable: () -> Unit,
    onSelectKeepDefault: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    OneUiCard(
        containerColor = OneUiCardSurface,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
            ) {
                OneUiBackChip(onClick = onBack)
                OneUiStepBadge("STEP 4 OF 4 • DM-VERITY")
            }
            Spacer(modifier = Modifier.width(6.dp))
            OneUiInfoButton(
                onClick = onInfoClick,
                tint = OneUiAmber,
                badgeBg = OneUiAmberSurface
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        Text(
            text = "Disable dm-verity Verification?",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.headlineSp.sp,
            lineHeight = (metrics.headlineSp + 5f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        OneUiNoteBanner(
            text = if (hasVbmetaPartition) {
                "Note: Pick Yes only for Port ROMs or GSIs (disables dm-verity verification). Pick Keep Default for official ROMs."
            } else {
                "Note: For Port ROMs or GSIs (disables verification automatically whenever vbmeta files exist)."
            },
            accent = OneUiAmber,
            surfaceBg = OneUiAmberSurface
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
        ) {
            OneUiChoiceGridCard(
                title = "Yes",
                subtitle = "Disables dm-verity & vbmeta verification for custom ports",
                tagLabel = "Disable Verification",
                icon = Icons.Default.Security,
                accent = OneUiAmber,
                iconBg = OneUiAmberSurface,
                surfaceBg = if (disableVbmeta) OneUiAmberSurface else OneUiInnerTile,
                isSelected = disableVbmeta,
                onClick = onSelectDisable,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            OneUiChoiceGridCard(
                title = "Keep Default",
                subtitle = "Keeps normal official dm-verity verification active",
                tagLabel = "Official ROMs",
                icon = Icons.Default.VerifiedUser,
                accent = OneUiEmerald,
                iconBg = OneUiActiveSurface,
                surfaceBg = if (!disableVbmeta) OneUiActiveSurface else OneUiInnerTile,
                isSelected = !disableVbmeta,
                onClick = onSelectKeepDefault,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

        OneUiSelectionPreviewPill(
            text = if (disableVbmeta) {
                "Selected right now: Disable Verification • Yes"
            } else {
                "Selected right now: dm-verity Verification • Keep Default"
            },
            accent = if (disableVbmeta) OneUiAmber else OneUiEmerald,
            surfaceBg = if (disableVbmeta) OneUiAmberSurface else OneUiActiveSurface
        )
    }
}

@Composable
private fun OneUiFlashSummaryCard(
    slotSummary: String,
    rebootSummary: String,
    wipeDataEnabled: Boolean,
    customCount: Int,
    disableVbmeta: Boolean,
    bootRowAnchor: AnchorBoundsHolder,
    wipeRowAnchor: AnchorBoundsHolder,
    customRowAnchor: AnchorBoundsHolder,
    vbmetaRowAnchor: AnchorBoundsHolder,
    onBack: () -> Unit,
    onEditBootSlot: () -> Unit,
    onEditWipe: () -> Unit,
    onEditCustomPartitions: () -> Unit,
    onEditVbmeta: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    OneUiCard(
        containerColor = OneUiCardSurface,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
            ) {
                OneUiBackChip(onClick = onBack)
                OneUiStepBadge("FINAL CHECK")
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Tap any box to edit",
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.captionSp.sp,
                fontWeight = FontWeight.Bold,
                color = OneUiTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        Text(
            text = "Check Your Choices",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.headlineSp.sp,
            lineHeight = (metrics.headlineSp + 5f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary
        )

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
        ) {
            OneUiSummaryGridTile(
                title = "Boot & Restart",
                valueLabel = "$slotSummary • $rebootSummary",
                icon = Icons.Default.Tune,
                accent = OneUiMint,
                badgeBg = OneUiActiveSurface,
                cardBg = OneUiInnerTile,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .trackAnchorBounds(bootRowAnchor),
                onClick = onEditBootSlot
            )

            OneUiSummaryGridTile(
                title = "Erase Data",
                valueLabel = if (wipeDataEnabled) "Yes • Clean Flash" else "No • Dirty Flash",
                icon = Icons.Default.DeleteSweep,
                accent = if (wipeDataEnabled) OneUiDangerRed else OneUiEmerald,
                badgeBg = if (wipeDataEnabled) OneUiDangerBadge else OneUiActiveSurface,
                cardBg = if (wipeDataEnabled) OneUiDangerSurface else OneUiInnerTile,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .trackAnchorBounds(wipeRowAnchor),
                onClick = onEditWipe
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isCompactWidth) 8.dp else 10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
        ) {
            OneUiSummaryGridTile(
                title = "Extra Files",
                valueLabel = if (customCount > 0) "$customCount Attached" else "Keep Default",
                icon = Icons.AutoMirrored.Filled.NoteAdd,
                accent = if (customCount > 0) OneUiEmerald else OneUiMint,
                badgeBg = if (customCount > 0) OneUiActiveSurface else OneUiCardSurface,
                cardBg = OneUiInnerTile,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .trackAnchorBounds(customRowAnchor),
                onClick = onEditCustomPartitions
            )

            OneUiSummaryGridTile(
                title = "dm-verity",
                valueLabel = if (disableVbmeta) "Yes • Disable Verification" else "Keep Default",
                icon = Icons.Default.VerifiedUser,
                accent = if (disableVbmeta) OneUiAmber else OneUiEmerald,
                badgeBg = if (disableVbmeta) OneUiAmberSurface else OneUiActiveSurface,
                cardBg = if (disableVbmeta) OneUiAmberSurface else OneUiInnerTile,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .trackAnchorBounds(vbmetaRowAnchor),
                onClick = onEditVbmeta
            )
        }
    }
}

@Composable
private fun AnchoredSlotRebootMenu(
    selectedSlot: String,
    rebootAfterFlash: Boolean,
    postFlashRebootTarget: String,
    detectedDeviceSlot: String?,
    originBounds: Rect?,
    onDismiss: () -> Unit,
    onSlotSelected: (String) -> Unit,
    onRebootOptionSelected: (Boolean, String) -> Unit,
    onInfoClick: (FeatureInfoKey, Rect?) -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val slotSummary = when (selectedSlot.lowercase()) {
        "b" -> "Slot B"
        "both" -> "A + B"
        else -> "Slot A"
    }
    val rebootSummary = when {
        !rebootAfterFlash -> "Stay Here"
        postFlashRebootTarget.equals("bootloader", ignoreCase = true) -> "Bootloader"
        else -> "Restart Phone"
    }

    AnchoredDialog(
        onDismissRequest = onDismiss,
        originBounds = originBounds,
        scrimColor = Color(0xC8000000),
        horizontalPadding = if (metrics.isCompactWidth) 12.dp else 16.dp,
        verticalPadding = if (metrics.isShortHeight) 14.dp else 24.dp
    ) { dismiss ->
        OneUiCard(
            modifier = Modifier
                .widthIn(max = metrics.maxDialogWidthDp.dp)
                .fillMaxWidth()
                .heightIn(max = metrics.maxMenuHeightDp.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape((metrics.cardCornerRadiusDp + 2f).coerceAtLeast(24f).dp),
            containerColor = OneUiCardSurfaceAlt,
            contentPadding = PaddingValues(metrics.cardPaddingDp.dp),
            animateSize = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(metrics.badgeMediumSizeDp.dp)
                            .clip(CircleShape)
                            .background(OneUiActiveSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Where to Install & Restart",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.cardTitleSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (detectedDeviceSlot != null) {
                                "Phone active slot: Slot $detectedDeviceSlot"
                            } else {
                                "Pick boot slot & what to do after"
                            },
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = OneUiMint,
                            fontSize = metrics.captionSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                OneUiMenuCloseButton { dismiss {} }
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                    .background(OneUiInnerTile)
                    .padding(metrics.innerCardPaddingDp.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Target Slot",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.bodySp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary
                    )
                    OneUiInfoButton(
                        onClick = { bounds -> onInfoClick(FeatureInfoKey.ADVANCED_TARGET_SLOT, bounds) },
                        badgeBg = OneUiCardSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SlotSegmentedToggle(
                    selectedSlot = selectedSlot,
                    onSlotSelected = onSlotSelected,
                    fullLabels = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "When Flashing Finishes",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.bodySp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OneUiTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                OneUiInfoButton(
                    onClick = { bounds -> onInfoClick(FeatureInfoKey.ADVANCED_REBOOT_AFTER_FLASH, bounds) },
                    badgeBg = OneUiInnerTile
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OneUiRebootOptionGrid(
                rebootAfterFlash = rebootAfterFlash,
                postFlashRebootTarget = postFlashRebootTarget,
                onSelect = onRebootOptionSelected
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

            OneUiSelectionPreviewPill(
                text = "Selected right now: $slotSummary • $rebootSummary",
                accent = OneUiEmerald,
                surfaceBg = OneUiActiveSurface
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                    .clip(CircleShape)
                    .background(OneUiEmerald)
                    .oneUiBounceClick(pressedScale = 0.97f) { dismiss {} },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Continue",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.bodySp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OneUiEmeraldDeep
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = OneUiEmeraldDeep,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AnchoredWipeSelectionMenu(
    wipeDataEnabled: Boolean,
    originBounds: Rect?,
    onDismiss: () -> Unit,
    onInfoClick: (Rect?) -> Unit,
    onSelect: (Boolean) -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    AnchoredDialog(
        onDismissRequest = onDismiss,
        originBounds = originBounds,
        scrimColor = Color(0xC8000000),
        horizontalPadding = if (metrics.isCompactWidth) 12.dp else 16.dp,
        verticalPadding = if (metrics.isShortHeight) 14.dp else 24.dp
    ) { dismiss ->
        OneUiCard(
            modifier = Modifier
                .widthIn(max = metrics.maxDialogWidthDp.dp)
                .fillMaxWidth()
                .heightIn(max = metrics.maxMenuHeightDp.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape((metrics.cardCornerRadiusDp + 2f).coerceAtLeast(24f).dp),
            containerColor = OneUiCardSurfaceAlt,
            contentPadding = PaddingValues(metrics.cardPaddingDp.dp),
            animateSize = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(metrics.badgeMediumSizeDp.dp)
                            .clip(CircleShape)
                            .background(OneUiDangerBadge),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = OneUiDangerRed,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Erase Old Apps & Data?",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.cardTitleSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Pick Yes for Clean Flash or No for Dirty Flash",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = OneUiTextSecondary,
                            fontSize = metrics.captionSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OneUiInfoButton(
                        onClick = onInfoClick,
                        tint = OneUiDangerRed,
                        badgeBg = OneUiDangerBadge
                    )
                    OneUiMenuCloseButton { dismiss {} }
                }
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

            OneUiNoteBanner(
                text = "Note: Switching to a different ROM usually needs Clean Flash. Updating the same ROM works with Dirty Flash.",
                accent = OneUiDangerRed,
                surfaceBg = OneUiDangerSurface
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
            ) {
                OneUiChoiceGridCard(
                    title = "Yes • Clean Flash",
                    subtitle = "Erases all apps, accounts, photos & internal files",
                    tagLabel = "New ROM",
                    icon = Icons.Default.DeleteSweep,
                    accent = OneUiDangerRed,
                    iconBg = OneUiDangerBadge,
                    surfaceBg = if (wipeDataEnabled) OneUiDangerActiveSurface else OneUiDangerSurface,
                    isSelected = wipeDataEnabled,
                    onClick = { onSelect(true) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                OneUiChoiceGridCard(
                    title = "No • Dirty Flash",
                    subtitle = "Keeps existing apps, photos & user logins safe",
                    tagLabel = "Same ROM Update",
                    icon = Icons.Default.Save,
                    accent = OneUiEmerald,
                    iconBg = OneUiActiveSurface,
                    surfaceBg = if (!wipeDataEnabled) OneUiActiveSurface else OneUiInnerTile,
                    isSelected = !wipeDataEnabled,
                    onClick = { onSelect(false) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

            OneUiSelectionPreviewPill(
                text = if (wipeDataEnabled) {
                    "Selected right now: Yes • Clean Flash (Erases data)"
                } else {
                    "Selected right now: No • Dirty Flash (Keeps data)"
                },
                accent = if (wipeDataEnabled) OneUiDangerRed else OneUiEmerald,
                surfaceBg = if (wipeDataEnabled) OneUiDangerSurface else OneUiActiveSurface
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                    .clip(CircleShape)
                    .background(OneUiEmerald)
                    .oneUiBounceClick(pressedScale = 0.97f) { dismiss {} },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Continue",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.bodySp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OneUiEmeraldDeep
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = OneUiEmeraldDeep,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AnchoredCustomPartitionsMenu(
    overrides: List<PartitionFileOverride>,
    formatBytes: (Long) -> String,
    originBounds: Rect?,
    onDismiss: () -> Unit,
    onInfoClick: (Rect?) -> Unit,
    onAddFiles: () -> Unit,
    onRemapOverride: (PartitionFileOverride, Rect?) -> Unit,
    onRemoveOverride: (String) -> Unit,
    onUseDefault: () -> Unit,
    onConfirmDone: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    AnchoredDialog(
        onDismissRequest = onDismiss,
        originBounds = originBounds,
        scrimColor = Color(0xC8000000),
        horizontalPadding = if (metrics.isCompactWidth) 12.dp else 16.dp,
        verticalPadding = if (metrics.isShortHeight) 12.dp else 22.dp
    ) { dismiss ->
        OneUiCard(
            modifier = Modifier
                .widthIn(max = metrics.maxDialogWidthDp.dp)
                .fillMaxWidth()
                .heightIn(max = metrics.maxMenuHeightDp.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape((metrics.cardCornerRadiusDp + 2f).coerceAtLeast(24f).dp),
            containerColor = OneUiCardSurfaceAlt,
            contentPadding = PaddingValues(metrics.cardPaddingDp.dp),
            animateSize = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(metrics.badgeMediumSizeDp.dp)
                            .clip(CircleShape)
                            .background(OneUiActiveSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Extra .img Files",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.cardTitleSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (overrides.isEmpty()) "Optional: swap or add files" else "${overrides.size} extra file(s) attached",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = OneUiMint,
                            fontSize = metrics.captionSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OneUiInfoButton(onClick = onInfoClick, badgeBg = OneUiInnerTile)
                    OneUiMenuCloseButton { dismiss {} }
                }
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

            OneUiNoteBanner(
                text = "Note: Optional — you can attach super_empty.img, or a Magisk / KernelSU patched boot.img or init_boot.img here.",
                accent = OneUiAmber,
                surfaceBg = OneUiAmberSurface
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                    .clip(CircleShape)
                    .background(OneUiEmerald)
                    .oneUiBounceClick(pressedScale = 0.97f, onClick = onAddFiles),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = null,
                        tint = OneUiEmeraldDeep,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "+ Pick .img Files",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.bodySp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiEmeraldDeep,
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

            if (overrides.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                        .background(OneUiInnerTile)
                        .padding(horizontal = metrics.cardPaddingDp.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = null,
                        tint = OneUiTextMuted,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No extra files added yet",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.bodySp.sp,
                        fontWeight = FontWeight.Bold,
                        color = OneUiTextSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Tap + Pick .img Files above, or tap Keep Default below",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.captionSp.sp,
                        fontWeight = FontWeight.Medium,
                        color = OneUiTextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    overrides.forEach { ov ->
                        val chipAnchor = rememberAnchorBounds()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape((metrics.innerCornerRadiusDp - 2f).coerceAtLeast(14f).dp))
                                .background(OneUiInnerTile)
                                .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ov.fileName,
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.subtitleSp.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatBytes(ov.fileSize),
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.microSp.sp,
                                    color = OneUiTextMuted
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Row(
                                modifier = Modifier
                                    .trackAnchorBounds(chipAnchor)
                                    .clip(CircleShape)
                                    .background(OneUiActiveSurface)
                                    .oneUiBounceClick(pressedScale = 0.94f) {
                                        onRemapOverride(ov, chipAnchor.bounds)
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = ov.targetStem,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = metrics.microSp.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiEmerald,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Choose target partition",
                                    tint = OneUiEmerald,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(OneUiDangerBadge)
                                    .oneUiBounceClick(pressedScale = 0.88f) {
                                        onRemoveOverride(ov.id)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove file",
                                    tint = OneUiDangerRed,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

            OneUiSelectionPreviewPill(
                text = if (overrides.isEmpty()) {
                    "Selected right now: Keep Default (Standard ROM files)"
                } else {
                    "Selected right now: Yes • ${overrides.size} extra file(s) attached"
                },
                accent = OneUiEmerald,
                surfaceBg = OneUiActiveSurface
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                        .clip(CircleShape)
                        .background(OneUiInnerTile)
                        .oneUiBounceClick(pressedScale = 0.96f) {
                            dismiss { onUseDefault() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Keep Default",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.subtitleSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = OneUiTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1.25f)
                        .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                        .clip(CircleShape)
                        .background(OneUiEmerald)
                        .oneUiBounceClick(pressedScale = 0.96f) {
                            dismiss { onConfirmDone() }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Continue",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.bodySp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiEmeraldDeep,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = OneUiEmeraldDeep,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnchoredVbmetaSelectionMenu(
    disableVbmeta: Boolean,
    hasVbmetaPartition: Boolean,
    originBounds: Rect?,
    onDismiss: () -> Unit,
    onInfoClick: (Rect?) -> Unit,
    onSelect: (Boolean) -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    AnchoredDialog(
        onDismissRequest = onDismiss,
        originBounds = originBounds,
        scrimColor = Color(0xC8000000),
        horizontalPadding = if (metrics.isCompactWidth) 12.dp else 16.dp,
        verticalPadding = if (metrics.isShortHeight) 14.dp else 24.dp
    ) { dismiss ->
        OneUiCard(
            modifier = Modifier
                .widthIn(max = metrics.maxDialogWidthDp.dp)
                .fillMaxWidth()
                .heightIn(max = metrics.maxMenuHeightDp.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape((metrics.cardCornerRadiusDp + 2f).coerceAtLeast(24f).dp),
            containerColor = OneUiCardSurfaceAlt,
            contentPadding = PaddingValues(metrics.cardPaddingDp.dp),
            animateSize = false
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(metrics.badgeMediumSizeDp.dp)
                            .clip(CircleShape)
                            .background(OneUiAmberSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = OneUiAmber,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "dm-verity • Disable Verification",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.cardTitleSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Choose Yes for Port ROMs or Keep Default",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = OneUiTextSecondary,
                            fontSize = metrics.captionSp.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OneUiInfoButton(
                        onClick = onInfoClick,
                        tint = OneUiAmber,
                        badgeBg = OneUiAmberSurface
                    )
                    OneUiMenuCloseButton { dismiss {} }
                }
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 9.dp else 12.dp))

            OneUiNoteBanner(
                text = if (hasVbmetaPartition) {
                    "Note: Pick Yes only for Port ROMs or GSIs so your phone can boot. Pick Keep Default for official ROMs."
                } else {
                    "Note: For Port ROMs or GSIs (disables verification automatically whenever vbmeta files exist)."
                },
                accent = OneUiAmber,
                surfaceBg = OneUiAmberSurface
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 8.dp else 10.dp)
            ) {
                OneUiChoiceGridCard(
                    title = "Yes",
                    subtitle = "Disables dm-verity & vbmeta verification for ports",
                    tagLabel = "Disable Verification",
                    icon = Icons.Default.Security,
                    accent = OneUiAmber,
                    iconBg = OneUiAmberSurface,
                    surfaceBg = if (disableVbmeta) OneUiAmberSurface else OneUiInnerTile,
                    isSelected = disableVbmeta,
                    onClick = { onSelect(true) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                OneUiChoiceGridCard(
                    title = "Keep Default",
                    subtitle = "Keeps normal official dm-verity verification active",
                    tagLabel = "Official ROMs",
                    icon = Icons.Default.VerifiedUser,
                    accent = OneUiEmerald,
                    iconBg = OneUiActiveSurface,
                    surfaceBg = if (!disableVbmeta) OneUiActiveSurface else OneUiInnerTile,
                    isSelected = !disableVbmeta,
                    onClick = { onSelect(false) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

            OneUiSelectionPreviewPill(
                text = if (disableVbmeta) {
                    "Selected right now: Disable Verification • Yes"
                } else {
                    "Selected right now: dm-verity Verification • Keep Default"
                },
                accent = if (disableVbmeta) OneUiAmber else OneUiEmerald,
                surfaceBg = if (disableVbmeta) OneUiAmberSurface else OneUiActiveSurface
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                    .clip(CircleShape)
                    .background(OneUiEmerald)
                    .oneUiBounceClick(pressedScale = 0.97f) { dismiss {} },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Continue",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.bodySp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OneUiEmeraldDeep
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = OneUiEmeraldDeep,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun OneUiStepBadge(text: String) {
    val metrics = LocalLeafUiMetrics.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(OneUiActiveSurface)
            .padding(horizontal = if (metrics.isCompactWidth) 8.dp else 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.microSp.sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiEmerald,
            letterSpacing = if (metrics.isNarrowOrZoomed) 0.1.sp else 0.4.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OneUiBackChip(onClick: () -> Unit) {
    val metrics = LocalLeafUiMetrics.current
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(OneUiInnerTile)
            .oneUiBounceClick(pressedScale = 0.92f, onClick = onClick)
            .padding(horizontal = if (metrics.isCompactWidth) 8.dp else 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = OneUiTextSecondary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(3.dp))
        Text(
            text = "Back",
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.captionSp.sp,
            fontWeight = FontWeight.Bold,
            color = OneUiTextSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun OneUiMenuCloseButton(onClick: () -> Unit) {
    val metrics = LocalLeafUiMetrics.current
    val btnSize = (metrics.badgeSmallSizeDp - 2f).coerceAtLeast(28f).dp
    Box(
        modifier = Modifier
            .size(btnSize)
            .clip(CircleShape)
            .background(OneUiInnerTile)
            .oneUiBounceClick(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close",
            tint = OneUiTextSecondary,
            modifier = Modifier.size((btnSize * 0.50f))
        )
    }
}

@Composable
private fun OneUiNoteBanner(
    text: String,
    accent: Color,
    surfaceBg: Color
) {
    val metrics = LocalLeafUiMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
            .background(surfaceBg)
            .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = if (metrics.isShortHeight) 8.dp else 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            fontWeight = FontWeight.SemiBold,
            color = OneUiTextPrimary.copy(alpha = 0.92f),
            lineHeight = (metrics.subtitleSp + 4.5f).sp
        )
    }
}

@Composable
private fun OneUiChoiceGridCard(
    title: String,
    subtitle: String,
    tagLabel: String,
    icon: ImageVector,
    accent: Color,
    iconBg: Color,
    surfaceBg: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val bgColor by animateColorAsState(
        targetValue = surfaceBg,
        animationSpec = tween(180),
        label = "choiceGridBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) accent.copy(alpha = 0.65f) else Color.Transparent,
        animationSpec = tween(180),
        label = "choiceGridBorder"
    )
    val cardShape = RoundedCornerShape(metrics.innerCornerRadiusDp.dp)
    val badgeSize = metrics.badgeMediumSizeDp.dp
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = metrics.choiceCardMinHeightDp.dp)
            .clip(cardShape)
            .background(bgColor)
            .then(
                if (isSelected) {
                    Modifier.border(1.3.dp, borderColor, cardShape)
                } else {
                    Modifier
                }
            )
            .oneUiBounceClick(pressedScale = 0.965f, onClick = onClick)
            .padding(metrics.innerCardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                )
            }
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else null,
                tint = if (isSelected) accent else OneUiTextMuted.copy(alpha = 0.45f),
                modifier = Modifier.size(if (metrics.isCompactWidth) 17.dp else 19.dp)
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 12.dp))

        Text(
            text = title,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.bodySp.sp,
            lineHeight = (metrics.bodySp + 4f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = OneUiTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subtitle,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.captionSp.sp,
            fontWeight = FontWeight.Medium,
            color = OneUiTextSecondary,
            lineHeight = (metrics.captionSp + 4f).sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(iconBg)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = tagLabel,
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.microSp.sp,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun OneUiSummaryGridTile(
    title: String,
    valueLabel: String,
    icon: ImageVector,
    accent: Color,
    badgeBg: Color,
    cardBg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val badgeSize = metrics.badgeSmallSizeDp.dp
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = (102f * metrics.spacingScale).dp)
            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
            .background(cardBg)
            .oneUiBounceClick(pressedScale = 0.965f, onClick = onClick)
            .padding(metrics.innerCardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size((metrics.badgeSmallSizeDp * 0.50f).dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Edit $title",
                tint = OneUiTextMuted,
                modifier = Modifier.size(17.dp)
            )
        }

        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 8.dp else 10.dp))

        Text(
            text = title,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            fontWeight = FontWeight.Bold,
            color = OneUiTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = valueLabel,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.bodySp.sp,
            lineHeight = (metrics.bodySp + 4f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OneUiSelectionPreviewPill(
    text: String,
    accent: Color,
    surfaceBg: Color
) {
    val metrics = LocalLeafUiMetrics.current
    val bgColor by animateColorAsState(
        targetValue = surfaceBg,
        animationSpec = tween(180),
        label = "selectionPreviewBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(bgColor)
            .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = text,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.captionSp.sp,
            fontWeight = FontWeight.Bold,
            color = OneUiTextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun OneUiScriptHeroCta(
    isContinueMode: Boolean,
    isDeviceReady: Boolean,
    isButtonEnabled: Boolean,
    onClick: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val isSolidActive = isContinueMode || isButtonEnabled
    val pillColor by animateColorAsState(
        targetValue = if (isSolidActive) OneUiEmerald else OneUiCardSurface,
        animationSpec = tween(180),
        label = "oneUiHeroCtaBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSolidActive) OneUiEmeraldDeep else OneUiTextSecondary,
        animationSpec = tween(180),
        label = "oneUiHeroCtaFg"
    )
    val badgeBg = if (isSolidActive) OneUiEmeraldDeep.copy(alpha = 0.14f) else OneUiInnerTile

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = metrics.heroCtaMinHeightDp.dp)
            .clip(CircleShape)
            .background(pillColor)
            .oneUiBounceClick(enabled = isButtonEnabled, pressedScale = 0.965f) {
                if (isButtonEnabled) onClick()
            }
            .padding(horizontal = metrics.cardPaddingDp.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(metrics.badgeMediumSizeDp.dp)
                    .clip(CircleShape)
                    .background(badgeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isContinueMode -> Icons.AutoMirrored.Filled.ArrowForward
                        !isDeviceReady -> Icons.Default.Usb
                        else -> Icons.Default.Bolt
                    },
                    contentDescription = null,
                    tint = if (isSolidActive) OneUiEmeraldDeep else OneUiMint,
                    modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                )
            }
            Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 10.dp else 12.dp))
            val heroLabel = when {
                isContinueMode -> "Continue"
                !isDeviceReady -> if (metrics.isNarrowOrZoomed) "Connect Fastboot" else "Plug In Phone (Fastboot)"
                !isButtonEnabled -> "No Flash Files Ready"
                else -> "Start Flashing"
            }
            AnimatedContent(
                targetState = heroLabel,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn(initialScale = 0.96f, animationSpec = tween(160)))
                        .togetherWith(fadeOut(tween(110)))
                },
                label = "heroCtaLabel"
            ) { labelText ->
                Text(
                    text = labelText,
                    fontFamily = IosRoundedFontFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = metrics.heroLabelSp.sp,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Start,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = if (isSolidActive) OneUiEmeraldDeep else OneUiTextMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}
