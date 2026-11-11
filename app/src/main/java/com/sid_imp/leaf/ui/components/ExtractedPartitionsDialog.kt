package com.sid_imp.leaf.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.script.ExtractedPartitionItem
import com.sid_imp.leaf.script.FlashScriptParser
import com.sid_imp.leaf.script.FlashTask
import com.sid_imp.leaf.script.FlashingPhase
import com.sid_imp.leaf.script.RomZipExtractor
import com.sid_imp.leaf.script.TaskType
import com.sid_imp.leaf.ui.theme.*

@Composable
fun ExtractedPartitionsDialog(
    romTitle: String,
    extractedPartitions: List<ExtractedPartitionItem>,
    plannedTasks: List<FlashTask>,
    selectedSlotLabel: String,
    formatBytes: (Long) -> String,
    originBounds: Rect? = null,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableIntStateOf(0) } // 0 = Images, 1 = Flash Order
    var selectedFilter by remember { mutableStateOf("All") }

    val totalBytes = remember(extractedPartitions) {
        extractedPartitions.sumOf { it.fileSize.coerceAtLeast(0L) }
    }
    val bootloaderCount = remember(extractedPartitions) {
        extractedPartitions.count { !it.isFastbootd }
    }
    val fastbootdCount = remember(extractedPartitions) {
        extractedPartitions.count { it.isFastbootd }
    }
    val missingFlashCount = remember(plannedTasks) {
        plannedTasks.count { it.type == TaskType.FLASH && !it.isFilePresent }
    }

    val filterOptions = remember(extractedPartitions) {
        val list = mutableListOf(
            "All" to "All (${extractedPartitions.size})",
            "Bootloader" to "Bootloader ($bootloaderCount)",
            "FastbootD" to "FastbootD ($fastbootdCount)"
        )
        listOf("Core", "Firmware", "VBMeta", "Logical", "Super Wipe", "Extra").forEach { cat ->
            val cnt = extractedPartitions.count { it.category == cat }
            if (cnt > 0) {
                list.add(cat to "$cat ($cnt)")
            }
        }
        list
    }

    val filteredPartitions = remember(extractedPartitions, selectedFilter) {
        when (selectedFilter) {
            "All" -> extractedPartitions
            "Bootloader" -> extractedPartitions.filter { !it.isFastbootd }
            "FastbootD" -> extractedPartitions.filter { it.isFastbootd }
            else -> extractedPartitions.filter { it.category == selectedFilter }
        }
    }

    val metrics = LocalLeafUiMetrics.current
    AnchoredDialog(
        onDismissRequest = onDismiss,
        originBounds = originBounds,
        scrimColor = Color(0xC8000000),
        horizontalPadding = metrics.screenHorizontalPaddingDp.dp,
        verticalPadding = 20.dp
    ) { dismiss ->
        OneUiCard(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (metrics.isShortHeight) 0.94f else 0.85f)
                .heightIn(max = metrics.maxDialogHeightDp.dp),
            shape = RoundedCornerShape(metrics.cardCornerRadiusDp.dp),
            containerColor = OneUiCardSurfaceAlt,
            animateSize = false,
            contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
        ) {
            // Header
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
                            .background(OneUiActiveSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ROM Partitions",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.cardTitleSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = romTitle.removePrefix("Direct ZIP: ").ifBlank { "Loaded ROM" },
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = OneUiMint,
                            fontSize = metrics.captionSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(metrics.badgeSmallSizeDp.dp)
                        .clip(CircleShape)
                        .background(OneUiInnerTile)
                        .oneUiBounceClick(pressedScale = 0.88f) { dismiss {} },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = OneUiTextSecondary,
                        modifier = Modifier.size((metrics.badgeSmallSizeDp * 0.48f).dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Compact Summary Metrics Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OneUiMetricPill(
                    label = "Images",
                    value = "${extractedPartitions.size}",
                    valueColor = OneUiEmerald,
                    modifier = Modifier.weight(1f)
                )
                OneUiMetricPill(
                    label = "Total Size",
                    value = if (totalBytes > 0L) formatBytes(totalBytes) else "—",
                    valueColor = OneUiTextPrimary,
                    modifier = Modifier.weight(1.25f)
                )
                OneUiMetricPill(
                    label = if (missingFlashCount > 0) "Missing" else selectedSlotLabel,
                    value = if (missingFlashCount > 0) "$missingFlashCount" else "${plannedTasks.size} steps",
                    valueColor = if (missingFlashCount > 0) OneUiDangerRed else OneUiMint,
                    modifier = Modifier.weight(1.1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Animated Sliding-Pill Tab Bar
            val tabs = listOf(
                "Images (${extractedPartitions.size})",
                "Flash Order (${plannedTasks.size})"
            )
            val tabBarHeight = (42f * metrics.spacingScale.coerceIn(0.90f, 1.08f) +
                ((metrics.fontScale - 1f).coerceAtLeast(0f) * 10f)).dp
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tabBarHeight)
                    .clip(CircleShape)
                    .background(OneUiTerminalBg)
                    .padding(4.dp)
            ) {
                val tabWidth = maxWidth / tabs.size
                val sliderOffset by animateDpAsState(
                    targetValue = tabWidth * activeTab,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "extractedTabSlider"
                )

                Box(
                    modifier = Modifier
                        .offset(x = sliderOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(OneUiEmerald)
                )

                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { idx, label ->
                        val selected = activeTab == idx
                        val fg by animateColorAsState(
                            targetValue = if (selected) OneUiEmeraldDeep else OneUiTextSecondary,
                            animationSpec = tween(160),
                            label = "extractedTabText"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .oneUiBounceClick(pressedScale = 0.96f) { activeTab = idx },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontFamily = IosRoundedFontFamily,
                                fontSize = 12.5.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                                color = fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            OneUiAnimatedSection(visible = activeTab == 0 && extractedPartitions.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        filterOptions.forEach { (key, label) ->
                            val selected = selectedFilter == key
                            val chipBg by animateColorAsState(
                                targetValue = if (selected) OneUiEmerald else OneUiInnerTile,
                                animationSpec = tween(160),
                                label = "filterChipBg"
                            )
                            val chipFg by animateColorAsState(
                                targetValue = if (selected) OneUiEmeraldDeep else OneUiTextSecondary,
                                animationSpec = tween(160),
                                label = "filterChipFg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(chipBg)
                                    .oneUiBounceClick(pressedScale = 0.94f) { selectedFilter = key }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = chipFg,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            AnimatedContent(
                targetState = activeTab,
                transitionSpec = {
                    val forward = targetState >= initialState
                    (fadeIn(tween(170)) + slideInHorizontally(
                        animationSpec = tween(200, easing = FastOutSlowInEasing)
                    ) { w -> if (forward) w / 6 else -w / 6 }).togetherWith(
                        fadeOut(tween(100)) + slideOutHorizontally(tween(120)) { w -> if (forward) -w / 6 else w / 6 }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "extractedContentSwitch"
            ) { tabIdx ->
                if (tabIdx == 0) {
                    if (filteredPartitions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (extractedPartitions.isEmpty()) {
                                    "No image files listed"
                                } else {
                                    "No $selectedFilter partitions in package"
                                },
                                fontFamily = IosRoundedFontFamily,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OneUiTextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredPartitions, key = { it.stem }) { item ->
                                OneUiExtractedPartitionTile(
                                    item = item,
                                    formatBytes = formatBytes
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        itemsIndexed(
                            items = plannedTasks,
                            key = { idx, t -> "${t.id}_$idx" }
                        ) { idx, task ->
                            OneUiFlashPlanStepTile(
                                stepNumber = idx + 1,
                                task = task,
                                formatBytes = formatBytes
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OneUiMetricPill(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
            .background(OneUiInnerTile)
            .padding(horizontal = if (metrics.isCompactWidth) 9.dp else 12.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.microSp.sp,
            fontWeight = FontWeight.SemiBold,
            color = OneUiTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.bodySp.sp,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun oneUiCategoryColors(category: String): Pair<Color, Color> {
    return when (category) {
        "Core" -> OneUiActiveSurface to OneUiEmerald
        "Logical" -> OneUiCyanSurface to OneUiCyan
        "VBMeta", "Super Wipe" -> OneUiAmberSurface to OneUiAmber
        "Firmware" -> Color(0xFF172D23) to OneUiMint
        else -> OneUiTrack to OneUiTextSecondary
    }
}

@Composable
private fun OneUiExtractedPartitionTile(
    item: ExtractedPartitionItem,
    formatBytes: (Long) -> String
) {
    val modeColor = when {
        !item.isIncludedInFlashPlan -> OneUiTextMuted
        item.category == "Super Wipe" -> OneUiAmber
        item.isFastbootd -> OneUiCyan
        else -> OneUiEmerald
    }
    val modeLabel = when {
        !item.isIncludedInFlashPlan -> "Skipped"
        item.isFastbootd -> "FastbootD Mode"
        else -> "Bootloader Mode"
    }
    val (catBg, catFg) = oneUiCategoryColors(item.category)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OneUiInnerShape)
            .background(if (item.isCustomOverride) OneUiActiveSurface else OneUiInnerTile)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(modeColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.stem,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(catBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.category,
                        fontFamily = IosRoundedFontFamily,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = catFg,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                if (item.isCustomOverride) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(OneUiEmerald)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Custom",
                            fontFamily = IosRoundedFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiEmeraldDeep,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            val targetText = if (item.targetPartitions.isNotEmpty()) {
                item.targetPartitions.joinToString(", ")
            } else {
                "Excluded"
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 125.dp)
                    .clip(CircleShape)
                    .background(if (item.isIncludedInFlashPlan) OneUiTerminalBg else OneUiAmberSurface)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = targetText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (item.isIncludedInFlashPlan) OneUiMint else OneUiAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (item.fileSize > 0L) {
                    "${item.fileName} • ${formatBytes(item.fileSize)}"
                } else {
                    item.fileName
                },
                fontFamily = IosRoundedFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = OneUiTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = modeLabel,
                fontFamily = IosRoundedFontFamily,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = modeColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun OneUiFlashPlanStepTile(
    stepNumber: Int,
    task: FlashTask,
    formatBytes: (Long) -> String
) {
    val isMissingFlash = task.type == TaskType.FLASH && !task.isFilePresent
    val isRebootOrFinalize = task.isRebootTask || task.phase == FlashingPhase.FINALIZATION
    val stem = if (task.type == TaskType.FLASH) {
        if (FlashScriptParser.isWipeSuperTask(task)) "super_empty"
        else FlashScriptParser.normalizePartitionStem(task.partition.ifBlank { task.fileName })
    } else ""
    val category = if (stem.isNotBlank()) RomZipExtractor.classifyPartitionCategory(stem, task.phase) else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OneUiInnerShape)
            .background(if (isMissingFlash) OneUiDangerSurface else OneUiInnerTile)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 28.dp)
                    .clip(CircleShape)
                    .background(OneUiActiveSurface)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "$stepNumber",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiMint
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayName,
                    fontFamily = IosRoundedFontFamily,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isMissingFlash) OneUiDangerRed else OneUiTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val modeStr = when {
                    task.phase == FlashingPhase.FASTBOOTD_TRANSITION -> "Switch to FastbootD"
                    task.phase == FlashingPhase.LOGICAL_DYNAMIC -> "FastbootD"
                    isRebootOrFinalize -> "Finalization"
                    else -> "Bootloader"
                }
                val sub = when {
                    isMissingFlash -> "Missing image • ${category ?: ""} • $modeStr"
                    task.type == TaskType.FLASH && task.fileSize > 0L ->
                        "${task.fileName} • ${formatBytes(task.fileSize)} • ${category ?: ""} • $modeStr"
                    task.type == TaskType.FLASH && category != null ->
                        "$category • $modeStr"
                    else -> modeStr
                }
                Text(
                    text = sub,
                    fontFamily = IosRoundedFontFamily,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        isMissingFlash -> OneUiDangerRed
                        task.phase == FlashingPhase.LOGICAL_DYNAMIC -> OneUiCyan
                        else -> OneUiTextMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (task.type == TaskType.FLASH) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (task.isFilePresent) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = if (task.isFilePresent) "Present" else "Missing",
                tint = if (task.isFilePresent) OneUiEmerald else OneUiDangerRed,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}
