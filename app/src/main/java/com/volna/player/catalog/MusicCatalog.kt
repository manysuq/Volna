package com.volna.player.catalog

import android.util.Log
import com.volna.player.search.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

/**
 * Каталог музыки: исполнители, альбомы и их треки.
 *
 * Метаданные берём из открытого API iTunes Search (ключ не нужен):
 * там есть обложки, даты релизов и треклисты. Само аудио по-прежнему
 * стримится с YouTube — iTunes используется только как оглавление.
 */
object MusicCatalog {

    private const val TAG = "MusicCatalog"

    /** iTunes помечает синглы как "Название - Single". */
    private val SINGLE_SUFFIX = Regex("\\s*-\\s*Single\\s*$", RegexOption.IGNORE_CASE)
    private const val BASE = "https://itunes.apple.com/search"
    private const val LOOKUP = "https://itunes.apple.com/lookup"

    /** Исполнитель. */
    data class Artist(
        val id: Long,
        val name: String,
        val genre: String,
        val artworkUrl: String,
    )

    /** Альбом. */
    data class Album(
        val id: Long,
        val title: String,
        val artistName: String,
        val year: String,
        val artworkUrl: String,
        val trackCount: Int,
    )

    /** Поиск исполнителей по имени. */
    suspend fun searchArtists(query: String, limit: Int = 20): List<Artist> =
        withContext(Dispatchers.IO) {
            val url = "$BASE?term=${encode(query)}&entity=musicArtist&limit=$limit"
            val results = request(url) ?: return@withContext emptyList()
            results.mapNotNull { item ->
                val id = item.optLong("artistId", 0L)
                val name = item.optString("artistName")
                if (id == 0L || name.isEmpty()) return@mapNotNull null
                Artist(
                    id = id,
                    name = name,
                    genre = item.optString("primaryGenreName"),
                    artworkUrl = artwork(item, 300),
                )
            }.also { Log.d(TAG, "Исполнителей найдено: ${it.size}") }
        }

    /** Альбомы исполнителя. */
    suspend fun albumsOf(artist: Artist, limit: Int = 60): List<Album> =
        withContext(Dispatchers.IO) {
            val url = "$LOOKUP?id=${artist.id}&entity=album&limit=$limit"
            val results = request(url) ?: return@withContext emptyList()
            results.mapNotNull { item ->
                if (item.optString("wrapperType") != "collection") return@mapNotNull null
                val id = item.optLong("collectionId", 0L)
                val title = item.optString("collectionName")
                if (id == 0L || title.isEmpty()) return@mapNotNull null
                Album(
                    id = id,
                    // убираем служебный хвост " - Single"
                    title = title.replace(SINGLE_SUFFIX, "").trim(),
                    artistName = item.optString("artistName", artist.name),
                    year = item.optString("releaseDate").take(4),
                    artworkUrl = artwork(item, 600),
                    trackCount = item.optInt("trackCount"),
                )
            }.sortedByDescending { it.year }.also {
                Log.d(TAG, "Альбомов у ${artist.name}: ${it.size}")
            }
        }

    /**
     * Треки альбома. Их нельзя стримить напрямую: id iTunes не является
     * ссылкой на YouTube. Возвращаем их как Track с ключом поиска,
     * чтобы экран мог найти версию на YouTube.
     */
    suspend fun tracksOf(album: Album): List<CatalogTrack> =
        withContext(Dispatchers.IO) {
            val url = "$LOOKUP?id=${album.id}&entity=song"
            val results = request(url) ?: return@withContext emptyList()
            results.filter { it.optString("wrapperType") == "track" }.mapNotNull { item ->
                val name = item.optString("trackName")
                if (name.isEmpty()) return@mapNotNull null
                CatalogTrack(
                    number = item.optInt("trackNumber"),
                    title = name,
                    artistName = item.optString("artistName", album.artistName),
                    durationMs = item.optLong("trackTimeMillis"),
                    artworkUrl = artwork(item, 300),
                )
            }.sortedBy { it.number }.also { Log.d(TAG, "Треков в альбоме: ${it.size}") }
        }

    /** Трек альбома: как искать его на YouTube. */
    data class CatalogTrack(
        val number: Int,
        val title: String,
        val artistName: String,
        val durationMs: Long,
        val artworkUrl: String,
    ) {
        /** Готовый запрос для нашего поиска на YouTube. */
        val searchQuery: String
            get() = "$title $artistName audio".trim()

        /** Track-заглушка: реальную ссылку даст поиск по [searchQuery]. */
        fun toTrack(found: Track): Track = found
    }

    private fun request(url: String): List<JSONObject>? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "iTunes/12.0 (Android)")
            }
            val code = connection.responseCode
            if (code != 200) {
                Log.w(TAG, "Запрос каталога вернул HTTP $code")
                null
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONObject(text).optJSONArray("results")
                if (array == null) {
                    emptyList()
                } else {
                    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка каталога: ${e.message}", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Обложка нужного размера: iTunes отдаёт 100x100, меняем на 600x600. */
    private fun artwork(item: JSONObject, size: Int): String {
        val url = item.optString("artworkUrl100")
        if (url.isEmpty()) return ""
        return url.replace("100x100", "${size}x${size}")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")
}
