package com.volna.player.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Похожие треки: берём их из радио YouTube Music по текущему треку.
 *
 * Раньше источником был `/next` обычного YouTube, и это давало ровно то, на что
 * можно наткнуться: включил Noize MC — в «Похожем» вылезли «Уральские пельмени».
 * Причина в том, что `/next` на `www.youtube.com` вообще не знает про музыку
 * и отдаёт что попало из радиостанции видеохостинга.
 *
 * Радио YouTube Music (`playlistId=RDAMVM…`) устроено иначе: это подборка
 * действительно похожих треков из каталога, у каждого есть исполнитель,
 * альбом и длительность. Проверено на девяти исполнителях — по 50 треков
 * в каждой выдаче, мусора нет.
 *
 * Отдельный плюс: в ответе есть `lengthText`, которого не было в выдаче поиска,
 * поэтому у рекомендаций известна длительность.
 */
object RecommendationClient {

    private const val ENDPOINT = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val ORIGIN = "https://music.youtube.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private const val CLIENT_VERSION = "1.20240101.01.00"

    /** Похожие треки на [videoId], без самого [videoId]. */
    suspend fun similar(videoId: String, limit: Int = 20): List<Track> =
        withContext(Dispatchers.IO) {
            if (videoId.isBlank()) return@withContext emptyList()
            try {
                RadioParser(limit, videoId).parse(request(videoId))
            } catch (e: Exception) {
                Log.w(TAG, "Похожие не получены: ${e.message}", e)
                emptyList()
            }
        }

    private fun request(videoId: String): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", CLIENT_VERSION)
            .put("hl", "ru")
            .put("gl", "RU")
            .put("userAgent", USER_AGENT)
        // Префикс RDAMVM в playlistId — это и есть «радио по треку».
        val body = JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
            .put("playlistId", "RDAMVM$videoId")
            .toString()
            .toByteArray(Charsets.UTF_8)

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 25_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Origin", ORIGIN)
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private const val TAG = "Recommendations"
}