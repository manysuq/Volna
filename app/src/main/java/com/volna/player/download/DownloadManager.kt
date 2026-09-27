package com.volna.player.download

import android.content.Context
import com.ytdl.core.DownloadRequest
import com.ytdl.core.ProgressListener
import com.ytdl.core.YtDlp
import com.volna.player.R
import com.volna.player.search.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Менеджер скачиваний: обёртка над нашей Java-библиотекой.
 *
 * Отвечает за скачивание аудио в фоне, отслеживает прогресс по каждому
 * треку и умеет отменять загрузку.
 */
class DownloadManager(private val context: Context) {

    private val ytdlp = YtDlp()
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Публичное хранилище: общая папка Music/Volna + реестр скачанного. */
    val store = DownloadStore(context)

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Запускает скачивание аудио трека. Возвращает файл или ошибку. */
    fun download(track: Track, audioOnly: Boolean = true, onDone: (Result<File>) -> Unit) {
        if (jobs.containsKey(track.id)) return

        val job = scope.launch {
            // Сначала во временный файл: MediaStore требует готовый поток,
            // а писать напрямую в него нельзя. Временный каталог чистится
            // системой, поэтому недоудалённый файл не копится.
            val target = File(tempDir(), fileName(track))
            try {
                val request = DownloadRequest(track.videoUrl)
                    .outputDir(tempDir())
                    .format(if (audioOnly) "bestaudio" else "bestvideo+bestaudio")
                    .outputTemplate("%(title)s.%(ext)s")
                    .overwrite(true)
                    .writeThumbnail(false)

                val file = withContext(Dispatchers.IO) {
                    ytdlp.download(request, object : ProgressListener {
                        override fun onStart(totalBytes: Long, fileName: String) {
                            update(track.id) { it.copy(total = totalBytes, fileName = fileName) }
                        }

                        override fun onProgress(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Double) {
                            update(track.id) {
                                it.copy(
                                    downloaded = downloadedBytes,
                                    total = if (totalBytes > 0) totalBytes else it.total,
                                    speed = bytesPerSecond,
                                )
                            }
                        }

                        override fun onFinish(file: File) = Unit

                        override fun onError(error: Exception) {
                            update(track.id) { it.copy(error = error.message) }
                        }
                    })
                }
                // Публикуем в общую папку: во внутреннем хранилище файл
                // не найти ни файловым менеджером, ни музыкальной библиотекой,
                // и он исчезает при удалении приложения.
                val uri = store.publish(file, track)
                if (uri == null) {
                    file.delete()
                    update(track.id) {
                        it.copy(
                            state = DownloadState.FAILED,
                            error = context.getString(R.string.download_publish_failed),
                        )
                    }
                    onDone(Result.failure(IllegalStateException("publish failed")))
                    return@launch
                }
                store.remember(track, uri, file.length())
                file.delete()
                update(track.id) { it.copy(state = DownloadState.DONE, path = uri, saved = true) }
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                update(track.id) { it.copy(state = DownloadState.FAILED, error = message) }
                onDone(Result.failure(e))
            } finally {
                jobs.remove(track.id)
            }
        }
        jobs[track.id] = job
        update(track.id) { it.copy(state = DownloadState.QUEUED) }
    }

    /** Отменяет активную загрузку трека. */
    fun cancel(trackId: String) {
        ytdlp.cancel()
        jobs.remove(trackId)?.cancel()
        _progress.value = _progress.value.toMutableMap().apply { remove(trackId) }
    }

    fun isDownloading(trackId: String): Boolean = jobs.containsKey(trackId)

    fun progressOf(trackId: String): DownloadProgress? = _progress.value[trackId]

    private fun update(trackId: String, block: (DownloadProgress) -> DownloadProgress) {
        _progress.value = _progress.value.toMutableMap().apply {
            this[trackId] = block(this[trackId] ?: DownloadProgress(trackId))
        }
    }

    private fun tempDir(): File = File(context.cacheDir, "music-dl").apply { mkdirs() }

    private fun fileName(track: Track): String =
        com.ytdl.core.FileUtils.sanitizeName(track.title) + ".m4a"
}
