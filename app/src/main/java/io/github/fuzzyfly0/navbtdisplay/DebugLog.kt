package io.github.fuzzyfly0.navbtdisplay

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device log (and logcat mirror, tag [TAG]) used to find out what each
 * navigation app actually puts into its notification. Only notifications of the
 * supported navigation apps are ever written here.
 */
object DebugLog {
    const val TAG = "NavBtDisplay"
    private const val FILE_NAME = "debug.log"
    private const val MAX_BYTES = 256 * 1024

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    @Synchronized
    fun log(context: Context, message: String) {
        Log.i(TAG, message)
        if (!Prefs(context).debugLog) return
        runCatching {
            val f = file(context)
            if (f.length() > MAX_BYTES) {
                // Keep the newer half so the log never grows without bound.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
            f.appendText("${timeFormat.format(Date())} $message\n")
        }.onFailure { Log.w(TAG, "Writing debug log failed", it) }
    }

    @Synchronized
    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
    }
}
