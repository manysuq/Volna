package com.volna.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Гасить ли сервис, когда система убирает задачу.
 *
 * Тест на главный случай бага: раньше решение принималось по «звук идёт
 * сейчас», и одна буферизация посреди песни убивала сервис навсегда.
 */
class ServiceSurvivalTest {

    @Test
    fun `играем и очередь есть — сервис живёт`() {
        assertTrue(ServiceSurvival.shouldKeepAlive(playWhenReady = true, mediaItemCount = 1))
    }

    /** Главный случай: звук на секунду прервался, но играть мы всё ещё хотим. */
    @Test
    fun `пауза в звуке не значит паузу в намерениях`() {
        // isPlaying здесь не участвует вовсе — намерение пользователя важнее
        // мгновенного состояния звукового потока.
        assertTrue(ServiceSurvival.shouldKeepAlive(playWhenReady = true, mediaItemCount = 3))
    }

    @Test
    fun `пользователь поставил на паузу — сервис можно гасить`() {
        assertFalse(ServiceSurvival.shouldKeepAlive(playWhenReady = false, mediaItemCount = 1))
    }

    @Test
    fun `очередь пуста — сервис можно гасить`() {
        assertFalse(ServiceSurvival.shouldKeepAlive(playWhenReady = true, mediaItemCount = 0))
    }
}
