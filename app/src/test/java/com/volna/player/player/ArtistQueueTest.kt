package com.volna.player.player

import com.volna.player.catalog.MusicCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Порядок треков для «слушать всё» по исполнителю.
 *
 * Проверяем то, что жаловали: раньше перемешивался только стартовый трек, а
 * сам список оставался в порядке альбомов, и «слушать всё» у исполнителя
 * звучало как «слушать альбомы по очереди».
 */
class ArtistQueueTest {

    private fun t(number: Int, title: String) = MusicCatalog.CatalogTrack(
        number = number,
        title = title,
        artistName = "Noize MC",
        durationMs = 180_000L,
        artworkUrl = "",
    )

    private val albumA = listOf(t(1, "A1"), t(2, "A2"), t(3, "A3"))
    private val albumB = listOf(t(1, "B1"), t(2, "B2"), t(3, "B3"))

    /**
     * Главный случай: альбомы перемежаются.
     *
     * Считаем, сколько раз подряд шли треки одного альбома. В порядке альбомов
     * это всегда 1, то есть два соседних трека из одного альбома.
     */
    /**
     * Настоящее перемежание: альбом возвращается после ухода (A … B … A).
     *
     * Проверять «есть ли смена альбома» бессмысленно — в порядке альбомов
     * смена ровно одна, AAAA BBBB, и такой тест проходит и без перемешивания.
     * А вот вернуться к первому альбому после второго в блочном порядке
     * невозможно в принципе, поэтому свойство честно различает их.
     */
    @Test
    fun `после второго альбома бывает возврат к первому`() {
        var сидовСВозвратом = 0
        repeat(30) { seed ->
            val q = ArtistQueue.build(listOf(albumA, albumB), Random(seed))
            val буквы = q.map { it.title.first() }
            val ушлиИзПервого = буквы.indexOf('B')
            if (ушлиИзПервого in 0 until буквы.size && 'A' in буквы.drop(ушлиИзПервого)) {
                сидовСВозвратом++
            }
        }
        assertTrue(
            "альбомы идут блоками и не перемежаются",
            сидовСВозвратом > 0,
        )
    }

    /**
     * Обратная сторона того же: при перемешивании соседних треков из одного
     * альбома быть почти не должно. В порядке альбомов их ровно 4 из 5 пар.
     */
    @Test
    fun `соседних треков из одного альбома почти не остаётся`() {
        var парИзОдногоАльбома = 0
        repeat(30) { seed ->
            val q = ArtistQueue.build(listOf(albumA, albumB), Random(seed))
            for (i in 1 until q.size) {
                if (q[i].title.first() == q[i - 1].title.first()) парИзОдногоАльбома++
            }
        }
        val безПеремешивания = 30 * 4
        assertTrue(
            "треки идут блоками: пар из одного альбома $парИзОдногоАльбома из $безПеремешивания",
            парИзОдногоАльбома < безПеремешивания,
        )
    }

    @Test
    fun `порядок альбомов не сохраняется`() {
        val поАльбомам = (albumA + albumB).map { it.title }
        val перемешано = ArtistQueue.build(listOf(albumA, albumB), Random(7))
            .map { it.title }
        assertNotEquals("порядок альбомов сохранился", поАльбомам, перемешано)
    }

    @Test
    fun `все уникальные треки попадают в очередь`() {
        val q = ArtistQueue.build(listOf(albumA, albumB))
        assertEquals(6, q.size)
        assertEquals(6, q.map { it.title }.toSet().size)
    }

    /** Сингл и альбом часто содержат один и тот же трек — он должен быть один. */
    @Test
    fun `дубли по названию схлопываются`() {
        val a = listOf(t(1, "Один"), t(2, "Два"))
        val b = listOf(t(1, "один"), t(3, "Три"))
        val q = ArtistQueue.build(listOf(a, b))
        assertEquals(3, q.size)
    }

    @Test
    fun `один альбом остаётся целым`() {
        val q = ArtistQueue.build(listOf(albumA))
        assertEquals(albumA.map { it.title }.toSet(), q.map { it.title }.toSet())
    }

    @Test
    fun `пустой исполнитель не ломает`() {
        assertTrue(ArtistQueue.build(emptyList()).isEmpty())
    }
}
