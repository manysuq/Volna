package com.volna.player.library

import android.content.Context
import com.volna.player.search.Track
import org.json.JSONArray
import org.json.JSONObject

/** Плейлист пользователя: свои треки в своём порядке. */
data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
)

/**
 * «Нравится» и свои плейлисты.
 *
 * Хранится в SharedPreferences целиком, в JSON: треков мало, меняются они
 * редко, а отдельная база ради списка строк была бы лишней. Состояние
 * переживает перезапуск процесса, поэтому при запуске ничего не теряется.
 */
class LibraryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── «Нравится» ─────────────────────────────────────────────────────────

    fun favorites(): List<Track> = readTracks(KEY_FAVORITES)

    fun isFavorite(trackId: String): Boolean = favorites().any { it.id == trackId }

    /** Переключает «нравится»; возвращает новое состояние. */
    fun toggleFavorite(track: Track): Boolean {
        val current = favorites()
        val isFav = current.any { it.id == track.id }
        val updated = if (isFav) current.filterNot { it.id == track.id } else current + track
        writeTracks(KEY_FAVORITES, updated)
        return !isFav
    }

    // ── Плейлисты ──────────────────────────────────────────────────────────

    fun playlists(): List<Playlist> = try {
        val array = JSONArray(prefs.getString(KEY_PLAYLISTS, null) ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            Playlist(id, o.optString("name"), o.optLong("createdAt"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun tracksOf(playlistId: String): List<Track> = readTracks(keyTracks(playlistId))

    fun trackCount(playlistId: String): Int = tracksOf(playlistId).size

    fun createPlaylist(name: String): Playlist {
        val playlist = Playlist(
            id = "pl_" + System.currentTimeMillis().toString(36) + "_" + name.hashCode().toString(36),
            name = name.trim().ifEmpty { "—" },
            createdAt = System.currentTimeMillis(),
        )
        writePlaylists(playlists() + playlist)
        return playlist
    }

    fun renamePlaylist(playlistId: String, name: String) {
        writePlaylists(playlists().map { if (it.id == playlistId) it.copy(name = name.trim()) else it })
    }

    fun deletePlaylist(playlistId: String) {
        prefs.edit().remove(keyTracks(playlistId)).apply()
        writePlaylists(playlists().filterNot { it.id == playlistId })
    }

    fun contains(playlistId: String, trackId: String): Boolean =
        tracksOf(playlistId).any { it.id == trackId }

    /** Добавляет трек в плейлист. false — трек там уже был. */
    fun addToPlaylist(playlistId: String, track: Track): Boolean {
        val current = tracksOf(playlistId)
        if (current.any { it.id == track.id }) return false
        writeTracks(keyTracks(playlistId), current + track)
        return true
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        writeTracks(keyTracks(playlistId), tracksOf(playlistId).filterNot { it.id == trackId })
    }

    /** Переносит трек внутри плейлиста — для кнопок «выше»/«ниже». */
    fun moveInPlaylist(playlistId: String, trackId: String, delta: Int) {
        val current = tracksOf(playlistId).toMutableList()
        val from = current.indexOfFirst { it.id == trackId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, current.lastIndex)
        if (from == to) return
        current.add(to, current.removeAt(from))
        writeTracks(keyTracks(playlistId), current)
    }

    // ── Сериализация ───────────────────────────────────────────────────────

    private fun readTracks(key: String): List<Track> = try {
        val array = JSONArray(prefs.getString(key, null) ?: "[]")
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.toTrack() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeTracks(key: String, tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { array.put(it.toJson()) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun writePlaylists(items: List<Playlist>) {
        val array = JSONArray()
        items.forEach {
            array.put(
                JSONObject().apply {
                    put("id", it.id); put("name", it.name); put("createdAt", it.createdAt)
                },
            )
        }
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    private fun keyTracks(playlistId: String) = "$KEY_PREFIX_TRACKS$playlistId"

    private fun Track.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("channel", channel)
        put("durationSeconds", durationSeconds); put("thumbnailUrl", thumbnailUrl)
        put("videoUrl", videoUrl); put("isOfficialMusic", isOfficialMusic)
        put("musicArtist", musicArtist); put("album", album)
    }

    private fun JSONObject.toTrack(): Track? {
        val id = optString("id")
        if (id.isEmpty()) return null
        return Track(
            id = id,
            title = optString("title"),
            channel = optString("channel"),
            durationSeconds = optInt("durationSeconds", -1),
            thumbnailUrl = optString("thumbnailUrl"),
            videoUrl = optString("videoUrl"),
            isOfficialMusic = optBoolean("isOfficialMusic", false),
            musicArtist = optString("musicArtist"),
            album = optString("album"),
        )
    }

    private companion object {
        const val PREFS = "volna_library"
        const val KEY_FAVORITES = "favorites"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_PREFIX_TRACKS = "playlist_tracks_"
    }
}
