package com.sid_imp.leaf.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sid_imp.leaf.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun checkStorageBrowsePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun isSameStorageDir(a: File?, b: File?): Boolean {
    if (a == null || b == null) return false
    return try {
        a.canonicalPath == b.canonicalPath
    } catch (_: Exception) {
        a.absolutePath == b.absolutePath
    }
}

private data class UiBrowserFile(
    val file: File,
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isZip: Boolean,
    val isImg: Boolean,
    val isScript: Boolean,
    val sizeLabel: String
)

private data class UiDirScanResult(
    val files: List<UiBrowserFile>,
    val partitionCount: Int,
    val hasScript: Boolean
)

/**
 * Unified OneUI ROM Picker merging ROM ZIP archives and extracted ROM folders
 * into a single intuitive chooser, with directory scanning moved off the UI thread.
 */
@Composable
fun UnifiedRomPickerDialog(
    originBounds: Rect? = null,
    onDismissRequest: () -> Unit,
    onFolderSelected: (File) -> Unit,
    onZipFileSelected: (File) -> Unit,
    onLaunchSystemZipPicker: () -> Unit,
    onLaunchSystemFolderPicker: () -> Unit,
    onPickFilesAlternative: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasStorageManagerPermission by remember {
        mutableStateOf(checkStorageBrowsePermission(context))
    }

    val downloadsDir = remember {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }
    val phoneRootDir = remember {
        Environment.getExternalStorageDirectory()
    }
    val initialDir = remember {
        if (downloadsDir != null && downloadsDir.exists()) downloadsDir else phoneRootDir
    }

    var currentDir by remember { mutableStateOf(initialDir) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var scanState by remember { mutableStateOf(UiDirScanResult(emptyList(), 0, false)) }
    var removableRoots by remember { mutableStateOf<List<StorageRootOption>>(emptyList()) }
    var showNoSdCardHint by remember { mutableStateOf(false) }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasStorageManagerPermission = granted
        refreshTrigger++
    }

    LaunchedEffect(currentDir, hasStorageManagerPermission, refreshTrigger) {
        val permGranted = withContext(Dispatchers.IO) {
            checkStorageBrowsePermission(context)
        }
        hasStorageManagerPermission = permGranted

        val discoveredVolumes = withContext(Dispatchers.IO) {
            StorageVolumeHelper.discoverRemovableStorageRoots(context)
        }
        removableRoots = discoveredVolumes
        if (discoveredVolumes.isNotEmpty()) {
            showNoSdCardHint = false
        }

        if (permGranted) {
            scanState = withContext(Dispatchers.IO) {
                if (!currentDir.exists() || !currentDir.isDirectory) {
                    return@withContext UiDirScanResult(emptyList(), 0, false)
                }
                val raw = currentDir.listFiles()?.toList().orEmpty()
                val sorted = raw.sortedWith(
                    compareBy<File>(
                        {
                            when {
                                it.isDirectory -> 0
                                it.name.endsWith(".zip", ignoreCase = true) -> 1
                                it.name.endsWith(".img", ignoreCase = true) ||
                                    it.name.endsWith(".bin", ignoreCase = true) ||
                                    it.name.endsWith(".raw", ignoreCase = true) ||
                                    it.name.endsWith(".sh", ignoreCase = true) ||
                                    it.name.endsWith(".bat", ignoreCase = true) -> 2
                                else -> 3
                            }
                        },
                        { it.name.lowercase(Locale.US) }
                    )
                )
                var imgs = 0
                var hasFlashScript = false
                val items = sorted.map { f ->
                    val isDir = f.isDirectory
                    val name = f.name
                    val low = name.lowercase(Locale.US)
                    val isZip = !isDir && low.endsWith(".zip")
                    val isImg = !isDir && (low.endsWith(".img") || low.endsWith(".bin") || low.endsWith(".raw"))
                    val isScript = !isDir && (low.endsWith(".sh") || low.endsWith(".bat"))
                    if (isImg) imgs++
                    if (isScript) hasFlashScript = true
                    UiBrowserFile(
                        file = f,
                        name = name,
                        path = f.absolutePath,
                        isDir = isDir,
                        isZip = isZip,
                        isImg = isImg,
                        isScript = isScript,
                        sizeLabel = if (isDir || isZip) "" else oneUiFormatSize(f.length())
                    )
                }
                UiDirScanResult(items, imgs, hasFlashScript)
            }
        } else {
            scanState = UiDirScanResult(emptyList(), 0, false)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val metrics = LocalLeafUiMetrics.current
    AnchoredDialog(
        onDismissRequest = onDismissRequest,
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
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size((metrics.badgeMediumSizeDp * 0.50f).dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Choose ROM",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            color = OneUiTextPrimary,
                            fontSize = metrics.cardTitleSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Pick a .zip file or a ROM folder",
                            fontFamily = IosRoundedFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = OneUiTextSecondary,
                            fontSize = metrics.captionSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

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

            // Quick System Picker Strip (Works on every phone right away)
            val quickBtnHorizPad = if (metrics.isCompactWidth) 8.dp else 12.dp
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                        .clip(CircleShape)
                        .background(OneUiInnerTile)
                        .oneUiBounceClick(pressedScale = 0.96f) {
                            dismiss { onLaunchSystemZipPicker() }
                        }
                        .padding(horizontal = quickBtnHorizPad, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = null,
                        tint = OneUiEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pick .zip File",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.subtitleSp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                        .clip(CircleShape)
                        .background(OneUiInnerTile)
                        .oneUiBounceClick(pressedScale = 0.96f) {
                            dismiss { onLaunchSystemFolderPicker() }
                        }
                        .padding(horizontal = quickBtnHorizPad, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = OneUiMint,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pick Folder",
                        fontFamily = IosRoundedFontFamily,
                        fontSize = metrics.subtitleSp.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!hasStorageManagerPermission) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                        .background(OneUiInnerTile)
                        .padding(metrics.cardPaddingDp.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(metrics.badgeLargeSizeDp.dp)
                            .clip(CircleShape)
                            .background(OneUiActiveSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderSpecial,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size((metrics.badgeLargeSizeDp * 0.50f).dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Browse Storage Inside App",
                        fontFamily = IosRoundedFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary,
                        fontSize = metrics.cardTitleSp.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Allow storage access to open folders directly (including SD Card), or use Pick .zip File / Pick Folder above.",
                        fontFamily = IosRoundedFontFamily,
                        color = OneUiTextSecondary,
                        fontSize = metrics.subtitleSp.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = (metrics.subtitleSp + 4.5f).sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                            .clip(CircleShape)
                            .background(OneUiEmerald)
                            .oneUiBounceClick(pressedScale = 0.97f) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                        } catch (_: Exception) {}
                                    }
                                } else {
                                    legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Allow Storage Access",
                            fontFamily = IosRoundedFontFamily,
                            color = OneUiEmeraldDeep,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = metrics.bodySp.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                            .clip(CircleShape)
                            .background(OneUiCardSurface)
                            .oneUiBounceClick(pressedScale = 0.97f) {
                                dismiss { onPickFilesAlternative() }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Pick Multiple Files Instead",
                            fontFamily = IosRoundedFontFamily,
                            color = OneUiMint,
                            fontWeight = FontWeight.Bold,
                            fontSize = metrics.subtitleSp.sp
                        )
                    }
                }
            } else {
                val dirFiles = scanState.files
                val partitionCount = scanState.partitionCount
                val hasScript = scanState.hasScript
                val isAtStorageRoot = isSameStorageDir(currentDir, phoneRootDir) ||
                    removableRoots.any { isSameStorageDir(currentDir, it.dir) }
                val breadcrumbText = remember(currentDir, phoneRootDir, removableRoots) {
                    val removablePairs = removableRoots.flatMap { opt ->
                        listOf(opt.dir.path to opt.label, opt.id to opt.label).distinct()
                    }
                    StorageVolumeHelper.formatStorageBreadcrumb(
                        currentPath = currentDir.path,
                        phoneRootPath = phoneRootDir?.path,
                        removableRoots = removablePairs
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                            .background(OneUiInnerTile)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val parent = currentDir.parentFile
                        if (!isAtStorageRoot && parent != null && parent.canRead()) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(OneUiActiveSurface)
                                    .oneUiBounceClick(pressedScale = 0.88f) {
                                        currentDir = parent
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Up",
                                    tint = OneUiEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = breadcrumbText,
                            fontFamily = FontFamily.Monospace,
                            color = OneUiMint,
                            fontSize = metrics.captionSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val inDownloads = downloadsDir != null && (
                        isSameStorageDir(currentDir, downloadsDir) ||
                            StorageVolumeHelper.isPathInsideRoot(currentDir.path, downloadsDir.path)
                        )
                    val inPhoneStorage = !inDownloads && phoneRootDir != null && (
                        isSameStorageDir(currentDir, phoneRootDir) ||
                            StorageVolumeHelper.isPathInsideRoot(currentDir.path, phoneRootDir.path)
                        )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (downloadsDir != null && downloadsDir.exists()) {
                            OneUiShortcutChip(
                                label = "Downloads",
                                icon = Icons.Default.Download,
                                isActive = inDownloads
                            ) {
                                showNoSdCardHint = false
                                currentDir = downloadsDir
                            }
                        }
                        if (phoneRootDir != null && phoneRootDir.exists()) {
                            OneUiShortcutChip(
                                label = "Phone Storage",
                                icon = Icons.Default.Smartphone,
                                isActive = inPhoneStorage
                            ) {
                                showNoSdCardHint = false
                                currentDir = phoneRootDir
                            }
                        }
                        if (removableRoots.isNotEmpty()) {
                            removableRoots.forEach { volume ->
                                val isInVolume = isSameStorageDir(currentDir, volume.dir) ||
                                    StorageVolumeHelper.isPathInsideRoot(currentDir.path, volume.dir.path) ||
                                    StorageVolumeHelper.isPathInsideRoot(currentDir.path, volume.id)
                                OneUiShortcutChip(
                                    label = volume.label,
                                    icon = if (volume.isSdCard) Icons.Default.SdCard else Icons.Default.Usb,
                                    isActive = isInVolume
                                ) {
                                    showNoSdCardHint = false
                                    currentDir = volume.dir
                                }
                            }
                        } else {
                            OneUiShortcutChip(
                                label = "SD Card",
                                icon = Icons.Default.SdCard,
                                isActive = showNoSdCardHint
                            ) {
                                scope.launch {
                                    val freshRoots = withContext(Dispatchers.IO) {
                                        StorageVolumeHelper.discoverRemovableStorageRoots(context)
                                    }
                                    removableRoots = freshRoots
                                    if (freshRoots.isNotEmpty()) {
                                        showNoSdCardHint = false
                                        currentDir = freshRoots.first().dir
                                    } else {
                                        showNoSdCardHint = !showNoSdCardHint
                                    }
                                }
                            }
                        }
                    }

                    if (showNoSdCardHint && removableRoots.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(metrics.innerCornerRadiusDp.dp))
                                .background(OneUiAmberSurface)
                                .padding(horizontal = metrics.innerCardPaddingDp.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SdCard,
                                contentDescription = null,
                                tint = OneUiAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "No direct SD Card found on this device. Use Pick .zip File or Pick Folder above to select from external storage.",
                                fontFamily = IosRoundedFontFamily,
                                color = OneUiTextPrimary.copy(alpha = 0.92f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = metrics.captionSp.sp,
                                lineHeight = (metrics.captionSp + 4f).sp
                            )
                        }
                    }

                    if (partitionCount > 0 || hasScript) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(OneUiChipShape)
                                .background(OneUiActiveSurface)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = OneUiEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasScript) {
                                    "ROM folder found ($partitionCount images + flash script)"
                                } else {
                                    "ROM folder found ($partitionCount images)"
                                },
                                fontFamily = IosRoundedFontFamily,
                                color = OneUiEmerald,
                                fontWeight = FontWeight.Bold,
                                fontSize = metrics.captionSp.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (dirFiles.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Nothing in this folder",
                                        fontFamily = IosRoundedFontFamily,
                                        color = OneUiTextSecondary,
                                        fontSize = metrics.bodySp.sp
                                    )
                                }
                            }
                        }

                        items(dirFiles, key = { it.path }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(OneUiChipShape)
                                    .background(if (item.isZip) OneUiCyanSurface.copy(alpha = 0.55f) else Color.Transparent)
                                    .then(
                                        if (item.isDir) {
                                            Modifier.oneUiBounceClick(pressedScale = 0.985f) {
                                                currentDir = item.file
                                            }
                                        } else if (item.isZip) {
                                            Modifier.oneUiBounceClick(pressedScale = 0.985f) {
                                                dismiss { onZipFileSelected(item.file) }
                                            }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        item.isDir -> Icons.Default.Folder
                                        item.isZip -> Icons.Default.FolderZip
                                        item.isImg -> Icons.Default.Memory
                                        item.isScript -> Icons.Default.Terminal
                                        else -> Icons.Default.Description
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        item.isDir -> OneUiMint
                                        item.isZip -> OneUiCyan
                                        item.isImg -> OneUiEmerald
                                        item.isScript -> OneUiMint
                                        else -> OneUiTextMuted
                                    },
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = item.name,
                                    fontFamily = IosRoundedFontFamily,
                                    color = if (item.isDir || item.isZip || item.isImg || item.isScript) OneUiTextPrimary else OneUiTextSecondary,
                                    fontWeight = if (item.isDir || item.isZip || item.isImg) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = metrics.subtitleSp.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (item.isZip) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(OneUiCyan)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "ZIP",
                                            fontFamily = IosRoundedFontFamily,
                                            color = OneUiCyanDeep,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = metrics.microSp.sp
                                        )
                                    }
                                } else if (item.sizeLabel.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = item.sizeLabel,
                                        fontFamily = FontFamily.Monospace,
                                        color = OneUiTextMuted,
                                        fontSize = metrics.microSp.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = metrics.actionPillMinHeightDp.dp)
                            .clip(CircleShape)
                            .background(OneUiEmerald)
                            .oneUiBounceClick(pressedScale = 0.97f) {
                                dismiss { onFolderSelected(currentDir) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (partitionCount > 0) "Use This Folder ($partitionCount files)" else "Use This Folder",
                            fontFamily = IosRoundedFontFamily,
                            color = OneUiEmeraldDeep,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = metrics.bodySp.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PartitionTargetPickerDialog(
    title: String = "Choose Partition Slot",
    currentTarget: String,
    partitions: List<String>,
    originBounds: Rect? = null,
    onDismiss: () -> Unit,
    onSelectPartition: (String) -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
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
                .heightIn(max = (metrics.screenHeightDp * 0.78f).coerceIn(260f, 560f).dp),
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
                            .size(metrics.badgeSmallSizeDp.dp)
                            .clip(CircleShape)
                            .background(OneUiActiveSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = OneUiEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        fontFamily = IosRoundedFontFamily,
                        fontWeight = FontWeight.ExtraBold,
                        color = OneUiTextPrimary,
                        fontSize = metrics.cardTitleSp.sp,
                        maxLines = 1,
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
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (metrics.maxDialogWidthDp >= 460f) 3 else 2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
            ) {
                items(partitions, key = { it }) { stem ->
                    val selected = stem.equals(currentTarget, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(OneUiChipShape)
                            .background(if (selected) OneUiEmerald else OneUiInnerTile)
                            .oneUiBounceClick(pressedScale = 0.96f) {
                                dismiss { onSelectPartition(stem) }
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = stem,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (selected) OneUiEmeraldDeep else OneUiTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OneUiShortcutChip(
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val metrics = LocalLeafUiMetrics.current
    val bg by animateColorAsState(
        targetValue = if (isActive) OneUiEmerald else OneUiInnerTile,
        animationSpec = tween(160),
        label = "shortcutChipBg"
    )
    val fg by animateColorAsState(
        targetValue = if (isActive) OneUiEmeraldDeep else OneUiTextSecondary,
        animationSpec = tween(160),
        label = "shortcutChipFg"
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .oneUiBounceClick(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontFamily = IosRoundedFontFamily,
            fontSize = metrics.captionSp.sp,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun oneUiFormatSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
