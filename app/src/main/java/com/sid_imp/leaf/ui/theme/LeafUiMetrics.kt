package com.sid_imp.leaf.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.roundToInt

enum class LeafWidthClass {
    COMPACT,
    STANDARD,
    EXPANDED
}

data class LeafUiMetrics(
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val smallestWidthDp: Int,
    val fontScale: Float,
    val widthClass: LeafWidthClass,
    val isCompactWidth: Boolean,
    val isNarrowOrZoomed: Boolean,
    val isShortHeight: Boolean,
    val isLandscape: Boolean,
    val spacingScale: Float,
    val textScale: Float,
    val maxContentWidthDp: Float,
    val maxFocusContentWidthDp: Float,
    val maxDialogWidthDp: Float,
    val maxDialogHeightDp: Float,
    val maxMenuHeightDp: Float,
    val dockMaxWidthDp: Float,
    val screenHorizontalPaddingDp: Float,
    val sectionGapDp: Float,
    val cardPaddingDp: Float,
    val innerCardPaddingDp: Float,
    val cardCornerRadiusDp: Float,
    val innerCornerRadiusDp: Float,
    val headerLogoSizeDp: Float,
    val headerButtonSizeDp: Float,
    val badgeLargeSizeDp: Float,
    val badgeMediumSizeDp: Float,
    val badgeSmallSizeDp: Float,
    val heroCtaMinHeightDp: Float,
    val actionPillMinHeightDp: Float,
    val dockHeightDp: Float,
    val choiceCardMinHeightDp: Float,
    val rebootCardMinHeightDp: Float,
    val consoleDefaultHeightDp: Float,
    val brandTitleSp: Float,
    val headlineSp: Float,
    val cardTitleSp: Float,
    val bodySp: Float,
    val subtitleSp: Float,
    val captionSp: Float,
    val microSp: Float,
    val heroLabelSp: Float,
    val dockLabelSp: Float
)

