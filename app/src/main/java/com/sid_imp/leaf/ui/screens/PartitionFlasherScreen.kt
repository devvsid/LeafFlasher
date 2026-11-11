package com.sid_imp.leaf.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.fastboot.FastbootMode
import com.sid_imp.leaf.ui.FeatureInfoKey
import com.sid_imp.leaf.ui.MainViewModel
import com.sid_imp.leaf.ui.components.*
import com.sid_imp.leaf.ui.theme.*
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
fun PartitionFlasherScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val partitionName by viewModel.singlePartitionName.collectAsState()
    val selectedFileUri by viewModel.singleSelectedFileUri.collectAsState()
    val selectedFileName by viewModel.singleSelectedFileName.collectAsState()
    val selectedFileSize by viewModel.singleSelectedFileSize.collectAsState()
    val disableVerity by viewModel.singleDisableVerity.collectAsState()

    val fastbootMode by viewModel.fastbootMode.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val isFlashing by viewModel.isSinglePartitionFlashing.collectAsState()
    val isLogExpanded by viewModel.isLogExpanded.collectAsState()

    var showPartitionPicker by remember { mutableStateOf(false) }
    var partitionPickerOrigin by remember { mutableStateOf<Rect?>(null) }
    var activeFeatureInfo by remember { mutableStateOf<Pair<FeatureInfoKey, Rect?>?>(null) }

    val changePartitionAnchor = rememberAnchorBounds()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.setSingleSelectedFile(it) }
    }

    val isVbmeta = partitionName.lowercase().startsWith("vbmeta")
    val isDeviceReady = fastbootMode == FastbootMode.BOOTLOADER || fastbootMode == FastbootMode.FASTBOOTD
    val slotLabel = when {
        partitionName.equals("super_empty", ignoreCase = true) -> "Wipe Super"
        partitionName.equals("super", ignoreCase = true) -> "Direct Super"
        else -> {
            val s = deviceInfo?.currentSlot?.takeIf { it.equals("a", true) || it.equals("b", true) }?.uppercase()
            if (s != null) "Active Slot $s" else "Current Active Slot"
        }
    }

    val metrics = LocalLeafUiMetrics.current

    // Auto-dismiss popups when flashing begins for Pure Focus Mode
    LaunchedEffect(isFlashing) {
        if (isFlashing) {
            showPartitionPicker = false
            activeFeatureInfo = null
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val availableHeight = maxHeight
        val useFullScreenFocus = isFlashing && availableHeight >= 340.dp
        val expandConsoleFull = useFullScreenFocus && isLogExpanded
        val sectionGap = metrics.sectionGapDp.dp
        val maxColWidth = if (isFlashing) metrics.maxFocusContentWidthDp.dp else metrics.maxContentWidthDp.dp

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

            // 1. Single Partition Setup + Hero CTA (hidden while flashing for full-screen focus)
            OneUiAnimatedSection(visible = !isFlashing) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OneUiCard(
                        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
                    ) {
                        val cardShape = RoundedCornerShape(metrics.innerCornerRadiusDp.dp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = null,
                                    tint = OneUiEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Flash One Image",
                                    fontFamily = IosRoundedFontFamily,
                                    color = OneUiTextPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = metrics.cardTitleSp.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OneUiInfoButton(
                                onClick = { bounds -> activeFeatureInfo = FeatureInfoKey.SINGLE_PARTITION to bounds }
                            )
                        }

                        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

                        // Animated File Selector Tile
                        val fileTileBg by animateColorAsState(
                            targetValue = if (selectedFileUri != null) OneUiActiveSurface else OneUiInnerTile,
                            animationSpec = tween(180),
                            label = "singleFileTileBg"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(fileTileBg)
                                .oneUiBounceClick(enabled = !isFlashing, pressedScale = 0.975f) {
                                    filePickerLauncher.launch(arrayOf("*/*", "application/octet-stream"))
                                }
                                .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 12.dp),
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
                                        .background(if (selectedFileUri != null) OneUiEmerald else OneUiCardSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (selectedFileUri != null) Icons.Default.FileOpen else Icons.Default.FolderOpen,
                                        contentDescription = "Choose Image",
                                        tint = if (selectedFileUri != null) OneUiEmeraldDeep else OneUiMint,
                                        modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.48f).dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 10.dp else 12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (selectedFileName.isNotEmpty()) selectedFileName else "Pick Image File (.img)",
                                        fontFamily = IosRoundedFontFamily,
                                        fontSize = metrics.bodySp.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = OneUiTextPrimary,
                                        maxLines = if (metrics.isNarrowOrZoomed) 2 else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (selectedFileSize > 0L) {
                                            "${viewModel.formatBytes(selectedFileSize)} • Tap to change file"
                                        } else {
                                            "For root boot.img, init_boot, recovery..."
                                        },
                                        fontFamily = IosRoundedFontFamily,
                                        fontSize = metrics.captionSp.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selectedFileSize > 0L) OneUiMint else OneUiTextMuted,
                                        maxLines = if (metrics.isNarrowOrZoomed) 2 else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = OneUiTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Target Partition Row
                        OneUiAnimatedSection(visible = selectedFileUri != null && partitionName.isNotBlank()) {
                            Column {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(cardShape)
                                        .background(OneUiInnerTile)
                                        .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Target: ",
                                                fontFamily = IosRoundedFontFamily,
                                                fontSize = metrics.subtitleSp.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = OneUiTextSecondary
                                            )
                                            Text(
                                                text = partitionName,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = metrics.bodySp.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = OneUiEmerald,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = slotLabel,
                                            fontFamily = IosRoundedFontFamily,
                                            fontSize = metrics.captionSp.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = OneUiMint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .trackAnchorBounds(changePartitionAnchor)
                                            .clip(CircleShape)
                                            .background(OneUiEmerald)
                                            .oneUiBounceClick(pressedScale = 0.93f) {
                                                partitionPickerOrigin = changePartitionAnchor.bounds
                                                showPartitionPicker = true
                                            }
                                            .padding(
                                                horizontal = if (metrics.isCompactWidth) 10.dp else 12.dp,
                                                vertical = 6.dp
                                            )
                                    ) {
                                        Text(
                                            text = "Change",
                                            fontFamily = IosRoundedFontFamily,
                                            fontSize = metrics.captionSp.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = OneUiEmeraldDeep,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }

                        // Disable AVB Switch (only when vbmeta target selected)
                        OneUiAnimatedSection(visible = isVbmeta) {
                            Column {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(cardShape)
                                        .background(OneUiInnerTile)
                                        .oneUiBounceClick(enabled = !isFlashing, pressedScale = 0.985f) {
                                            viewModel.setSingleDisableVerity(!disableVerity)
                                        }
                                        .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = if (disableVerity) OneUiDangerRed else OneUiMint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Disable AVB",
                                            modifier = Modifier.weight(1f, fill = false),
                                            fontFamily = IosRoundedFontFamily,
                                            fontSize = metrics.bodySp.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = OneUiTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        OneUiInfoButton(
                                            onClick = { bounds -> activeFeatureInfo = FeatureInfoKey.DISABLE_AVB to bounds },
                                            badgeBg = OneUiCardSurface
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    OneUiSwitch(
                                        checked = disableVerity,
                                        activeTrackColor = OneUiEmerald,
                                        inactiveTrackColor = OneUiCardSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(sectionGap))

                    // 2. Hero Flash Pill CTA
                    val canFlash = selectedFileUri != null && partitionName.isNotBlank() && isDeviceReady && !isFlashing
                    OneUiSinglePartitionHeroButton(
                        canFlash = canFlash,
                        isFlashing = isFlashing,
                        hasFileSelected = selectedFileUri != null,
                        isDeviceReady = isDeviceReady,
                        progressPercentFlow = viewModel.singlePartitionProgressPercent,
                        onClick = { viewModel.flashSinglePartition() }
                    )

                    Spacer(modifier = Modifier.height(sectionGap))
                }
            }

            // 3. Dedicated Single-Partition Progress Card (shown prominently when flashing)
            OneUiAnimatedSection(visible = isFlashing) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OneUiSinglePartitionProgressCard(
                        partitionName = partitionName,
                        selectedFileName = selectedFileName,
                        progressPercentFlow = viewModel.singlePartitionProgressPercent,
                        statusTextFlow = viewModel.singlePartitionStatusText,
                        speedMbFlow = viewModel.singlePartitionSpeedMb
                    )
                    Spacer(modifier = Modifier.height(sectionGap))
                }
            }

            // 4. Full-Viewport Leaves Console Log Card during active flash
            LeavesConsoleCard(
                logsFlow = viewModel.logs,
                isExpanded = isLogExpanded,
                onToggleExpanded = { viewModel.toggleLogExpanded() },
                onClearLogs = { viewModel.clearLogs() },
                consoleHeight = if (useFullScreenFocus) {
                    (availableHeight * 0.50f).coerceIn(140.dp, 380.dp)
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

    if (showPartitionPicker) {
        PartitionTargetPickerDialog(
            title = "Choose Partition",
            currentTarget = partitionName,
            partitions = viewModel.getSuggestedPartitionTargets(),
            originBounds = partitionPickerOrigin,
            onDismiss = { showPartitionPicker = false },
            onSelectPartition = { picked ->
                viewModel.setSinglePartitionTarget(picked)
            }
        )
    }

    activeFeatureInfo?.let { (infoKey, origin) ->
        OneUiFeatureInfoDialog(
            featureKey = infoKey,
            originBounds = origin,
            onDismiss = { activeFeatureInfo = null }
        )
    }
}

@Composable
private fun OneUiSinglePartitionHeroButton(
    canFlash: Boolean,
    isFlashing: Boolean,
    hasFileSelected: Boolean,
    isDeviceReady: Boolean,
    progressPercentFlow: StateFlow<Int>,
    onClick: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val flashProgressPercent = if (isFlashing) {
        progressPercentFlow.collectAsState().value
    } else {
        0
    }
    val isSolidActive = canFlash || isFlashing
    val pillColor by animateColorAsState(
        targetValue = if (isSolidActive) OneUiEmerald else OneUiCardSurface,
        animationSpec = tween(180),
        label = "oneUiSingleHeroBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSolidActive) OneUiEmeraldDeep else OneUiTextSecondary,
        animationSpec = tween(180),
        label = "oneUiSingleHeroFg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = metrics.heroCtaMinHeightDp.dp)
            .clip(CircleShape)
            .background(pillColor)
            .oneUiBounceClick(enabled = canFlash, pressedScale = 0.965f) {
                if (canFlash) onClick()
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
                    .background(if (isSolidActive) OneUiEmeraldDeep.copy(alpha = 0.14f) else OneUiInnerTile),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = if (isSolidActive) OneUiEmeraldDeep else OneUiMint,
                    modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                )
            }
            Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 10.dp else 12.dp))
            val heroLabel = when {
                isFlashing -> "Flashing ($flashProgressPercent%)"
                !hasFileSelected -> "Select Image First"
                !isDeviceReady -> "Connect Fastboot"
                else -> "Flash Partition"
            }
            Text(
                text = heroLabel,
                fontFamily = IosRoundedFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = metrics.heroLabelSp.sp,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
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

@Composable
private fun OneUiSinglePartitionProgressCard(
    partitionName: String,
    selectedFileName: String,
    progressPercentFlow: StateFlow<Int>,
    statusTextFlow: StateFlow<String>,
    speedMbFlow: StateFlow<Double>
) {
    val metrics = LocalLeafUiMetrics.current
    val flashProgressPercent by progressPercentFlow.collectAsState()
    val flashStatusText by statusTextFlow.collectAsState()
    val transferSpeedMb by speedMbFlow.collectAsState()

    val animatedSingleProgress by animateFloatAsState(
        targetValue = (flashProgressPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "oneUiSingleProgress"
    )
    val pulseTransition = rememberInfiniteTransition(label = "singleFlashPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "singleFlashDotAlpha"
    )

    OneUiCard(
        containerColor = OneUiActiveSurface,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(metrics.badgeMediumSizeDp.dp)
                        .clip(CircleShape)
                        .background(OneUiEmerald),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = OneUiEmeraldDeep,
                        modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (partitionName.isNotBlank()) "Flashing $partitionName" else "Flashing Partition",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.cardTitleSp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selectedFileName.isNotBlank()) {
                        Text(
                            text = selectedFileName,
                            fontFamily = IosRoundedFontFamily,
                            fontSize = metrics.captionSp.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OneUiMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(OneUiCardSurface)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .graphicsLayer { alpha = pulseAlpha }
                        .clip(CircleShape)
                        .background(OneUiEmerald)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$flashProgressPercent%",
                    fontFamily = FontFamily.Monospace,
                    fontSize = metrics.subtitleSp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OneUiEmerald
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { animatedSingleProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = OneUiEmerald,
            trackColor = OneUiTrack
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = flashStatusText.ifBlank { "Writing partition image..." },
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.captionSp.sp,
                color = OneUiMint,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (transferSpeedMb > 0.0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, "%.2f MB/s", transferSpeedMb),
                    fontSize = metrics.captionSp.sp,
                    color = OneUiTextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
