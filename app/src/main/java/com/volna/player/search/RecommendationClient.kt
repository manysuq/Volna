package com.volna.player.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Похожие треки по текущему: берёт их из рекомендаций YouTube
 * к открытому видео (внутренний эндпоинт /next).
 *
 * Отдельная реализация вместо готовых сервисов (Deezer/Spotify):
 * результат сразу пригоден для стриминга нашим плеером.
 */
object RecommendationClient {

    private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
    private const val USER_AGENT =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
    private const val MAX_DEPTH = 12
    private const val MAX_NODES = 4000

    /** Похожие треки к [videoId], исключая сам [videoId]. */
    suspend fun related(videoId: String, limit: Int = 20): List<Track> =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("context", contextJson())
                .put("videoId", videoId)
                .toString()

            val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setFixedLengthStreamingMode(body.toByteArray().size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Origin", "https://www.youtube.com")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                if (code != 200) {
                    Log.w(TAG, "Рекомендации недоступны: HTTP $code")
                    return@withContext emptyList()
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                parse(JSONObject(text), videoId, limit)
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка получения рекомендаций: ${e.message}", e)
                emptyList()
            } finally {
                connection.disconnect()
            }
        }

    private fun contextJson(): JSONObject = JSONObject().put(
        "client",
        JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", "20.10.38")
            .put("androidSdkVersion", 34)
            .put("hl", "en")
            .put("gl", "US")
            .put("userAgent", USER_AGENT),
    )

    private fun parse(root: JSONObject, currentId: String, limit: Int): List<Track> {
        val found = LinkedHashMap<String, Track>()
        var visited = 0
        walk(root, 0, currentId, found, limit) { visited++ }
        Log.d(TAG, "Рекомендаций найдено: ${found.size}")
        return found.values.toList()
    }

    private fun walk(
        node: Any?,
        depth: Int,
        currentId: String,
        out: MutableMap<String, Track>,
        limit: Int,
        visited: () -> Int,
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= limit) return
        when (node) {
            is JSONObject -> {
                val id = node.optString("videoId")
                if (id.isNotEmpty() && id != currentId) {
                    val title = TrackText.titleOf(node)
                    if (title.isNotEmpty() && !out.containsKey(id)) {
                        out[id] = Track(
                            id = id,
                            title = title,
                            channel = TrackText.channelOf(node),
                            durationSeconds = TrackText.durationOf(node),
                            thumbnailUrl = TrackText.thumbnailOf(node, id),
                            videoUrl = "https://www.youtube.com/watch?v=$id",
                        )
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    if (visited() > MAX_NODES) return
                    walk(node.opt(keys.next()), depth + 1, currentId, out, limit, visited)
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    if (visited() > MAX_NODES) return
                    walk(node.opt(i), depth + 1, currentId, out, limit, visited)
                }
            }
        }
    }

    private const val TAG = "Recommendations"
}

/**
 * Разбор текстовых полей результатов YouTube: название, канал,
 * длительность и обложка. Общий для поиска и рекомендаций.
 */
internal object TrackText {

    private val TITLE_KEYS = arrayOf("title", "headline", "label")
    private val CHANNEL_KEYS = arrayOf(
        "longBylineText", "shortBylineText", "ownerText", "channelName",
    )

    fun titleOf(node: JSONObject): String {
        for (key in TITLE_KEYS) {
            textOf(node.opt(key))?.let { if (it.isNotBlank()) return it.trim() }
        }
        return ""
    }

    fun channelOf(node: JSONObject): String {
        for (key in CHANNEL_KEYS) {
            textOf(node.opt(key))?.let { if (it.isNotBlank()) return it.trim() }
        }
        return node.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.let { textOf(it.opt("title")) }
            ?.trim()
            .orEmpty()
    }

    /** Длительность в секундах: 0, если неизвестна. */
    fun durationOf(node: JSONObject): Int {
        node.optString("lengthSeconds").trim().toIntOrNull()?.let { return it }
        for (key in arrayOf("lengthText", "duration", "text")) {
            parseDuration(textOf(node.opt(key)))?.let { return it }
        }
        return scanOverlays(node, 0) ?: 0
    }

    fun thumbnailOf(node: JSONObject, videoId: String): String {
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
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    /** "3:33" / "1:02:03" -> секунды. */
    fun parseDuration(text: String?): Int? {
        val value = text?.trim() ?: return null
        if (value.isEmpty()) return null
        val parts = value.split(":")
        var seconds = 0L
        for (part in parts) {
            val n = part.trim().toLongOrNull() ?: return null
            seconds = seconds * 60 + n
        }
        return seconds.toInt()
    }

    /** Достаёт текст из {"simpleText":"..."} или {"runs":[{"text":"..."}]}. */
    fun textOf(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> {
            value.optString("simpleText").takeIf { it.isNotEmpty() }
                ?: joinRuns(value.optJSONArray("runs"))
        }
        else -> null
    }

    private fun joinRuns(runs: org.json.JSONArray?): String? {
        if (runs == null) return null
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            runs.optJSONObject(i)?.optString("text")?.let { sb.append(it) }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private fun scanOverlays(node: JSONObject, depth: Int): Int? {
        if (depth > 5) return null
        val keys = node.keys()
        while (keys.hasNext()) {
            val child = node.opt(keys.next())
            if (child is JSONObject) {
                child.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.let { overlay ->
                    parseDuration(textOf(overlay.opt("text")))?.let { return it }
                }
                if (child.has("lengthText")) {
                    parseDuration(textOf(child.opt("lengthText")))?.let { return it }
                }
                scanOverlays(child, depth + 1)?.let { return it }
            } else if (child is org.json.JSONArray) {
                for (i in 0 until child.length()) {
                    child.optJSONObject(i)?.let { item ->
                        scanOverlays(item, depth + 1)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun normalizeUrl(url: String): String =
        if (url.startsWith("//")) "https:$url" else url
}
