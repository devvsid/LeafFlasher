package com.sid_imp.leaf.ui.components

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.sid_imp.leaf.ui.FeatureGuideCatalog
import com.sid_imp.leaf.ui.FeatureInfoKey
import com.sid_imp.leaf.ui.FeatureInfoSpec
import com.sid_imp.leaf.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun OneUiGradientBackground(
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OneUiAmoledBlack)
            .background(OneUiBackgroundBrush)
    ) {
        // Static atmospheric OneUI radial highlights (zero GPU blur cost)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val topCenter = Offset(size.width * 0.85f, size.height * 0.08f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(OneUiTopGlow, Color.Transparent),
                    center = topCenter,
                    radius = size.width * 0.78f
                ),
                center = topCenter,
                radius = size.width * 0.78f
            )

            val bottomCenter = Offset(size.width * 0.14f, size.height * 0.90f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(OneUiBottomGlow, Color.Transparent),
                    center = bottomCenter,
                    radius = size.width * 0.72f
                ),
                center = bottomCenter,
                radius = size.width * 0.72f
            )
        }
        content()
    }
}

/**
 * Tactile OneUI spring press animation for every card, button, badge, and chip.
 */
fun Modifier.oneUiBounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.968f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "oneUiBounceScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

@Composable
fun OneUiCard(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    containerColor: Color = OneUiCardSurface,
    borderColor: Color = Color.Transparent,
    borderWidth: Dp = 0.dp,
    animateSize: Boolean = true,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val resolvedShape = shape ?: RoundedCornerShape(metrics.cardCornerRadiusDp.dp)
    val resolvedPadding = contentPadding ?: PaddingValues(metrics.cardPaddingDp.dp)
    val animatedContainer by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(190),
        label = "oneUiCardBg"
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (animateSize) {
                    Modifier.animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.90f,
                            stiffness = 440f
                        )
                    )
                } else {
                    Modifier
                }
            )
            .clip(resolvedShape)
            .background(animatedContainer)
            .then(
                if (borderWidth > 0.dp && borderColor != Color.Transparent) {
                    Modifier.border(borderWidth, borderColor, resolvedShape)
                } else {
                    Modifier
                }
            )
            .padding(resolvedPadding),
        content = content
    )
}

@Composable
fun OneUiAnimatedSection(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(tween(150)),
        exit = shrinkVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut(tween(110))
    ) {
        content()
    }
}

@Composable
fun OneUiSwitch(
    checked: Boolean,
    activeTrackColor: Color = OneUiEmerald,
    inactiveTrackColor: Color = OneUiTrack,
    modifier: Modifier = Modifier
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) activeTrackColor else inactiveTrackColor,
        animationSpec = tween(180),
        label = "oneUiSwitchTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "oneUiSwitchThumb"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color.White else OneUiTextSecondary,
        animationSpec = tween(160),
        label = "oneUiSwitchThumbColor"
    )

    Box(
        modifier = modifier
            .width(50.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(trackColor),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

class AnchorBoundsHolder {
    var bounds by mutableStateOf<Rect?>(null)
}

@Composable
fun rememberAnchorBounds(): AnchorBoundsHolder = remember { AnchorBoundsHolder() }

fun Modifier.trackAnchorBounds(holder: AnchorBoundsHolder): Modifier =
    this.onGloballyPositioned { coords ->
        if (coords.isAttached) {
            val rect = coords.boundsInRoot()
            if (holder.bounds != rect) {
                holder.bounds = rect
            }
        }
    }

class OneUiOverlayEntry(
    val key: Any,
    val onDismissRequestState: State<() -> Unit>,
    val originBoundsState: State<Rect?>,
    val scrimColorState: State<Color>,
    val horizontalPaddingState: State<Dp>,
    val verticalPaddingState: State<Dp>,
    val contentState: State<@Composable BoxScope.(triggerDismiss: (() -> Unit) -> Unit) -> Unit>
) {
    var requestDismiss: (() -> Unit)? = null
}

class OneUiOverlayHostState {
    val entries = mutableStateListOf<OneUiOverlayEntry>()
}

val LocalOneUiOverlayHost = staticCompositionLocalOf<OneUiOverlayHostState?> { null }

/**
 * In-window overlay portal host.
 * Renders origin-anchored menus and dialogs directly inside the main window hierarchy
 * so origin bounds and resting target bounds share the exact same root coordinate space,
 * stacked overlays compose cleanly, and system Back triggers smooth collapse-to-origin.
 */
@Composable
fun OneUiOverlayHost(
    content: @Composable BoxScope.() -> Unit
) {
    val hostState = remember { OneUiOverlayHostState() }

    CompositionLocalProvider(LocalOneUiOverlayHost provides hostState) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()

            hostState.entries.forEach { entry ->
                key(entry.key) {
                    OneUiOverlayLayer(entry = entry)
                }
            }
        }
    }

    if (hostState.entries.isNotEmpty()) {
        BackHandler(enabled = true) {
            hostState.entries.lastOrNull()?.requestDismiss?.invoke()
        }
    }
}

