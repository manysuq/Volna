package com.volna.player.search

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты [RadioParser] на настоящем ответе радио YouTube Music.
 *
 * Радио — источник «Похожих». Отдельная фикстура нужна, потому что разметка
 * у него своя (`playlistPanelVideoRenderer` вместо `musicResponsiveListItemRenderer`)
 * и именно на ней раньше сыпались посторонние треки вроде «Уральских пельменей».
 */
class RadioParserTest {

    private fun fixture(name: String): JSONObject {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }) { "Фикстура $name не найдена в ресурсах" }
        return JSONObject(text)
    }

    private val current = "He1fA2oCuRs" // Noize MC — «Кооператив Лебединое озеро»

    @Test
    fun `читает треки радио с исполнителем и длительностью`() {
        val tracks = RadioParser(limit = 20, currentId = current).parse(fixture("fx_radio.json"))

        assertEquals(2, tracks.size) // третий отсекается как исходный трек
        val first = tracks.first()
        assertEquals("de6QR_37Y5I", first.id)
        assertEquals("Никто не пострадал", first.title)
        assertEquals("Noize MC, Монеточка и Витя Исаев", first.musicArtist)
        assertEquals(2 * 60 + 33, first.durationSeconds)
        assertEquals("2:33", first.formattedDuration())
        assertTrue("радио отдаёт музыку из каталога", first.isOfficialMusic)
        assertTrue("обложка должна быть https", first.thumbnailUrl.startsWith("https://"))
    }

    @Test
    fun `не возвращает исходный трек`() {
        val tracks = RadioParser(limit = 20, currentId = current).parse(fixture("fx_radio.json"))

        assertTrue("исходный трек не должен попасть в похожие", tracks.none { it.id == current })
    }

    @Test
    fun `учитывает лимит и не отдаёт больше`() {
        assertEquals(1, RadioParser(limit = 1, currentId = current).parse(fixture("fx_radio.json")).size)
    }

    @Test
    fun `пустой ответ даёт пустой список`() {
        assertTrue(RadioParser(limit = 20, currentId = current).parse(JSONObject("{}")).isEmpty())
    }

    @Test
    fun `мусор в ответе не ломает разбор`() {
        // Добавлен entityBatchUpdate с чужим videoId — обход не должен его принять.
        val tracks = RadioParser(limit = 20, currentId = current).parse(fixture("fx_radio.json"))

        assertTrue("videoId не из очереди не должен попасть в выдачу", tracks.none { it.id == "ZZZZZZZZZZ" })
    }
}