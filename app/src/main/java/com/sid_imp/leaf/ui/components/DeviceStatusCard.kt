package com.sid_imp.leaf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sid_imp.leaf.fastboot.DeviceInfo
import com.sid_imp.leaf.fastboot.FastbootMode
import com.sid_imp.leaf.ui.theme.*

@Composable
fun DeviceStatusCard(
    mode: FastbootMode,
    deviceInfo: DeviceInfo?,
    onRefreshScan: () -> Unit,
    isAdbAwaitingAuth: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isFastbootConnected = mode.isBootloaderOrFastbootd
    val isAdbConnected = mode.isAdbConnected
    val isConnected = isFastbootConnected || isAdbConnected

    val targetAccent = when (mode) {
        FastbootMode.BOOTLOADER, FastbootMode.FASTBOOTD -> OneUiEmerald
        FastbootMode.ADB_SIDELOAD -> OneUiCyan
        FastbootMode.ADB_RECOVERY, FastbootMode.ADB -> OneUiMint
        FastbootMode.RECONNECTING -> OneUiCyan
        FastbootMode.DISCONNECTED -> if (isAdbAwaitingAuth) OneUiAmber else OneUiDangerRed
    }
    val accentColor by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(220),
        label = "oneUiStatusAccent"
    )

    val containerBg by animateColorAsState(
        targetValue = when {
            mode == FastbootMode.ADB_SIDELOAD || mode == FastbootMode.RECONNECTING -> OneUiCyanSurface
            isFastbootConnected || isAdbConnected -> OneUiActiveSurface
            isAdbAwaitingAuth -> OneUiAmberSurface
            else -> OneUiCardSurface
        },
        animationSpec = tween(220),
        label = "oneUiStatusBg"
    )

    val badgeIcon = when {
        mode == FastbootMode.ADB_SIDELOAD -> Icons.Default.SystemUpdate
        isFastbootConnected -> Icons.Default.Bolt
        isAdbConnected -> Icons.Default.Usb
        else -> Icons.Default.UsbOff
    }

    val titleText = when (mode) {
        FastbootMode.BOOTLOADER -> "Bootloader Ready"
        FastbootMode.FASTBOOTD -> "Fastbootd Ready"
        FastbootMode.ADB_SIDELOAD -> "ADB Sideload Ready"
        FastbootMode.ADB_RECOVERY -> "Recovery ADB Ready"
        FastbootMode.ADB -> "ADB Connected"
        FastbootMode.RECONNECTING -> "Reconnecting..."
        FastbootMode.DISCONNECTED -> if (isAdbAwaitingAuth) "Waiting for USB Allow" else "No Phone Connected"
    }

    // Never display device serial number for privacy
    val subtitleText = when (mode) {
        FastbootMode.BOOTLOADER, FastbootMode.FASTBOOTD -> {
            val slotSuffix = deviceInfo?.currentSlot?.takeIf {
                it.equals("a", true) || it.equals("b", true)
            }?.uppercase()?.let { " • Slot $it" } ?: ""
            if (deviceInfo != null && deviceInfo.productName != "Unknown") {
                "${deviceInfo.productName}$slotSuffix"
            } else {
                "Connected over USB OTG$slotSuffix"
            }
        }
        FastbootMode.ADB_SIDELOAD -> {
            if (deviceInfo != null && deviceInfo.productName != "Unknown" && deviceInfo.productName != "Recovery Sideload") {
                "${deviceInfo.productName} • Recovery Sideload"
            } else {
                "Ready to sideload ZIP packages"
            }
        }
        FastbootMode.ADB_RECOVERY -> {
            if (deviceInfo != null && deviceInfo.productName != "Unknown" && deviceInfo.productName != "ADB Recovery") {
                "${deviceInfo.productName} • Recovery"
            } else {
                "Recovery ADB active"
            }
        }
        FastbootMode.ADB -> {
            if (deviceInfo != null && deviceInfo.productName != "Unknown") {
                "${deviceInfo.productName} • ADB Ready"
            } else {
                "ADB device ready"
            }
        }
        FastbootMode.RECONNECTING -> "Waiting for phone to reconnect..."
        FastbootMode.DISCONNECTED -> {
            if (isAdbAwaitingAuth) {
                "Tap Allow on phone, or open Apply from ADB"
            } else {
                "Plug in Fastboot or Recovery Sideload cable"
            }
        }
    }

    val isPulsing = mode == FastbootMode.RECONNECTING || isAdbAwaitingAuth
    val dotAlpha = if (isPulsing) {
        val infinite = rememberInfiniteTransition(label = "statusPulse")
        val a by infinite.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(750, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "statusDotAlpha"
        )
        a
    } else {
        1f
    }

    val metrics = LocalLeafUiMetrics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(metrics.cardCornerRadiusDp.dp))
            .background(containerBg)
            .oneUiBounceClick(pressedScale = 0.975f, onClick = onRefreshScan)
            .padding(
                horizontal = metrics.cardPaddingDp.dp,
                vertical = (metrics.innerCardPaddingDp).dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            val iconCircleBg = if (mode == FastbootMode.DISCONNECTED && !isAdbAwaitingAuth) {
                OneUiDangerBadge
            } else {
                accentColor.copy(alpha = 0.16f)
            }
            Box(
                modifier = Modifier
                    .size(metrics.badgeMediumSizeDp.dp)
                    .clip(CircleShape)
                    .background(iconCircleBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.bodySp.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitleText,
                    fontFamily = IosRoundedFontFamily,
                    fontSize = metrics.subtitleSp.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isAdbAwaitingAuth && !isConnected) OneUiAmber else OneUiTextSecondary,
                    maxLines = if (metrics.isCompactWidth) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .size(10.dp)
                .graphicsLayer { alpha = dotAlpha }
                .clip(CircleShape)
                .background(accentColor)
        )
    }
}
