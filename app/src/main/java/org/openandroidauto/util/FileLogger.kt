package org.openandroidauto.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * Writes log messages to a file on the device for later retrieval.
 * File location: /sdcard/Android/data/org.openandroidauto/files/aa_log.txt
 */
object FileLogger {
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val file = File(dir, "aa_log.txt")
            writer = PrintWriter(FileWriter(file, false), true)
            log("FileLogger", "=== Session started ===")
        } catch (e: Exception) {
            Log.e("FileLogger", "Failed to init: ${e.message}")
        }
    }

    fun log(tag: String, msg: String) {
        val time = dateFormat.format(Date())
        writer?.println("$time $tag: $msg")
    }

    fun close() {
        log("FileLogger", "=== Session ended ===")
        writer?.close()
        writer = null
    }
}
