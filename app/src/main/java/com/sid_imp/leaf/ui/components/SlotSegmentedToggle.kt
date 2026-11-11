package com.sid_imp.leaf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.ui.theme.*

@Composable
fun SlotSegmentedToggle(
    selectedSlot: String, // "a", "b", "both"
    onSlotSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    fullLabels: Boolean = true
) {
    val metrics = LocalLeafUiMetrics.current
    val toggleHeight = (metrics.actionPillMinHeightDp - 2f).coerceIn(40f, 52f).dp
    val options = listOf(
        "a" to if (fullLabels) "Slot A" else "A",
        "b" to if (fullLabels) "Slot B" else "B",
        "both" to "A + B"
    )
    val selectedIndex = options.indexOfFirst { it.first.equals(selectedSlot, ignoreCase = true) }
        .coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(toggleHeight)
            .clip(CircleShape)
            .background(OneUiTerminalBg)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "slotSliderOffset"
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(OneUiEmerald)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { idx, (key, label) ->
                val isSelected = idx == selectedIndex
                val pillText by animateColorAsState(
                    targetValue = if (isSelected) OneUiEmeraldDeep else OneUiTextSecondary,
                    animationSpec = tween(160),
                    label = "oneUiSlotPillText"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .oneUiBounceClick(pressedScale = 0.95f) { onSlotSelected(key) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.subtitleSp.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                        color = pillText,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