fun computeLeafUiMetrics(
    screenWidthDp: Int,
    screenHeightDp: Int,
    smallestWidthDp: Int,
    fontScale: Float = 1.0f
): LeafUiMetrics {
    val w = screenWidthDp.coerceAtLeast(240)
    val h = screenHeightDp.coerceAtLeast(240)
    val sw = smallestWidthDp.coerceAtLeast(240)
    val fs = fontScale.coerceIn(0.8f, 1.8f)

    val isLandscape = w > h
    val isCompactWidth = w < 360 || (sw < 360 && !isLandscape)
    val isShortHeight = h < 640
    val widthClass = when {
        w >= 600 -> LeafWidthClass.EXPANDED
        isCompactWidth -> LeafWidthClass.COMPACT
        else -> LeafWidthClass.STANDARD
    }
    val isNarrowOrZoomed = isCompactWidth || fs > 1.18f || (w < 385 && fs > 1.08f)

    // Calibrated spacing & size multipliers around 400dp baseline
    val refWidth = if (widthClass == LeafWidthClass.EXPANDED) 600f else w.toFloat()
    val rawWidthRatio = (refWidth / 400f).coerceIn(0.80f, 1.14f)
    val heightFactor = when {
        h < 420 -> 0.86f
        h < 580 -> 0.92f
        h >= 860 -> 1.04f
        else -> 1.0f
    }
    val spacingScale = (rawWidthRatio * if (isShortHeight) 0.95f else 1.0f).coerceIn(0.80f, 1.14f)

    // Keep text readable across smallest-width changes without overflowing tight narrow screens
    val baseTextScale = when {
        w < 340 -> 0.87f
        w < 360 -> 0.91f
        w < 385 -> 0.96f
        sw >= 600 && w >= 600 -> 1.04f
        else -> 1.0f
    }
    // Gentle guard when system fontScale is very large on compact widths so fixed pills stay legible
    val fontOverflowGuard = if (w < 380 && fs > 1.20f) {
        (1.20f / fs).coerceIn(0.76f, 1.0f)
    } else if (fs > 1.40f) {
        (1.36f / fs).coerceIn(0.80f, 1.0f)
    } else {
        1.0f
    }
    val textScale = (baseTextScale * fontOverflowGuard).coerceIn(0.76f, 1.06f)

    fun dpScaled(base: Float, min: Float, max: Float, useHeight: Boolean = false): Float {
        val factor = if (useHeight) (spacingScale * 0.55f + heightFactor * 0.45f) else spacingScale
        return ((base * factor).coerceIn(min, max) * 2f).roundToInt() / 2f
    }

    fun spScaled(base: Float, min: Float, max: Float): Float {
        return ((base * textScale).coerceIn(min, max) * 2f).roundToInt() / 2f
    }

    val maxContentWidthDp = when {
        sw >= 600 && w >= 680 -> 640f
        w >= 600 -> 600f
        else -> w.toFloat()
    }
    val maxFocusContentWidthDp = when {
        w >= 760 -> 740f
        w >= 600 -> 660f
        else -> w.toFloat()
    }
    val dialogHorizMargin = if (isCompactWidth) 24f else 36f
    val maxDialogWidthDp = (w - dialogHorizMargin).coerceIn(220f, 540f)
    val maxDialogHeightDp = (h * (if (isShortHeight) 0.93f else 0.86f)).coerceIn(220f, 760f)
    val maxMenuHeightDp = (h * (if (isShortHeight) 0.92f else 0.86f)).coerceIn(220f, 720f)
    val dockMaxWidthDp = if (w >= 540) 480f else (w - if (isCompactWidth) 20f else 32f).coerceAtLeast(220f)

    val extraFontHeight = ((fs - 1.0f).coerceAtLeast(0f) * 14f)

    // Single-line dock & strip safety scale so labels never truncate even at 1.5x fontScale
    val singleLineGuard = if (fs > 1.15f) (1.15f / fs).coerceIn(0.72f, 1.0f) else 1.0f
    val dockLabelSp = ((11f * (if (isCompactWidth) 0.91f else 1.0f) * singleLineGuard).coerceIn(8.5f, 11.5f) * 2f).roundToInt() / 2f

    return LeafUiMetrics(
        screenWidthDp = w,
        screenHeightDp = h,
        smallestWidthDp = sw,
        fontScale = fs,
        widthClass = widthClass,
        isCompactWidth = isCompactWidth,
        isNarrowOrZoomed = isNarrowOrZoomed,
        isShortHeight = isShortHeight,
        isLandscape = isLandscape,
        spacingScale = spacingScale,
        textScale = textScale,
        maxContentWidthDp = maxContentWidthDp,
        maxFocusContentWidthDp = maxFocusContentWidthDp,
        maxDialogWidthDp = maxDialogWidthDp,
        maxDialogHeightDp = maxDialogHeightDp,
        maxMenuHeightDp = maxMenuHeightDp,
        dockMaxWidthDp = dockMaxWidthDp,
        screenHorizontalPaddingDp = when {
            w < 340 -> 11f
            isCompactWidth -> 12f
            widthClass == LeafWidthClass.EXPANDED -> 24f
            w >= 460 -> 20f
            else -> 16f
        },
        sectionGapDp = dpScaled(10f, 7f, 13f, useHeight = true),
        cardPaddingDp = dpScaled(16f, 12f, 20f),
        innerCardPaddingDp = dpScaled(12f, 9f, 15f),
        cardCornerRadiusDp = dpScaled(28f, 22f, 32f),
        innerCornerRadiusDp = dpScaled(20f, 16f, 24f),
        headerLogoSizeDp = dpScaled(42f, 34f, 46f),
        headerButtonSizeDp = dpScaled(40f, 34f, 44f),
        badgeLargeSizeDp = dpScaled(46f, 38f, 52f),
        badgeMediumSizeDp = dpScaled(40f, 33f, 44f),
        badgeSmallSizeDp = dpScaled(34f, 29f, 38f),
        heroCtaMinHeightDp = dpScaled(60f, 50f, 64f, useHeight = true) + (extraFontHeight * 0.4f),
        actionPillMinHeightDp = dpScaled(46f, 40f, 50f, useHeight = true) + (extraFontHeight * 0.3f),
        dockHeightDp = dpScaled(62f, 54f, 66f, useHeight = true) + extraFontHeight,
        choiceCardMinHeightDp = dpScaled(162f, 132f, 178f, useHeight = true),
        rebootCardMinHeightDp = dpScaled(116f, 98f, 130f, useHeight = true),
        consoleDefaultHeightDp = when {
            h < 460 -> 130f
            h < 680 -> 155f
            h >= 920 -> 215f
            else -> 180f
        },
        brandTitleSp = spScaled(21f, 17f, 23f),
        headlineSp = spScaled(18f, 15f, 20f),
        cardTitleSp = spScaled(15.5f, 13.5f, 17f),
        bodySp = spScaled(13.5f, 12f, 15f),
        subtitleSp = spScaled(12f, 10.5f, 13.5f),
        captionSp = spScaled(11f, 9.5f, 12.5f),
        microSp = ((9.5f * baseTextScale * singleLineGuard).coerceIn(8f, 10.5f) * 2f).roundToInt() / 2f,
        heroLabelSp = spScaled(15.5f, 13f, 17f),
        dockLabelSp = dockLabelSp
    )
}

val LocalLeafUiMetrics = staticCompositionLocalOf {
    computeLeafUiMetrics(
        screenWidthDp = 412,
        screenHeightDp = 915,
        smallestWidthDp = 412,
        fontScale = 1.0f
    )
}

@Composable
fun rememberLeafUiMetrics(): LeafUiMetrics {
    val config = LocalConfiguration.current
    return remember(
        config.screenWidthDp,
        config.screenHeightDp,
        config.smallestScreenWidthDp,
        config.fontScale
    ) {
        computeLeafUiMetrics(
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
            smallestWidthDp = config.smallestScreenWidthDp,
            fontScale = config.fontScale
        )
    }
}
