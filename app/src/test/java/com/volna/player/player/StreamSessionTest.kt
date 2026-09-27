package com.volna.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смена ссылки на ходу.
 *
 * Ссылка живёт недолго: YouTube даёт её с ограничением по объёму, на живых
 * ссылках 512 КБ отдаются, а запрос на мегабайт получает 403. Поэтому ссылку
 * нужно менять заранее, а по отказу — немедленно.
 */
class StreamSessionTest {

    private var calls = 0

    /** Каждый вызов выдаёт новую ссылку, как это делает резолвер. */
    private fun session() = StreamSession(refreshBlocking = { "ссылка-${++calls}" })

    @Test
    fun `принятая ссылка используется первой`() {
        val s = session()
        s.adopt("первая")
        s.refreshIfNeeded()
        assertEquals("первая", s.url)
        assertEquals("свежую ссылку зря не берём", 0, calls)
    }

    @Test
    fun `в пределах объёма ссылка не меняется`() {
        val s = session()
        s.adopt("первая")
        s.onRead(1000L)
        s.refreshIfNeeded()
        assertEquals("первая", s.url)
    }

    /** Главный случай: отработали объём — меняем до отказа. */
    @Test
    fun `после отработанного объёма берём новую ссылку`() {
        val s = session()
        s.adopt("первая")
        s.onRead(StreamRotation.ROTATE_AFTER_BYTES)
        s.refreshIfNeeded()
        assertEquals("ссылка-1", s.url)
    }

    /** Отказ 403 — обновляем сразу, не ждём следующего порога. */
    @Test
    fun `после отказа обновляем немедленно`() {
        val s = session()
        s.adopt("первая")
        s.onFailed("первая")
        s.refreshIfNeeded()
        assertEquals("ссылка-1", s.url)
    }

    @Test
    fun `новая ссылка начинает отсчёт с нуля`() {
        val s = session()
        s.adopt("первая")
        s.onRead(StreamRotation.ROTATE_AFTER_BYTES)
        s.refreshIfNeeded()
        s.onRead(10L)
        s.refreshIfNeeded()
        // Счёт пошёл заново, второй раз за этот объём не меняем.
        assertEquals("ссылка-1", s.url)
    }

    /**
     * Если ссылку взять не удалось, старую терять нельзя: иначе источник
     * останется вовсе без адреса, и вместо одной ошибки получим пустоту.
     */
    @Test
    fun `неудача обновления не выбрасывает прежнюю ссылку`() {
        val s = StreamSession(refreshBlocking = { null })
        s.adopt("первая")
        s.onRead(StreamRotation.ROTATE_AFTER_BYTES)
        s.refreshIfNeeded()
        assertEquals("прежняя ссылка должна остаться", "первая", s.url)
    }

    /** Чужой адрес не должен ронять текущий: он могл отдать 403. */
    @Test
    fun `отказ чужого адреса игнорируется`() {
        val s = session()
        s.adopt("первая")
        s.onFailed("другая")
        s.refreshIfNeeded()
        assertEquals("первая", s.url)
    }

    @Test
    fun `открытие другой ссылки обнуляет отсчёт`() {
        val s = session()
        s.adopt("первая")
        s.onRead(StreamRotation.ROTATE_AFTER_BYTES)
        s.onOpened("вторая")
        s.onRead(10L)
        assertFalse(StreamRotation.shouldRotate(10L, forced = false))
    }

    /**
     * Кружение ссылками — настоящая беда из отчёта: шесть обновлений за
     * 16 секунд на одном треке. Свежая ссылка, которая не открылась, больше
     * не должна порождать следующую: иначе выедается лимит запросов к
     * YouTube и в конце концов ошибка доходит до плеера.
     */
    @Test
    fun `неудачная свежая ссылка не вызывает следующую`() {
        val s = session()
        s.adopt("принятая")
        s.onFailed("принятая")
        s.refreshIfNeeded()
        val fresh = s.url
        assertFalse("свежая ссылка ещё не проверена", s.canRefresh())

        s.onFailed(fresh!!)
        s.refreshIfNeeded()          // крутить нельзя
        assertEquals(fresh, s.url)
        assertEquals("за неудачу берётся ровно одна свежая ссылка", 1, calls)
    }

    /** Успешное открытие снимает запрет: можно взять следующую ссылку. */
    @Test
    fun `удачное открытие разрешает обновление снова`() {
        val s = session()
        s.adopt("первая")
        s.onFailed("первая")
        s.refreshIfNeeded()
        assertFalse(s.canRefresh())

        s.onOpened("вторая")
        assertTrue(s.canRefresh())
    }
}
