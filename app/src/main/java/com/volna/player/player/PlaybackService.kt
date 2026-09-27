package com.volna.player.player

import com.volna.player.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.volna.player.search.Track
import com.volna.player.stream.StreamResolver
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Сервис воспроизведения на Media3.
 *
 * Основной режим — прямой стриминг аудиопотока с YouTube:
 * ExoPlayer читает поток по HTTP с докачкой, ничего не скачивая целиком.
 * Проигранное складывается в кэш, чтобы не тянуть заново.
 *
 * Сервис ещё и «глаза»: без [Player.Listener] обрыв потока выглядит как вечное
 * «Подключение…», поэтому ошибки разбора сети уезжают наружу через [error]
 * и [faults] — на них подписан ViewModel и перезапрашивает ссылку.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Без провайдера сервис не может уйти в foreground
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(com.volna.player.R.string.app_name)
                .build()
        )

        val cache = SimpleCache(
            File(cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(this)
        )

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(StreamResolver.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*"))

        // Заголовки упаковываем сами: Range нельзя задать через
        // setDefaultRequestProperties — ExoPlayer перетирает его своим, а при
        // первом открытии файла не формирует вовсе. Из-за этого YouTube
        // отвечал 403, и трек начинал играть только с третьей попытки.
        val rangedFactory = DataSource.Factory { RangeDataSource(httpFactory.createDataSource()) }
        val upstream = DefaultDataSource.Factory(this, rangedFactory)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer?.addListener(playerListener)
        mediaSession = MediaSession.Builder(this, exoPlayer!!).build()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING
            // Трек доиграл: сообщаем ViewModel, он возьмёт следующий сам.
            // Вместе с состоянием отдаём id доигравшего трека: событие может
            // прийти уже после того, как нажали другой трек, и без проверки
            // оно уводило бы воспроизведение не туда.
            if (playbackState == Player.STATE_ENDED) {
                _trackFinished.tryEmit(exoPlayer?.currentMediaItem?.mediaId)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // ExoPlayer сам перешёл на следующий трек очереди (автоплей).
            // Публикуем mediaId, чтобы ViewModel обновил mini/full плеер.
            _currentMediaId.value = mediaItem?.mediaId
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlayingState.value = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            val reason = describe(this@PlaybackService, error)
            Log.e(TAG, "Воспроизведение оборвалось: $reason", error)
            // В буфер кладём и текст ошибки, и её код: по одному тексту
            // понять причину нельзя, коды у Media3 различают близкие случаи.
            com.volna.player.LogBuffer.e(
                "PlaybackService",
                "обрыв: $reason, код=${error.errorCodeName}, uri=${error.localizedMessage}",
            )
            _buffering.value = false
            _isPlayingState.value = false
            _error.value = reason
            // Переподключение имеет смысл только для «поток сломался»:
            // протухшая ссылка и сетевой сбок лечатся новой ссылкой, а 403 —
            // нет. Раньше он тоже уходил в переподключение, отсюда было
            // ощущение, что play надо нажимать несколько раз.
            if (!isForbidden(error)) {
                _faults.tryEmit(Unit)
            }
        }
    }

    /**
     * Media3 уходит в foreground только когда воспроизведение реально
     * стартовало. Но система требует startForeground() в течение 5 секунд
     * после startForegroundService(), поэтому заглушечный вызов нужен.
     *
     * Само уведомление показываем не всегда: сервис поднимается заранее
     * (при запуске приложения, ради готовности плеера), и постоянная надпись
     * «Подключение…» висела бы в шторке даже когда ничего не играет.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        val hasQueue = (exoPlayer?.mediaItemCount ?: 0) > 0
        if (hasQueue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
        }
        return result
    }

    private fun buildPlaceholderNotification(): android.app.Notification {
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManagerCompat.from(this).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            NOTIFICATION_CHANNEL_ID
        } else {
            ""
        }
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_connecting))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        return notification
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Не умираем, если пользователь смахнул приложение из тулбаров. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        if (player == null || !player.isPlaying || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        exoPlayer?.removeListener(playerListener)
        mediaSession?.release()
        exoPlayer?.release()
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_CHANNEL_ID = "ytdl_playback"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        private const val HTTP_FORBIDDEN = 403
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000

        /** Сколько ждём подъёма сервиса: 40 попыток по 50 мс — около 2 секунд. */
        private const val PLAYER_WAIT_ATTEMPTS = 40
        private const val PLAYER_WAIT_STEP_MS = 50L

        private var instance: PlaybackService? = null

        // ── Наблюдаемое состояние: его читает ViewModel ──────────────────────

        /** Идёт ли буферизация: показываем «Подключаем…» вместо паузы. */
        private val _buffering = MutableStateFlow(false)
        val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

        /** Играет ли прямо сейчас (дублирует плеер, но без опроса из UI). */
        private val _isPlayingState = MutableStateFlow(false)
        val isPlayingState: StateFlow<Boolean> = _isPlayingState.asStateFlow()

        /** Текст последней ошибки; null — всё в порядке. */
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        /**
         * Короткий сигнал «поток упал, переподключись сам».
         *
         * Сюда не попадает 403: он означает «сервер отказал», а не «поток
         * сломался», и новая ссылка его не исправит. Раньше 403 уходил в
         * переподключение, и трек «начинал играть» с третьего нажатия —
         * на самом деле это был обход запроса без заголовка Range.
         */
        private val _faults = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val faults: SharedFlow<Unit> = _faults.asSharedFlow()

        /**
         * Трек доиграл до конца.
         *
         * Без этого сигнала песня просто останавливалась. Очередь ExoPlayer
         * состоит из одного MediaItem (следующий трек ViewModel ставит сам,
         * уже разобрав ссылку), поэтому переключаться дальше некому.
         */
        private val _trackFinished = MutableSharedFlow<String?>(extraBufferCapacity = 4)
        val trackFinished: SharedFlow<String?> = _trackFinished.asSharedFlow()

        /** mediaId текущего MediaItem: обновляется при автопереходе очереди. */
        private val _currentMediaId = MutableStateFlow<String?>(null)
        val currentMediaId: StateFlow<String?> = _currentMediaId.asStateFlow()

        /** Человеческое объяснение ошибки: VPN, 403, таймаут и т.п. */
        private fun describe(context: Context, error: PlaybackException): String {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) {
                    val code = cause.responseCode
                    return when {
                        code == 403 -> context.getString(R.string.net_403)
                        code == 404 -> context.getString(R.string.net_404)
                        code in 500..599 -> context.getString(R.string.net_5xx, code)
                        else -> context.getString(R.string.net_http, code)
                    }
                }
                if (cause is UnknownHostException) return context.getString(R.string.net_no_access)
                if (cause is SocketTimeoutException) return context.getString(R.string.net_timeout)
                if (cause is SSLException) return context.getString(R.string.net_tls)
                cause = cause.cause
            }
            return context.getString(R.string.net_player, error.errorCodeName)
        }

        fun create(context: Context) {
            if (instance != null) return
            try {
                context.startService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось запустить сервис заранее: ${e.message}")
            }
        }

        private fun ensureStarted(context: Context) {
            if (instance != null) return
            val intent = Intent(context, PlaybackService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "startForegroundService: ${e.message}")
                }
            } else {
                create(context)
            }
        }

        fun player(context: Context): ExoPlayer? = instance?.exoPlayer

        /**
         * Ждёт создания ExoPlayer: сервис поднимается асинхронно.
         *
         * Именно suspend с [delay], а не цикл с `Thread.sleep`: вызывается с
         * главного потока, и блокирующий сон не дал бы на нём же выполниться
         * `onCreate` сервиса — тот тоже живёт на main-потоке. Получалась
         * взаимоблокировка: первый тап всегда ждал 2 секунды и уходил с
         * «плеер не поднялся», а второй уже работал, потому что сервис к тому
         * моменту успевал подняться.
         */
        private suspend fun awaitPlayer(): ExoPlayer? {
            repeat(PLAYER_WAIT_ATTEMPTS) {            // до ~2 секунд
                instance?.exoPlayer?.let { return it }
                delay(PLAYER_WAIT_STEP_MS)
            }
            return instance?.exoPlayer
        }

        /**
         * Стрим трек по прямой ссылке. Ссылка [url] должна быть получена
         * недавно: у YouTube она живёт ограниченное время.
         */
        suspend fun playStream(context: Context, track: Track, url: String, queue: List<Pair<Track, String>>) {
            ensureStarted(context)
            val player = awaitPlayer()
            if (player == null) {
                val message = context.getString(R.string.error_player_unavailable)
                Log.w(TAG, "Плеер не поднялся, стрим не запущен: $message")
                _error.value = message
                return
            }
            _error.value = null
            _currentMediaId.value = track.id
            // Длительность и исполнителя кладём, сам id — нет: он бесполезен
            // для читателя отчёта и является лишней ссылкой на трек.
            com.volna.player.LogBuffer.d(
                "PlaybackService",
                "старт: «${track.title}» (${track.musicArtist}), в очереди ${queue.size}",
            )
            val items = queue.map { (item, itemUrl) -> buildItem(item, itemUrl) }
            val index = queue.indexOfFirst { (item, _) -> item.id == track.id }.coerceAtLeast(0)
            player.setMediaItems(items, index, 0L)
            player.prepare()
            player.playWhenReady = true
            // Заглушку ставим здесь, а не в onStartCommand: к этому моменту
            // очередь уже непустая, и надпись «Подключение…» исчезнет ровно
            // тогда, когда Media3 возьмёт управление уведомлением на себя.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Функция живёт в companion, а уведомление — метод сервиса.
                instance?.run {
                    startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
                }
            }
        }

        /** Добавляет трек в конец очереди, не прерывая текущий. */
        fun appendToQueue(context: Context, track: Track, url: String) {
            val player = player(context) ?: return
            if (player.mediaItemCount > 0 &&
                (0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId == track.id }
            ) {
                return // уже в очереди
            }
            player.addMediaItem(buildItem(track, url))
            player.prepare()
        }

        /** Перемотка на [positionMs]. */
        fun seekTo(context: Context, positionMs: Long) {
            val player = player(context) ?: return
            val limit = if (player.duration > 0) player.duration else Long.MAX_VALUE
            player.seekTo(positionMs.coerceIn(0L, limit))
        }

        /** Перемотка на [deltaMs] от текущей позиции: кнопки ±15 секунд. */
        fun skipBy(context: Context, deltaMs: Long) {
            val player = player(context) ?: return
            val limit = if (player.duration > 0) player.duration else Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, limit))
        }

        /** Ставит режим повтора: OFF / ALL / ONE. */
        fun setRepeatMode(context: Context, mode: Int) {
            player(context)?.repeatMode = mode
        }

        /**
         * Отказ сервера (403), а не обрыв потока.
         *
         * Причина лежит в цепочке исключений, а не на верхнем уровне, поэтому
         * ищем по всей. 403 означает, что сервер не даст данные и с новой
         * ссылкой, — переподключение тут только тратит время.
         */
        private fun isForbidden(error: Throwable): Boolean {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException &&
                    cause.responseCode == HTTP_FORBIDDEN
                ) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        private fun buildItem(track: Track, url: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.channel)
                        .build()
                )
                .build()
    }
}
