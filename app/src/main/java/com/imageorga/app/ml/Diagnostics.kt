package com.imageorga.app.ml

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the full details of the last acceleration failure so they can be read and shared
 * from Settings. The model runtime reports most of what went wrong only in the system log,
 * so the app's own recent log lines are stored alongside the error.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val MAX_LOG_CHARS = 60_000
    private val state = MutableStateFlow(false)

    /** True when a report is stored. */
    val available: StateFlow<Boolean> get() = state

    private fun file(context: Context) = File(context.filesDir, "last-acceleration-error.txt")

    fun init(context: Context) {
        state.value = file(context).exists()
    }

    fun record(context: Context, title: String, attempts: List<Pair<String, Throwable>>, notes: List<String>) {
        try {
            val text = buildString {
                appendLine(title)
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                if (Build.VERSION.SDK_INT >= 31) appendLine("Chip: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
                notes.forEach { appendLine(it) }
                attempts.forEach { (what, error) ->
                    appendLine()
                    appendLine("--- $what")
                    appendLine(error.toString())
                }
                appendLine()
                appendLine("--- app log")
                append(ownLog())
            }
            file(context).writeText(text)
            state.value = true
        } catch (t: Throwable) {
            Log.w(TAG, "could not store report", t)
        }
    }

    fun read(context: Context): String? = try {
        file(context).takeIf { it.exists() }?.readText()
    } catch (t: Throwable) {
        null
    }

    fun clear(context: Context) {
        file(context).delete()
        state.value = false
    }

    /** The app's own recent log lines; an app may always read those. */
    private fun ownLog(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-t", "600", "--pid=${Process.myPid()}", "-v", "brief")
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(3, TimeUnit.SECONDS)
        out.takeLast(MAX_LOG_CHARS)
    } catch (t: Throwable) {
        "(log not available: ${t.message})"
    }
}
