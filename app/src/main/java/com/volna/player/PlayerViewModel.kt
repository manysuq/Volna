package com.volna.player

import com.volna.player.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.volna.player.catalog.MusicCatalog
import com.volna.player.download.DownloadManager
import com.volna.player.download.DownloadProgress
import com.volna.player.player.PlaybackService
import com.volna.player.search.Track
import com.volna.player.search.RecommendationClient
import com.volna.player.search.TrackRepository
import com.volna.player.stream.StreamResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ViewModel приложения: поиск, стриминг и состояние плеера.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository()
    private val resolver = StreamResolver()

    val downloads = DownloadManager(app)
    val searchState = repository.state

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Что сейчас играет. */
    private val _nowPlaying = MutableStateFlow<Track?>(null)
    val nowPlaying: StateFlow<Track?> = _nowPlaying.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Состояние подготовки потока: пусто, грузим или ошибка. */
    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    /** Позиция и длительность для полоски прогресса. */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /** Плеер догружает поток: показываем индикатор, а не «играет». */
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    /** Текст последнего обрыва: раньше он просто терялся и трек «молча» не играл. */
    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    /** Player.REPEAT_MODE_OFF / ALL / ONE. */
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    init {
        val app = getApplication<Application>()
        viewModelScope.launch {
            PlaybackService.buffering.collect { _isBuffering.value = it }
        }
        viewModelScope.launch {
            PlaybackService.error.collect { message ->
                _playbackError.value = message
                if (message != null) _streamState.value = StreamState.Error(message)
            }
        }
        viewModelScope.launch {
            // Поток отвалился: молча берём свежую ссылку и переподключаемся
            PlaybackService.faults.collect { reconnect(app) }
        }
        viewModelScope.launch {
            // ExoPlayer сам перешёл на следующий MediaItem (автоплей очереди).
            // _nowPlaying до этого показывал предыдущий трек — отсюда и баг
            // «играет Столетняя война, а в мини-плеере Вселенная».
            PlaybackService.currentMediaId.collect { mediaId ->
                if (mediaId == null) return@collect
                val queued = streamQueue.firstOrNull { it.first.id == mediaId }?.first
                if (queued != null && queued.id != _nowPlaying.value?.id) {
                    _nowPlaying.value = queued
                    _playbackError.value = null
                    _streamState.value = StreamState.Ready
                }
            }
        }
    }

    /** Сколько раз подряд переподключаемся, прежде чем показать «Повторить». */
    private var retryAttempts = 0

    /** Поколение playStream: устаревший resolve не должен ронять очередь. */
    private var playGeneration = 0L

    /** Ссылка на текущий поток: нужна, чтобы перезапустить после обрыва. */
    private var currentUrl: String? = null

    fun onQueryChange(value: String) {
        _query.value = value
        viewModelScope.launch { repository.doSearch(value) }
    }

    /**
     * Главное действие: получить прямую ссылку на аудио и отдать её плееру.
     * Файл не скачивается целиком — ExoPlayer читает поток по мере надобности.
     */
    fun playStream(track: Track, queue: List<Track>) {
        playGeneration++
        val myGeneration = playGeneration
        _nowPlaying.value = track
        _streamState.value = StreamState.Resolving
        _playbackError.value = null
        retryAttempts = 0
        val rest = queue.filter { it.id != track.id }

        viewModelScope.launch {
            // Резолвим только текущий трек: предзагрузка всей очереди
            // занимала секунды и откладывала начало playback.
            val url = resolver.resolve(track)
            if (myGeneration != playGeneration) return@launch // тапнули другой трек
            if (url == null) {
                _streamState.value = StreamState.Error(getApplication<Application>().getString(R.string.error_no_url))
                return@launch
            }
            startPlayback(track, url)
            loadRecommendations(track)

            // Остальные треки подтягиваем уже во время игры — по одному,
            // чтобы следующий был готов к моменту переключения.
            for (next in rest.take(QUEUE_PRELOAD)) {
                if (myGeneration != playGeneration) return@launch
                val nextUrl = resolver.resolve(next)
                if (nextUrl != null && myGeneration == playGeneration) {
                    streamQueue.add(next to nextUrl)
                    PlaybackService.appendToQueue(getApplication(), next, nextUrl)
                }
            }
        }
    }

    /** Отдаёт готовую ссылку плееру. [resetQueue]=false — сохраняем очередь. */
    private fun startPlayback(track: Track, url: String, resetQueue: Boolean = true) {
        currentUrl = url
        if (resetQueue) {
            streamQueue = mutableListOf(track to url)
        } else {
            val index = streamQueue.indexOfFirst { it.first.id == track.id }
            if (index >= 0) streamQueue[index] = track to url else streamQueue.add(0, track to url)
        }
        PlaybackService.playStream(
            getApplication(), track, url,
            streamQueue.toList(),
        )
        _playbackError.value = null
        _streamState.value = StreamState.Ready
    }

    /**
     * Авто-переподключение после обрыва: ссылка YouTube живёт несколько часов
     * и привязана к IP, поэтому при 403/таймауте её нужно взять заново.
     */
    private fun reconnect(app: Application) {
        val track = _nowPlaying.value ?: return
        if (track.isLive) return                       // у трансляций ссылка не протухает
        if (retryAttempts >= MAX_RECONNECTS) {
            _streamState.value = StreamState.Error(getApplication<Application>().getString(R.string.error_stream_gave_up))
            return
        }
        retryAttempts++
        _streamState.value = StreamState.Resolving
        viewModelScope.launch {
            delay(700L * retryAttempts)
            val url = resolver.resolve(track)
            if (url == null) {
                _streamState.value = StreamState.Error(getApplication<Application>().getString(R.string.error_no_stream))
                return@launch
            }
            startPlayback(track, url, resetQueue = false)
        }
    }


    /** Очередь стрима: трек и его рабочая ссылка. */
    private var streamQueue: MutableList<Pair<Track, String>> = mutableListOf()

    /** Скачать трек на устройство (оставить как запасной вариант). */
    fun download(track: Track) {
        downloads.download(track) { result ->
            result.onSuccess { file -> android.util.Log.i("PlayerViewModel", "Скачано: ${file.name}") }
        }
    }

    fun cancelDownload(trackId: String) = downloads.cancel(trackId)

    fun togglePlayPause() {
        val player = PlaybackService.player(getApplication()) ?: run {
            // Сервис ещё не поднялся (или был убит): начинаем заново
            _nowPlaying.value?.let { playStream(it, emptyList()) }
            return
        }
        if (player.isPlaying) player.pause() else player.play()
        _isPlaying.value = player.isPlaying
    }

    fun seekTo(positionMs: Long) {
        PlaybackService.seekTo(getApplication(), positionMs)
    }

    /** Перемотка на ±[deltaMs]: кнопки «15 назад / 15 вперёд». */
    fun skipBy(deltaMs: Long) {
        PlaybackService.skipBy(getApplication(), deltaMs)
    }

    fun nextTrack() = step(1)

    fun previousTrack() = step(-1)

    /** Ходим по уже подготовленной очереди; иначе — в начало текущего. */
    private fun step(delta: Int) {
        val current = _nowPlaying.value ?: return
        val size = streamQueue.size
        if (size <= 1) {
            PlaybackService.seekTo(getApplication(), 0L)
            return
        }
        val index = streamQueue.indexOfFirst { it.first.id == current.id }.coerceAtLeast(0)
        val nextIndex = (index + delta + size) % size
        val (track, _) = streamQueue[nextIndex]
        // Ссылка из очереди могла протухнуть, поэтому берём свежую
        playStream(track, emptyList())
    }

    /** OFF → ALL → ONE → OFF. */
    fun cycleRepeat() {
        _repeatMode.value = PlaybackService.cycleRepeatMode(getApplication())
    }

    fun onPlaybackStateChanged(playing: Boolean) {
        _isPlaying.value = playing
    }

    fun onProgress(positionMs: Long, durationMs: Long) {
        _position.value = positionMs
        _duration.value = durationMs
    }

    // ---- Рекомендации и каталог ----

    private val _recommendations = MutableStateFlow<List<Track>>(emptyList())
    val recommendations: StateFlow<List<Track>> = _recommendations.asStateFlow()

    private val _recommendationsLoading = MutableStateFlow(false)
    val recommendationsLoading: StateFlow<Boolean> = _recommendationsLoading.asStateFlow()

    private val _artistQuery = MutableStateFlow("")
    val artistQuery: StateFlow<String> = _artistQuery.asStateFlow()

    private val _artists = MutableStateFlow<List<MusicCatalog.Artist>>(emptyList())
    val artists: StateFlow<List<MusicCatalog.Artist>> = _artists.asStateFlow()

    private val _artistsLoading = MutableStateFlow(false)
    val artistsLoading: StateFlow<Boolean> = _artistsLoading.asStateFlow()

    private val _albums = MutableStateFlow<List<MusicCatalog.Album>>(emptyList())
    val albums: StateFlow<List<MusicCatalog.Album>> = _albums.asStateFlow()

    private val _albumTracks = MutableStateFlow<List<MusicCatalog.CatalogTrack>>(emptyList())
    val albumTracks: StateFlow<List<MusicCatalog.CatalogTrack>> = _albumTracks.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    /** Как только включили трек — подгружаем похожее. */
    private var lastRecommendationsFor: String? = null

    private fun loadRecommendations(track: Track) {
        if (lastRecommendationsFor == track.id) return
        lastRecommendationsFor = track.id
        _recommendationsLoading.value = true
        viewModelScope.launch {
            _recommendations.value = RecommendationClient.related(track.id)
            _recommendationsLoading.value = false
        }
    }

    fun onArtistQueryChange(value: String) {
        _artistQuery.value = value
        if (value.trim().length < 2) {
            _artists.value = emptyList()
            return
        }
        _artistsLoading.value = true
        viewModelScope.launch {
            _artists.value = MusicCatalog.searchArtists(value)
            _artistsLoading.value = false
        }
    }

    fun openArtist(artist: MusicCatalog.Artist) {
        _catalogLoading.value = true
        viewModelScope.launch {
            _albums.value = MusicCatalog.albumsOf(artist)
            _catalogLoading.value = false
        }
    }

    fun openAlbum(album: MusicCatalog.Album) {
        _catalogLoading.value = true
        viewModelScope.launch {
            _albumTracks.value = MusicCatalog.tracksOf(album)
            _catalogLoading.value = false
        }
    }

    /**
     * Трек альбома играет через поиск YouTube: id iTunes не является
     * ссылкой на видео, поэтому находим версию и стримим её.
     *
     * Раньше брался просто первый результат — им оказывалось любое видео
     * (часто стрим или ремикс), которое не играло. Теперь выбираем
     * самое похожее по названию и исполнителю.
     */
    fun playCatalogTrack(catalogTrack: MusicCatalog.CatalogTrack) {
        _artistQuery.value = catalogTrack.searchQuery   // показываем, что ищем
        viewModelScope.launch {
            _streamState.value = StreamState.Resolving
            val found = runCatching {
                repository.doSearch(catalogTrack.searchQuery)
                bestMatch(catalogTrack, repository.tracks.value)
            }.getOrNull()
            if (found == null) {
                _streamState.value = StreamState.Error(
                    getApplication<Application>().getString(
                        R.string.error_not_found_on_youtube, catalogTrack.title,
                    ),
                )
                return@launch
            }
            playStream(found, repository.tracks.value)
        }
    }

    /** Из выдачи YouTube берём видео, которое больше всего похоже на трек альбома. */
    private fun bestMatch(
        catalogTrack: MusicCatalog.CatalogTrack,
        tracks: List<Track>,
    ): Track? {
        val wantedTitle = tokens(catalogTrack.title)
        val wantedArtist = tokens(catalogTrack.artistName)

        return tracks
            .filter { !it.isLive }                       // трансляции не берём
            .maxByOrNull { track ->
                val title = tokens(track.title)
                val channel = tokens(track.channel) + tokens(track.musicArtist)
                var score = 0
                // YouTube сам пометил видео как трек — это главный признак
                if (track.isOfficialMusic) score += 4
                score += wantedTitle.count { it in title } * 3
                score += wantedArtist.count { it in channel } * 2
                // Совпадение исполнителя в самом названии видео
                if (wantedArtist.any { it in title }) score += 2
                // Короткое совпадение целиком — самый сильный признак
                if (wantedTitle.any { it.length > 3 && title.contains(it) }) score += 2
                score
            }
    }

    /** Слова длиной больше двух букв: «the», «и», «feat» нам не нужны. */
    private fun tokens(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .split(Regex("[^a-zа-я0-9]+"))
            .filter { it.length > 2 }
        .toSet()

    /** Повторная попытка после обрыва: ссылка могла протухнуть. */
    fun retryStream() {
        val track = _nowPlaying.value ?: return
        retryAttempts = 0
        playStream(track, emptyList())
    }
}

/** Состояние подготовки потока. */
/** Сколько треков готовим заранее во время игры. */
private const val QUEUE_PRELOAD = 3

/** Сколько раз подряд переподключаемся после обрыва, прежде чем сдаться. */
private const val MAX_RECONNECTS = 3

sealed interface StreamState {
    data object Idle : StreamState
    data object Resolving : StreamState
    data object Ready : StreamState
    data class Error(val message: String) : StreamState
}

/** Трек, скачанный на устройство. */
data class DownloadedTrack(val track: Track, val file: java.io.File)
