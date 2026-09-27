package com.volna.player

import com.volna.player.search.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Тесты [CatalogMatch] на настоящей выдаче YouTube Music.
 *
 * Данные — из лога разбора жалобы: запрос «Спящая красавица Noize MC audio»
 * возвращает 11 результатов, где верный трек первый, а рядом лежат треки
 * того же исполнителя («Пчёлы слов», «У моей девхонки»). Именно они и
 * включались вместо нужного.
 */
class CatalogMatchTest {

    private fun track(
        id: String,
        title: String,
        artist: String = "Noize MC",
        official: Boolean = true,
        duration: Int = Track.DURATION_UNKNOWN,
    ) = Track(
        id = id,
        title = title,
        channel = artist,
        durationSeconds = duration,
        thumbnailUrl = "",
        videoUrl = "",
        isOfficialMusic = official,
        musicArtist = artist,
    )

    /** Та самая выдача: верный трек и пять треков того же исполнителя. */
    private val noize = listOf(
        track("LBmQKUdKy8g", "Спящая красавица"),
        track("C9Exd_G_xr4", "Пчёлы слов"),
        track("5InXjo8TWw0", "У моей девчонки"),
        track("wYYHYUpqQT8", "Настоящего"),
        track("RsffKo-A0Lo", "Хозяин леса"),
    )

    @Test
    fun `берёт точное совпадение, а не первый трек того же альбома`() {
        val picked = CatalogMatch.pick("Спящая красавица", "Noize MC", noize)

        assertEquals("Спящая красавица", picked?.title)
    }

    @Test
    fun `не берёт чужой трек того же альбома`() {
        val picked = CatalogMatch.pick("Спящая красавица", "Noize MC", noize)

        assertEquals(false, picked?.title == "Пчёлы слов")
    }

    @Test
    fun `точный трек побеждает даже чужой канал с похожим названием`() {
        val withClip = noize + track("dUoKJgApTwI", "Атлантида - Noize MC [КЛИП]", artist = "mh_bogdanowicz", official = false) +
            track("real1", "Атлантида")

        val picked = CatalogMatch.pick("Атлантида", "Noize MC", withClip)

        assertEquals("официальный трек важнее клипа с тем же словом", "Атлантида", picked?.title)
    }

    @Test
    fun `без совпадений возвращает null, а не первый трек`() {
        val picked = CatalogMatch.pick("Другая песня", "Кто-то другой", noize)

        assertNull("совпадений нет — лучше сказать, чем включить чужое", picked)
    }

    @Test
    fun `пустая выдача не падает`() {
        assertNull(CatalogMatch.pick("Спящая красавица", "Noize MC", emptyList()))
    }

    @Test
    fun `трансляции не берутся`() {
        val live = listOf(track("live1", "Спящая красавица", duration = Track.DURATION_LIVE))

        assertNull(CatalogMatch.pick("Спящая красавица", "Noize MC", live))
    }

    @Test
    fun `суффикс вроде Live не мешает узнать трек`() {
        val variants = listOf(track("x1", "Атлантида (Live)"))

        assertEquals("Live", CatalogMatch.pick("Атлантида", "Noize MC", variants)?.title?.let { "Live" })
    }

    /**
     * Случай из лога: «Атлантида» от Noize MC в выдаче YouTube Music
     * отсутствует, и остаётся одноимённый трек группы Atlantida Project.
     *
     * Название совпадает идеально, но это другая песня: играть её — значит
     * подменить трек и сразу получить 403, как и было в логе. Правильный ответ
     * здесь — `null`: приложение скажет «в YouTube Music нет» и уйдёт искать
     * в видео. Именно это и проверяет тест.
     */
    @Test
    fun `одноимённый трек чужого исполнителя не выдаётся за наш`() {
        val results = listOf(
            track("atl", "Атлантида", artist = "Atlantida Project"),
            track("noize", "Иордан (feat. Atlantida Project)", artist = "Noize MC"),
        )

        assertNull(
            "название совпало, но песня другая — лучше уйти в видео",
            CatalogMatch.pick("Атлантида", "Noize MC", results),
        )
    }

    /**
     * Обратный случай: одинаковое название у двух исполнителей, официальный
     * трек Noize MC должен победить одноимённый чужой.
     */
    @Test
    fun `при одинаковом названии побеждает совпавший исполнитель`() {
        val results = listOf(
            track("other", "Атлантида", artist = "Atlantida Project"),
            track("noize", "Атлантида", artist = "Noize MC"),
        )

        val picked = CatalogMatch.pick("Атлантида", "Noize MC", results)

        assertEquals("Noize MC", picked?.musicArtist)
    }

    @Test
    fun `клип фаната с именем исполнителя в названии подходит`() {
        val results = listOf(
            track("clip", "Атлантида - Noize MC [КЛИП]", artist = "mh_bogdanowicz", official = false),
        )

        val picked = CatalogMatch.pick("Атлантида", "Noize MC", results)

        assertEquals("исполнитель есть в названии — это наш трек", "Атлантида - Noize MC [КЛИП]", picked?.title)
    }

    // ---- Запасной подбор, когда в YouTube Music трека нет ----