@Composable
private fun OneUiOverlayLayer(entry: OneUiOverlayEntry) {
    val metrics = LocalLeafUiMetrics.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var restingBounds by remember { mutableStateOf<Rect?>(null) }
    var isDismissing by remember { mutableStateOf(false) }

    val effectiveHPadding = if (metrics.isCompactWidth) {
        minOf(entry.horizontalPaddingState.value, 12.dp)
    } else {
        entry.horizontalPaddingState.value
    }
    val effectiveVPadding = if (metrics.isShortHeight) {
        minOf(entry.verticalPaddingState.value, 14.dp)
    } else {
        entry.verticalPaddingState.value
    }

    val triggerDismiss: (() -> Unit) -> Unit = { afterDismiss ->
        if (!isDismissing) {
            isDismissing = true
            scope.launch {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 165, easing = FastOutSlowInEasing)
                )
                entry.onDismissRequestState.value()
                afterDismiss()
            }
        }
    }

    SideEffect {
        entry.requestDismiss = { triggerDismiss {} }
    }
    DisposableEffect(entry) {
        onDispose { entry.requestDismiss = null }
    }

    LaunchedEffect(entry.key) {
        var frameWait = 0
        while (restingBounds == null && frameWait < 2) {
            withFrameNanos { }
            frameWait++
        }
        if (!isDismissing && progress.targetValue < 1f) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = 510f
                )
            )
        }
    }

    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(entry.scrimColorState.value)
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = { triggerDismiss {} }
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .displayCutoutPadding()
                .imePadding()
                .padding(
                    horizontal = effectiveHPadding,
                    vertical = effectiveVPadding
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = metrics.maxDialogWidthDp.dp)
                    .wrapContentSize()
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached && !isDismissing) {
                            val next = coords.boundsInRoot()
                            if (restingBounds != next) {
                                restingBounds = next
                            }
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            val p = progress.value.coerceIn(0f, 1f)
                            val target = restingBounds
                            val origin = entry.originBoundsState.value
                            if (target != null && origin != null &&
                                target.width > 1f && target.height > 1f &&
                                origin.width > 1f && origin.height > 1f
                            ) {
                                val minScaleX = (origin.width / target.width).coerceIn(0.18f, 0.96f)
                                val minScaleY = (origin.height / target.height).coerceIn(0.14f, 0.96f)
                                val dx = origin.center.x - target.center.x
                                val dy = origin.center.y - target.center.y
                                scaleX = minScaleX + (1f - minScaleX) * p
                                scaleY = minScaleY + (1f - minScaleY) * p
                                translationX = dx * (1f - p)
                                translationY = dy * (1f - p)
                                alpha = (p / 0.22f).coerceIn(0f, 1f)
                            } else {
                                val s = 0.88f + 0.12f * p
                                scaleX = s
                                scaleY = s
                                translationY = 24f * (1f - p)
                                alpha = (p / 0.24f).coerceIn(0f, 1f)
                            }
                        }
                        .clickable(
                            interactionSource = cardInteraction,
                            indication = null,
                            onClick = {}
                        )
                ) {
                    val render = entry.contentState.value
                    render(this, triggerDismiss)
                }
            }
        }
    }
}

