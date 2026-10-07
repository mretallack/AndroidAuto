package org.openandroidauto.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Writes log messages to timestamped files. One file per session.
 * Auto-deletes sessions older than 20 most recent.
 * File location: /sdcard/Android/data/org.openandroidauto/files/logs/aa_log_YYYY-MM-DD_HH-mm-ss.txt
 * Latest session also symlinked as aa_log.txt for easy access.
 */
object FileLogger {
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private const val MAX_FILES = 20

    fun init(context: Context) {
        try {
            val baseDir = context.getExternalFilesDir(null) ?: return
            val logDir = File(baseDir, "logs")
            logDir.mkdirs()

            // Create timestamped file
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(logDir, "aa_log_$ts.txt")
            writer = PrintWriter(FileWriter(file, false), true)

            // Also write to aa_log.txt (overwrite) for easy `adb pull`
            val latest = File(baseDir, "aa_log.txt")
            latest.writeText("") // clear
            writer = CombinedWriter(
                PrintWriter(FileWriter(file, false), true),
                PrintWriter(FileWriter(latest, false), true)
            )

            // Cleanup old files
            val logs = logDir.listFiles { f -> f.name.startsWith("aa_log_") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
            logs.drop(MAX_FILES).forEach { it.delete() }

            log("FileLogger", "=== Session started ($ts) ===")
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to init: ${e.message}")
        }
    }

    fun log(tag: String, msg: String) {
        val time = dateFormat.format(Date())
        (writer as? CombinedWriter)?.println("$time $tag: $msg")
            ?: writer?.println("$time $tag: $msg")
    }

    fun close() {
        log("FileLogger", "=== Session ended ===")
        (writer as? CombinedWriter)?.closeAll() ?: writer?.close()
        writer = null
    }

    private class CombinedWriter(
        private val session: PrintWriter,
        private val latest: PrintWriter
    ) : PrintWriter(session) {
        override fun println(x: String?) {
            session.println(x)
            latest.println(x)
        }
        fun closeAll() {
            session.close()
            latest.close()
        }
    }
}
