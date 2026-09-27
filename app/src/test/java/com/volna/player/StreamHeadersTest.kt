package com.volna.player

import com.volna.player.player.StreamHeaders
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты заголовков сетевого запроса к аудиопотоку.
 *
 * Проверяют причину «трек начинает играть только с третьего раза»: YouTube
 * отвечает 403 на запрос без Range и 206 на запрос с ним, а ExoPlayer при
 * первом открытии файла Range не формирует. Проверено на живом потоке
 * (id LBmQKUdKy8g): без Range — HTTP 403, с Range — HTTP 206.
 */
class StreamHeadersTest {

    @Test
    fun `добавляет Range когда его нет`() {
        val headers = StreamHeaders.withRange(mapOf("Accept" to "*/*"))

        assertEquals("bytes=0-", headers["Range"])
    }

    @Test
    fun `не перетирает Range который задал ExoPlayer`() {
        // При перемотке ExoPlayer формирует свой диапазон — подменять его
        // на «с начала» нельзя, иначе перемотка сломалась бы.
        val withRange = mapOf("Range" to "bytes=500000-600000")

        val result = StreamHeaders.withRange(withRange)

        assertEquals("bytes=500000-600000", result["Range"])
    }

    @Test
    fun `учитывает другой регистр названия заголовка`() {
        val result = StreamHeaders.withRange(mapOf("range" to "bytes=10-20"))

        assertEquals("диапазон уже есть, второй не добавляем", 1, result.size)
    }

    @Test
    fun `не ломает остальные заголовки`() {
        val result = StreamHeaders.withRange(mapOf("Accept" to "*/*", "User-Agent" to "volna"))

        assertEquals("*/*", result["Accept"])
        assertEquals("volna", result["User-Agent"])
        assertEquals("bytes=0-", result["Range"])
    }

    @Test
    fun `пустые заголовки тоже получают Range`() {
        assertEquals("bytes=0-", StreamHeaders.withRange(emptyMap())["Range"])
    }
}