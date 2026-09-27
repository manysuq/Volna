package com.volna.player

import androidx.media3.common.Player
import com.volna.player.AlbumOrder.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты правила перехода внутри альбома.
 *
 * Проверяют то, что описывал баг: включил трек с альбома, нажал «вперёд» —
 * и поехал похожий трек вместо следующего по альбому. Причина была в том, что
 * очередь строилась из выдачи поиска по одной песне. Теперь очередь альбома
 * ведётся отдельно, а радио включается только после конца треклиста.
 */
class AlbumOrderTest {

    private val off = Player.REPEAT_MODE_OFF
    private val all = Player.REPEAT_MODE_ALL
    private val one = Player.REPEAT_MODE_ONE

    @Test
    fun `вперёд внутри альбома идёт по треклисту`() {
        assertEquals(Target.Track(1), AlbumOrder.target(5, 0, 1, off))
        assertEquals(Target.Track(4), AlbumOrder.target(5, 3, 1, off))
    }

    @Test
    fun `после последнего трека альбома включается радио`() {
        assertEquals(Target.Radio, AlbumOrder.target(5, 4, 1, off))
    }

    @Test
    fun `назад с первого трека перезапускает его`() {
        assertEquals(Target.Restart, AlbumOrder.target(5, 0, -1, off))
    }

    @Test
    fun `назад внутри альбома идёт по треклисту`() {
        assertEquals(Target.Track(2), AlbumOrder.target(5, 3, -1, off))
    }

    @Test
    fun `репит альбома заворачивает с конца на начало`() {
        assertEquals(Target.Track(0), AlbumOrder.target(5, 4, 1, all))
    }

    @Test
    fun `репит альбома заворачивает с начала в конец`() {
        assertEquals(Target.Track(4), AlbumOrder.target(5, 0, -1, all))
    }

    @Test
    fun `репит одного трека не заворачивает альбом`() {
        // Иначе «вперёд» на последнем треке крутил бы один и тот же трек.
        assertEquals(Target.Radio, AlbumOrder.target(5, 4, 1, one))
    }

    @Test
    fun `пустой альбом не ломает переход`() {
        assertEquals(Target.Restart, AlbumOrder.target(0, 0, 1, off))
    }

    @Test
    fun `альбом из одного трека сразу уходит в радио`() {
        assertEquals(Target.Radio, AlbumOrder.target(1, 0, 1, off))
    }

    /**
     * Правило, из-за которого песня останавливалась на середине альбома.
     *
     * `STATE_ENDED` приходит один раз на трек, и если его не поймать, очередь
     * дальше не двигается: следующий MediaItem появляется только после того,
     * как ViewModel сам разберёт ссылку на следующий трек.
     */
    @Test
    fun `окончание трека ведёт вперёд, а не в начало`() {
        val target = AlbumOrder.target(4, 1, 1, off)

        assertEquals("после окончания трека ждём следующий по альбому", Target.Track(2), target)
    }

    @Test
    fun `окончание последнего трека без репита уходит в радио`() {
        assertEquals(Target.Radio, AlbumOrder.target(4, 3, 1, off))
    }

    /**
     * Повтор одного трека: ExoPlayer повторяет сам, и наш автопереход обязан
     * промолчать — иначе он тут же прервал бы повтор.
     */
    @Test
    fun `при повторе одного трека автопереход не срабатывает`() {
        assertFalse("повтор одного трека крутит сам плеер", AlbumOrder.shouldAdvance(one))
    }

    @Test
    fun `в остальных режимах автопереход срабатывает`() {
        assertTrue(AlbumOrder.shouldAdvance(off))
        assertTrue(AlbumOrder.shouldAdvance(all))
    }

    /**
     * Гонка, из-за которой нажатие на трек альбома сразу уводило в другой
     * альбом: событие «доиграл» приходило уже после нажатия нового трека.
     */
    @Test
    fun `событие о доигравшем чужом треке игнорируется`() {
        assertFalse(
            "доиграл «Пчёлы слов», а нажали «Спящую красавицу»",
            AlbumOrder.isFresh(finishedId = "C9Exd_G_xr4", currentId = "LBmQKUdKy8g"),
        )
    }

    @Test
    fun `событие о доигравшем текущем треке принимается`() {
        assertTrue(AlbumOrder.isFresh(finishedId = "LBmQKUdKy8g", currentId = "LBmQKUdKy8g"))
    }

    @Test
    fun `неизвестный доигравший трек не блокирует переход`() {
        assertTrue(AlbumOrder.isFresh(finishedId = null, currentId = "LBmQKUdKy8g"))
        assertTrue(AlbumOrder.isFresh(finishedId = "LBmQKUdKy8g", currentId = null))
    }
}