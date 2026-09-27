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
/**
 * Перемешивание.
 *
 * Баг был такой: кнопка перемешивания бросала на случайный трек один раз, а
 * дальше `target` снова считал `index + 1` — и порядок возвращался, причём уже
 * от того трека, куда бросило. Проверяем, что режим держится на каждом шаге.
 */
class AlbumShuffleTest {

    private val off = Player.REPEAT_MODE_OFF

    @Test
    fun `вперемешку идёт по всему альбому, а не по порядку`() {
        val target = AlbumOrder.target(5, 2, 1, off, shuffled = true) { 4 }
        assertEquals(Target.Track(4), target)
    }

    @Test
    fun `перемешивание не возвращает порядок на втором шаге`() {
        // Порядок дал бы 3; перемешивание обязано снова взять случайный трек.
        val target = AlbumOrder.target(5, 3, 1, off, shuffled = true) { 0 }
        assertEquals(Target.Track(0), target)
    }

    @Test
    fun `перемешивание никогда не выбирает текущий трек`() {
        val sizes = 3..12
        for (size in sizes) {
            for (current in 0 until size) {
                val picked = AlbumOrder.randomOtherIndex(size, current) { 7 % size }
                assertTrue(
                    "размер=$size текущий=$current выпал=$picked",
                    picked != current && picked in 0 until size,
                )
            }
        }
    }

    @Test
    fun `случайный выбор не зацикливается на одном треке`() {
        // Раньше был `while (next == index) next = Random.nextInt(size)`.
        // С генератором, который всегда возвращает одно и то же, этот цикл
        // не заканчивается никогда — и вешает поток. Проверяем выход за шаг:
        // выпал текущий — отступаем на следующий по кругу.
        assertEquals(2, AlbumOrder.randomOtherIndex(4, 1) { 1 })
        assertEquals(0, AlbumOrder.randomOtherIndex(4, 3) { 3 })
    }

    @Test
    fun `назад при перемешивании идёт по треклисту`() {
        assertEquals(Target.Track(2), AlbumOrder.target(5, 3, -1, off, shuffled = true) { 0 })
    }

    @Test
    fun `выключенное перемешивание ведёт себя как раньше`() {
        assertEquals(Target.Track(3), AlbumOrder.target(5, 2, 1, off, shuffled = false) { 0 })
    }

    @Test
    fun `альбом из одного трека при перемешивании не ломается`() {
        assertEquals(0, AlbumOrder.randomOtherIndex(1, 0) { 0 })
    }
}