/**
 * Origin-anchored dialog/menu container:
 * - Expands cleanly out from the tapped button, tile, row, or icon bounds.
 * - Collapses smoothly right back into that exact origin position before unmounting.
 */
@Composable
fun AnchoredDialog(
    onDismissRequest: () -> Unit,
    originBounds: Rect? = null,
    scrimColor: Color = Color(0xC8000000),
    horizontalPadding: Dp = 18.dp,
    verticalPadding: Dp = 26.dp,
    content: @Composable BoxScope.(triggerDismiss: (() -> Unit) -> Unit) -> Unit
) {
    val overlayHost = LocalOneUiOverlayHost.current
    val currentOnDismiss = rememberUpdatedState(onDismissRequest)
    val currentOrigin = rememberUpdatedState(originBounds)
    val currentScrim = rememberUpdatedState(scrimColor)
    val currentHPadding = rememberUpdatedState(horizontalPadding)
    val currentVPadding = rememberUpdatedState(verticalPadding)
    val currentContent = rememberUpdatedState(content)

    if (overlayHost != null) {
        val overlayKey = remember { Any() }
        val entry = remember(overlayKey) {
            OneUiOverlayEntry(
                key = overlayKey,
                onDismissRequestState = currentOnDismiss,
                originBoundsState = currentOrigin,
                scrimColorState = currentScrim,
                horizontalPaddingState = currentHPadding,
                verticalPaddingState = currentVPadding,
                contentState = currentContent
            )
        }
        DisposableEffect(overlayHost, entry) {
            overlayHost.entries.add(entry)
            onDispose {
                overlayHost.entries.remove(entry)
            }
        }
        return
    }

    val metrics = LocalLeafUiMetrics.current
    val effectiveHPadding = if (metrics.isCompactWidth) minOf(horizontalPadding, 12.dp) else horizontalPadding
    val effectiveVPadding = if (metrics.isShortHeight) minOf(verticalPadding, 14.dp) else verticalPadding

    // Fallback path when rendered outside OneUiOverlayHost (e.g., Compose previews)
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var restingBounds by remember { mutableStateOf<Rect?>(null) }
    var isDismissing by remember { mutableStateOf(false) }

    val triggerDismiss: (() -> Unit) -> Unit = { afterDismiss ->
        if (!isDismissing) {
            isDismissing = true
            scope.launch {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 165, easing = FastOutSlowInEasing)
                )
                currentOnDismiss.value()
                afterDismiss()
            }
        }
    }

    LaunchedEffect(Unit) {
        var frameWait = 0
        while (restingBounds == null && frameWait < 2) {
            withFrameNanos { }
            frameWait++
        }
        if (!isDismissing && progress.targetValue < 1f) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.88f,
                    stiffness = 510f
                )
            )
        }
    }

    Dialog(
        onDismissRequest = { triggerDismiss {} },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current
        SideEffect {
            val win = (dialogView.parent as? DialogWindowProvider)?.window
            win?.setDimAmount(0f)
            win?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val dialogLoc = remember { IntArray(2) }
        val scrimInteraction = remember { MutableInteractionSource() }
        val cardInteraction = remember { MutableInteractionSource() }

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                    .background(scrimColor)
                    .clickable(
                        interactionSource = scrimInteraction,
                        indication = null,
                        onClick = { triggerDismiss {} }
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = effectiveHPadding, vertical = effectiveVPadding),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = metrics.maxDialogWidthDp.dp)
                        .wrapContentSize()
                        .onGloballyPositioned { coords ->
                            if (coords.isAttached && !isDismissing) {
                                dialogView.getLocationOnScreen(dialogLoc)
                                val next = coords.boundsInRoot().translate(
                                    dialogLoc[0].toFloat(),
                                    dialogLoc[1].toFloat()
                                )
                                if (restingBounds != next) {
                                    restingBounds = next
                                }
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .graphicsLayer {
                                val p = progress.value.coerceIn(0f, 1f)
                                val target = restingBounds
                                val origin = originBounds
                                if (target != null && origin != null &&
                                    target.width > 1f && target.height > 1f &&
                                    origin.width > 1f && origin.height > 1f
                                ) {
                                    val minScaleX = (origin.width / target.width).coerceIn(0.18f, 0.96f)
                                    val minScaleY = (origin.height / target.height).coerceIn(0.14f, 0.96f)
                                    val dx = origin.center.x - target.center.x
                                    val dy = origin.center.y - target.center.y
                                    scaleX = minScaleX + (1f - minScaleX) * p
                                    scaleY = minScaleY + (1f - minScaleY) * p
                                    translationX = dx * (1f - p)
                                    translationY = dy * (1f - p)
                                    alpha = (p / 0.22f).coerceIn(0f, 1f)
                                } else {
                                    val s = 0.88f + 0.12f * p
                                    scaleX = s
                                    scaleY = s
                                    translationY = 24f * (1f - p)
                                    alpha = (p / 0.24f).coerceIn(0f, 1f)
                                }
                            }
                            .clickable(
                                interactionSource = cardInteraction,
                                indication = null,
                                onClick = {}
                            )
                    ) {
                        content(triggerDismiss)
                    }
                }
            }
        }
    }
}

