package com.sid_imp.leaf.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Samsung OneUI Dark Bubbly Squircle Geometry
val OneUiCardCornerRadius = 28.dp
val OneUiInnerCornerRadius = 20.dp
val OneUiChipCornerRadius = 16.dp

val OneUiCardShape = RoundedCornerShape(OneUiCardCornerRadius)
val OneUiInnerShape = RoundedCornerShape(OneUiInnerCornerRadius)
val OneUiChipShape = RoundedCornerShape(OneUiChipCornerRadius)
val OneUiPillShape = CircleShape

// Subtle AMOLED Multi-Stop Ambient Gradient Backdrop
val OneUiBgTop = Color(0xFF0A2218)
val OneUiBgUpperMid = Color(0xFF04110B)
val OneUiBgMid = Color(0xFF020504)
val OneUiBgBottom = Color(0xFF051114)
val OneUiAmoledBlack = Color(0xFF020403)

val OneUiBackgroundBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to OneUiBgTop,
        0.26f to OneUiBgUpperMid,
        0.62f to OneUiBgMid,
        1.0f to OneUiBgBottom
    )
)

// Subtle Ambient Radial Glow Colors (drawn softly over dark AMOLED base)
val OneUiTopGlow = Color(0x221ED484)
val OneUiBottomGlow = Color(0x1438BDF8)

// Crisp OneUI Tonal Card Surfaces (borderless depth over subtle gradient bg)
val OneUiCardSurface = Color(0xFF131B17)
val OneUiCardSurfaceAlt = Color(0xFF16201B)
val OneUiInnerTile = Color(0xFF1B2520)
val OneUiInnerTileElevated = Color(0xFF222F28)
val OneUiActiveSurface = Color(0xFF123022)
val OneUiActiveBorder = Color(0x521ED484)
val OneUiCardSubtleRim = Color(0x14FFFFFF)

// Frosted-Look Brand Title & Creator Pill Gradient Brushes
val LeafBrandTitleBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFFDDF0E5),
        Color(0xFF8BD8B5),
        Color(0xFF34C488),
        Color(0xFF47BDAD)
    )
)

val LeafCreatorAccentBrush = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFFA2E3C4),
        Color(0xFF33BF85),
        Color(0xFF52C99A)
    )
)

// OneUI Accent Palette
val OneUiEmerald = Color(0xFF1ED484)
val OneUiEmeraldDeep = Color(0xFF052416)
val OneUiMint = Color(0xFF7CE8B7)
val OneUiSage = Color(0xFFB3DFC9)

// Sideload / FastbootD Cyan Accents
val OneUiCyan = Color(0xFF38BDF8)
val OneUiCyanSurface = Color(0xFF102530)
val OneUiCyanBorder = Color(0x4D38BDF8)
val OneUiCyanDeep = Color(0xFF041E2B)

// Unified Danger Red Palette (matches No Phone Connected card red #EF4444 everywhere)
val OneUiDangerRed = Color(0xFFEF4444)
val OneUiDangerBright = OneUiDangerRed
val OneUiDangerSurface = Color(0xFF211313)
val OneUiDangerActiveSurface = Color(0xFF301616)
val OneUiDangerBorder = Color(0x66EF4444)
val OneUiDangerBadge = Color(0xFF351919)

// Amber Notice Accents
val OneUiAmber = Color(0xFFF59E0B)
val OneUiAmberSurface = Color(0xFF281D0D)

// High-Contrast Readable Text Tokens
val OneUiTextPrimary = Color(0xFFF3F8F5)
val OneUiTextSecondary = Color(0xFFA5C2B3)
val OneUiTextMuted = Color(0xFF6F8F7E)

// Leaves Console & Track Tokens
val OneUiTerminalBg = Color(0xFF090F0C)
val OneUiTrack = Color(0xFF223129)
