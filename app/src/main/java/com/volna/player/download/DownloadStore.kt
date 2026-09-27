package com.volna.player.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.volna.player.search.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Один сохранённый трек: всё, что нужно, чтобы открыть его без сети. */
data class SavedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val uri: String,
    /** Ссылка на трек в YouTube: в «Поделиться» уходит она, а не content://. */
    val sourceUrl: String,
    val durationSeconds: Int,
    val thumbnailUrl: String,
    val sizeBytes: Long,
    val savedAt: Long,
)

/**
 * Хранилище скачанных треков.
 *
 * Куда класть файл — единственное решение, в котором легко ошибиться, и
 * ошибка выглядит не как баг, а как «ничего не произошло». Поэтому:
 *
 *  - На Android 10+ (API 29+) файл уходит в общую папку Music/Volna через
 *    MediaStore. Раньше он попадал в `context.filesDir/music` — во внутреннее
 *    хранилище приложения: файла не видно ни в одном файловом менеджере, он не
 *    попадает в музыкальную библиотеку и исчезает при удалении приложения.
 *  - На Android 7-9 (API 24-28) общей папки через MediaStore ещё нет, пишем
 *    прямо в Music/Volna — там нужен доступ к внешнему хранилищу.
 *
 * Реестр лежит в своём файле, потому что прогресс жил только в памяти: после
 * перезапуска приложение забывало, что вообще скачано, и галочка исчезала.
 */
class DownloadStore(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val registryFile: File get() = File(appContext.filesDir, "downloads.json")

    /** Публичная папка с треками; на старых версиях — она же корень записи. */
    val publicDirName: String = "${Environment.DIRECTORY_MUSIC}/Volna"

    // ── Реестр ────────────────────────────────────────────────────────────

    fun list(): List<SavedTrack> {
        if (!registryFile.exists()) return emptyList()
        return try {
            val array = JSONArray(registryFile.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                SavedTrack(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    uri = o.optString("uri"),
                    sourceUrl = o.optString("sourceUrl"),
                    durationSeconds = o.optInt("duration"),
                    thumbnailUrl = o.optString("thumb"),
                    sizeBytes = o.optLong("size"),
                    savedAt = o.optLong("savedAt"),
                ).takeIf { it.id.isNotEmpty() && it.uri.isNotEmpty() && exists(it.uri) }
            }
        } catch (e: Exception) {
            // Повреждённый реестр не должен ронять запуск: считаем, что
            // скачанного нет. Файлы при этом остаются на месте.
            emptyList()
        }
    }

    private fun save(items: List<SavedTrack>) {
        val array = JSONArray()
        items.forEach { t ->
            array.put(
                JSONObject().apply {
                    put("id", t.id); put("title", t.title); put("artist", t.artist)
                    put("album", t.album); put("uri", t.uri); put("sourceUrl", t.sourceUrl)
                    put("duration", t.durationSeconds)
                    put("thumb", t.thumbnailUrl); put("size", t.sizeBytes); put("savedAt", t.savedAt)
                },
            )
        }
        runCatching { registryFile.writeText(array.toString()) }
    }

    fun find(trackId: String): SavedTrack? = list().firstOrNull { it.id == trackId }

    fun isSaved(trackId: String): Boolean = find(trackId) != null

    // ── Публикация файла в общую папку ─────────────────────────────────────

    private fun fileName(track: Track): String =
        com.ytdl.core.FileUtils.sanitizeName(track.title) + ".m4a"

    /**
     * Кладёт готовый файл в общую папку и возвращает uri для воспроизведения.
     *
     * Сначала вставляем запись с IS_PENDING: до конца копирования файл не виден
     * ни медиатеке, ни другим приложениям, поэтому прерванная загрузка не
     * оставит half-файла в Music.
     */
    fun publish(source: File, track: Track): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(source, fileName(track))
        } else {
            publishLegacy(source, fileName(track))
        }

    private fun publishViaMediaStore(source: File, name: String): String? = try {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, publicDirName)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            null
        } else {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null, null,
            )
            uri.toString()
        }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(source: File, name: String): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Volna",
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, name)
        return try {
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            Uri.fromFile(target).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Регистрирует трек в реестре. */
    fun remember(track: Track, uri: String, sizeBytes: Long): SavedTrack {
        val item = SavedTrack(
            id = track.id,
            title = track.title,
            artist = track.musicArtist.ifBlank { track.channel },
            album = track.album,
            uri = uri,
            sourceUrl = track.videoUrl,
            durationSeconds = track.durationSeconds,
            thumbnailUrl = track.thumbnailUrl,
            sizeBytes = sizeBytes,
            savedAt = System.currentTimeMillis(),
        )
        save(list().filterNot { it.id == item.id } + item)
        return item
    }

    /** Удаляет файл и запись реестра. */
    fun remove(trackId: String): Boolean {
        val item = find(trackId) ?: return false
        val removed = runCatching {
            val uri = Uri.parse(item.uri)
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() } != null
            } else {
                resolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
        save(list().filterNot { it.id == trackId })
        return removed
    }

    /** Файл на месте? Иначе запись протухла — её надо выкинуть из реестра. */
    private fun exists(uriString: String): Boolean = try {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it).exists() } == true
            else -> resolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }
    } catch (e: Exception) {
        false
    }

    /** Суммарный размер сохранённого — показываем в настройках. */
    fun totalSizeBytes(): Long = list().sumOf { it.sizeBytes }
}
