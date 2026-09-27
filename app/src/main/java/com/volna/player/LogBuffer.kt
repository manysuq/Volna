package com.volna.player

import android.util.Log

/**
 * Кольцевой буфер последних записей журнала.
 *
 * Нужен для пункта «Скопировать логи» в настройках: без него пользователь не
 * может прислать диагностику — logcat на телефоне без компьютера недоступен.
 *
 * Пишем только то, что нужно для разбора проблем (поиск, резолв, плеер),
 * и не пишем лишнего: буфер живёт в памяти, но текст всё равно может утечь
 * на скриншоте, поэтому запросы пользователя не сохраняем целиком.
 */
object LogBuffer {

    private const val TAG = "Volna"
    private const val MAX_LINES = 400

    private val lines = ArrayDeque<String>()

    /** Пишет строку в буфер и в logcat. */
    fun d(area: String, message: String) {
        add("D", area, message)
    }

    fun w(area: String, message: String) {
        add("W", area, message)
    }

    fun e(area: String, message: String) {
        add("E", area, message)
    }

    private fun add(level: String, area: String, message: String) {
        val stamp = java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.US,
        ).format(java.util.Date())
        val line = "$stamp $level/$area: $message"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        when (level) {
            "E" -> Log.e(TAG, "$area: $message")
            "W" -> Log.w(TAG, "$area: $message")
            else -> Log.d(TAG, "$area: $message")
        }
    }

    /** Текст для буфера обмена; пустая строка, если записей ещё не было. */
    fun dump(header: String): String = synchronized(lines) {
        if (lines.isEmpty()) return ""
        buildString {
            append(header)
            append('\n')
            lines.forEach { append(it).append('\n') }
        }
    }

    fun isEmpty(): Boolean = synchronized(lines) { lines.isEmpty() }
}