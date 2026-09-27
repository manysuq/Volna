package com.volna.player.player

/**
 * Заголовки сетевого запроса к аудиопотоку.
 *
 * Вынесено отдельно от [RangeDataSource], чтобы правило можно было проверить
 * тестом без Android-классов.
 */
internal object StreamHeaders {

    const val RANGE = "Range"

    const val USER_AGENT = "User-Agent"

    /**
     * Диапазон для первого запроса.
     *
     * `bytes=0-` — «от начала и до конца», а не кусок: длину потока ExoPlayer
     * на первом открытии ещё не знает, и обрезанный диапазон сбил бы его
     * подсчёт.
     */
    const val FIRST_RANGE = "bytes=0-"

    /**
     * Добавляет [RANGE], если его ещё нет.
     *
     * Нужен из-за особенности YouTube: на запрос **без** Range он отвечает 403,
     * на запрос с Range — 206. Проверка ссылки в [com.volna.player.stream.StreamResolver]
     * Range шлёт, а ExoPlayer при первом открытии файла — нет, потому что
     * `buildRangeRequestHeader` для позиции 0 возвращает null. Отсюда были
     * «403, потом 403, и только с третьего раза играет».
     */
    fun withRange(headers: Map<String, String>): Map<String, String> {
        if (headers.keys.any { it.equals(RANGE, ignoreCase = true) }) return headers
        return headers + (RANGE to FIRST_RANGE)
    }
}