@Composable
fun OneUiInfoButton(
    onClick: (Rect?) -> Unit,
    tint: Color = OneUiMint,
    badgeBg: Color = OneUiInnerTileElevated,
    modifier: Modifier = Modifier
) {
    val metrics = LocalLeafUiMetrics.current
    val btnScale = metrics.spacingScale.coerceIn(0.88f, 1.10f)
    val anchor = rememberAnchorBounds()
    Box(
        modifier = modifier
            .size((26f * btnScale).dp)
            .trackAnchorBounds(anchor)
            .clip(CircleShape)
            .background(badgeBg)
            .oneUiBounceClick(pressedScale = 0.88f) { onClick(anchor.bounds) },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Info",
            tint = tint,
            modifier = Modifier.size((15f * btnScale).dp)
        )
    }
}

@Composable
fun OneUiFeatureInfoDialog(
    featureKey: FeatureInfoKey,
    originBounds: Rect? = null,
    onDismiss: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val spec: FeatureInfoSpec = remember(featureKey) {
        FeatureGuideCatalog.getFeatureInfo(featureKey)
    }
    val accent = if (spec.isCaution) OneUiDangerRed else OneUiEmerald
    val badgeBg = if (spec.isCaution) OneUiDangerBadge else OneUiActiveSurface

    AnchoredDialog(
        onDismissRequest = onDismiss,
        originBounds = originBounds,
        scrimColor = Color(0xC8000000),
        horizontalPadding = metrics.screenHorizontalPaddingDp.dp,
        verticalPadding = 24.dp
    ) { dismiss ->
        OneUiCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = metrics.maxMenuHeightDp.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(metrics.cardCornerRadiusDp.dp),
            containerColor = OneUiCardSurfaceAlt,
            animateSize = false,
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
                    Box(
                        modifier = Modifier
                            .size(metrics.badgeMediumSizeDp.dp)
                            .clip(CircleShape)
                            .background(badgeBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = spec.icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.52f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = spec.title,
                        fontFamily = IosRoundedFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary,
                        fontSize = metrics.cardTitleSp.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "${spec.whatItDoes} ${spec.whenToUse}",
                fontFamily = IosRoundedFontFamily,
                fontSize = metrics.bodySp.sp,
                fontWeight = FontWeight.SemiBold,
                color = OneUiTextPrimary,
                lineHeight = (metrics.bodySp + 6f).sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                    .background(OneUiInnerTile)
                    .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 11.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Lightbulb,
                    contentDescription = null,
                    tint = OneUiAmber,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = spec.tip,
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.subtitleSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OneUiSage,
                    lineHeight = (metrics.subtitleSp + 5f).sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                    .clip(CircleShape)
                    .background(OneUiEmerald)
                    .oneUiBounceClick(pressedScale = 0.97f) { dismiss {} },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Got It",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.bodySp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = OneUiEmeraldDeep
                )
            }
        }
    }
}

