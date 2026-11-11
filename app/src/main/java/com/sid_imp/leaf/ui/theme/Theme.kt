package com.sid_imp.leaf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val LeafOneUiColorScheme = darkColorScheme(
    primary = OneUiEmerald,
    onPrimary = OneUiEmeraldDeep,
    primaryContainer = OneUiActiveSurface,
    onPrimaryContainer = OneUiMint,
    secondary = OneUiCyan,
    onSecondary = OneUiCyanDeep,
    background = OneUiAmoledBlack,
    onBackground = OneUiTextPrimary,
    surface = OneUiCardSurface,
    onSurface = OneUiTextPrimary,
    surfaceVariant = OneUiInnerTile,
    onSurfaceVariant = OneUiTextSecondary,
    outline = OneUiCardSubtleRim
)

@Composable
fun LeafFlasherTheme(
    content: @Composable () -> Unit
) {
    val metrics = rememberLeafUiMetrics()
    CompositionLocalProvider(LocalLeafUiMetrics provides metrics) {
        MaterialTheme(
            colorScheme = LeafOneUiColorScheme,
            typography = Typography,
            content = content
        )
    }
}
