package com.volna.player.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

/**
 * Один найденный видеотрек.
 *
 * @param id идентификатор видео на YouTube (11 символов)
 * @param title название трека
 * @param channel канал/исполнитель (может быть пустым, если YouTube его не отдал)
 * @param durationSeconds длительность в секундах; -1 — длительность неизвестна,
 *        -2 — это прямая трансляция (LIVE)
 * @param thumbnailUrl прямая ссылка на обложку (всегда https)
 * @param videoUrl ссылка на страницу видео
 * @param isOfficialMusic YouTube пометил видео как музыкальный трек (бейдж «Music»)
 * @param musicArtist исполнитель из этого бейджа, если YouTube его отдал
 * @param album название альбома, если источник поиска его отдаёт
 */
data class Track(
    val id: String,
    val title: String,
    val channel: String,
    val durationSeconds: Int,
    val thumbnailUrl: String,
    val videoUrl: String,
    val isOfficialMusic: Boolean = false,
    val musicArtist: String = "",
    val album: String = "",
) {
    /** Длительность известна (прямая трансляция и «неизвестно» — не известна). */
    val hasKnownDuration: Boolean
        get() = durationSeconds >= 0

    /** Признак прямой трансляции. */
    val isLive: Boolean
        get() = durationSeconds == DURATION_LIVE

    /** Человекочитаемая длительность «мм:сс» / «ч:мм:сс»; для неизвестной — пустая строка. */
    fun formattedDuration(): String {
        if (durationSeconds == DURATION_LIVE) return "LIVE"
        if (durationSeconds < 0) return ""
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    companion object {
        /** Длительность неизвестна. */
        const val DURATION_UNKNOWN: Int = -1

        /** Видео идёт прямо сейчас. */
        const val DURATION_LIVE: Int = -2
    }
}

/** Ошибка поиска: сеть, HTTP-код, неожиданный формат ответа. */
class YouTubeSearchException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Поиск видео на YouTube через внутренний API InnerTube
 * (`POST https://www.youtube.com/youtubei/v1/search`) без внешних зависимостей:
 * HTTP — `HttpURLConnection`, JSON — `org.json` (входит в Android).
 *
 * Используется клиент ANDROID 20.10.38: он отдаёт длительность, канал и прямые
 * ссылки на обложки без капчи и без подписи запроса.
 *
 * Класс потокобезопасен: состояние запроса не хранится, один экземпляр можно
 * переиспользовать из нескольких корутин.
 */
class YouTubeSearch(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    /**
     * Выполняет поиск и возвращает список треков.
     *
     * Пустой [query] не обращается к сети и возвращает пустой список.
     * [limit] ограничивается диапазоном 1..100.
     *
     * @throws YouTubeSearchException при сетевых ошибках, HTTP-ответе с кодом
     *         не 2xx или нечитаемом теле ответа.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Track> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) {
                Log.d(TAG, "Пустой поисковый запрос, обращение к сети пропущено")
                return@withContext emptyList()
            }
            val wanted = limit.coerceIn(1, MAX_LIMIT)
            val payload = buildRequestBody(cleanQuery).toString().toByteArray(Charsets.UTF_8)

            val connection = try {
                URL(ENDPOINT).openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw YouTubeSearchException("Не удалось открыть соединение с YouTube", e)
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
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Origin", ORIGIN)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                    setFixedLengthStreamingMode(payload.size)
                }
                connection.outputStream.use { it.write(payload) }

                val code = try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw YouTubeSearchException("Нет связи с YouTube: ${describe(e)}", e)
                }

                val raw = readBody(connection, code)
                if (code !in 200..299) {
                    throw YouTubeSearchException(
                        "Поиск недоступен: сервер вернул HTTP $code" +
                            if (raw.isEmpty()) "" else " (${abbreviate(raw, 200)})",
                    )
                }
                if (raw.isBlank()) {
                    throw YouTubeSearchException("Пустой ответ от сервера поиска")
                }


                val root = try {
                    JSONObject(raw)
                } catch (e: JSONException) {
                    throw YouTubeSearchException("Не удалось разобрать ответ поиска: ${describe(e)}", e)
                }
                root.optJSONObject("error")?.let { error ->
                    val reason = error.optJSONObject("status")?.let { status ->
                        status.optString("message").ifBlank { status.optString("reason") }
                    } ?: error.optString("message")
                    throw YouTubeSearchException(
                        "YouTube отклонил запрос: " + reason.ifBlank { "причина не указана" },
                    )
                }

                val tracks = SearchParser(wanted, cleanQuery).parse(root)
                Log.d(TAG, "По запросу «$cleanQuery» найдено треков: ${tracks.size}")
                tracks
            } catch (e: YouTubeSearchException) {
                throw e
            } catch (e: IOException) {
                throw YouTubeSearchException("Ошибка сети при поиске: ${describe(e)}", e)
            } catch (e: JSONException) {
                throw YouTubeSearchException("Неожиданный формат ответа поиска: ${describe(e)}", e)
            } finally {
                // разрываем соединение даже при отмене корутины
                runCatching { connection.disconnect() }
            }
        }


    /** Тело запроса InnerTube. Запрос экранируется самим JSONObject. */
    private fun buildRequestBody(query: String): JSONObject {
        val client = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", CLIENT_VERSION)
            .put("androidSdkVersion", ANDROID_SDK_VERSION)
            .put("hl", "hl")
            .put("gl", "GL")
            .put("userAgent", USER_AGENT)
        return JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("query", query)
            .put("params", SEARCH_PARAMS)
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
                        Log.w(TAG, "Ответ поиска обрезан: больше $MAX_RESPONSE_BYTES байт")
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
        const val TAG = "YouTubeSearch"

        const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
        const val ORIGIN = "https://www.youtube.com"
        const val CLIENT_VERSION = "20.10.38"
        const val ANDROID_SDK_VERSION = 34
        const val USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"

        /** Фильтр InnerTube: только видео (EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D). */
        const val SEARCH_PARAMS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"

        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000

        /** 8 МБ — с запасом; больше поисковая выдача не возвращает. */
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

        fun describe(t: Throwable): String =
            t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

        fun abbreviate(s: String, max: Int): String {
            val flat = s.replace('\n', ' ').trim()
            return if (flat.length <= max) flat else flat.substring(0, max) + "…"
        }
    }
}


/**
 * Рекурсивный обход JSON-дерева ответа InnerTube и сбор треков.
 *
 * YouTube отдаёт разнородные структуры: videoRenderer в выдаче, compactVideoRenderer
 * в боковой панели, а внутри — вложенные playlistPanelVideoRenderer / reelItemRenderer.
 * Поэтому вместо жёсткой схемы используется обход «собери всё, где есть videoId и title».
 *
 * Экземпляр создаётся на один вызов [YouTubeSearch.search], потокобезопасность
 * не требуется. Класс internal — доступен юнит-тестам модуля, но не входит
 * в публичный API приложения.
 */
internal class SearchParser(
    private val limit: Int,
    private val query: String = "",
) {

    /** Треки в порядке появления в ответе; ключ — videoId (дедупликация). */
    private val tracks = LinkedHashMap<String, Track>()

    private var visited = 0

    /** Собираем с запасом: часть выдачи отсеется при ранжировании. */
    private val collectLimit = (limit * 3).coerceAtMost(MAX_COLLECT)

    /**
     * Отобранные видео в порядке «сначала треки».
     *
     * В выдаче YouTube вперемешку идут официальные треки (с бейджем «Music»),
     * клипы, миксы на час и стримы. Раньше просто брался порядок ответа, и
     * первым мог оказаться ремикс или запись стрима. Теперь треки идут первыми,
     * а из остальных выкидываются заведомо не-треки (длиннее получаса).
     */
    fun parse(root: Any?): List<Track> {
        walk(root, 0)

        val all = tracks.values.toList()
        val music = all.filter { it.isOfficialMusic }
        val rest = all.filterNot { it.isOfficialMusic }

        // Бейдж «Music» клиент ANDROID не отдаёт (проверено на живой выдаче),
        // поэтому порядок и «чистоту» списка решает сам парсер.
        val wanted = normalize(query)
        val ranked = (music + rest).sortedByDescending { score(it, wanted) }

        val clean = ranked.filterNot { isDerivative(it) }
        val result = if (clean.size >= MIN_RESULTS) clean else ranked

        Log.d(TAG, "Выдача: всего ${all.size}, треков ${music.size}, после отсева ${result.size}")
        return result.take(limit)
    }

    /**
     * Отсекаем заведомо не-трек: трансляции, концерты, ремиксы, каверы.
     *
     * YouTube в выдаче на русском треке отдаёт живые концерты, «slowed/reverb»,
     * каверы и часовые миксы — они и оказывались в списке первыми.
     */
    private fun isDerivative(track: Track): Boolean {
        val title = track.title.lowercase(Locale.ROOT).replace('ё', 'е')
        if (DERIVATIVE_MARKERS.any { it in title }) return true
        // Часовая «Compilation»/«Radio» без слов про трек
        return track.durationSeconds > MAX_DURATION_SECONDS
    }

    /**
     * Насколько видео похоже на искомый трек.
     *
     * Точное совпадение названия с запросом — самый сильный признак: у
     * авто-треков YouTube Music название равно названию песни.
     */
    private fun score(track: Track, wanted: String): Int {
        if (wanted.isEmpty()) return 0
        val title = normalize(track.title)
        val channel = normalize(track.channel) + normalize(track.musicArtist)

        var score = 0
        if (title == wanted) score += 12
        else if (wanted in title) score += 7
        else if (title in wanted) score += 4
        // Совпадение по отдельным словам запроса
        val words = wanted.split(' ').filter { it.length > 2 }
        score += words.count { it in title } * 2
        // Исполнитель из запроса нашёлся в названии или в канале
        score += words.count { it in channel } * 2
        // Авто-трек YouTube Music: «Исполнитель — Название (Topic)»
        if ("topic" in track.title.lowercase(Locale.ROOT)) score += 5
        if (track.isOfficialMusic) score += 6
        // Разумная длина песни
        val d = track.durationSeconds
        if (d in 60..900) score += 3
        if (d == Track.DURATION_LIVE) score -= 12
        if (d > MAX_DURATION_SECONDS) score -= 8
        return score
    }

    /** Приводит строку к сравнимому виду: без знаков препинания, в нижнем регистре. */
    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun walk(node: Any?, depth: Int) {
        if (node == null || depth > MAX_DEPTH || visited >= MAX_NODES) return
        visited++
        when (node) {
            is JSONObject -> {
                if (tracks.size < collectLimit) readTrack(node)
                val keys = node.keys()
                while (keys.hasNext()) {
                    walk(node.opt(keys.next()), depth + 1)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    walk(node.opt(i), depth + 1)
                }
            }
            // скаляры игнорируем
            else -> Unit
        }
    }


    /** Достаёт трек из объекта, если в нём есть videoId и непустой title. */
    private fun readTrack(node: JSONObject) {
        val id = node.optString("videoId").trim().takeIf { it.isNotEmpty() } ?: return
        val title = extractTitle(node)?.trim().orEmpty()
        if (title.isEmpty()) return

        val music = extractMusicBadge(node)
        val track = Track(
            id = id,
            title = title,
            channel = music.second ?: extractChannel(node),
            durationSeconds = extractDuration(node) ?: Track.DURATION_UNKNOWN,
            thumbnailUrl = extractThumbnail(node, id),
            videoUrl = "https://www.youtube.com/watch?v=$id",
            isOfficialMusic = music.first,
            musicArtist = music.second.orEmpty(),
        )

        val existing = tracks[id]
        if (existing == null) {
            tracks[id] = track
        } else if (existing.channel.isEmpty() && track.channel.isNotEmpty()) {
            // дубликат (выдача + микс-лента): оставляем запись с каналом
            tracks[id] = track
        }
    }

    /**
     * YouTube помечает официальные треки двумя способами:
     *  - `badges[].musicInlineBadgeRenderer` — нотка «Music» у названия;
     *  - `badges[].metadataBadgeRenderer` со значком `MUSIC` — «Трек» / «Music».
     *
     * Возвращает пару «помечен как трек» + «исполнитель из бейджа» (может быть null).
     */
    private fun extractMusicBadge(node: JSONObject): Pair<Boolean, String?> {
        val badges = node.optJSONArray("badges")
        if (badges != null) {
            for (i in 0 until badges.length()) {
                val badge = badges.optJSONObject(i) ?: continue
                if (badge.has("musicInlineBadgeRenderer")) {
                    val inline = badge.optJSONObject("musicInlineBadgeRenderer")
                    val label = textOf(inline?.opt("accessibilityData")?.let {
                        (it as? JSONObject)?.opt("label")
                    })
                    val icon = inline?.optJSONObject("thumbnailIcon")
                        ?.optJSONArray("thumbnails")
                        ?.optJSONObject(0)
                        ?.optString("url")
                        .orEmpty()
                    if (label != null || icon.contains("music", ignoreCase = true)) {
                        return true to label?.takeIf { it.isNotBlank() }
                    }
                }
                val meta = badge.optJSONObject("metadataBadgeRenderer")
                if (meta != null) {
                    val style = meta.optString("style")
                    val iconType = meta.optString("icon", meta.optString("iconType"))
                    if (style.contains("MUSIC", ignoreCase = true) ||
                        iconType.contains("MUSIC", ignoreCase = true)
                    ) {
                        return true to textOf(meta.opt("label"))?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }
        return false to null
    }

    /** Название: title → headline (элементы плейлиста). */
    private fun extractTitle(node: JSONObject): String? =
        TITLE_KEYS.firstNotNullOfOrNull { key -> textOf(node.opt(key)) }

    /** Канал: короткие варианты важнее, в longBylineText бывает «Канал • 1,2 млн просмотров». */
    private fun extractChannel(node: JSONObject): String {
        for (key in CHANNEL_KEYS) {
            val value = textOf(node.opt(key))?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return node.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.let { textOf(it.opt("title"))?.trim() }
            .orEmpty()
    }

    /** Длительность в секундах: lengthSeconds → lengthText → оверлеи обложки. */
    private fun extractDuration(node: JSONObject): Int? {
        node.optString("lengthSeconds").trim().toIntOrNull()?.let { return it }
        parseDuration(textOf(node.opt("lengthText")))?.let { return it }
        parseDuration(textOf(node.opt("duration")))?.let { return it }
        return scanOverlays(node, 0)
    }


    /** Ищет длительность в thumbnailOverlays (там лежит «3:33» поверх обложки). */
    private fun scanOverlays(node: JSONObject, depth: Int): Int? {
        if (depth > 5) return null
        val keys = node.keys()
        while (keys.hasNext()) {
            when (val child = node.opt(keys.next())) {
                is JSONObject -> {
                    val timeStatus = child.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                    if (timeStatus != null) {
                        parseDuration(textOf(timeStatus.opt("text")))?.let { return it }
                    }
                    if (child.has("lengthText")) {
                        parseDuration(textOf(child.opt("lengthText")))?.let { return it }
                    }
                    scanOverlays(child, depth + 1)?.let { return it }
                }

                is JSONArray -> {
                    for (i in 0 until child.length()) {
                        val item = child.optJSONObject(i) ?: continue
                        scanOverlays(item, depth + 1)?.let { return it }
                    }
                }

                else -> Unit
            }
        }
        return null
    }

    /** Обложка: берём наибольшую по площади и приводим к https. */
    private fun extractThumbnail(node: JSONObject, videoId: String): String {
        val array = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        if (array != null) {
            var bestUrl: String? = null
            var bestArea = -1L
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url").trim().takeIf { it.isNotEmpty() } ?: continue
                val area = item.optInt("width", 0).toLong() * item.optInt("height", 0).toLong()
                if (area > bestArea) {
                    bestArea = area
                    bestUrl = url
                }
            }
            if (bestUrl != null) return normalizeUrl(bestUrl)
        }
        // запасная обложка — доступна для любого валидного videoId
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }


    companion object {
        private const val TAG = "YouTubeSearch"

        /** Предохранители от неожиданно глубоких/широких деревьев. */
        const val MAX_DEPTH = 40
        const val MAX_NODES = 200_000

        /** Больше этого собирать смысла нет: limit срезается при выдаче. */
        const val MAX_COLLECT = 150

        /** Столько результатов нужно, чтобы разрешить выбросить мусор. */
        const val MIN_RESULTS = 5

        /** Разумная длительность трека: до 30 минут. */
        const val MAX_DURATION_SECONDS = 1800

        /** Слова-маркеры «это не трек, а производный ролик». */
        private val DERIVATIVE_MARKERS = listOf(
            "live", "концерт", "concert", "premiere", "премьера", "стрим", "stream",
            "reaction", "реакция", "реакти", "пресмотр", "смотрю", "разбор",
            "dj set", "djsets", "mix", "микс", "радио", "radio", "подборка",
            "compilation", " slowed", "reverb", "remix", "ремикс", "cover", "кавер",
            "instrumental", "минус", "караоке", "karaoke", "reaction video",
            " teaser", "trailer", "превью", "1 hour", "час", "full album", "альбом целиком",
        )

        private val TITLE_KEYS = arrayOf("title", "headline")

        /** Порядок важен: короткие варианты приоритетнее длинных. */
        private val CHANNEL_KEYS = arrayOf(
            "channelTitle",
            "shortBylineText",
            "uploaderName",
            "author",
            "ownerText",
            "bylineText",
            "videoOwnerChannelTitle",
            "longBylineText",
        )

        /**
         * Разбирает текст InnerTube: {"simpleText":"..."}, {"runs":[{"text":"..."}]},
         * {"accessibility":{...}} или обычную строку. Runs склеиваются без разделителя.
         */
        fun textOf(node: Any?): String? = when (node) {
            is String -> node
            is JSONObject -> when {
                node.has("simpleText") -> node.optString("simpleText")
                node.has("text") -> node.optString("text")
                node.has("runs") -> {
                    val runs = node.optJSONArray("runs")
                    if (runs == null) {
                        null
                    } else {
                        buildString {
                            for (i in 0 until runs.length()) {
                                val t = runs.optJSONObject(i)?.optString("text")
                                if (!t.isNullOrEmpty()) append(t)
                            }
                        }
                    }
                }

                node.has("accessibility") -> textOf(
                    node.optJSONObject("accessibility")?.optJSONObject("accessibilityData"),
                )

                else -> null
            }

            else -> null
        }


        /**
         * «3:33» → 213, «1:02:03» → 3723, «LIVE» → [Track.DURATION_LIVE],
         * неразобранное значение → null.
         */
        fun parseDuration(raw: String?): Int? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text.contains("live", ignoreCase = true) ||
                text.contains("прямой эфир", ignoreCase = true) ||
                text.contains("сейчас", ignoreCase = true)
            ) {
                return Track.DURATION_LIVE
            }
            val parts = text.split(':')
            if (parts.size !in 2..3) return null
            var total = 0L
            for (part in parts) {
                val value = part.trim().toIntOrNull() ?: return null
                if (value < 0) return null
                total = total * 60 + value
            }
            if (total > Int.MAX_VALUE) return null
            return total.toInt()
        }

        /** `//i.ytimg.com/...` → `https://i.ytimg.com/...`; http → https. */
        fun normalizeUrl(url: String): String = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.substring("http://".length)
            else -> url
        }
    }
}

