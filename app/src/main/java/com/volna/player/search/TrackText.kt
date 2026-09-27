package com.volna.player.search

import org.json.JSONObject

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