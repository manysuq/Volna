package com.volna.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок смены ссылки на поток.
 *
 * Проверяем по замерам на живых ссылках: 512 КБ отдаются, запрос на мегабайт
 * получает 403. Значит менять ссылку надо заранее, иначе разрыв посреди песни
 * никуда не денется.
 */
class StreamRotationTest {

    @Test
    fun `свежая ссылка не трогается`() {
        assertFalse(StreamRotation.shouldRotate(consumedBytes = 0, forced = false))
        assertFalse(StreamRotation.shouldRotate(consumedBytes = 100_000, forced = false))
    }

    /**
     * Порог обязан быть ниже мегабайта: на мегабайт приходит 403.
     *
     * Проверено на живых ссылках: 512 КБ отдаются, 1 МБ — отказ. Значит
     * запас нужен именно на этом промежутке, и на 512 КБ мы уже обязаны
     * перейти на новую ссылку, не дожидаясь отказа.
     */
    @Test
    fun `порог ниже точки отказа`() {
        assertTrue(
            "порог должен быть меньше мегабайта, иначе будет 403",
            StreamRotation.ROTATE_AFTER_BYTES < 1024L * 1024,
        )
        assertTrue(
            "на 512 КБ уже переходим: это запас перед отказом на мегабайте",
            StreamRotation.shouldRotate(consumedBytes = 512L * 1024, forced = false),
        )
        assertFalse(
            "до порога ссылку не трогаем",
            StreamRotation.shouldRotate(
                StreamRotation.ROTATE_AFTER_BYTES - 1, forced = false,
            ),
        )
    }

    @Test
    fun `после отказа обновляем сразу`() {
        assertTrue(StreamRotation.shouldRotate(consumedBytes = 10, forced = true))
    }
}