@Composable
fun OneUiOnboardingTutorDialog(
    originBounds: Rect? = null,
    onFinish: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val steps = remember { FeatureGuideCatalog.tutorSteps }
    var currentIndex by remember { mutableIntStateOf(0) }
    val step = steps[currentIndex]
    val isLast = currentIndex == steps.lastIndex
    val accent = if (step.isBlueAccent) OneUiCyan else OneUiEmerald
    val ctaTextColor = if (step.isBlueAccent) OneUiCyanDeep else OneUiEmeraldDeep

    AnchoredDialog(
        onDismissRequest = onFinish,
        originBounds = originBounds,
        scrimColor = Color(0xD4000000),
        horizontalPadding = metrics.screenHorizontalPaddingDp.dp,
        verticalPadding = 24.dp
    ) { dismiss ->
        OneUiCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = metrics.maxMenuHeightDp.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(metrics.cardCornerRadiusDp.dp),
            containerColor = OneUiCardSurfaceAlt,
            animateSize = false,
            contentPadding = PaddingValues(metrics.cardPaddingDp.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = step.badge,
                    fontFamily = IosRoundedFontFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Skip",
                    fontFamily = IosRoundedFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = OneUiTextSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OneUiInnerTile)
                        .oneUiBounceClick(pressedScale = 0.92f) { dismiss {} }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            AnimatedContent(
                targetState = currentIndex,
                transitionSpec = {
                    val forward = targetState >= initialState
                    (fadeIn(tween(180)) + slideInHorizontally(
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) { w -> if (forward) w / 4 else -w / 4 }).togetherWith(
                        fadeOut(tween(110)) + slideOutHorizontally(tween(140)) { w -> if (forward) -w / 4 else w / 4 }
                    )
                },
                label = "tutorStepContent"
            ) { idx ->
                val s = steps[idx]
                val sAccent = if (s.isBlueAccent) OneUiCyan else OneUiEmerald
                val sBadgeBg = if (s.isBlueAccent) OneUiCyanSurface else OneUiActiveSurface

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(metrics.badgeLargeSizeDp.dp)
                                .clip(CircleShape)
                                .background(sBadgeBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = s.icon,
                                contentDescription = null,
                                tint = sAccent,
                                modifier = Modifier.size((metrics.badgeLargeSizeDp * 0.50f).dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.title,
                                fontFamily = IosRoundedFontFamily,
                                fontSize = metrics.headlineSp.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = OneUiTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = s.tileFocus,
                                fontFamily = IosRoundedFontFamily,
                                fontSize = metrics.subtitleSp.sp,
                                fontWeight = FontWeight.Bold,
                                color = OneUiMint
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = s.explanation,
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.bodySp.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OneUiTextPrimary,
                        lineHeight = (metrics.bodySp + 6f).sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                            .background(OneUiInnerTile)
                            .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = OneUiAmber,
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = s.proTip,
                            fontFamily = IosRoundedFontFamily,
                            fontSize = metrics.subtitleSp.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OneUiSage,
                            lineHeight = (metrics.subtitleSp + 5f).sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                steps.forEachIndexed { idx, _ ->
                    val selected = idx == currentIndex
                    val dotWidth by animateDpAsState(
                        targetValue = if (selected) 24.dp else 7.dp,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                        label = "tutorDotWidth"
                    )
                    val dotColor by animateColorAsState(
                        targetValue = if (selected) accent else OneUiTrack,
                        animationSpec = tween(180),
                        label = "tutorDotColor"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .width(dotWidth)
                            .height(7.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (currentIndex > 0) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(CircleShape)
                            .background(OneUiInnerTile)
                            .oneUiBounceClick(pressedScale = 0.96f) { currentIndex-- },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Back",
                            fontFamily = IosRoundedFontFamily,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = OneUiTextSecondary
                        )
                    }
                }

                val ctaBg by animateColorAsState(
                    targetValue = accent,
                    animationSpec = tween(180),
                    label = "tutorCtaBg"
                )

                Box(
                    modifier = Modifier
                        .weight(if (currentIndex > 0) 1.35f else 1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .clip(CircleShape)
                        .background(ctaBg)
                        .oneUiBounceClick(pressedScale = 0.96f) {
                            if (isLast) dismiss {} else currentIndex++
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isLast) "Start Using App" else "Next Step",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = ctaTextColor
                    )
                }
            }
        }
    }
}
