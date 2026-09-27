package com.volna.player.search

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [MusicSearchParser] на настоящих ответах InnerTube.
 *
 * Фикстуры в `resources` — вырезанные из живых ответов `music.youtube.com`
 * элементы выдачи (по одному на тип), поэтому тесты ловят именно те изменения
 * разметки, которые ломают парсер в проде, и не зависят от сети.
 */
class MusicSearchParserTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }) { "Фикстура $name не найдена в ресурсах" }
        return JSONObject(text)
    }

    @Test
    fun `разбирает официальный трек из вёрстки WEB_REMIX`() {
        val track = MusicSearchParser(limit = 10, query = "Группа крови")
            .parse(fixture("fx_webremix_song.json"))
            .single()

        assertEquals("EByofhvVRco", track.id)
        assertEquals("Группа крови", track.title)
        assertEquals("Кино", track.channel)
        assertTrue("официальный трек должен помечаться как музыка", track.isOfficialMusic)
        assertEquals("Кино", track.musicArtist)
        assertEquals("https://www.youtube.com/watch?v=EByofhvVRco", track.videoUrl)
        assertTrue("обложка должна быть https", track.thumbnailUrl.startsWith("https://"))
    }

    @Test
    fun `разбирает официальный трек из вёрстки ANDROID_MUSIC`() {
        val track = MusicSearchParser(limit = 10, query = "Группа крови")
            .parse(fixture("fx_android_song.json"))
            .single()

        assertEquals("EByofhvVRco", track.id)
        assertEquals("Группа крови", track.title)
        assertEquals("Кино", track.channel)
        assertTrue(track.isOfficialMusic)
        assertEquals("Кино", track.musicArtist)
    }

    @Test
    fun `обычное видео не выдаётся за официальный трек`() {
        val track = MusicSearchParser(limit = 10, query = "Группа крови")
            .parse(fixture("fx_webremix_video.json"))
            .single()

        assertFalse("UGC-видео не должно помечаться как трек", track.isOfficialMusic)
        assertEquals("", track.musicArtist)
        assertTrue("у видео должен быть виден загрузивший", track.channel.isNotEmpty())
    }

    @Test
    fun `пустой ответ даёт пустой список`() {
        assertTrue(MusicSearchParser(limit = 10).parse(JSONObject("{}")).isEmpty())
    }

    @Test
    fun `null вместо ответа не приводит к падению`() {
        assertTrue(MusicSearchParser(limit = 10).parse(null).isEmpty())
    }

    @Test
    fun `элемент без videoId пропускается`() {
        val json = JSONObject()
            .put(
                "musicResponsiveListItemRenderer",
                JSONObject()
                    .put("flexColumns", org.json.JSONArray().put(column("Без видео"))),
            )
        assertTrue(MusicSearchParser(limit = 10).parse(json).isEmpty())
    }

    @Test
    fun `альбом без videoId не попадает в выдачу`() {
        val json = JSONObject()
            .put(
                "musicResponsiveListItemRenderer",
                JSONObject()
                    .put("playlistItemData", JSONObject().put("videoId", ""))
                    .put(
                        "flexColumns",
                        org.json.JSONArray()
                            .put(column("Группа крови"))
                            .put(column("Альбом • Кино • 1988")),
                    ),
            )
        assertTrue(MusicSearchParser(limit = 10).parse(json).isEmpty())
    }

    @Test
    fun `точное совпадение названия поднимает трек в выдаче`() {
        val result = MusicSearchParser(limit = 5, query = "Группа крови").parse(
            items(
                item("Z4jQ4hZfk00", "Звезда по имени Солнце", "Композиция \u2022 Кино"),
                item("EByofhvVRco", "Группа крови", "Композиция \u2022 Кино"),
            ),
        )

        assertEquals(
            "точное совпадение с запросом должно идти первым",
            "Группа крови",
            result.first().title,
        )
    }

    @Test
    fun `официальные треки идут раньше обычных видео`() {
        val result = MusicSearchParser(limit = 5, query = "Группа крови").parse(
            items(
                item("xKpzH5bxYsk", "Виктор Цой - Группа Крови", "Видео \u2022 foleywarlocks"),
                item("EByofhvVRco", "Группа крови", "Композиция \u2022 Кино"),
            ),
        )

        assertTrue("композиция должна быть выше видео", result.first().isOfficialMusic)
    }

    @Test
    fun `ремикс уходит вниз относительно оригинала`() {
        val result = MusicSearchParser(limit = 5, query = "Группа крови").parse(
            items(
                item("Z9E1CUllU6A", "Группа крови (DJ Vini remix)", "Композиция \u2022 Aman Future"),
                item("EByofhvVRco", "Группа крови", "Композиция \u2022 Кино"),
            ),
        )

        assertEquals("оригинал должен быть выше ремикса", "Группа крови", result.first().title)
    }

    @Test
    fun `дубли одного видео не попадают в выдачу дважды`() {
        val result = MusicSearchParser(limit = 10, query = "Группа крови").parse(
            items(
                item("EByofhvVRco", "Группа крови", "Композиция \u2022 Кино"),
                item("EByofhvVRco", "Группа крови", "Композиция \u2022 Кино"),
                item("EByofhvVRco", "Группа крови", "Композиция \u2022 Кино"),
            ),
        )

        assertEquals(1, result.size)
    }

    @Test
    fun `limit ограничивает число результатов`() {
        val many = (1..8).map {
            item("videoId%04d".format(it), "Трек $it", "Композиция \u2022 Артист")
        }

        assertEquals(3, MusicSearchParser(limit = 3, query = "Трек").parse(items(*many.toTypedArray())).size)
    }

    @Test
    fun `альбомы и плейлисты отбрасываются`() {
        val result = MusicSearchParser(limit = 10, query = "Группа крови").parse(
            items(
                item("EByofhvVRco", "Группа крови", "Композиция \u2022 Кино"),
                // Альбомы и плейлисты приходят без videoId — их не должно быть в выдаче
                item("OLAK5uy_kino1988", "Группа крови", "Альбом \u2022 Кино \u2022 1988"),
            ),
        )

        assertEquals(1, result.size)
        assertEquals("EByofhvVRco", result.single().id)
    }

    @Test
    fun `длительность остаётся неизвестной`() {
        val track = MusicSearchParser(limit = 10)
            .parse(fixture("fx_webremix_song.json"))
            .single()

        assertEquals(
            "YouTube Music не отдаёт длительность в выдаче",
            Track.DURATION_UNKNOWN,
            track.durationSeconds,
        )
        assertEquals("", track.formattedDuration())
    }

    @Test
    fun `обложка приходит по https и не пустая`() {
        val track = MusicSearchParser(limit = 10)
            .parse(fixture("fx_android_song.json"))
            .single()

        assertTrue(track.thumbnailUrl.isNotEmpty())
        assertTrue(track.thumbnailUrl.startsWith("https://"))
    }

    /**
     * Карточка «лучший результат» из настоящего ответа InnerTube.
     *
     * Регрессия: «Молчанка» от Noize MC в общем списке выдачи не встречается,
     * она лежит только в `musicCardShelfRenderer`. Пока этот блок не разбирался,
     * подбирался чужой трек — «Безмозглая музыка», у которого совпал исполнитель.
     */
    @Test
    fun `разбирает карточку лучшего результата`() {
        val tracks = MusicSearchParser(limit = 10, query = "молчанка noize mc")
            .parse(fixture("fx_webremix_card.json"))

        val card = tracks.firstOrNull { it.id == "9o3B03nztsY" }
        assertTrue("карточка «Молчанка» должна попасть в выдачу", card != null)
        card!!
        assertEquals("Молчанка", card.title)
        assertEquals("Noize MC", card.channel)
        assertTrue("карточка помечена как официальный трек", card.isOfficialMusic)
        assertEquals("https://www.youtube.com/watch?v=9o3B03nztsY", card.videoUrl)
    }

    /**
     * Регрессия: подпись карточки — «Композиция • Исполнитель • 4:41».
     * Третья часть здесь длительность, а не альбом, как в обычном пункте списка.
     */
    @Test
    fun `в карточке третья часть подписи это длительность а не альбом`() {
        val card = MusicSearchParser(limit = 10, query = "молчанка noize mc")
            .parse(fixture("fx_webremix_card.json"))
            .first { it.id == "9o3B03nztsY" }

        assertEquals(281, card.durationSeconds)
        assertTrue("длительность должна быть известна", card.hasKnownDuration)
        assertEquals("4:41", card.formattedDuration())
        assertEquals("длительность не должна попасть в альбом", "", card.album)
    }

    /** Карточка — лучшее совпадение, и подбор обязан поставить её первой. */
    @Test
    fun `карточка побеждает похожие треки того же исполнителя`() {
        val tracks = MusicSearchParser(limit = 10, query = "молчанка noize mc")
            .parse(fixture("fx_webremix_card.json"))

        assertEquals("9o3B03nztsY", tracks.first().id)
        assertTrue(
            "точный запрос не должен уводить на «${tracks.first().title}»",
            tracks.first().title.equals("Молчанка", ignoreCase = true),
        )
    }

    /** Разделители «•» в карточке приходят отдельными run — склейка обязана их сохранить. */
    @Test
    fun `склейка runs сохраняет разделители подписи`() {
        val runs = org.json.JSONArray()
            .put(JSONObject().put("text", "Композиция"))
            .put(JSONObject().put("text", " • "))
            .put(JSONObject().put("text", "Noize MC"))
            .put(JSONObject().put("text", " • "))
            .put(JSONObject().put("text", "4:41"))

        val node = JSONObject()
            .put(
                "musicCardShelfRenderer",
                JSONObject()
                    .put(
                        "title",
                        JSONObject().put("runs", org.json.JSONArray().put(JSONObject().put("text", "Молчанка"))),
                    )
                    .put("subtitle", JSONObject().put("runs", runs))
                    .put(
                        "onTap",
                        JSONObject()
                            .put("watchEndpoint", JSONObject().put("videoId", "9o3B03nztsY")),
                    ),
            )

        val track = MusicSearchParser(limit = 5, query = "молчанка").parse(node).single()
        assertEquals("Молчанка", track.title)
        assertEquals("Noize MC", track.channel)
        assertEquals(281, track.durationSeconds)
    }

    /** Альбом в подписи не должен приниматься за длительность. */
    @Test
    fun `название альбома не путается с длительностью`() {
        val track = MusicSearchParser(limit = 5, query = "Кино")
            .parse(item("EByofhvVRco", "Группа крови", "Композиция • Кино • Детский"))
            .single()

        assertEquals("Детский", track.album)
        assertFalse("длительность остаётся неизвестной", track.hasKnownDuration)
    }

    /**
     * Элемент выдачи в вёрстке `WEB_REMIX`: [subtitle] идёт «Тип • Исполнитель • Альбом».
     * Пустой [id] имитирует альбом или плейлист — играть из них нечего.
     */
    private fun item(id: String, title: String, subtitle: String): JSONObject {
        val columns = org.json.JSONArray()
            .put(column(title))
            .put(column(subtitle))
        return JSONObject()
            .put(
                "musicResponsiveListItemRenderer",
                JSONObject()
                    .put("playlistItemData", JSONObject().put("videoId", id))
                    .put("flexColumns", columns),
            )
    }

    /** Список элементов в том же виде, что приходит от сервера. */
    private fun items(vararg nodes: JSONObject): org.json.JSONArray =
        org.json.JSONArray().apply { nodes.forEach { put(it) } }

    /** Колонка `WEB_REMIX` в том же виде, что приходит от сервера. */
    private fun column(value: String): JSONObject = JSONObject()
        .put(
            "musicResponsiveListItemFlexColumnRenderer",
            JSONObject().put("text", JSONObject().put("simpleText", value)),
        )
}
