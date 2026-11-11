package com.sid_imp.leaf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.ui.theme.*
import kotlinx.coroutines.flow.StateFlow

@Composable
fun LeavesConsoleCard(
    logsFlow: StateFlow<List<String>>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClearLogs: () -> Unit,
    consoleHeight: Dp? = null,
    fillAvailableHeight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    val resolvedConsoleHeight = consoleHeight ?: metrics.consoleDefaultHeightDp.dp
    val clipboardManager = LocalClipboardManager.current
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "oneUiLeavesChevron"
    )
    val toggleBg by animateColorAsState(
        targetValue = if (isExpanded) OneUiActiveSurface else OneUiInnerTile,
        animationSpec = tween(160),
        label = "oneUiLeavesToggleBg"
    )

    OneUiCard(
        modifier = modifier,
        animateSize = !fillAvailableHeight,
        contentPadding = PaddingValues(metrics.innerCardPaddingDp.dp.coerceAtLeast(11.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .oneUiBounceClick(pressedScale = 0.98f, onClick = onToggleExpanded)
            ) {
                Box(
                    modifier = Modifier
                        .size(metrics.badgeMediumSizeDp.dp)
                        .clip(CircleShape)
                        .background(OneUiActiveSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = OneUiEmerald,
                        modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.48f).dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Leaves Log",
                        fontFamily = IosRoundedFontFamily,
                        color = OneUiTextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = metrics.bodySp.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isExpanded) "Tap to collapse" else "Tap to view live output",
                        fontFamily = IosRoundedFontFamily,
                        color = OneUiTextMuted,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = metrics.captionSp.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val actionBtnSize = metrics.badgeSmallSizeDp.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(actionBtnSize)
                        .clip(CircleShape)
                        .background(OneUiInnerTile)
                        .oneUiBounceClick(pressedScale = 0.90f) {
                            clipboardManager.setText(AnnotatedString(logsFlow.value.joinToString("\n")))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy logs",
                        tint = OneUiMint,
                        modifier = Modifier.size((metrics.badgeSmallSizeDp * 0.48f).dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(actionBtnSize)
                        .clip(CircleShape)
                        .background(OneUiInnerTile)
                        .oneUiBounceClick(pressedScale = 0.90f, onClick = onClearLogs),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Clear logs",
                        tint = OneUiTextSecondary,
                        modifier = Modifier.size((metrics.badgeSmallSizeDp * 0.50f).dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(actionBtnSize)
                        .clip(CircleShape)
                        .background(toggleBg)
                        .oneUiBounceClick(pressedScale = 0.90f, onClick = onToggleExpanded),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = if (isExpanded) OneUiEmerald else OneUiTextSecondary,
                        modifier = Modifier
                            .size((metrics.badgeSmallSizeDp * 0.58f).dp)
                            .rotate(chevronRotation)
                    )
                }
            }
        }

        if (fillAvailableHeight && isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                val logs by logsFlow.collectAsState()
                OneUiTerminalLogView(
                    logs = logs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        } else {
            OneUiAnimatedSection(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    val logs by logsFlow.collectAsState()
                    OneUiTerminalLogView(
                        logs = logs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(resolvedConsoleHeight)
                    )
                }
            }
        }
    }
}

@Composable
private fun OneUiTerminalLogView(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    val logTextSp = (11.5f * metrics.textScale.coerceIn(0.86f, 1.08f)).sp
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (logs.size - 1).coerceAtLeast(0)
    )

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
            .background(OneUiTerminalBg)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 10.dp)
        ) {
            if (logs.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    Text(
                        text = "Ready. Connect phone via USB OTG",
                        fontFamily = FontFamily.Monospace,
                        fontSize = logTextSp,
                        color = OneUiTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                itemsIndexed(
                    items = logs,
                    key = { index, line -> "$index:${line.hashCode()}" },
                    contentType = { _, _ -> "log" }
                ) { _, line ->
                    val lineColor = when {
                        line.startsWith("✗") || line.contains("fail", ignoreCase = true) || line.contains("error", ignoreCase = true) -> OneUiDangerRed
                        line.startsWith("✓") -> OneUiEmerald
                        line.startsWith("Flashing") ||
                            line.startsWith("Sideload") ||
                            line.startsWith("Wiping") ||
                            line.startsWith("Formatting") ||
                            line.startsWith("Erasing") ||
                            line.startsWith("Rebooting") ||
                            line.startsWith("Retrying") ||
                            line.startsWith(">") -> OneUiCyan
                        line.startsWith("Skipping") || line.startsWith("Note:") -> OneUiAmber
                        else -> OneUiTextSecondary
                    }

                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = logTextSp,
                        color = lineColor,
                        lineHeight = (11.5f * metrics.textScale.coerceIn(0.86f, 1.08f) + 4.5f).sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 1.5.dp)
                    )
                }
            }
        }
    }
}
