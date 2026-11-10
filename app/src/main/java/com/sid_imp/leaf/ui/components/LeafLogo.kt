package com.sid_imp.leaf.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sid_imp.leaf.R

/**
 * Renders the Swirling Leaves emblem matching the app launcher icon
 * with a clean static emerald glow (defaulting to non-animated for 120Hz UI smoothness).
 */
@Composable
fun LeafLogo(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    animated: Boolean = false
) {
    val glowAlpha = if (animated) {
        val infiniteTransition = rememberInfiniteTransition(label = "logoGlow")
        val anim by infiniteTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowAlpha"
        )
        anim
    } else {
        0.65f
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = glowAlpha }
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x4D1EC981),
                        Color(0x2419A974),
                        Color.Transparent
                    ),
                    radius = this.size.width * 0.7f
                )
            )
        }

        Image(
            painter = painterResource(id = R.drawable.leaf_emblem),
            contentDescription = "Leaf Flasher Logo",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}
