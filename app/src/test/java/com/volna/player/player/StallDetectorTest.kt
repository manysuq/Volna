package com.volna.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сторож зависания.
 *
 * Зависание воспроизведения воспроизвести в тесте нельзя, а вот решение
 * «это ещё не зависание» — можно, и именно оно важнее: лишний перезапуск
 * потока рвёт музыку, слишком поздний оставляет пользователя слушать тишину.
 */
class StallDetectorTest {

    @Test
    fun `растущая позиция застоя не даёт`() {
        assertEquals(0, StallDetector.nextStreak(true, 5_000, 4_000, 0))
        assertEquals(0, StallDetector.nextStreak(true, 9_000, 4_000, 3))
    }

    @Test
    fun `стоящая позиция накапливает застой`() {
        assertEquals(1, StallDetector.nextStreak(true, 4_000, 4_000, 0))
        assertEquals(2, StallDetector.nextStreak(true, 4_000, 4_000, 1))
    }

    /** Главный случай бага: буфер кончился, позиция стоит, а ошибки нет. */
    @Test
    fun `одинаковая позиция два замера подряд даёт зависание`() {
        val first = StallDetector.nextStreak(true, 62_000, 62_000, 0)
        val second = StallDetector.nextStreak(true, 62_000, 62_000, first)
        assertEquals(2, second)
    }

    @Test
    fun `на паузе застоя нет`() {
        assertEquals(0, StallDetector.nextStreak(false, 62_000, 62_000, 5))
    }

    /**
     * Перемотка назад позицию уменьшает. Считать это застоем нельзя: это
     * действие пользователя, и после него позиция снова растёт — но если
     * застой накоплен, сторож сработает на пустом месте и дёрнет поток.
     */
    @Test
    fun `перемотка назад сбрасывает застой`() {
        assertEquals(0, StallDetector.nextStreak(true, 10_000, 62_000, 2))
    }

    /** Первый замер: lastPosition = -1, а позиция 0, и это не застой. */
    @Test
    fun `первый замер не считается застоем`() {
        assertEquals(0, StallDetector.nextStreak(true, 0, -1, 0))
    }
}
