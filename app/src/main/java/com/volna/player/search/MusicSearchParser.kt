package com.volna.player.search

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Разбор ответа InnerTube-поиска YouTube Music в список [Track].
 *
 * YouTube Music отдаёт две разные вёрстки одного и того же, в зависимости от клиента:
 *
 *  * `WEB_REMIX` — `musicResponsiveListItemRenderer` с колонками
 *    (`flexColumns[0]` — название, `flexColumns[1]` — «Композиция • Кино»)
 *    и `playlistItemData.videoId`;
 *  * `ANDROID_MUSIC` — `elementRenderer → newElement → … →
 *    musicListItemWrapperModel → musicListItemData`, где те же данные лежат
 *    плоско в полях `title` / `subtitle`, а `videoId` — в
 *    `onTap.innertubeCommand.watchEndpoint`.
 *
 * Поддерживаются обе схемы, а сам обход — рекурсивный: YouTube периодически
 * меняет, в какие секции упакована выдача, и жёсткий путь до узкого места
 * перестаёт работать при каждом таком изменении.
 *
 * Экземпляр создаётся на один вызов поиска; потокобезопасность не требуется.
 * Класс internal — доступен юнит-тестам модуля, но не входит в публичный API.
 */
internal class MusicSearchParser(
    private val limit: Int,
    private val query: String = "",
) {

    /** Найденные треки в порядке появления в ответе; ключ — videoId. */
    private val tracks = LinkedHashMap<String, Track>()

    private var visited = 0

    /** Собираем с запасом: часть выдачи отсеется фильтром по типу. */
    private val collectLimit = (limit * 3).coerceAtMost(MAX_COLLECT)

    /**
     * Официальные треки из ответа, лучшие — первыми.
     *
     * YouTube Music размечает каждый элемент типом, поэтому отсев точный:
     * оставляем `Композиция` (в англоязычной локали — `Song`), а если таких
     * мало — добираем `Видео`, но не альбомы, плейлисты и подкасты: играть
     * из них нечего.
     */
    fun parse(root: Any?): List<Track> {
        walk(root, 0)

        val all = tracks.values.toList()
        val wanted = normalize(query)
        val songs = all.filter { it.isOfficialMusic }
        val videos = all.filterNot { it.isOfficialMusic }
        // Порядок выдачи YouTube Music уже отсортирован по релевантности, поэтому
        // сортируем стабильно по оценке: при равных баллах исходный порядок сохранён.
        val ranked = (songs + videos)
            .mapIndexed { index, track -> Ranked(index, track, score(track, wanted)) }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.index })
            .map { it.track }

        val result = ranked.take(limit)
        Log.d(
            TAG,
            "Выдача YTM: всего ${all.size}, композиций ${songs.size}, после отбора ${result.size}",
        )
        return result
    }

    private class Ranked(val index: Int, val track: Track, val score: Int)

    /**
     * Насколько трек похож на искомый.
     *
     * У официальных треков YouTube Music название равно названию песни, а
     * исполнитель лежит в подписи, поэтому точное совпадение названия с
     * запросом — сильнейший признак.
     */
    private fun score(track: Track, wanted: String): Int {
        if (wanted.isEmpty()) return 0
        val title = normalize(track.title)
        val artist = normalize(track.channel) + normalize(track.musicArtist)

        var score = 0
        if (title == wanted) score += 12
        else if (wanted in title) score += 7
        else if (title.isNotEmpty() && title in wanted) score += 4

        val words = wanted.split(' ').filter { it.length > 2 }
        score += words.count { it in title } * 2
        score += words.count { it in artist } * 2

        // Авто-треки каталога: «Исполнитель — Название (Topic)»
        if (TOPIC_MARKER in track.title.lowercase(Locale.ROOT)) score += 5
        if (track.isOfficialMusic) score += 6
        val lowered = track.title.lowercase(Locale.ROOT)
        if (DERIVATIVE_MARKERS.any { it in lowered }) score -= 6
        return score
    }

    /** Приводит строку к сравнимому виду: без знаков препинания, в нижнем регистре. */
    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(NON_LETTER, " ")
            .trim()
            .replace(SPACES, " ")

    private fun walk(node: Any?, depth: Int) {
        if (node == null || depth > MAX_DEPTH || visited >= MAX_NODES) return
        visited++
        when (node) {
            is JSONObject -> {
                if (tracks.size < collectLimit) {
                    readWebRemixItem(node.optJSONObject(ITEM_WEB_REMIX))
                    readAndroidMusicItem(node.optJSONObject(ITEM_ANDROID_MUSIC))
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    walk(node.opt(keys.next()), depth + 1)
                }
            }

            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
            else -> Unit
        }
    }
    /** Элемент вёрстки `WEB_REMIX`. */
    private fun readWebRemixItem(item: JSONObject?) {
        if (item == null) return
        val id = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (!isValidId(id)) return

        val columns = item.optJSONArray("flexColumns")
        if (columns == null || columns.length() == 0) return
        val title = columnText(columns.optJSONObject(0)).trim()
        if (title.isEmpty()) return

        // Подпись: «Композиция • Кино • Альбом» либо «Видео • Автор • 12 млн просмотров».
        val subtitle = if (columns.length() > 1) columnText(columns.optJSONObject(1)) else ""
        val isSong = kindOf(subtitle) in SONG_KINDS
        val rest = parts(subtitle)
        val artist = if (isSong) rest.getOrNull(1).orEmpty() else rest.firstOrNull().orEmpty()
        val album = if (isSong) rest.getOrNull(2).orEmpty() else ""

        add(
            Track(
                id = id,
                title = title,
                channel = artist,
                durationSeconds = Track.DURATION_UNKNOWN,
                thumbnailUrl = thumbnailOf(item.optJSONObject("thumbnail"), id),
                videoUrl = watchUrl(id),
                isOfficialMusic = isSong,
                musicArtist = if (isSong) artist else "",
                album = album,
            ),
        )
    }

    /** Элемент вёрстки `ANDROID_MUSIC`: те же данные, но плоско и под обёрткой. */
    private fun readAndroidMusicItem(model: JSONObject?) {
        if (model == null) return
        // В ответе ANDROID_MUSIC поля лежат на уровень ниже обёртки.
        val data = model.optJSONObject(ITEM_DATA) ?: return
        val id = data.optJSONObject("onTap")
            ?.optJSONObject("innertubeCommand")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            .orEmpty()
        if (!isValidId(id)) return

        val title = data.optString("title").trim()
        if (title.isEmpty()) return

        val subtitle = data.optString("subtitle")
        val isSong = kindOf(subtitle) in SONG_KINDS
        val rest = parts(subtitle)
        val artist = if (isSong) rest.getOrNull(1).orEmpty() else rest.firstOrNull().orEmpty()
        val album = if (isSong) rest.getOrNull(2).orEmpty() else ""

        add(
            Track(
                id = id,
                title = title,
                channel = artist,
                durationSeconds = Track.DURATION_UNKNOWN,
                thumbnailUrl = thumbnailOf(data.optJSONObject("thumbnail"), id),
                videoUrl = watchUrl(id),
                isOfficialMusic = isSong,
                musicArtist = if (isSong) artist else "",
                album = album,
            ),
        )
    }

    /** Тип элемента: текст до первого разделителя «•», в нижнем регистре. */
    private fun kindOf(subtitle: String): String =
        subtitle.substringBefore(SEPARATOR).trim().lowercase(Locale.ROOT)

    /** Части подписи, разделённые «•», без пустых. */
    private fun parts(subtitle: String): List<String> =
        subtitle.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** Кладёт трек в набор, не затирая уже найденный (для дублей в разных секциях). */
    private fun add(track: Track) {
        if (tracks[track.id] == null) tracks[track.id] = track
    }

    /**
     * Текст колонки.
     *
     * Колонка приходит обёрнутой в `musicResponsiveListItemFlexColumnRenderer`,
     * а текст внутри неё лежит либо в `simpleText`, либо в списке `runs`,
     * который склеивается без разделителя.
     */
    private fun columnText(column: JSONObject?): String {
        if (column == null) return ""
        val flex = column.optJSONObject(FLEX_COLUMN) ?: return ""
        val text = flex.optJSONObject("text") ?: return ""
        text.optString("simpleText").takeIf { it.isNotEmpty() }?.let { return it }
        val runs = text.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                runs.optJSONObject(i)?.optString("text")?.let { append(it) }
            }
        }
    }

    /**
     * Обложка: у `WEB_REMIX` это `musicThumbnailRenderer.thumbnail.thumbnails`,
     * у `ANDROID_MUSIC` — `image.sources`. Берём наибольшую по площади.
     */
    private fun thumbnailOf(node: JSONObject?, videoId: String): String {
        val array = node?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: node?.optJSONObject("image")?.optJSONArray("sources")

        if (array != null) {
            var bestUrl: String? = null
            var bestArea = -1L
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url").trim().ifEmpty { continue }
                val area = item.optInt("width", 0).toLong() * item.optInt("height", 0).toLong()
                if (area > bestArea) {
                    bestArea = area
                    bestUrl = url
                }
            }
            if (bestUrl != null) return normalizeUrl(bestUrl)
        }
        // Запасная обложка — доступна для любого валидного videoId
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    companion object {
        private const val TAG = "YouTubeMusicSearch"

        private const val ITEM_WEB_REMIX = "musicResponsiveListItemRenderer"
        private const val ITEM_ANDROID_MUSIC = "musicListItemWrapperModel"
        private const val ITEM_DATA = "musicListItemData"
        private const val FLEX_COLUMN = "musicResponsiveListItemFlexColumnRenderer"

        /** YouTube Music разделяет части подписи символом «•». */
        private const val SEPARATOR = "•"

        /** Официальные треки: русская и английская локали, разные регионы. */
        private val SONG_KINDS = setOf(
            "композиция", "композиції", "композиція", "трек", "песня",
            "song", "songs", "track", "tracks",
        )

        private const val TOPIC_MARKER = "(topic)"

        /** Слова-маркеры «это не тот трек, а производный ролик». */
        private val DERIVATIVE_MARKERS = listOf(
            "live", "концерт", "concert", "premiere", "премьера", "стрим", "stream",
            "reaction", "реакция", "dj set", "mix", "микс", "подборка",
            "slowed", "reverb", "remix", "ремикс", "cover", "кавер",
            "instrumental", "минус", "караоке", "karaoke",
        )

        private val NON_LETTER = Regex("[^a-zа-я0-9]+")
        private val SPACES = Regex("\\s+")
        private val ID = Regex("^[A-Za-z0-9_-]{11}$")

        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 200_000
        private const val MAX_COLLECT = 150

        /** video id — ровно 11 символов; иначе это не то, что нам нужно. */
        fun isValidId(id: String?): Boolean = id != null && ID.matches(id)

        /**
         * Ссылка на видео.
         *
         * Именно `www.youtube.com`, а не `music.youtube.com`: аудиопоток отдаётся
         * только обычному клиенту InnerTube, а `player` с клиентом `WEB_REMIX`
         * возвращает `UNPLAYABLE`.
         */
        fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

        /** `//host/...` → `https://host/...`; http → https. */
        fun normalizeUrl(url: String): String = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.substring("http://".length)
            else -> url
        }
    }
}