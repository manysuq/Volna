package com.volna.player.search

import android.util.Log
import org.json.JSONObject

/**
 * Разбор радио-очереди YouTube Music: элементы лежат
 * в `playlistPanelVideoRenderer`.
 *
 * У каждого есть `lengthText` («3:11») и `longBylineText` вида
 * «Исполнитель • Альбом • Год» — исполнитель в нём всегда первый.
 *
 * Обход общий с остальными парсерами: структура ответа глубокая
 * и точный путь до элементов меняется от запуска к запуску.
 */
internal class RadioParser(
    private val limit: Int,
    private val currentId: String,
) {

    private val tracks = LinkedHashMap<String, Track>()
    private var visited = 0

    fun parse(root: Any?): List<Track> {
        walk(root, 0)
        val result = tracks.values.take(limit)
        Log.d(TAG, "Похожих треков: ${tracks.size}, берём ${result.size}")
        return result
    }

    private fun walk(node: Any?, depth: Int) {
        if (node == null || depth > MAX_DEPTH || visited > MAX_NODES) return
        visited++
        when (node) {
            is JSONObject -> {
                // Берём с запасом, иначе исходный трек съест одно место.
                if (tracks.size < limit + RESERVE) {
                    node.optJSONObject(ITEM)?.let { read(it) }
                }
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), depth + 1)
            }

            is org.json.JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            else -> Unit
        }
    }

    private fun read(item: JSONObject) {
        val id = item.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            .orEmpty()
        if (!MusicSearchParser.isValidId(id) || id == currentId || tracks[id] != null) return

        val title = TrackText.titleOf(item).trim()
        if (title.isEmpty()) return

        val byline = TrackText.textOf(item.opt("longBylineText"))
            ?.substringBefore(SEPARATOR)
            .orEmpty()
            .trim()
        val duration = TrackText.parseDuration(TrackText.textOf(item.opt("lengthText")))
            ?: Track.DURATION_UNKNOWN

        tracks[id] = Track(
            id = id,
            title = title,
            channel = byline,
            durationSeconds = duration,
            thumbnailUrl = TrackText.thumbnailOf(item, id),
            videoUrl = "https://www.youtube.com/watch?v=$id",
            isOfficialMusic = true,
            musicArtist = byline,
        )
    }

    private companion object {
        const val TAG = "RadioParser"
        const val ITEM = "playlistPanelVideoRenderer"
        const val SEPARATOR = "•"
        const val MAX_DEPTH = 40
        const val MAX_NODES = 200_000
        const val RESERVE = 10
    }
}