package com.sid_imp.leaf.ui.components

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.script.ActiveExtractionWorker
import com.sid_imp.leaf.script.ExecutionProgress
import com.sid_imp.leaf.script.FlashExecutionState
import com.sid_imp.leaf.script.ZipExtractionProgress
import com.sid_imp.leaf.ui.theme.*
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
fun ProgressBarWithSpeed(
    progressFlow: StateFlow<ExecutionProgress>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    val progress by progressFlow.collectAsState()
    val animatedStepProgress by animateFloatAsState(
        targetValue = (progress.stepPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "oneUiStepProgress"
    )
    val animatedOverallProgress by animateFloatAsState(
        targetValue = (progress.overallPercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "oneUiOverallProgress"
    )

    OneUiCard(
        modifier = modifier,
        containerColor = OneUiCardSurface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (progress.totalSteps > 0)
                    "Part ${progress.currentStep} of ${progress.totalSteps}"
                else "Ready",
                fontFamily = IosRoundedFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = metrics.cardTitleSp.sp,
                color = OneUiTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (progress.transferSpeedMbPerSec > 0.0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, "%.1f MB/s", progress.transferSpeedMbPerSec),
                    fontFamily = FontFamily.Monospace,
                    fontSize = metrics.subtitleSp.sp,
                    color = OneUiEmerald,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = progress.statusMessage,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            fontWeight = FontWeight.SemiBold,
            color = when (progress.state) {
                FlashExecutionState.WAITING_FOR_RECONNECT -> OneUiCyan
                FlashExecutionState.FAILED -> OneUiDangerRed
                FlashExecutionState.COMPLETED -> OneUiEmerald
                else -> OneUiTextSecondary
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "This File",
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.captionSp.sp,
                fontWeight = FontWeight.Bold,
                color = OneUiTextMuted
            )
            Text(
                text = "${progress.stepPercent}%",
                fontFamily = FontFamily.Monospace,
                fontSize = metrics.captionSp.sp,
                fontWeight = FontWeight.Bold,
                color = OneUiTextSecondary
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { animatedStepProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape),
            color = OneUiEmerald,
            trackColor = OneUiTrack
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Overall Progress",
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.captionSp.sp,
                fontWeight = FontWeight.Bold,
                color = OneUiTextMuted
            )
            Text(
                text = "${progress.overallPercent}%",
                fontFamily = FontFamily.Monospace,
                fontSize = metrics.captionSp.sp,
                fontWeight = FontWeight.Bold,
                color = OneUiTextSecondary
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { animatedOverallProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape),
            color = OneUiMint,
            trackColor = OneUiTrack
        )

        if (progress.state == FlashExecutionState.RUNNING || progress.state == FlashExecutionState.PAUSED) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val btnHeight = metrics.actionPillMinHeightDp.dp
                if (progress.state == FlashExecutionState.PAUSED) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = btnHeight)
                            .clip(CircleShape)
                            .background(OneUiEmerald)
                            .oneUiBounceClick(pressedScale = 0.96f, onClick = onResume),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Resume",
                            fontFamily = IosRoundedFontFamily,
                            color = OneUiEmeraldDeep,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = metrics.bodySp.sp
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = btnHeight)
                            .clip(CircleShape)
                            .background(OneUiInnerTile)
                            .oneUiBounceClick(pressedScale = 0.96f, onClick = onPause),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Pause",
                            fontFamily = IosRoundedFontFamily,
                            color = OneUiTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = metrics.bodySp.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = btnHeight)
                        .clip(CircleShape)
                        .background(OneUiDangerRed)
                        .oneUiBounceClick(pressedScale = 0.96f, onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Cancel",
                        fontFamily = IosRoundedFontFamily,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = metrics.bodySp.sp
                    )
                }
            }
        }
    }
}