    @Test
    fun `запасной подбор берёт видео по совпадению названия`() {
        val videos = listOf(
            track("v1", "Атлантида - Noize MC [КЛИП]", artist = "mh_bogdanowicz", official = false),
            track("v2", "Плейлист Noize MC", artist = "какой-то канал", official = false),
        )

        assertEquals(
            "Атлантида - Noize MC [КЛИП]",
            CatalogMatch.pickLoose("Атлантида", videos)?.title,
        )
    }

    @Test
    fun `запасной подбор не берёт совсем другое`() {
        val videos = listOf(track("v3", "Плейлист Noize MC", artist = "канал", official = false))

        assertNull("нет ни одного совпадения — лучше тишина", CatalogMatch.pickLoose("Атлантида", videos))
    }

    @Test
    fun `запасной подбор игнорирует трансляции`() {
        val live = listOf(track("l", "Атлантида Live", duration = Track.DURATION_LIVE))

        assertNull(CatalogMatch.pickLoose("Атлантида", live))
    }

    // ---- Переделки: slowed, reverb, ремиксы ----

    /**
     * Случай из лога: вместо «Молчанки» играла «Noize MC - Молчанка
     * (slowed & reverb)» — фанатская версия на 147 просмотров, 317 секунд
     * вместо 280. Победила потому, что имя исполнителя есть в названии.
     */
    @Test
    fun `замедленная версия не выдаётся за оригинал`() {
        val results = listOf(
            track("slow", "Noize MC - Молчанка (slowed & reverb)", artist = "Повар из Слиты", official = false),
        )

        assertNull("это другая версия трека", CatalogMatch.pick("Молчанка", "Noize MC", results))
    }

    @Test
    fun `оригинал побеждает переделку в одном списке`() {
        val results = listOf(
            track("slow", "Noize MC - Молчанка (slowed & reverb)", artist = "Повар из Слиты", official = false),
            track("real", "Молчанка", artist = "Noize MC"),
        )

        assertEquals("Молчанка", CatalogMatch.pick("Молчанка", "Noize MC", results)?.title)
    }

    @Test
    fun `если просят именно ремикс то ремикс подходит`() {
        val results = listOf(track("rmx", "Атлантида (Remix)", artist = "Noize MC"))

        assertEquals(
            "метка совпадает с запросом — значит искали именно это",
            "Атлантида (Remix)",
            CatalogMatch.pick("Атлантида (Remix)", "Noize MC", results)?.title,
        )
    }

    @Test
    fun `live версия остаётся доступной`() {
        // Live — нормальная запись, а не переделка: в каталоге трека такой
        // вариант бывает единственным, и отбрасывать его нельзя.
        val results = listOf(track("lv", "Молчанка (Live)", artist = "Noize MC"))

        assertEquals("Молчанка (Live)", CatalogMatch.pick("Молчанка", "Noize MC", results)?.title)
    }

    /**
     * Случай из лога: просили «Молчанку», а включилась «Безмозглая музыка».
     *
     * Общее у них только исполнитель — 6 очков за канал. Раньше этого
     * хватало, потому что совпадение исполнителя считалось достаточным.
     */
    @Test
    fun `трек того же исполнителя не выдаётся за другой трек`() {
        val results = listOf(
            track("bm", "Безмозглая музыка", artist = "Noize MC"),
            track("mol", "молчанка", artist = "творожный бизнес"),
        )

        assertNull(
            "общих слов с «Молчанкой» нет — это другой трек",
            CatalogMatch.pick("Молчанка", "Noize MC", results),
        )
    }

    /**
     * Регрессия: раньше точное совпадение названия было достаточным, и
     * «Молчанка» от «творожного бизнеса» выдавалась за «Молчанку» Noize MC —
     * совпадения исполнителя не требовалось вовсе.
     */
    @Test
    fun `точное название чужого исполнителя не проходит`() {
        val results = listOf(track("mol", "молчанка", artist = "творожный бизнес"))

        assertNull(
            "название совпало, исполнитель нет — это другой трек",
            CatalogMatch.pick("Молчанка", "Noize MC", results),
        )
    }

    /** Пустое каталожное поле исполнителя не должно блокировать подбор. */
    @Test
    fun `без каталожного исполнителя берём точное название`() {
        val results = listOf(track("x", "Молчанка", artist = "кто угодно"))

        assertEquals("Молчанка", CatalogMatch.pick("Молчанка", "", results)?.title)
    }

    /**
     * «26.04» — название целиком из коротких чисел. Раньше фильтр слов
     * короче трёх букв съедал его целиком, сравнивать было не с чем, и трек,
     * который лежит в каталоге и в выдаче, не находился.
     */
    @Test
    fun `название из коротких чисел находится`() {
        val found = CatalogMatch.pick(
            wantedTitle = "26.04",
            wantedArtist = "Noize MC",
            candidates = listOf(
                track("a", "Noize MC — 26.04 на гитаре кавер и разбор", artist = "канал"),
                track("b", "26.04"),
            ),
        )
        assertEquals("b", found?.id)
    }

    /** Тот же случай, но с настоящей подписью «Композиция • Noize MC». */
    @Test
    fun `композиция с числовым названием находится`() {
        val found = CatalogMatch.pick(
            wantedTitle = "26.04",
            wantedArtist = "Noize MC",
            candidates = listOf(
                track("acid", "26.04", artist = "Acid Westerns"),
                track("topic", "26.04", artist = "Noize MC"),
            ),
        )
        assertEquals("исполнитель должен решить, кому принадлежит трек", "topic", found?.id)
    }
}
