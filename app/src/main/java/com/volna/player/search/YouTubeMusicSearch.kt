package com.volna.player.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Поиск треков в YouTube Music через внутренний API InnerTube
 * (`POST https://music.youtube.com/youtubei/v1/search`).
 *
 * Зачем он нужен рядом с [YouTubeSearch]: обычный поиск YouTube отдаёт вперемешку
 * официальные треки, клипы, концерты, ремиксы и стримы, поэтому в выдаче
 * «Кино — Группа крови» первым мог оказаться часовой микс. YouTube Music в том
 * же InnerTube размечает результат по типам (`Композиция`, `Видео`, `Альбом`,
 * `Плейлист`, `Подкаст`) и отдаёт `videoId` прямо в `playlistItemData`, поэтому
 * официальные треки отделяются от всего остального одной проверкой типа.
 *
 * Клиент `WEB_REMIX` выбран потому, что именно он отдаёт
 * `musicResponsiveListItemRenderer` с разбивкой «Композиция • Исполнитель • Альбом».
 * `ANDROID_MUSIC` используется как запасной: у него другая вёрстка
 * (`elementRenderer -> musicListItemWrapperModel`), но тот же смысл полей.
 *
 * Класс потокобезопасен: состояние запроса не хранится, один экземпляр можно
 * переиспользовать из нескольких корутин.
 */
class YouTubeMusicSearch(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    /** Описание клиента InnerTube: имя, версия, User-Agent и доп. поля контекста. */
    private class ClientProfile(
        val name: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any> = emptyMap(),
    )

    /**
     * Выполняет поиск и возвращает официальные треки.
     *
     * Пустой [query] не обращается к сети и возвращает пустой список.
     * [limit] ограничивается диапазоном 1..100.
     *
     * Клиенты перебираются по очереди: пока один не вернул непустую выдачу,
     * берётся следующий. Так смена вёрстки YouTube Music не ломает поиск —
     * упадёт лишь тот клиент, чья схема поменялась.
     *
     * @throws YouTubeSearchException если ни один клиент не ответил или все
     *         вернули пустую выдачу.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Track> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) {
                Log.d(TAG, "Пустой поисковый запрос, обращение к сети пропущено")
                return@withContext emptyList()
            }
            val wanted = limit.coerceIn(1, MAX_LIMIT)

            var lastError: YouTubeSearchException? = null
            for (profile in CLIENTS) {
                val tracks = try {
                    MusicSearchParser(wanted, cleanQuery)
                        .parse(request(cleanQuery, profile))
                } catch (e: YouTubeSearchException) {
                    Log.w(TAG, "Клиент ${profile.name} не ответил: ${e.message}")
                    lastError = e
                    continue
                } catch (e: IOException) {
                    Log.w(TAG, "Клиент ${profile.name}: ошибка сети", e)
                    lastError = YouTubeSearchException("Ошибка сети: ${describe(e)}", e)
                    continue
                } catch (e: JSONException) {
                    Log.w(TAG, "Клиент ${profile.name}: нечитаемый ответ", e)
                    lastError = YouTubeSearchException("Неожиданный формат ответа: ${describe(e)}", e)
                    continue
                }

                if (tracks.isNotEmpty()) {
                    Log.d(TAG, "«$cleanQuery»: клиент ${profile.name} дал ${tracks.size} треков")
                    return@withContext tracks
                }
                lastError =
                    YouTubeSearchException("YouTube Music не нашёл треки по запросу «$cleanQuery»")
            }

            throw lastError ?: YouTubeSearchException("YouTube Music недоступен")
        }
    /** Один запрос к InnerTube и разбор ответа. */
    private suspend fun request(query: String, profile: ClientProfile): JSONObject {
        val payload = buildRequestBody(query, profile).toString().toByteArray(Charsets.UTF_8)

        val connection = try {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw YouTubeSearchException("Не удалось открыть соединение с YouTube Music", e)
        }

        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", profile.userAgent)
                setRequestProperty("Origin", ORIGIN)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                setFixedLengthStreamingMode(payload.size)
            }
            connection.outputStream.use { it.write(payload) }

            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw YouTubeSearchException("Нет связи с YouTube Music: ${describe(e)}", e)
            }

            val raw = readBody(connection, code)
            if (code !in 200..299) {
                throw YouTubeSearchException(
                    "YouTube Music вернул HTTP $code" +
                        if (raw.isEmpty()) "" else " (${abbreviate(raw, 200)})",
                )
            }
            if (raw.isBlank()) {
                throw YouTubeSearchException("Пустой ответ от YouTube Music")
            }

            val root = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                throw YouTubeSearchException("Не удалось разобрать ответ: ${describe(e)}", e)
            }
            root.optJSONObject("error")?.let { error ->
                val status = error.optJSONObject("status")
                val reason = status?.optString("message")?.ifBlank { status.optString("reason") }
                    ?: error.optString("message")
                throw YouTubeSearchException(
                    "YouTube Music отклонил запрос: " + reason.ifBlank { "причина не указана" },
                )
            }
            return root
        } finally {
            // разрываем соединение даже при отмене корутины
            runCatching { connection.disconnect() }
        }
    }

    /** Тело запроса InnerTube; запрос экранируется самим JSONObject. */
    private fun buildRequestBody(query: String, profile: ClientProfile): JSONObject {
        val client = JSONObject()
            .put("clientName", profile.name)
            .put("clientVersion", profile.version)
            .put("hl", LANGUAGE)
            .put("gl", REGION)
        for ((key, value) in profile.extra) {
            client.put(key, value)
        }
        client.put("userAgent", profile.userAgent)
        return JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", query)
    }

    /**
     * Читает тело ответа (обычное или ошибочное) строкой UTF-8 с ограничением
     * размера и проверкой отмены корутины.
     */
    private suspend fun readBody(connection: HttpURLConnection, code: Int): String {
        val stream: InputStream = try {
            if (code >= 400) connection.errorStream else connection.inputStream
        } catch (e: IOException) {
            throw YouTubeSearchException("Не удалось прочитать ответ: ${describe(e)}", e)
        } ?: return ""

        return try {
            stream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val chunk = StringBuilder(64 * 1024)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        Log.w(TAG, "Ответ YouTube Music обрезан: больше $MAX_RESPONSE_BYTES байт")
                        break
                    }
                    chunk.append(String(buffer, 0, read, Charsets.UTF_8))
                }
                chunk.toString()
            }
        } catch (e: IOException) {
            throw YouTubeSearchException("Не удалось прочитать ответ: ${describe(e)}", e)
        }
    }
    private companion object {
        const val TAG = "YouTubeMusicSearch"

        const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"
        const val ORIGIN = "https://music.youtube.com"
        const val LANGUAGE = "ru"
        const val REGION = "RU"

        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000

        /** 16 МБ: ответ ANDROID_MUSIC заметно крупнее, чем у WEB_REMIX. */
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

        /** Клиенты по убыванию приоритета. */
        val CLIENTS: List<ClientProfile> = listOf(
            ClientProfile(
                name = "WEB_REMIX",
                version = "1.20240101.01.00",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            ),
            ClientProfile(
                name = "ANDROID_MUSIC",
                version = "8.27.54",
                userAgent = "com.google.android.apps.youtube.music/8.27.54 " +
                    "(Linux; U; Android 14) gzip",
                extra = mapOf("androidSdkVersion" to 34),
            ),
        )

        fun describe(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

        fun abbreviate(s: String, max: Int): String {
            val flat = s.replace('\n', ' ').trim()
            return if (flat.length <= max) flat else flat.substring(0, max) + "…"
        }
    }
}