package com.sid_imp.leaf.ui.components

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

data class StorageRootOption(
    val id: String,
    val label: String,
    val isRemovable: Boolean,
    val isSdCard: Boolean,
    val dir: File
)

object StorageVolumeHelper {

    fun extractStorageRootFromAppFilesDir(appExternalDirPath: String?): String? {
        if (appExternalDirPath.isNullOrBlank()) return null
        val normalized = appExternalDirPath.replace('\\', '/')
        val idx = normalized.indexOf("/Android/data/")
        if (idx <= 0) return null
        val root = normalized.substring(0, idx)
        return root.ifBlank { null }
    }

    fun formatRemovableVolumeLabel(
        rawDescription: String?,
        existingLabels: Set<String>
    ): String {
        val desc = rawDescription?.trim().orEmpty()
        val low = desc.lowercase(Locale.US)
        val isUsb = low.contains("usb") || low.contains("otg")
        val baseLabel = if (isUsb) "USB Drive" else "SD Card"
        if (baseLabel !in existingLabels) return baseLabel
        var index = 2
        while ("$baseLabel $index" in existingLabels) {
            index++
        }
        return "$baseLabel $index"
    }

    fun isPathInsideRoot(path: String, rootPath: String): Boolean {
        val cleanPath = path.trimEnd('/', '\\')
        val cleanRoot = rootPath.trimEnd('/', '\\')
        if (cleanRoot.isEmpty()) return false
        return cleanPath == cleanRoot || cleanPath.startsWith("$cleanRoot/")
    }

    fun formatStorageBreadcrumb(
        currentPath: String,
        phoneRootPath: String?,
        removableRoots: List<Pair<String, String>>
    ): String {
        for ((rootPath, label) in removableRoots) {
            val cleanRoot = rootPath.trimEnd('/', '\\')
            if (isPathInsideRoot(currentPath, cleanRoot)) {
                val rel = currentPath.removePrefix(cleanRoot).trimStart('/', '\\')
                return if (rel.isEmpty()) label else "$label/$rel"
            }
        }
        if (!phoneRootPath.isNullOrBlank()) {
            val cleanPhone = phoneRootPath.trimEnd('/', '\\')
            if (isPathInsideRoot(currentPath, cleanPhone)) {
                val rel = currentPath.removePrefix(cleanPhone).trimStart('/', '\\')
                return if (rel.isEmpty()) "Phone Storage" else "Phone Storage/$rel"
            }
        }
        return currentPath.replace("/storage/emulated/0", "Phone Storage")
    }

    fun discoverRemovableStorageRoots(context: Context): List<StorageRootOption> {
        val phoneRoot = try {
            Environment.getExternalStorageDirectory()
        } catch (_: Exception) {
            null
        }
        val phoneCanonical = phoneRoot?.let {
            try { it.canonicalPath } catch (_: Exception) { it.absolutePath }
        }

        val candidates = linkedMapOf<String, Pair<File, String?>>()
        val storageManager = try {
            context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        } catch (_: Exception) {
            null
        }

        fun addCandidate(dir: File?, rawDescription: String?) {
            if (dir == null || !dir.exists() || !dir.isDirectory || !dir.canRead()) return
            val canonical = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (phoneCanonical != null && canonical == phoneCanonical) return
            val emulated = try { Environment.isExternalStorageEmulated(dir) } catch (_: Exception) { false }
            if (emulated) return
            if (!candidates.containsKey(canonical)) {
                candidates[canonical] = dir to rawDescription
            } else if (!rawDescription.isNullOrBlank() && candidates[canonical]?.second.isNullOrBlank()) {
                candidates[canonical] = dir to rawDescription
            }
        }

        // 1. StorageManager storageVolumes (Android 11+ exposes directory directly)
        if (storageManager != null) {
            try {
                for (vol in storageManager.storageVolumes) {
                    if (vol.isPrimary) continue
                    val state = vol.state
                    val isMounted = state == Environment.MEDIA_MOUNTED ||
                        state == Environment.MEDIA_MOUNTED_READ_ONLY
                    if (!isMounted) continue
                    val desc = try { vol.getDescription(context) } catch (_: Exception) { null }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val dir = vol.directory
                        if (dir != null && (vol.isRemovable || !vol.isEmulated)) {
                            addCandidate(dir, desc)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. ContextCompat.getExternalFilesDirs resolves mounted SD card roots across API 24-34
        try {
            val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            for (extDir in externalDirs) {
                val rootPath = extractStorageRootFromAppFilesDir(extDir?.absolutePath) ?: continue
                val rootFile = File(rootPath)
                val desc = if (storageManager != null) {
                    try {
                        storageManager.getStorageVolume(rootFile)?.getDescription(context)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                addCandidate(rootFile, desc)
            }
        } catch (_: Exception) {}

        // 3. Direct /storage scan fallback for All Files Access
        try {
            val storageDir = File("/storage")
            if (storageDir.exists() && storageDir.canRead()) {
                storageDir.listFiles()?.forEach { child ->
                    val name = child.name.lowercase(Locale.US)
                    if (name !in setOf("emulated", "self", "enc_emulated") && !name.startsWith(".")) {
                        val desc = if (storageManager != null) {
                            try {
                                storageManager.getStorageVolume(child)?.getDescription(context)
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                        addCandidate(child, desc)
                    }
                }
            }
        } catch (_: Exception) {}

        val usedLabels = mutableSetOf<String>()
        val result = mutableListOf<StorageRootOption>()
        candidates.forEach { (canonical, pair) ->
            val (dir, rawDesc) = pair
            val label = formatRemovableVolumeLabel(rawDesc, usedLabels)
            usedLabels.add(label)
            val isSd = !label.startsWith("USB", ignoreCase = true)
            result.add(
                StorageRootOption(
                    id = canonical,
                    label = label,
                    isRemovable = true,
                    isSdCard = isSd,
                    dir = dir
                )
            )
        }
        return result.sortedByDescending { it.isSdCard }
    }
}
