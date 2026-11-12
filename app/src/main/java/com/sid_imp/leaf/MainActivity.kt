package com.sid_imp.leaf

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.sid_imp.leaf.ui.MainViewModel
import com.sid_imp.leaf.ui.components.DeviceStatusCard
import com.sid_imp.leaf.ui.components.LeafLogo
import com.sid_imp.leaf.ui.components.OneUiGradientBackground
import com.sid_imp.leaf.ui.components.OneUiOnboardingTutorDialog
import com.sid_imp.leaf.ui.components.OneUiOverlayHost
import com.sid_imp.leaf.ui.components.oneUiBounceClick
import com.sid_imp.leaf.ui.components.rememberAnchorBounds
import com.sid_imp.leaf.ui.components.trackAnchorBounds
import com.sid_imp.leaf.ui.screens.AdbSideloadScreen
import com.sid_imp.leaf.ui.screens.DeviceControlScreen
import com.sid_imp.leaf.ui.screens.PartitionFlasherScreen
import com.sid_imp.leaf.ui.screens.ScriptFlasherScreen
import com.sid_imp.leaf.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.attachHostActivity(this)

        handleIntent(intent)

        setContent {
            LeafFlasherTheme {
                OneUiGradientBackground {
                    OneUiOverlayHost {
                        MainAppScreen(viewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.attachHostActivity(this)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.attachHostActivity(this)
        viewModel.scanDevices(force = false)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val device: android.hardware.usb.UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, android.hardware.usb.UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
        }
        if (device != null) {
            viewModel.handleUsbDeviceAttached(device)
        } else {
            viewModel.scanDevices(force = false)
        }
    }
}

enum class NavigationTab(val title: String, val icon: ImageVector) {
    ROM_SCRIPT("Flasher", Icons.Default.Home),
    PARTITION("Partition", Icons.Default.Layers),
    SIDELOAD("Sideload", Icons.Default.SystemUpdate),
    CONTROLS("Tools", Icons.Default.Tune)
}

@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val metrics = LocalLeafUiMetrics.current
    var selectedTab by remember { mutableStateOf(NavigationTab.ROM_SCRIPT) }
    val showTutor by viewModel.showOnboardingTutor.collectAsState()
    val isFlashingActive by viewModel.isFlashingActive.collectAsState()
    val isZipExtracting by viewModel.isZipExtracting.collectAsState()
    val isSingleFlashing by viewModel.isSinglePartitionFlashing.collectAsState()
    val isSelectiveExtracting by viewModel.isSelectiveExtracting.collectAsState()
    val isSideloading by viewModel.isSideloading.collectAsState()
    val focusMode = isFlashingActive || isZipExtracting || isSingleFlashing || isSelectiveExtracting || isSideloading
    val helpTutorAnchor = rememberAnchorBounds()
    var tutorOriginBounds by remember { mutableStateOf<Rect?>(null) }
    var refreshSpinStep by remember { mutableIntStateOf(0) }
    val refreshRotation by animateFloatAsState(
        targetValue = refreshSpinStep * 360f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "headerRefreshSpin"
    )

    val topContentMaxWidth = (if (focusMode) metrics.maxFocusContentWidthDp else metrics.maxContentWidthDp).dp

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.statusBars.union(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                        )
                    )
                    .padding(
                        horizontal = metrics.screenHorizontalPaddingDp.dp,
                        vertical = if (metrics.isShortHeight) 6.dp else 10.dp
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = topContentMaxWidth)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        LeafLogo(size = metrics.headerLogoSizeDp.dp, animated = false)
                        Spacer(modifier = Modifier.width(if (metrics.isCompactWidth) 8.dp else 10.dp))
                        Column {
                            // Preserved Frosted Header Look: Gradient "Leaf Flasher" title + "by sid_imp" pill (no OneUI text)
                            Text(
                                text = "Leaf Flasher",
                                style = TextStyle(
                                    brush = LeafBrandTitleBrush,
                                    fontFamily = IosRoundedFontFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = metrics.brandTitleSp.sp,
                                    letterSpacing = 0.2.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(if (metrics.isShortHeight) 2.dp else 3.dp))

                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(OneUiActiveSurface)
                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(OneUiEmerald)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "by sid_imp",
                                        style = TextStyle(
                                            brush = LeafCreatorAccentBrush,
                                            fontFamily = IosRoundedFontFamily,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = metrics.captionSp.sp,
                                            letterSpacing = 0.3.sp
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = focusMode,
                        transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(130))) },
                        label = "headerRightMode"
                    ) { inFocus ->
                        if (!inFocus) {
                            Row(
                                modifier = Modifier.padding(start = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (metrics.isCompactWidth) 6.dp else 8.dp)
                            ) {
                                val btnSize = metrics.headerButtonSizeDp.dp
                                val iconSize = (metrics.headerButtonSizeDp * 0.48f).dp
                                Box(
                                    modifier = Modifier
                                        .size(btnSize)
                                        .trackAnchorBounds(helpTutorAnchor)
                                        .clip(CircleShape)
                                        .background(OneUiCardSurface)
                                        .oneUiBounceClick(pressedScale = 0.88f) {
                                            tutorOriginBounds = helpTutorAnchor.bounds
                                            viewModel.openOnboardingTutor()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                                        contentDescription = "Open App Guide",
                                        tint = OneUiMint,
                                        modifier = Modifier.size(iconSize)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(btnSize)
                                        .clip(CircleShape)
                                        .background(OneUiCardSurface)
                                        .oneUiBounceClick(pressedScale = 0.88f) {
                                            refreshSpinStep++
                                            viewModel.scanDevices(force = true)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh USB Connection",
                                        tint = OneUiEmerald,
                                        modifier = Modifier
                                            .size(iconSize)
                                            .graphicsLayer { rotationZ = refreshRotation }
                                    )
                                }
                            }
                        } else {
                            OneUiHeaderFocusBadge(
                                label = when {
                                    isZipExtracting -> "Unpacking"
                                    isSelectiveExtracting -> "Extracting"
                                    isSideloading -> "Sideloading"
                                    isSingleFlashing -> "Installing"
                                    else -> "Flashing"
                                },
                                isBlueAccent = isSideloading
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !focusMode,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f)
                ) + fadeIn(tween(180)),
                exit = shrinkVertically(
                    animationSpec = tween(160, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(120))
            ) {
                // Fluid Spring-Animated OneUI Floating Pill Dock
                val tabs = NavigationTab.entries
                val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
                val activePillColor by animateColorAsState(
                    targetValue = if (selectedTab == NavigationTab.SIDELOAD) OneUiCyan else OneUiEmerald,
                    animationSpec = tween(220),
                    label = "dockActivePillColor"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.union(
                                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                            )
                        )
                        .padding(
                            horizontal = metrics.screenHorizontalPaddingDp.dp,
                            vertical = if (metrics.isShortHeight) 6.dp else 10.dp
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .widthIn(max = metrics.dockMaxWidthDp.dp)
                            .fillMaxWidth()
                            .height(metrics.dockHeightDp.dp)
                            .clip(CircleShape)
                            .background(OneUiCardSurface)
                            .padding(5.dp)
                    ) {
                        val tabWidth = maxWidth / tabs.size
                        val indicatorOffset by animateDpAsState(
                            targetValue = tabWidth * selectedIndex,
                            animationSpec = spring(
                                dampingRatio = 0.82f,
                                stiffness = 460f
                            ),
                            label = "dockSliderOffset"
                        )

                        Box(
                            modifier = Modifier
                                .offset(x = indicatorOffset)
                                .width(tabWidth)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(activePillColor)
                        )

                        val dockIconSize = (19f * metrics.spacingScale.coerceIn(0.88f, 1.08f)).dp
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEach { tab ->
                                val isSelected = selectedTab == tab
                                val activeFg = if (tab == NavigationTab.SIDELOAD) OneUiCyanDeep else OneUiEmeraldDeep
                                val fgColor by animateColorAsState(
                                    targetValue = if (isSelected) activeFg else OneUiTextSecondary,
                                    animationSpec = tween(180),
                                    label = "oneUiDockFg"
                                )
                                val iconScale by animateFloatAsState(
                                    targetValue = if (isSelected) 1.08f else 0.96f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "dockIconScale"
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(CircleShape)
                                        .oneUiBounceClick(pressedScale = 0.94f) { selectedTab = tab }
                                        .padding(horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title,
                                        tint = fgColor,
                                        modifier = Modifier
                                            .size(dockIconSize)
                                            .graphicsLayer {
                                                scaleX = iconScale
                                                scaleY = iconScale
                                            }
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = tab.title,
                                        fontFamily = IosRoundedFontFamily,
                                        fontSize = metrics.dockLabelSp.sp,
                                        letterSpacing = if (metrics.isNarrowOrZoomed) (-0.2).sp else 0.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = fgColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = !focusMode,
                enter = expandVertically(
                    animationSpec = spring(dampingRatio = 0.88f, stiffness = 420f)
                ) + fadeIn(tween(180)),
                exit = shrinkVertically(
                    animationSpec = tween(160, easing = FastOutSlowInEasing)
                ) + fadeOut(tween(120))
            ) {
                OneUiDeviceStatusBar(viewModel = viewModel)
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    (fadeIn(tween(170, easing = FastOutSlowInEasing)) + slideInHorizontally(tween(180, easing = FastOutSlowInEasing)) { w ->
                        if (forward) w / 16 else -w / 16
                    }).togetherWith(
                        fadeOut(tween(100)) + slideOutHorizontally(tween(110)) { w ->
                            if (forward) -w / 16 else w / 16
                        }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = "oneUiTabTransition"
            ) { tab ->
                when (tab) {
                    NavigationTab.ROM_SCRIPT -> ScriptFlasherScreen(viewModel)
                    NavigationTab.PARTITION -> PartitionFlasherScreen(viewModel)
                    NavigationTab.SIDELOAD -> AdbSideloadScreen(viewModel)
                    NavigationTab.CONTROLS -> DeviceControlScreen(viewModel)
                }
            }
        }
    }

    if (showTutor && !focusMode) {
        OneUiOnboardingTutorDialog(
            originBounds = tutorOriginBounds,
            onFinish = { viewModel.completeOnboardingTutor() }
        )
    }
}

@Composable
private fun OneUiHeaderFocusBadge(
    label: String,
    isBlueAccent: Boolean
) {
    val metrics = LocalLeafUiMetrics.current
    val accent = if (isBlueAccent) OneUiCyan else OneUiEmerald
    val bg = if (isBlueAccent) OneUiCyanSurface else OneUiActiveSurface
    val pulseTransition = rememberInfiniteTransition(label = "headerFocusPulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "headerFocusDotAlpha"
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .graphicsLayer { alpha = pulseAlpha }
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.captionSp.sp,
            fontWeight = FontWeight.ExtraBold,
            color = accent,
            maxLines = 1
        )
    }
}

@Composable
private fun OneUiDeviceStatusBar(viewModel: MainViewModel) {
    val metrics = LocalLeafUiMetrics.current
    val mode by viewModel.fastbootMode.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val isAdbAwaitingAuth by viewModel.adbAwaitingAuth.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = metrics.screenHorizontalPaddingDp.dp,
                vertical = 2.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        DeviceStatusCard(
            mode = mode,
            deviceInfo = deviceInfo,
            onRefreshScan = { viewModel.scanDevices(force = true) },
            isAdbAwaitingAuth = isAdbAwaitingAuth,
            modifier = Modifier
                .widthIn(max = metrics.maxContentWidthDp.dp)
                .fillMaxWidth()
        )
    }
}
