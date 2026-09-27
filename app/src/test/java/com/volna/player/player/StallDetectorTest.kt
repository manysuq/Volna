package com.volna.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сторож зависания.
 *
 * Главный случай появился из живого лога: во время переподключения плеер стоит
 * в простое, и сторож объявлял это зависанием, отправляя ещё один запрос к
 * YouTube поверх уже идущего. От этого отказы приходили чаще.
 */
class StallDetectorTest {

    private val ready = StallDetector.STATE_READY
    private val idle = StallDetector.STATE_IDLE
    private val ended = StallDetector.STATE_ENDED
    private val buffering = 2

    @Test
    fun `растущая позиция застоя не даёт`() {
        assertEquals(0, StallDetector.nextStreak(true, ready, 5_000, 4_000, 0))
        assertEquals(0, StallDetector.nextStreak(true, ready, 9_000, 4_000, 3))
    }

    @Test
    fun `стоящая позиция накапливает застой`() {
        assertEquals(1, StallDetector.nextStreak(true, ready, 4_000, 4_000, 0))
        assertEquals(2, StallDetector.nextStreak(true, ready, 4_000, 4_000, 1))
    }

    @Test
    fun `одинаковая позиция два замера подряд даёт зависание`() {
        val first = StallDetector.nextStreak(true, ready, 62_000, 62_000, 0)
        val second = StallDetector.nextStreak(true, ready, 62_000, 62_000, first)
        assertEquals(2, second)
    }

    @Test
    fun `на паузе застоя нет`() {
        assertEquals(0, StallDetector.nextStreak(false, ready, 62_000, 62_000, 5))
    }

    /**
     * Главный случай бага: приложение само остановило поток, чтобы взять
     * свежую ссылку. Плеер стоит в простое, и ждать тут нечего.
     */
    @Test
    fun `простой между переподключениями зависанием не считается`() {
        assertEquals(0, StallDetector.nextStreak(true, idle, 0, 0, 1))
    }

    /** Дослушал до конца — дальше идти некуда. */
    @Test
    fun `конец трека зависанием не считается`() {
        assertEquals(0, StallDetector.nextStreak(true, ended, 180_000, 180_000, 1))
    }

    /** Буферизация — нормальная работа, застой там ещё не означает обрыв. */
    @Test
    fun `буферизация копится, но не докладывает о зависании`() {
        val streak = StallDetector.nextStreak(true, buffering, 0, 0, 5)
        assertEquals(6, streak)
    }

    @Test
    fun `перемотка назад сбрасывает застой`() {
        assertEquals(0, StallDetector.nextStreak(true, ready, 10_000, 62_000, 2))
    }

    @Test
    fun `первый замер не считается застоем`() {
        assertEquals(0, StallDetector.nextStreak(true, ready, 0, -1, 0))
    }
}
