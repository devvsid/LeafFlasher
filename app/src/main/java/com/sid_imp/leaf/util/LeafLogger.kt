package com.sid_imp.leaf.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LeafLogger {
    private const val TAG = "LeafLogger"
    private const val MAX_MEMORY_LINES = 10000

    private val logBuffer = mutableListOf<String>()
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var logFileWriter: PrintWriter? = null
    @Volatile
    private var currentLogFile: File? = null

    fun init(context: Context) {
        synchronized(lock) {
            try {
                val logDir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "logs")
                if (!logDir.exists()) logDir.mkdirs()
                val file = File(logDir, "leaf_flasher_latest.log")
                currentLogFile = file
                logFileWriter = PrintWriter(FileWriter(file, true), true)
                log("LeafLogger initialized. Session started at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                log("Host Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) [Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT}]")
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing log file", e)
            }
        }
    }

    fun log(message: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] $message"
        Log.i(TAG, message)

        synchronized(lock) {
            if (logBuffer.size >= MAX_MEMORY_LINES) {
                logBuffer.removeAt(0)
            }
            logBuffer.add(entry)

            try {
                logFileWriter?.println(entry)
            } catch (_: Exception) {}
        }
    }

    fun logError(message: String, throwable: Throwable? = null) {
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp] ERROR: $message" + (throwable?.let { "\n" + Log.getStackTraceString(it) } ?: "")
        Log.e(TAG, message, throwable)

        synchronized(lock) {
            if (logBuffer.size >= MAX_MEMORY_LINES) {
                logBuffer.removeAt(0)
            }
            logBuffer.add(entry)

            try {
                logFileWriter?.println(entry)
            } catch (_: Exception) {}
        }
    }

    fun getAllLogs(): List<String> {
        synchronized(lock) {
            return ArrayList(logBuffer)
        }
    }

    fun clear() {
        synchronized(lock) {
            logBuffer.clear()
            try {
                currentLogFile?.writeText("")
            } catch (_: Exception) {}
        }
    }

    /**
     * Packages full log file, copies to public downloads, and launches system share
     * targeting Telegram or any other document viewer.
     */
    fun shareLogFile(context: Context) {
        try {
            val timestamp = fileDateFormat.format(Date())
            val exportDir = File(context.cacheDir, "shared_logs")
            if (!exportDir.exists()) exportDir.mkdirs()
            val exportFile = File(exportDir, "leaf_flasher_log_$timestamp.txt")

            val allLines = getAllLogs()
            val header = buildString {
                appendLine("================================================================================")
                appendLine("                       LEAF FLASHER COMPREHENSIVE DIAGNOSTIC LOG                ")
                appendLine("================================================================================")
                appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
                appendLine("Host Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Android OS: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("App Version: 2.2.0")
                appendLine("Total Log Entries: ${allLines.size}")
                appendLine("================================================================================")
                appendLine()
            }

            val fullText = header + allLines.joinToString("\n")
            exportFile.writeText(fullText)

            // Also mirror to storage export paths per user rules
            try {
                val targets = listOf(
                    File("/storage/emulated/0/Download/Andronix/"),
                    File("/storage/emulated/0/Download/"),
                    File("/sdcard/download/")
                )
                for (target in targets) {
                    if (target.exists() && target.isDirectory) {
                        File(target, exportFile.name).writeText(fullText)
                    }
                }
            } catch (_: Exception) {}

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Leaf Flasher Log: ${exportFile.name}")
                putExtra(Intent.EXTRA_TEXT, "Leaf Flasher diagnostic log attached for debugging.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Send Log File to Telegram / App")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
            Toast.makeText(context, "Log file generated: ${exportFile.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed sharing log file", e)
            Toast.makeText(context, "Failed to share log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
