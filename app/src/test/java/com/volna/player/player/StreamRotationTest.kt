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

    /**
     * Отказ адреса НЕ должен вызывать немедленное обновление.
     *
     * Из ночного отчёта: ссылка менялась каждые 2-3 секунды, при битрейте
     * opus это в десять раз быстрее, чем позволяет порог в 400 КБ. Значит
     * новую ссылку брали не по объёму, а по факту неудачного открытия — и
     * так выедался лимит запросов к YouTube, после чего не открывалась уже
     * никакая. Пока не открылась ни одна ссылка, брать следующую бессмысленно.
     */
    @Test
    fun `отказ адреса не вызывает немедленного обновления`() {
        assertFalse(
            "при отказе ждём порога по объёму, а не гонимся за новой ссылкой",
            StreamRotation.shouldRotate(consumedBytes = 10, forced = true),
        )
    }

    /** Порог по объёму работает независимо от отказа. */
    @Test
    fun `исчерпанная квота обновляет и без отказа`() {
        assertTrue(
            StreamRotation.shouldRotate(
                consumedBytes = StreamRotation.ROTATE_AFTER_BYTES,
                forced = false,
            ),
        )
    }
}