@Composable
fun OneUiZipProgressCard(
    zipProgressFlow: StateFlow<ZipExtractionProgress>,
    formatBytes: (Long) -> String,
    maxWorkersListHeight: Dp,
    title: String = "Unpacking ROM Files",
    onCancel: (() -> Unit)? = null
) {
    val metrics = LocalLeafUiMetrics.current
    val zipProgress by zipProgressFlow.collectAsState()
    val animatedZipProgress by animateFloatAsState(
        targetValue = (zipProgress.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(110, easing = FastOutSlowInEasing),
        label = "oneUiZipProgress"
    )
    val pulseTransition = rememberInfiniteTransition(label = "zipWorkerPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "zipDotAlpha"
    )

    OneUiCard(
        containerColor = OneUiCardSurface,
        contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = IosRoundedFontFamily,
                    color = OneUiTextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = metrics.cardTitleSp.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                val fileCountSubtitle = if (zipProgress.totalFiles > 0) {
                    "${zipProgress.filesExtracted} of ${zipProgress.totalFiles} files finished"
                } else {
                    zipProgress.statusMessage.ifBlank { "Getting files ready..." }
                }
                Text(
                    text = fileCountSubtitle,
                    fontFamily = IosRoundedFontFamily,
                    color = OneUiMint,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = metrics.captionSp.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(OneUiActiveSurface)
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
                    text = if (zipProgress.activeWorkers.size > 1) {
                        "${zipProgress.activeWorkers.size} Parallel"
                    } else {
                        "In Progress"
                    },
                    fontFamily = IosRoundedFontFamily,
                    color = OneUiEmerald,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = metrics.captionSp.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { animatedZipProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(9.dp)
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
            val statsText = buildString {
                if (zipProgress.totalPayloadBytes > 0L) {
                    append(formatBytes(zipProgress.totalBytesExtracted))
                    append(" / ")
                    append(formatBytes(zipProgress.totalPayloadBytes))
                } else {
                    append(zipProgress.statusMessage.ifBlank { "Reading package..." })
                }
                if (zipProgress.speedMbPerSec > 0.05) {
                    append(String.format(Locale.US, " • %.1f MB/s", zipProgress.speedMbPerSec))
                }
            }
            Text(
                text = statsText,
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.captionSp.sp,
                fontWeight = FontWeight.SemiBold,
                color = OneUiTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${zipProgress.percent}%",
                fontFamily = FontFamily.Monospace,
                color = OneUiMint,
                fontWeight = FontWeight.ExtraBold,
                fontSize = metrics.bodySp.sp
            )
        }

        if (zipProgress.activeWorkers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                    .background(OneUiInnerTile)
                    .padding(metrics.innerCardPaddingDp.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (zipProgress.activeWorkers.size > 1) {
                                "Unpacking side by side"
                            } else {
                                "Unpacking file"
                            },
                            fontFamily = IosRoundedFontFamily,
                            fontSize = metrics.captionSp.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${zipProgress.activeWorkers.size} active",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.microSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = OneUiMint
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxWorkersListHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    zipProgress.activeWorkers.forEach { worker ->
                        key(worker.partitionName) {
                            OneUiActiveWorkerProgressRow(
                                worker = worker,
                                pulseAlpha = pulseAlpha,
                                formatBytes = formatBytes
                            )
                        }
                    }
                }
            }
        } else if (zipProgress.statusMessage.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(OneUiInnerTile)
                    .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .graphicsLayer { alpha = pulseAlpha }
                        .clip(CircleShape)
                        .background(OneUiEmerald)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = zipProgress.statusMessage,
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.captionSp.sp,
                    fontWeight = FontWeight.Medium,
                    color = OneUiTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (onCancel != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                    .clip(CircleShape)
                    .background(OneUiDangerBadge)
                    .oneUiBounceClick(pressedScale = 0.96f, onClick = onCancel),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Cancel Extraction",
                    fontFamily = IosRoundedFontFamily,
                    color = OneUiDangerRed,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = metrics.bodySp.sp
                )
            }
        }
    }
}

@Composable
private fun OneUiActiveWorkerProgressRow(
    worker: ActiveExtractionWorker,
    pulseAlpha: Float,
    formatBytes: (Long) -> String
) {
    val metrics = LocalLeafUiMetrics.current
    val animatedWorkerProgress by animateFloatAsState(
        targetValue = (worker.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(110, easing = FastOutSlowInEasing),
        label = "worker_${worker.partitionName}"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
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
                        .size(6.dp)
                        .graphicsLayer { alpha = pulseAlpha }
                        .clip(CircleShape)
                        .background(OneUiEmerald)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "${worker.partitionName}.img",
                    fontFamily = FontFamily.Monospace,
                    fontSize = metrics.subtitleSp.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (worker.fileSizeBytes > 0L) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = formatBytes(worker.fileSizeBytes),
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.microSp.sp,
                        fontWeight = FontWeight.Medium,
                        color = OneUiTextMuted,
                        maxLines = 1
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${worker.percent}%",
                fontFamily = FontFamily.Monospace,
                fontSize = metrics.captionSp.sp,
                fontWeight = FontWeight.Bold,
                color = OneUiMint
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animatedWorkerProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape),
            color = OneUiEmerald,
            trackColor = OneUiCardSurface
        )
    }
}
