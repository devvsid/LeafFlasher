package com.sid_imp.leaf.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.script.ExecutionProgress
import com.sid_imp.leaf.script.FlashExecutionState
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
