package com.sid_imp.leaf.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.ui.FeatureInfoKey
import com.sid_imp.leaf.ui.MainViewModel
import com.sid_imp.leaf.ui.components.LeavesConsoleCard
import com.sid_imp.leaf.ui.components.OneUiCard
import com.sid_imp.leaf.ui.components.OneUiFeatureInfoDialog
import com.sid_imp.leaf.ui.components.OneUiInfoButton
import com.sid_imp.leaf.ui.components.oneUiBounceClick
import com.sid_imp.leaf.ui.theme.*

@Composable
fun DeviceControlScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val isLogExpanded by viewModel.isLogExpanded.collectAsState()
    val isFormattingData by viewModel.isFormattingData.collectAsState()
    val fastbootMode by viewModel.fastbootMode.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()

    var customCommand by remember { mutableStateOf("") }
    var activeFeatureInfo by remember { mutableStateOf<Pair<FeatureInfoKey, Rect?>?>(null) }
    val isAdb = fastbootMode.isAdbConnected
    val currentSlot = deviceInfo?.currentSlot?.lowercase()
    val metrics = LocalLeafUiMetrics.current
    val sectionGap = metrics.sectionGapDp.dp

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = metrics.maxContentWidthDp.dp)
                .fillMaxSize()
                .imePadding()
                .padding(
                    horizontal = metrics.screenHorizontalPaddingDp.dp,
                    vertical = if (metrics.isShortHeight) 4.dp else 6.dp
                )
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 4.dp else 6.dp))

            // 1. Command Runner Borderless OneUI Card
            OneUiCard(
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
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Command Runner",
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
                        onClick = { bounds -> activeFeatureInfo = FeatureInfoKey.COMMAND_RUNNER to bounds }
                    )
                }

                Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

                val inputHeight = metrics.actionPillMinHeightDp.dp.coerceAtLeast(44.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(inputHeight)
                            .clip(CircleShape)
                            .background(OneUiInnerTile)
                            .padding(horizontal = metrics.cardPaddingDp.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = customCommand,
                            onValueChange = { customCommand = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            cursorBrush = SolidColor(OneUiEmerald),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (customCommand.isNotBlank()) {
                                        viewModel.executeCustomCommand(customCommand)
                                    }
                                }
                            ),
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                color = OneUiTextPrimary,
                                fontSize = metrics.subtitleSp.sp
                            ),
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (customCommand.isEmpty()) {
                                        Text(
                                            text = if (isAdb) "Enter adb / shell command..." else "Enter fastboot command...",
                                            fontSize = metrics.captionSp.sp,
                                            color = OneUiTextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(inputHeight)
                            .clip(CircleShape)
                            .background(OneUiEmerald)
                            .oneUiBounceClick(pressedScale = 0.90f) {
                                if (customCommand.isNotBlank()) {
                                    viewModel.executeCustomCommand(customCommand)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Run Command",
                            tint = OneUiEmeraldDeep,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(sectionGap))

            // 2. Reboot Controls 2x2 Grid (Reboot System, Bootloader, Fastbootd, Recovery)
            OneUiCard(
                contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = OneUiEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reboot Controls",
                        fontFamily = IosRoundedFontFamily,
                        color = OneUiTextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = metrics.cardTitleSp.sp
                    )
                }

                Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OneUiRebootTile(
                        title = "Reboot System",
                        icon = Icons.Default.PowerSettingsNew,
                        onClick = { viewModel.rebootDevice("") },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    OneUiRebootTile(
                        title = "Bootloader",
                        icon = Icons.Default.StayCurrentPortrait,
                        onClick = { viewModel.rebootDevice("bootloader") },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OneUiRebootTile(
                        title = "Fastbootd",
                        icon = Icons.Default.Bolt,
                        onClick = { viewModel.rebootDevice("fastboot") },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    OneUiRebootTile(
                        title = "Recovery",
                        icon = Icons.Default.RestartAlt,
                        onClick = { viewModel.rebootDevice("recovery") },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            Spacer(modifier = Modifier.height(sectionGap))

            // 3. Set Active Slot OneUI Card
            OneUiCard(
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
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Set Active Slot",
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
                        onClick = { bounds -> activeFeatureInfo = FeatureInfoKey.SET_ACTIVE_SLOT to bounds }
                    )
                }

                Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 10.dp else 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("a" to "Slot A", "b" to "Slot B").forEach { (slotKey, label) ->
                        val isCurrent = currentSlot == slotKey
                        val bg by animateColorAsState(
                            targetValue = if (isCurrent) OneUiActiveSurface else OneUiInnerTile,
                            animationSpec = tween(180),
                            label = "activeSlotTileBg"
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .oneUiBounceClick(pressedScale = 0.96f) { viewModel.setActiveSlot(slotKey) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (isCurrent) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(OneUiEmerald)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = label,
                                fontFamily = IosRoundedFontFamily,
                                fontSize = metrics.bodySp.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isCurrent) OneUiEmerald else OneUiTextPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(sectionGap))

            // 4. Crimson Format Data Card
            OneUiCard(
                modifier = Modifier.oneUiBounceClick(enabled = !isFormattingData, pressedScale = 0.975f) {
                    viewModel.formatData()
                },
                containerColor = if (isFormattingData) OneUiDangerActiveSurface else OneUiDangerSurface,
                contentPadding = PaddingValues(
                    horizontal = metrics.innerCardPaddingDp.dp.coerceAtLeast(12.dp),
                    vertical = 12.dp
                )
            ) {
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
                                .size(metrics.badgeMediumSizeDp.dp)
                                .clip(CircleShape)
                                .background(OneUiDangerBadge),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "Format Data",
                                tint = OneUiDangerRed,
                                modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 10.dp else 12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isFormattingData) "Formatting Data..." else "Format Data",
                                fontFamily = IosRoundedFontFamily,
                                fontSize = metrics.bodySp.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = OneUiDangerRed,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Erases all files, apps & lock data",
                                fontFamily = IosRoundedFontFamily,
                                fontSize = metrics.captionSp.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OneUiTextMuted,
                                maxLines = if (metrics.isNarrowOrZoomed) 2 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
                    ) {
                        OneUiInfoButton(
                            onClick = { bounds -> activeFeatureInfo = FeatureInfoKey.FORMAT_DATA to bounds },
                            tint = OneUiDangerRed,
                            badgeBg = OneUiDangerBadge
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = OneUiDangerRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(sectionGap))

            LeavesConsoleCard(
                logsFlow = viewModel.logs,
                isExpanded = isLogExpanded,
                onToggleExpanded = { viewModel.toggleLogExpanded() },
                onClearLogs = { viewModel.clearLogs() },
                consoleHeight = metrics.consoleDefaultHeightDp.dp
            )

            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 14.dp else 20.dp))
        }
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
private fun OneUiRebootTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
            .background(OneUiInnerTile)
            .oneUiBounceClick(pressedScale = 0.96f, onClick = onClick)
            .padding(
                horizontal = if (metrics.isCompactWidth) 8.dp else 12.dp,
                vertical = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = OneUiEmerald,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.subtitleSp.sp,
            lineHeight = (metrics.subtitleSp + 3f).sp,
            fontWeight = FontWeight.Bold,
            color = OneUiTextPrimary,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )
    }
}
