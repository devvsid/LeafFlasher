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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.fastboot.AdbSideloadProgress
import com.sid_imp.leaf.fastboot.AdbSideloadState
import com.sid_imp.leaf.fastboot.FastbootMode
import com.sid_imp.leaf.ui.FeatureInfoKey
import com.sid_imp.leaf.ui.MainViewModel
import com.sid_imp.leaf.ui.components.LeavesConsoleCard
import com.sid_imp.leaf.ui.components.OneUiAnimatedSection
import com.sid_imp.leaf.ui.components.OneUiCard
import com.sid_imp.leaf.ui.components.OneUiFeatureInfoDialog
import com.sid_imp.leaf.ui.components.OneUiInfoButton
import com.sid_imp.leaf.ui.components.oneUiBounceClick
import com.sid_imp.leaf.ui.theme.*
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
fun AdbSideloadScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val zipUri by viewModel.sideloadZipUri.collectAsState()
    val zipName by viewModel.sideloadZipName.collectAsState()
    val zipSize by viewModel.sideloadZipSize.collectAsState()
    val isSideloading by viewModel.isSideloading.collectAsState()
    val fastbootMode by viewModel.fastbootMode.collectAsState()
    val isLogExpanded by viewModel.isLogExpanded.collectAsState()
    val sideloadProgress by viewModel.sideloadProgress.collectAsState()

    var showSideloadInfo by remember { mutableStateOf(false) }
    var sideloadInfoOrigin by remember { mutableStateOf<Rect?>(null) }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.setSideloadZip(it) }
    }

    val isSideloadReady = fastbootMode == FastbootMode.ADB_SIDELOAD
    val canStartSideload = zipUri != null && isSideloadReady && !isSideloading
    val canSwitchMode = (fastbootMode.isAdbConnected || fastbootMode.isBootloaderOrFastbootd) && !isSideloading
    val metrics = LocalLeafUiMetrics.current

    // Dismiss info dialog when sideload begins for Pure Focus Mode
    LaunchedEffect(isSideloading) {
        if (isSideloading) {
            showSideloadInfo = false
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        val availableHeight = maxHeight
        val useFullScreenFocus = isSideloading && availableHeight >= 360.dp
        val expandConsoleFull = useFullScreenFocus && isLogExpanded
        val sectionGap = metrics.sectionGapDp.dp
        val maxColWidth = if (isSideloading) metrics.maxFocusContentWidthDp.dp else metrics.maxContentWidthDp.dp

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

            // 1 + 2. Borderless OneUI Sideload Package Card + Hero CTA (hidden while sideloading)
            OneUiAnimatedSection(visible = !isSideloading) {
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
                                    imageVector = Icons.Default.SystemUpdate,
                                    contentDescription = null,
                                    tint = OneUiCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ADB Sideload",
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
                                onClick = { bounds ->
                                    sideloadInfoOrigin = bounds
                                    showSideloadInfo = true
                                },
                                tint = OneUiCyan,
                                badgeBg = OneUiCyanSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

                        val packageBg by animateColorAsState(
                            targetValue = if (zipUri != null) OneUiCyanSurface else OneUiInnerTile,
                            animationSpec = tween(180),
                            label = "sideloadPkgBg"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(packageBg)
                                .oneUiBounceClick(enabled = !isSideloading, pressedScale = 0.975f) {
                                    zipPickerLauncher.launch(
                                        arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*")
                                    )
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
                                        .background(if (zipUri != null) OneUiCyan else OneUiCardSurface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (zipUri != null) Icons.Default.FolderZip else Icons.Default.AddCircleOutline,
                                        contentDescription = "Choose Sideload ZIP",
                                        tint = if (zipUri != null) OneUiCyanDeep else OneUiCyan,
                                        modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 10.dp else 12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (zipName.isNotEmpty()) zipName else "Choose ZIP Package",
                                        fontFamily = IosRoundedFontFamily,
                                        fontSize = metrics.bodySp.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = OneUiTextPrimary,
                                        maxLines = if (metrics.isNarrowOrZoomed) 2 else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (zipSize > 0L) {
                                            "${viewModel.formatBytes(zipSize)} • Tap to change ZIP"
                                        } else {
                                            "GApps, Magisk, OTA updates & ZIPs"
                                        },
                                        fontFamily = IosRoundedFontFamily,
                                        fontSize = metrics.captionSp.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (zipSize > 0L) OneUiCyan else OneUiTextMuted,
                                        maxLines = if (metrics.isNarrowOrZoomed) 2 else 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (zipUri != null && !isSideloading) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(OneUiDangerBadge)
                                        .oneUiBounceClick(pressedScale = 0.93f) { viewModel.clearSideloadZip() }
                                        .padding(horizontal = if (metrics.isCompactWidth) 9.dp else 11.dp, vertical = 6.dp)
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
                            } else {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = OneUiTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(sectionGap))

                    OneUiSideloadHeroCta(
                        canStart = canStartSideload,
                        isSideloading = isSideloading,
                        hasZipSelected = zipUri != null,
                        isSideloadReady = isSideloadReady,
                        progressFlow = viewModel.sideloadProgress,
                        onClick = { viewModel.startAdbSideload() }
                    )
                }
            }

            // 3. Live Sideload Progress Card (focal card during active sideload)
            OneUiAnimatedSection(visible = sideloadProgress.state != AdbSideloadState.IDLE) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (!isSideloading) {
                        Spacer(modifier = Modifier.height(sectionGap))
                    }
                    OneUiSideloadProgressSection(
                        progress = sideloadProgress,
                        fallbackFileName = zipName,
                        formatBytes = viewModel::formatBytes,
                        onCancel = { viewModel.cancelAdbSideload() }
                    )
                }
            }

            // 4. Streamlined Balanced Quick Device Actions (hidden while sideloading)
            OneUiAnimatedSection(visible = !isSideloading) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(sectionGap))

                    OneUiCard(
                        contentPadding = PaddingValues(metrics.innerCardPaddingDp.dp.coerceAtLeast(12.dp))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PhonelinkSetup,
                                contentDescription = null,
                                tint = OneUiCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Quick Recovery Actions",
                                fontFamily = IosRoundedFontFamily,
                                color = OneUiTextPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = metrics.cardTitleSp.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (fastbootMode != FastbootMode.ADB_SIDELOAD) {
                                OneUiActionPillButton(
                                    title = "Enter Sideload",
                                    icon = Icons.Default.SystemUpdate,
                                    enabled = canSwitchMode,
                                    accentTint = OneUiCyan,
                                    onClick = { viewModel.enterAdbSideloadMode() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            } else {
                                OneUiActionPillButton(
                                    title = "Reboot Recovery",
                                    icon = Icons.Default.RestartAlt,
                                    enabled = canSwitchMode,
                                    accentTint = OneUiCyan,
                                    onClick = { viewModel.rebootDevice("recovery") },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }

                            OneUiActionPillButton(
                                title = "Reboot System",
                                icon = Icons.Default.PowerSettingsNew,
                                enabled = canSwitchMode,
                                accentTint = OneUiEmerald,
                                onClick = { viewModel.rebootDevice("") },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(sectionGap))

            // 5. Full-Viewport Leaves Console Card while sideloading
            LeavesConsoleCard(
                logsFlow = viewModel.logs,
                isExpanded = isLogExpanded,
                onToggleExpanded = { viewModel.toggleLogExpanded() },
                onClearLogs = { viewModel.clearLogs() },
                consoleHeight = if (useFullScreenFocus) {
                    (availableHeight * 0.45f).coerceIn(140.dp, 360.dp)
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

    if (showSideloadInfo) {
        OneUiFeatureInfoDialog(
            featureKey = FeatureInfoKey.ADB_SIDELOAD,
            originBounds = sideloadInfoOrigin,
            onDismiss = { showSideloadInfo = false }
        )
    }
}

@Composable
private fun OneUiSideloadHeroCta(
    canStart: Boolean,
    isSideloading: Boolean,
    hasZipSelected: Boolean,
    isSideloadReady: Boolean,
    progressFlow: StateFlow<AdbSideloadProgress>,
    onClick: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val progress = if (isSideloading) {
        progressFlow.collectAsState().value
    } else {
        null
    }
    val isSolidActive = canStart || isSideloading
    val pillColor by animateColorAsState(
        targetValue = if (isSolidActive) OneUiCyan else OneUiCardSurface,
        animationSpec = tween(180),
        label = "oneUiSideloadCtaBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSolidActive) OneUiCyanDeep else OneUiTextSecondary,
        animationSpec = tween(180),
        label = "oneUiSideloadCtaFg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = metrics.heroCtaMinHeightDp.dp)
            .clip(CircleShape)
            .background(pillColor)
            .oneUiBounceClick(enabled = canStart, pressedScale = 0.965f) {
                if (canStart) onClick()
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
                    .background(if (isSolidActive) OneUiCyanDeep.copy(alpha = 0.14f) else OneUiCyanSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.UploadFile,
                    contentDescription = null,
                    tint = if (isSolidActive) OneUiCyanDeep else OneUiCyan,
                    modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                )
            }
            Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 10.dp else 12.dp))
            val heroLabel = when {
                isSideloading && progress?.state == AdbSideloadState.INSTALLING_ON_DEVICE ->
                    "Installing on Device (${progress.percent}%)"
                isSideloading -> "Sideloading (${progress?.percent ?: 0}%)"
                !hasZipSelected -> "Select ZIP First"
                !isSideloadReady -> if (metrics.isNarrowOrZoomed) "Connect Sideload" else "Connect Recovery Sideload"
                else -> "Start ADB Sideload"
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
            tint = if (isSolidActive) OneUiCyanDeep else OneUiTextMuted,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun OneUiSideloadProgressSection(
    progress: AdbSideloadProgress,
    fallbackFileName: String,
    formatBytes: (Long) -> String,
    onCancel: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val isRunning = progress.state == AdbSideloadState.SENDING ||
        progress.state == AdbSideloadState.INSTALLING_ON_DEVICE
    val displayFileName = progress.fileName.ifBlank { fallbackFileName }

    val animatedPct by animateFloatAsState(
        targetValue = (progress.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(120, easing = FastOutSlowInEasing),
        label = "oneUiSideloadPct"
    )
    val pulseTransition = rememberInfiniteTransition(label = "sideloadPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sideloadDotAlpha"
    )

    val cardBg by animateColorAsState(
        targetValue = when (progress.state) {
            AdbSideloadState.FAILED, AdbSideloadState.CANCELLED -> OneUiDangerSurface
            AdbSideloadState.COMPLETED -> OneUiActiveSurface
            else -> OneUiCyanSurface
        },
        animationSpec = tween(200),
        label = "sideloadProgCardBg"
    )

    OneUiCard(
        containerColor = cardBg,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        if (displayFileName.isNotBlank()) {
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
                            .size(metrics.badgeSmallSizeDp.dp)
                            .clip(CircleShape)
                            .background(OneUiCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = OneUiCyanDeep,
                            modifier = Modifier.size((metrics.badgeSmallSizeDp * 0.52f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = displayFileName,
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.bodySp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OneUiCardSurface)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .graphicsLayer { alpha = pulseAlpha }
                                .clip(CircleShape)
                                .background(OneUiCyan)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                    }
                    Text(
                        text = "${progress.percent}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = metrics.captionSp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiCyan
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = progress.statusMessage,
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.subtitleSp.sp,
                fontWeight = FontWeight.ExtraBold,
                color = when (progress.state) {
                    AdbSideloadState.FAILED, AdbSideloadState.CANCELLED -> OneUiDangerRed
                    AdbSideloadState.COMPLETED -> OneUiEmerald
                    else -> OneUiCyan
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (displayFileName.isBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${progress.percent}%",
                    fontFamily = FontFamily.Monospace,
                    fontSize = metrics.subtitleSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { animatedPct },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = when (progress.state) {
                AdbSideloadState.FAILED, AdbSideloadState.CANCELLED -> OneUiDangerRed
                AdbSideloadState.COMPLETED -> OneUiEmerald
                else -> OneUiCyan
            },
            trackColor = OneUiTrack
        )

        if (progress.totalBytes > 0L) {
            Spacer(modifier = Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatBytes(progress.uniqueBytesSent)} / ${formatBytes(progress.totalBytes)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = metrics.captionSp.sp,
                    color = OneUiTextSecondary
                )
                if (progress.speedMbPerSec > 0.0 && progress.state == AdbSideloadState.SENDING) {
                    Text(
                        text = String.format(Locale.US, "%.2f MB/s", progress.speedMbPerSec),
                        fontFamily = FontFamily.Monospace,
                        fontSize = metrics.captionSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = OneUiCyan
                    )
                }
            }
        }

        if (isRunning) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                    .clip(CircleShape)
                    .background(OneUiDangerRed)
                    .oneUiBounceClick(pressedScale = 0.96f, onClick = onCancel),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Cancel Sideload",
                    fontFamily = IosRoundedFontFamily,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = metrics.bodySp.sp
                )
            }
        }
    }
}

@Composable
private fun OneUiActionPillButton(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    accentTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
            .clip(CircleShape)
            .background(OneUiInnerTile)
            .oneUiBounceClick(enabled = enabled, pressedScale = 0.96f, onClick = onClick)
            .padding(
                horizontal = if (metrics.isCompactWidth) 8.dp else 10.dp,
                vertical = 9.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (enabled) accentTint else OneUiTextMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            lineHeight = (metrics.subtitleSp + 3f).sp,
            fontWeight = FontWeight.Bold,
            color = if (enabled) OneUiTextPrimary else OneUiTextMuted,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}
