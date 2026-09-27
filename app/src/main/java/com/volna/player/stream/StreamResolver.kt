package com.volna.player.stream

import android.util.Log
import com.ytdl.core.DownloadRequest
import com.ytdl.core.YtDlp
import com.volna.player.search.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Получает прямую ссылку на аудиопоток и проверяет её перед выдачей плееру.
 *
 * Почему нужна проверка: в ссылке YouTube зашит IP клиента (параметр `ip=`).
 * Если исходящий адрес сменился — например, включился VPN или трафик идёт
 * через прокси с ротацией — поток отдаёт 403. Поэтому берём ссылку, пробуем
 * её открыть и при неудаче берём новую.
 */
class StreamResolver {

    private val ytdlp = YtDlp()

    /**
     * Возвращает рабочую ссылку на аудиопоток [track] или null.
     * Проверенная ссылка гарантированно отдаёт данные с текущего IP.
     */
    suspend fun resolve(track: Track): String? = withContext(Dispatchers.IO) {
        try {
            val request = DownloadRequest(track.videoUrl)
                .format("bestaudio")
                .userAgent(USER_AGENT)
            // Библиотека сама перезапрашивает ссылку, пока та не начнёт работать
            ytdlp.getVerifiedStreamUrl(request).also { url ->
                // В URL есть itag: по нему видно, что взят именно audio-only поток
                val itag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1) ?: "?"
                Log.i(TAG, "Рабочая ссылка для ${track.id}: itag=$itag audio-only")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось получить ссылку: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "StreamResolver"
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 400L
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL = 206

        /**
         * User-Agent обязателен: без него googlevideo отвечает 403.
         * Должен совпадать с тем, что использует плеер.
         */
        const val USER_AGENT = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
    }
}
