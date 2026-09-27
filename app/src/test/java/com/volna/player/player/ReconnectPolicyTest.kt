package com.volna.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пауза перед повторной попыткой.
 *
 * Тест появился из реального лога, где девять раз подряд шло
 * «переподключение 1/3»: счётчик обнулялся сам у себя, поэтому лимит был
 * недостижим, а пауза не росла. Здесь проверяем ровно то, что сломалось —
 * что номер попытки действительно доходит до паузы.
 */
class ReconnectPolicyTest {

    @Test
    fun `первая попытка ждёт меньше всех`() {
        assertEquals(700L, ReconnectPolicy.delayMs(1))
    }

    @Test
    fun `пауза растёт с номером попытки`() {
        assertTrue(ReconnectPolicy.delayMs(2) > ReconnectPolicy.delayMs(1))
        assertTrue(ReconnectPolicy.delayMs(3) > ReconnectPolicy.delayMs(2))
        assertTrue(ReconnectPolicy.delayMs(5) > ReconnectPolicy.delayMs(4))
    }

    /**
     * Потолок обязателен: лимита попыток теперь нет, а без потолка линейный
     * рост уводил паузу на десятки минут — на третьей минуте музыка выглядит
     * сломанной, даже если ссылка давно могла заработать.
     */
    @Test
    fun `пауза не растёт бесконечно`() {
        val cap = ReconnectPolicy.delayMs(1_000)
        assertEquals(cap, ReconnectPolicy.delayMs(500))
        assertEquals(cap, ReconnectPolicy.delayMs(1_000_000))
        assertTrue("пауза не должна превышать 10 секунд, было $cap", cap <= 10_000L)
    }

    /** Счётчик не должен залипнуть на нуле — из-за этого была вечная попытка 1. */
    @Test
    fun `попытка с некорректным номером не даёт нулевую паузу`() {
        assertEquals(700L, ReconnectPolicy.delayMs(0))
        assertEquals(700L, ReconnectPolicy.delayMs(-5))
    }
}
