package com.fgogotran.util

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Lightweight logging wrapper around android.util.Log.
// All messages are tagged with "FGO/ComponentName" for easy filtering with logcat.
// Logging is opt-in because OCR/debug logs can include user-visible dialogue text.
//
// 除了可选的 logcat 输出外，所有日志还会写入内存环形缓冲（始终开启，
// 与 logcat 开关无关），可在「设置 → 错误纪录 → 调试日志」里直接查看、
// 复制或导出，方便没有 adb 环境的用户排查问题。

object FgoLogger {

    @Volatile
    private var isEnabled: Boolean = false

    private const val BUFFER_CAPACITY = 1000
    private val buffer = ArrayDeque<String>()
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    // Flow traces, data sizes, timing info.
    fun debug(tag: String, message: String) {
        if (isEnabled) Log.d("FGO/$tag", message)
        capture("D", tag, message)
    }

    // State transitions, lifecycle events, user-visible changes.
    fun info(tag: String, message: String) {
        if (isEnabled) Log.i("FGO/$tag", message)
        capture("I", tag, message)
    }

    // Recoverable issues that don't break the pipeline but may degrade output.
    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        if (isEnabled) Log.w("FGO/$tag", message, throwable)
        capture("W", tag, message + throwable?.let { " | ${it.message}" }.orEmpty())
    }

    // Caught exceptions - something definitely went wrong at this point.
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (isEnabled) Log.e("FGO/$tag", message, throwable)
        capture("E", tag, message + throwable?.let { " | ${it.message}" }.orEmpty())
    }

    private fun capture(level: String, tag: String, message: String) {
        val line = buildString {
            append(timestampFormatter.format(Instant.now()))
            append(' ')
            append(level)
            append(" FGO/")
            append(tag)
            append(": ")
            append(message)
        }
        synchronized(buffer) {
            if (buffer.size >= BUFFER_CAPACITY) {
                buffer.removeFirst()
            }
            buffer.addLast(line)
        }
    }

    /** 返回当前缓冲日志快照（最新在最后）。 */
    fun dumpBuffer(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clearBuffer() {
        synchronized(buffer) { buffer.clear() }
    }
}
