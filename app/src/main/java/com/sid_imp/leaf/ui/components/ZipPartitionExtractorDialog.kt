package com.sid_imp.leaf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.script.FlashScriptParser
import com.sid_imp.leaf.script.ZipPartitionEntry
import com.sid_imp.leaf.script.ZipPartitionOrigin
import com.sid_imp.leaf.ui.theme.*
import java.util.Locale

private fun extractorCategoryColors(category: String): Pair<Color, Color> {
    return when (category) {
        "Core" -> OneUiActiveSurface to OneUiEmerald
        "Logical" -> OneUiCyanSurface to OneUiCyan
        "VBMeta", "Super Wipe" -> OneUiAmberSurface to OneUiAmber
        "Firmware" -> Color(0xFF172D23) to OneUiMint
        else -> OneUiTrack to OneUiTextSecondary
    }
}

@Composable
fun ZipPartitionExtractorDialog(
    archiveName: String,
    isInspecting: Boolean,
    partitions: List<ZipPartitionEntry>,
    errorMessage: String?,
    outputFolderLabel: String,
    isCustomOutputFolder: Boolean,
    formatBytes: (Long) -> String,
    originBounds: Rect? = null,
    onDismiss: () -> Unit,
    onChooseOutputFolder: () -> Unit,
    onResetOutputFolder: () -> Unit,
    onExtractSelected: (List<ZipPartitionEntry>) -> Unit
) {
    var selectedIds by remember(partitions) { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember(partitions) { mutableStateOf("") }
    var selectedCategory by remember(partitions) { mutableStateOf("All") }

    val rootPresetIds = remember(partitions) {
        partitions.filter { entry ->
            val stem = FlashScriptParser.normalizePartitionStem(entry.name)
            stem == "boot" || stem == "init_boot"
        }.map { it.id }.toSet()
    }

    val categories = remember(partitions) {
        buildList {
            add("All" to "All (${partitions.size})")
            listOf("Core", "VBMeta", "Firmware", "Logical", "Super Wipe", "Extra").forEach { cat ->
                val count = partitions.count { it.category == cat }
                if (count > 0) add(cat to "$cat ($count)")
            }
        }
    }

    val filteredPartitions = remember(partitions, selectedCategory, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.US)
        partitions.filter { entry ->
            val catMatch = selectedCategory == "All" || entry.category == selectedCategory
            val qMatch = q.isEmpty() ||
                entry.name.lowercase(Locale.US).contains(q) ||
                entry.outputFileName.lowercase(Locale.US).contains(q)
            catMatch && qMatch
        }
    }

    val selectedEntries = remember(partitions, selectedIds) {
        partitions.filter { it.id in selectedIds }
    }
    val selectedTotalBytes = remember(selectedEntries) {
        selectedEntries.sumOf { maxOf(0L, it.sizeBytes) }
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
                .fillMaxHeight(if (metrics.isShortHeight) 0.94f else 0.87f)
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
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select .img Partitions",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.cardTitleSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = archiveName.ifBlank { "Scanning ROM archive..." },
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

            when {
                isInspecting -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = OneUiEmerald,
                            trackColor = OneUiTrack,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(38.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Inspecting ROM Archive...",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.bodySp.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Text(
                            text = "Listing partition images without unpacking multi-GB payloads",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = OneUiTextSecondary,
                            fontSize = metrics.captionSp.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                                .background(OneUiDangerSurface)
                                .padding(metrics.cardPaddingDp.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = OneUiDangerRed,
                                modifier = Modifier.size(34.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Could Not Read Partitions",
                                fontFamily = IosRoundedFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                color = OneUiTextPrimary,
                                fontSize = metrics.bodySp.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = errorMessage,
                                fontFamily = IosRoundedFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = OneUiTextSecondary,
                                fontSize = metrics.subtitleSp.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                                .clip(CircleShape)
                                .background(OneUiInnerTile)
                                .oneUiBounceClick(pressedScale = 0.97f) { dismiss {} },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Close",
                                fontFamily = IosRoundedFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                color = OneUiTextPrimary,
                                fontSize = metrics.bodySp.sp
                            )
                        }
                    }
                }

                else -> {
                    // Save destination selector strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                            .background(OneUiInnerTile)
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = OneUiMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Save Folder",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.microSp.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OneUiTextMuted
                                )
                                Text(
                                    text = outputFolderLabel,
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.captionSp.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = OneUiTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isCustomOutputFolder) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(OneUiCardSurface)
                                        .oneUiBounceClick(pressedScale = 0.93f) { onResetOutputFolder() }
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "Default",
                                        fontFamily = IosRoundedFontFamily,
                                        fontSize = metrics.microSp.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = OneUiMint,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(OneUiActiveSurface)
                                    .oneUiBounceClick(pressedScale = 0.93f) { onChooseOutputFolder() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "Change",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.microSp.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = OneUiEmerald,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Search field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(OneUiTerminalBg)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = OneUiTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search partition (boot, init_boot, dtbo...)",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = metrics.captionSp.sp,
                                    color = OneUiTextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = metrics.captionSp.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OneUiTextPrimary
                                ),
                                cursorBrush = SolidColor(OneUiEmerald),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = OneUiTextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .oneUiBounceClick(pressedScale = 0.85f) { searchQuery = "" }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Quick Action + Category Filter Strip
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (rootPresetIds.isNotEmpty()) {
                            val presetSelected = rootPresetIds.all { it in selectedIds }
                            val bg by animateColorAsState(
                                targetValue = if (presetSelected) OneUiEmerald else OneUiActiveSurface,
                                animationSpec = tween(150),
                                label = "presetBg"
                            )
                            val fg by animateColorAsState(
                                targetValue = if (presetSelected) OneUiEmeraldDeep else OneUiEmerald,
                                animationSpec = tween(150),
                                label = "presetFg"
                            )
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(bg)
                                    .oneUiBounceClick(pressedScale = 0.94f) {
                                        selectedIds = if (presetSelected) {
                                            selectedIds - rootPresetIds
                                        } else {
                                            selectedIds + rootPresetIds
                                        }
                                    }
                                    .padding(horizontal = 11.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = fg,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "boot / init_boot",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = fg,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (selectedIds.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(OneUiDangerBadge)
                                    .oneUiBounceClick(pressedScale = 0.94f) { selectedIds = emptySet() }
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Clear (${selectedIds.size})",
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = OneUiDangerRed,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (filteredPartitions.isNotEmpty()) {
                            val filteredIds = remember(filteredPartitions) { filteredPartitions.map { it.id }.toSet() }
                            val allVisibleSelected = filteredIds.isNotEmpty() && filteredIds.all { it in selectedIds }
                            if (!allVisibleSelected) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(OneUiInnerTile)
                                        .oneUiBounceClick(pressedScale = 0.94f) {
                                            selectedIds = selectedIds + filteredIds
                                        }
                                        .padding(horizontal = 11.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Select All",
                                        fontFamily = IosRoundedFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = OneUiMint,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        categories.forEach { (key, label) ->
                            val active = selectedCategory == key
                            val chipBg by animateColorAsState(
                                targetValue = if (active) OneUiEmerald else OneUiInnerTile,
                                animationSpec = tween(150),
                                label = "catChipBg"
                            )
                            val chipFg by animateColorAsState(
                                targetValue = if (active) OneUiEmeraldDeep else OneUiTextSecondary,
                                animationSpec = tween(150),
                                label = "catChipFg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(chipBg)
                                    .oneUiBounceClick(pressedScale = 0.94f) { selectedCategory = key }
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = IosRoundedFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = chipFg,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Multi-select partition list
                    if (filteredPartitions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No partitions match your filter",
                                fontFamily = IosRoundedFontFamily,
                                fontSize = metrics.subtitleSp.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = OneUiTextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            items(filteredPartitions, key = { it.id }) { entry ->
                                val isChecked = entry.id in selectedIds
                                val rowBg by animateColorAsState(
                                    targetValue = if (isChecked) OneUiActiveSurface else OneUiInnerTile,
                                    animationSpec = tween(150),
                                    label = "zipEntryRowBg"
                                )
                                val (catBg, catFg) = extractorCategoryColors(entry.category)

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(OneUiInnerShape)
                                        .background(rowBg)
                                        .oneUiBounceClick(pressedScale = 0.985f) {
                                            selectedIds = if (isChecked) {
                                                selectedIds - entry.id
                                            } else {
                                                selectedIds + entry.id
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        // Custom OneUI checkbox
                                        Box(
                                            modifier = Modifier
                                                .size(21.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isChecked) OneUiEmerald else OneUiCardSurface)
                                                .then(
                                                    if (!isChecked) {
                                                        Modifier.border(
                                                            1.4.dp,
                                                            OneUiTextMuted,
                                                            RoundedCornerShape(6.dp)
                                                        )
                                                    } else Modifier
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isChecked) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = OneUiEmeraldDeep,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.outputFileName,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = metrics.subtitleSp.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = OneUiTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val sourceLabel = if (entry.origin == ZipPartitionOrigin.PAYLOAD_BIN) {
                                                "payload.bin"
                                            } else {
                                                "ZIP file"
                                            }
                                            val metaText = if (entry.sizeBytes > 0L) {
                                                "${formatBytes(entry.sizeBytes)} • $sourceLabel"
                                            } else {
                                                sourceLabel
                                            }
                                            Text(
                                                text = metaText,
                                                fontFamily = IosRoundedFontFamily,
                                                fontSize = metrics.microSp.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isChecked) OneUiMint else OneUiTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(catBg)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = entry.category,
                                            fontFamily = IosRoundedFontFamily,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = catFg,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val canExtract = selectedEntries.isNotEmpty()
                    val ctaBg by animateColorAsState(
                        targetValue = if (canExtract) OneUiEmerald else OneUiInnerTile,
                        animationSpec = tween(160),
                        label = "extractCtaBg"
                    )
                    val ctaFg by animateColorAsState(
                        targetValue = if (canExtract) OneUiEmeraldDeep else OneUiTextSecondary,
                        animationSpec = tween(160),
                        label = "extractCtaFg"
                    )
                    val ctaText = when {
                        selectedEntries.isEmpty() -> "Select 1 or more images"
                        selectedTotalBytes > 0L -> {
                            val noun = if (selectedEntries.size == 1) "Image" else "Images"
                            "Extract ${selectedEntries.size} $noun • ${formatBytes(selectedTotalBytes)}"
                        }
                        else -> {
                            val noun = if (selectedEntries.size == 1) "Image" else "Images"
                            "Extract ${selectedEntries.size} $noun"
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                            .clip(CircleShape)
                            .background(ctaBg)
                            .oneUiBounceClick(enabled = canExtract, pressedScale = 0.97f) {
                                dismiss { onExtractSelected(selectedEntries) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = ctaText,
                            fontFamily = IosRoundedFontFamily,
                            fontSize = metrics.bodySp.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = ctaFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
