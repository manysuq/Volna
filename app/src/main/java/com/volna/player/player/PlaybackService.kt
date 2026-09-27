package com.volna.player.player

import com.volna.player.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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


        // Канал создаём сами, до первого startForeground().
        //
        // Его создавал Media3 — но только когда публиковал своё уведомление.
        // Теперь первым уведомление публикуем мы, и без канала оно уходит в
        // никуда: сервис формально станет foreground, а уведомления не будет —
        // и это ровно тот случай, который система наказывает.
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(com.volna.player.R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
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
        // Кэш потока. Отключался флагом сборки при разборе зависания на минуте;
        // гипотеза не подтвердилась, поэтому кэш остаётся.
        val cache = SimpleCache(
            File(cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(this)
        )
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
            // Без вейклока процессор засыпает вместе с экраном, и при выключенном
            // экране трек подключается, но не играет: буфер пустеет, а поток
            // воспроизведения стоит, пока пользователь не коснётся экрана. Для
            // стриминга нужен NETWORK — он держит и CPU, и Wi-Fi.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer?.addListener(playerListener)
        mediaSession = MediaSession.Builder(this, exoPlayer!!).build()
        stallHandler.postDelayed(stallWatch, STALL_TICK_MS)
    }

    /**
     * Держим процессор включённым, пока играет музыка.
     *
     * Раньше вейклока в проекте не было вообще. `setWakeMode(WAKE_MODE_NETWORK)`
     * — это WifiLock: он не даёт WiFi уснуть, но процессор не держит. В фоне
     * Android переводит процесс в ограниченную cgroup и через минуту замораживает
     * его целиком. Плеер при этом не умирает, а засыпает: позиция сохраняется,
     * и пережать застрявшее место нельзя, потому что процесс не обрабатывает
     * команды. На переднем плане заморозки нет, и там всё играет — ровно то,
     * что и наблюдалось.
     *
     * Таймит нужен как страховка: если по какой-то причине освободить не
     * удастся, вейклок сам отпустится, и батарея не сядет молча.
     */
    /** Держим ли мы сервис в foreground своими руками. */
    private var inForeground = false

    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        com.volna.player.LogBuffer.d("PlaybackService", "вейклок процессора взят")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld != true) return
        wakeLock?.release()
        com.volna.player.LogBuffer.d("PlaybackService", "вейклок процессора отпущен")
    }

    /**
     * Сторож зависания.
     *
     * Проверяем позицию раз в [STALL_TICK_MS]: пока играем, она обязана расти.
     * Не растёт — значит буфер кончился, а долив не идёт, и ждать больше
     * нечего. Сообщаем позицию, на которой остановились, чтобы ViewModel
     * перезапустил поток с этого места, а не с нуля.
     */
    private val stallHandler = Handler(Looper.getMainLooper())
    private var lastPosition = -1L
    private var stuckTicks = 0

    /**
     * Явно уходим в foreground, и не полагаемся на Media3.
     *
     * Android требует: запустил через startForegroundService() — позвони
     * startForeground() в течение 60 секунд, иначе сервис уничтожается. Именно
     * это и происходило: ровно через 60 секунд после старта музыка обрывалась,
     * причём плеер в этот момент был совершенно здоров (isPlaying, состояние
     * «готов», позиция 60 секунд) — умирал не плеер, а сервис.
     *
     * Раньше startForeground() не вызывался в проекте вообще: предполагалось,
     * что Media3 опубликует своё уведомление и поднимет сервис сам. На практике
     * этого не происходило, и никакие настройки батареи — отключение оптимизации,
     * автозапуск, удержание в фоне — этого не меняют: ограничение контракта
     * фреймворка, а не политика системы.
     *
     * Идентификатор и канал взяты те же, что у Media3 (1001 и ytdl_playback).
     * Своё уведомление не мешает: Media3 публикует своё с тем же id и просто
     * заменяет наше. А вот stopForeground(REMOVE) стирал бы чужое уведомление —
     * поэтому его здесь нет, и добавлять нельзя.
     */
    /** Сбрасывает накопленную полосу застоя при старте нового потока. */
    fun resetStallWatch() {
        stuckTicks = 0
        lastPosition = -1L
    }

    private fun promoteToForeground() {
        if (inForeground) return
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(com.volna.player.R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(com.volna.player.R.string.app_name))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        inForeground = true
        com.volna.player.LogBuffer.d("PlaybackService", "сервис переведён в foreground")
    }

    private val stallWatch = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (player != null) {
                val position = player.currentPosition
                val playing = player.playWhenReady
                stuckTicks = StallDetector.nextStreak(playing, position, lastPosition, stuckTicks)
                // Идёт и позиция растёт — сторож молчит, как и должен.
                if (playing && stuckTicks >= STALL_TICKS_BEFORE_REPORT) {
                    stuckTicks = 0
                    com.volna.player.LogBuffer.w(
                        "PlaybackService",
                        "ЗАВИСАНИЕ на ${position}мс: буфер=${player.bufferedPercentage}% " +
                            "позади буфера=${position - player.bufferedPosition}мс",
                    )
                    _stalled.tryEmit(position)
                }
                lastPosition = position
            }
            stallHandler.postDelayed(this, STALL_TICK_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING
            // Позиция и объём в буфере на каждой смене состояния: по ним видно,
            // стоит ли плеер потому, что кончились данные (буфер пуст, играет
            // позади позиции), или потому, что просто не играет при полном буфере.
            com.volna.player.LogBuffer.d(
                "PlaybackService",
                "состояние=${stateName(playbackState)} позиция=${exoPlayer?.currentPosition} " +
                    "в буфере=${exoPlayer?.bufferedPercentage}% " +
                    "позади буфера=${exoPlayer?.currentPosition?.minus(exoPlayer?.bufferedPosition ?: 0L)}мс",
            )
            // Трек доиграл: сообщаем ViewModel, он возьмёт следующий сам.
            // Вместе с состоянием отдаём id доигравшего трека: событие может
            // прийти уже после того, как нажали другой трек, и без проверки
            // оно уводило бы воспроизведение не туда.
            if (playbackState == Player.STATE_ENDED) {
                val p = exoPlayer
                com.volna.player.LogBuffer.d(
                    "PlaybackService",
                    "трек доиграл: playWhenReady=${p?.playWhenReady} " +
                        "подавление=${p?.let { suppressionName(it.playbackSuppressionReason) }} " +
                        "в очереди=${p?.mediaItemCount}",
                )
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
            // Логируем каждое переключение «играет / не играет»: без этого
            // остановка на середине трека не оставляет в отчёте вообще ничего —
            // ни ошибки, ни STATE_ENDED, ни перехода, просто тишина.
            com.volna.player.LogBuffer.w(
                "PlaybackService",
                "isPlaying=$isPlaying позиция=${exoPlayer?.currentPosition}",
            )
        }

        /**
         * Ключевое событие для бага «фон ставит на паузу, само не отпускает».
         *
         * Смысл [reason]: Media3 сообщает, ПОЧЕМУ переключился playWhenReady.
         * Отсюда видно, кто его снял — потеря аудиофокуса, шум в наушниках,
         * конец трека или подавление. Прежде это не отслеживалось вовсе, и
         * остановка посреди трека не попадала в отчёт: ни ошибки, ни конца
         * трека не происходит, плеер просто молчит.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            com.volna.player.LogBuffer.w(
                "PlaybackService",
                "playWhenReady=$playWhenReady причина=${pwrName(reason)} " +
                    "подавление=${suppressionName(exoPlayer?.playbackSuppressionReason ?: 0)}",
            )
        }

        /** Появление и снятие подавления — вторая половина того же диагноза. */
        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            com.volna.player.LogBuffer.w(
                "PlaybackService",
                "подавление=${suppressionName(reason)} " +
                    "playWhenReady=${exoPlayer?.playWhenReady} " +
                    "isPlaying=${exoPlayer?.isPlaying}",
            )
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
            // 403 отправляем на переподключение: ссылка зашита IP клиента и
            // стареет, поэтому новая ссылка от 403 спасает. Раньше я здесь 403
            // отсекал, посчитав его «сервер не даст данных» — и автопереподключение
            // переставало работать совсем: в логе оставалось «переподключаемся»,
            // а по факту ничего не происходило. Проверено на живых ссылках:
            // свежая отдаёт 200/206, отработавшая — 403.
            _faults.tryEmit(Unit)
        }
    }

    /**
     * Уведомление в шторке и управление в динамическом острове.
     *
     * Свое уведомление здесь было лишним звеном и главным источником багов:
     * Media3 публикует медиауведомление сам, и идентификатор у него тот же —
     * 1001 (DEFAULT_NOTIFICATION_ID). Наша заглушка «Подключение…» занимала
     * этот номер, а stopForeground(REMOVE) стирал уже чужое уведомление.
     *
     * Обязательный startForeground() после startForegroundService() решается
     * без заглушки: сервис в foreground-режиме поднимается только из
     * playStream(), который сразу ставит очередь и включает воспроизведение,
     * и Media3 успевает перехватить уведомление задолго до истечения
     * пяти секунд. Служебный вызов create() идёт через startService() и
     * foreground-уведомления не требует.
     */
    /**
     * START_STICKY: если система убьёт сервис, он поднимется обратно.
     *
     * Возвращалось значение по умолчанию, то есть «не поднимать». Любая причина,
     * по которой система решила убрать foreground-сервис, навсегда оставляла
     * приложение без музыки — до перезапуска вручную.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * Не умираем, если пользователь смахнул приложение из тулбаров.
     *
     * Раньше решение принималось по `isPlaying`, и это было главной причиной,
     * по которой фоновое воспроизведение обрывалось на минуте. `isPlaying`
     * означает «звук идёт ПРЯМО СЕЙЧАС» и на миг становится false при любой
     * буферизации, подавлении или паузе между треками. Достаточно одного такого
     * мгновения, чтобы `stopSelf()` навсегда убил сервис вместе с музыкой —
     * и восстановить его было уже нечем.
     *
     * Правильный вопрос не «звук сейчас идёт», а «пользователь хочет, чтобы
     * шло». Это `playWhenReady`: на паузе пользователь сам её снял, и вот тогда
     * сервис действительно можно гасить.
     *
     * Отдельная забота: на некоторых прошивках (OriginOS) задача убирается уже
     * при блокировке экрана, поэтому вызывается и не по вине пользователя.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        val wantsPlayback = player != null &&
            ServiceSurvival.shouldKeepAlive(player.playWhenReady, player.mediaItemCount)
        com.volna.player.LogBuffer.w(
            "PlaybackService",
            "задача убрана: playWhenReady=${player?.playWhenReady} " +
                "isPlaying=${player?.isPlaying} в очереди=${player?.mediaItemCount} " +
                "позиция=${player?.currentPosition} → ${if (wantsPlayback) "продолжаем" else "гасим"}",
        )
        if (!wantsPlayback) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        val p = exoPlayer
        com.volna.player.LogBuffer.w(
            "PlaybackService",
            "СЕРВИС УБИТ: playWhenReady=${p?.playWhenReady} isPlaying=${p?.isPlaying} " +
                "состояние=${p?.let { stateName(it.playbackState) }} " +
                "в очереди=${p?.mediaItemCount} позиция=${p?.currentPosition}",
        )
        stallHandler.removeCallbacks(stallWatch)
        releaseWakeLock()
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
        /** Как часто сторож смотрит на позицию. */
        private const val STALL_TICK_MS = 5_000L

        /**
         * Столько одинаковых замеров подряд считаем зависанием.
         *
         * Два замера по 5 секунд — десять секунд тишины. Меньше нельзя: сеть
         * подвисает на пару секунд и сама отпускает, и лишний перезапуск потока
         * только рвёт музыку. Больше нельзя: пользователь успевает решить, что
         * музыка зависла, и закрыть приложение.
         */
        private const val STALL_TICKS_BEFORE_REPORT = 2

        private const val WAKE_LOCK_TAG = "Volna:playback"

        /** Страховка от утечки вейклока: три часа — дольше любой песни. */
        private const val WAKE_LOCK_TIMEOUT_MS = 3 * 60 * 60 * 1000L
        private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
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
         * Сюда попадает и 403: ссылка зашита IP клиента и стареет,
         * поэтому свежая ссылка от него спасает. Проверено на живых
         * потоках: новая отдаёт 200/206, отработавшая — 403.
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

        /**
         * Плеер играет, но позиция не идёт — сеть в фоне недоступна.
         *
         * Отдельный сигнал, а не onPlayerError: при обрыве загрузки ошибки нет,
         * плеер просто стоит с готовым буфером и молча стоит на месте. Раз
         * снаружи это неотличимо от «пользователь нажал паузу», поэтому
         * без отдельного сигнала зависание не ловилось никем.
         */
        private val _stalled = MutableSharedFlow<Long>(extraBufferCapacity = 2)
        val stalled: SharedFlow<Long> = _stalled.asSharedFlow()

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
            // Раньше здесь стоял `if (instance != null) return`: сервис, поднятый
            // через create() обычным startService(), считался «уже живым», и
            // foreground-версия не запускалась вовсе — а именно она нужна, чтобы
            // позже корректно вызвать startForeground().
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
        suspend fun playStream(
            context: Context,
            track: Track,
            url: String,
            queue: List<Pair<Track, String>>,
            startPositionMs: Long = 0L,
        ) {
            ensureStarted(context)
            val player = awaitPlayer()
            if (player == null) {
                val message = context.getString(R.string.error_player_unavailable)
                Log.w(TAG, "Плеер не поднялся, стрим не запущен: $message")
                _error.value = message
                return
            }
            // Уходим в foreground сразу, как только сервис поднялся: на это
            // есть 5 секунд, а awaitPlayer() сам по себе ждёт до двух. Откладывать
            // до конца подготовки нельзя — иначе окно закроется на ровной минуте,
            // как и было.
            // Сторож зависания приводим в исходное состояние.
            //
            // Он жил между треками: накопленная полоса застоя от прошлого
            // потока переезжала на новый, и тот объявлялся зависшим в ту же
            // секунду, что и старт, хотя позиция просто ещё не успела
            // сдвинуться. Побочно это лишний перезапуск и ещё один запрос
            // к YouTube — то есть ровно то, чего мы добиваемся.
            instance?.resetStallWatch()
            instance?.promoteToForeground()
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
            // Стартуем с места, где плеер встал: после переподключения из-за
            // зависания возвращаться к началу нельзя, иначе песня начинается
            // заново на том месте, где её только что слушали.
            player.setMediaItems(items, index, startPositionMs.coerceAtLeast(0L))
            player.prepare()
            // Именно play(), а не playWhenReady = true.
            //
            // Плеер сам управляет аудиофокусом (handleAudioFocus = true), и если
            // в фоне фокус на секунду перехватили — уведомление, другой
            // аудиоплеер, doze — Media3 не снимает звук, а ставит ПОДАВЛЕНИЕ:
            // playWhenReady остаётся true, но звука нет. Присваивание
            // playWhenReady = true в этом состоянии — no-op, потому что флаг
            // уже true: снимает подавление только смена false -> true.
            //
            // play() снимает его явно, поэтому следующий трек после автоперехода
            // в фоне стартует сам, а не ждёт нажатия Play. Именно это и было
            // видно как «вторая песня загрузилась, но не играет».
            //
            // Логируем состояние до и после: без него отличить «подавление»
            // от «плеер вообще не тот» по отчёту о баге невозможно.
            com.volna.player.LogBuffer.d(
                "PlaybackService",
                "старт: playWhenReady=${player.playWhenReady} " +
                    "подавление=${suppressionName(player.playbackSuppressionReason)}",
            )
            instance?.acquireWakeLock()
            player.play()
            com.volna.player.LogBuffer.d(
                "PlaybackService",
                "после play(): playWhenReady=${player.playWhenReady} " +
                    "подавление=${suppressionName(player.playbackSuppressionReason)} " +
                    "isPlaying=${player.isPlaying}",
            )
        }

        /**
         * Человеческое имя причины подавления.
         *
         * Само значение — это битовая маска (`Player` объединяет несколько причин
         * в одно int), поэтому в отчёт о баге клали голое число, в котором
         * ничего не прочитать.
         */
        private fun suppressionName(reason: Int): String {
            if (reason == Player.PLAYBACK_SUPPRESSION_REASON_NONE) return "нет"
            return if (reason and Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS != 0) {
                "аудиофокус"
            } else {
                // Неизвестную комбинацию битов не угадываем: печатаем как есть.
                "прочее($reason)"
            }
        }

        /**
         * Почему Media3 переключил playWhenReady.
         *
         * Отвечает на вопрос «кто остановил фон»: потеря аудиофокуса, шум в
         * наушниках, конец трека или слишком долгое подавление. Имена констант
         * сверены с media3-common 1.8.0 — в ней их всего шесть, и половина
         * названий, которые гуглятся по памяти, не существует.
         */
        private fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "простой"
            Player.STATE_BUFFERING -> "буферизация"
            Player.STATE_READY -> "готов"
            Player.STATE_ENDED -> "конец"
            else -> "прочее($state)"
        }

        private fun pwrName(reason: Int): String = when (reason) {
            Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "нажал пользователь"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "потеря аудиофокуса"
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "шум в наушниках"
            Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "внешний контроллер"
            Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "конец трека"
            // Плеер молчал слишком долго и снял playWhenReady сам. Похоже на
            // баг «встал на минуте и больше не отпускает».
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "подавление затянулось"
            else -> "прочее($reason)"
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
