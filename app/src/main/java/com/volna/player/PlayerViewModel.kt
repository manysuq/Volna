package com.volna.player

import com.volna.player.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.volna.player.catalog.MusicCatalog
import com.volna.player.download.DownloadManager
import com.volna.player.download.SavedTrack
import com.volna.player.download.DownloadProgress
import com.volna.player.player.PlaybackService
import com.volna.player.search.Track
import com.volna.player.search.RecommendationClient
import com.volna.player.search.SearchMode
import com.volna.player.search.TrackRepository
import com.volna.player.stream.StreamResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel приложения: поиск, стриминг и состояние плеера.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackRepository()
    private val resolver = StreamResolver()

    val downloads = DownloadManager(app)

    /** Скачанные треки: читается из реестра, переживает перезапуск. */
    private val _savedTracks = MutableStateFlow<List<SavedTrack>>(emptyList())
    val savedTracks: StateFlow<List<SavedTrack>> = _savedTracks.asStateFlow()
    val searchState = repository.state

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Что ищем: треки или видео. Сохраняется между запусками. */
    private val _searchMode = MutableStateFlow(SearchMode.Tracks)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

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

    /** Перемешан ли альбом. */
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    init {
        val app = getApplication<Application>()
        // Режим поиска восстанавливаем сразу, иначе переключатель мигнёт
        // с «треков» на «видео» уже после первого кадра.
        _searchMode.value = SearchMode.load(app)
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
            // Трек доиграл: переключаемся так же, как по кнопке «вперёд».
            // Событие приходит с id доигравшего трека: если пользователь за это
            // время нажал другой трек, переключаться нельзя — иначе нажатие
            // «Спящая красавица» уводило в ранее открытый альбом.
            PlaybackService.trackFinished.collect { finishedId ->
                onTrackFinished(finishedId)
            }
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

    /**
     * Номер последнего поиска трека из альбома.
     *
     * Ответ приходит из сети через сотни миллисекунд, и за это время
     * пользователь вполне может нажать другой трек. Без счётчика поздний
     * ответ перебивает свежий — и включается не то, что только что нажали.
     */
    private var pendingAlbumLookups = 0L

    /** Поколение playStream: устаревший resolve не должен ронять очередь. */
    private var playGeneration = 0L

    /** Ссылка на текущий поток: нужна, чтобы перезапустить после обрыва. */
    private var currentUrl: String? = null

    fun onQueryChange(value: String) {
        _query.value = value
        viewModelScope.launch { repository.doSearch(value, _searchMode.value) }
    }

    /**
     * Переключение «треки / видео». Результаты перезапрашиваются сразу:
     * оставлять старую выдачу путающе — она из другого источника.
     */
    fun onSearchModeChange(mode: SearchMode) {
        if (_searchMode.value == mode) return
        _searchMode.value = mode
        SearchMode.save(getApplication(), mode)
        val current = _query.value
        if (current.isNotBlank()) {
            viewModelScope.launch { repository.doSearch(current, mode) }
        }
    }

    /**
     * Главное действие: получить прямую ссылку на аудио и отдать её плееру.
     * Файл не скачивается целиком — ExoPlayer читает поток по мере надобности.
     */
    fun playStream(track: Track, queue: List<Track>) = play(track, queue, album = null)

    /**
     * Запуск трека. [album] не пустой, если играем из альбома: тогда «вперёд»
     * идёт по его треклисту, а не по результатам поиска.
     */
    private fun play(track: Track, queue: List<Track>, album: AlbumContext?) {
        albumContext = album
        playGeneration++
        val myGeneration = playGeneration
        _nowPlaying.value = track
        _streamState.value = StreamState.Resolving
        _playbackError.value = null
        retryAttempts = 0
        val rest = queue.filter { it.id != track.id }
        // В альбоме очередь ExoPlayer собирается нами, а не подгружается заранее,
        // поэтому повтор «всё» ведём сами: иначе плевер зациклит один трек.
        if (album != null && _repeatMode.value == Player.REPEAT_MODE_ALL) {
            PlaybackService.setRepeatMode(getApplication(), Player.REPEAT_MODE_OFF)
        }

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
    private suspend fun startPlayback(track: Track, url: String, resetQueue: Boolean = true) {
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
        // Счётчик попыток живёт только до первого успеха. Раньше он не
        // обнулялся здесь, и три ошибки за сессии (на любых треках) выдавали
        // «сдались», после чего автопереподключение переставало работать до
        // перезапуска приложения: play() его сбрасывает, а reconnect — нет.
        retryAttempts = 0
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
        // Каждое переподключение попадает в лог: «играет через раз» — это
        // именно цепочка попыток, и по ней видно, что именно ломается.
        com.volna.player.LogBuffer.w(
            "PlayerViewModel",
            "переподключение $retryAttempts/$MAX_RECONNECTS: «${track.title}»",
        )
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

    /**
     * Треклист альбома и позиция в нём.
     *
     * Нужен, чтобы «вперёд» шёл по альбому, а не по результатам поиска. Пока
     * играет трек из поиска, контекст равен null и работает старая логика очереди.
     */
    private var albumContext: AlbumContext? = null

    /** Скачать трек в общую папку Music/Volna. */
    fun download(track: Track) {
        downloads.download(track) { result ->
            // Успешный файл уходит в реестр внутри DownloadManager, а наружу
            // отдаётся только ошибка: временный файл к этому моменту удалён.
            result.onFailure { refreshSaved() }
            refreshSaved()
        }
    }

    fun cancelDownload(trackId: String) = downloads.cancel(trackId)

    /** Перечитывает реестр: он меняется и при загрузке, и при удалении. */
    fun refreshSaved() {
        _savedTracks.value = downloads.store.list()
    }

    /**
     * Играет сохранённый трек с диска, без обращения к сети.
     *
     * Отдельный путь, а не playStream: тот идёт в YouTube за ссылкой, а здесь
     * уже есть готовый локальный uri. Плейлист при этом сбрасывается — соседних
     * локальных треков в очереди не строим, «вперёд» уйдёт в радио.
     */
    fun playSaved(item: SavedTrack) {
        val track = Track(
            id = item.id,
            title = item.title,
            channel = item.artist,
            durationSeconds = item.durationSeconds,
            thumbnailUrl = item.thumbnailUrl,
            videoUrl = item.uri,
            isOfficialMusic = true,
            musicArtist = item.artist,
            album = item.album,
        )
        albumContext = null
        playGeneration++
        retryAttempts = 0
        _nowPlaying.value = track
        _streamState.value = StreamState.Ready
        _playbackError.value = null
        viewModelScope.launch { startPlayback(track, item.uri) }
    }

    /** Удаляет файл с диска и запись из реестра. */
    fun deleteSaved(trackId: String) {
        downloads.store.remove(trackId)
        refreshSaved()
    }

    fun togglePlayPause() {
        val player = PlaybackService.player(getApplication()) ?: run {
            // Сервис ещё не поднялся (или был убит): начинаем заново.
            // Контекст альбома сохраняем, иначе «вперёд» потерял бы треклист.
            _nowPlaying.value?.let { play(it, emptyList(), albumContext) }
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

    /**
     * Трек закончился сам.
     *
     * [finishedId] — id доигравшего трека. Если он не совпадает с тем, что
     * сейчас играет, событие устарело: пользователь успел нажать другой трек,
     * и переключаться нельзя.
     *
     * Повтор одного трека ExoPlayer делает сам, поэтому сюда мы попадаем только
     * когда надо идти дальше. Иначе песня просто замолкала на последнем треке.
     */
    private fun onTrackFinished(finishedId: String?) {
        val current = _nowPlaying.value ?: return
        if (!AlbumOrder.isFresh(finishedId, current.id)) return   // устаревшее
        if (!AlbumOrder.shouldAdvance(_repeatMode.value)) return
        step(1)
    }

    fun nextTrack() = step(1)

    fun previousTrack() = step(-1)

    /**
     * Куда идти дальше.
     *
     * Раньше «вперёд» всегда шёл по `streamQueue` — очереди результатов поиска.
     * Именно поэтому включённый с альбома трек уводил на что-то похожее, а не на
     * следующий трек альбома: очередь собиралась из выдачи поиска *по одной
     * песне*, и её вторым элементом был чужой результат.
     *
     * Теперь приоритет такой:
     *  1. альбом — идём по его треклисту;
     *  2. репит «всё» — возвращаемся на первый трек альбома;
     *  3. конец альбома или трек из поиска — радио YouTube Music;
     *  4. иначе — обычная очередь поиска.
     */
    private fun step(delta: Int) {
        val current = _nowPlaying.value ?: return
        val album = albumContext
        if (album != null) {
            when (val target = AlbumOrder.target(album.tracks.size, album.index, delta, _repeatMode.value)) {
                // Репит альбома: с конца возвращаемся к началу, с начала — в конец.
                is AlbumOrder.Target.Track -> playAlbumTrack(target.index)
                // Конец альбома: продолжаем похожим, как это делает любой плеер.
                // Но не для сингла из одного трека: там молчаливый переход на
                // чужой трек выглядит как «включилось не то, что я нажал».
                AlbumOrder.Target.Radio ->
                    if (album.tracks.size > 1 || delta < 0) playFromRadio()
                    else com.volna.player.LogBuffer.d(
                        "PlayerViewModel",
                        "сингл из одного трека: автопереход отменён",
                    )
                // Назад на первом треке — как обычно, в начало текущего.
                AlbumOrder.Target.Restart -> PlaybackService.seekTo(getApplication(), 0L)
            }
            return
        }
        val size = streamQueue.size
        if (size <= 1) {
            // Одиночный трек из поиска: следующий — из радио.
            if (delta > 0 && size == 1) playFromRadio() else PlaybackService.seekTo(getApplication(), 0L)
            return
        }
        val index = streamQueue.indexOfFirst { it.first.id == current.id }.coerceAtLeast(0)
        val nextIndex = (index + delta + size) % size
        val (track, _) = streamQueue[nextIndex]
        // Ссылка из очереди могла протухнуть, поэтому берём свежую
        playStream(track, emptyList())
    }

    /** Играет трек альбома по индексу: ищет его на YouTube и запускает. */
    private fun playAlbumTrack(index: Int) {
        val album = albumContext ?: return
        val catalogTrack = album.tracks.getOrNull(index) ?: return
        // Пока идёт поиск, пользователь может нажать другой трек: без этой
        // метки поздний ответ перебьёт свежий запрос и включит не то.
        val myGeneration = ++pendingAlbumLookups
        viewModelScope.launch {
            _streamState.value = StreamState.Resolving
            val found = findCatalogTrack(catalogTrack)
            if (myGeneration != pendingAlbumLookups) return@launch   // нажали другое
            if (found == null) {
                // Трек не нашёлся: молча переходим дальше, чтобы не встать колом.
                if (index != album.index) step(1)
                return@launch
            }
            // Очередь альбома строим сами: результаты поиска нам не нужны.
            play(found, emptyList(), AlbumContext(album.tracks, index))
        }
    }

    /**
     * Продолжение по похожим.
     *
     * Раньше здесь брался [_recommendations] без проверки, чей это список.
     * Если человек до этого слушал другой трек, в списке лежали похожие на
     * *него* — и нажатие «вперёд» включало чужой трек (так и вышло «Пчёлы
     * слов» вместо «Спящей красавицы»). Теперь список используется только
     * если он загружен именно для текущего трека, иначе берём радио заново.
     */
    private fun playFromRadio() {
        val track = _nowPlaying.value ?: return
        val mine = if (lastRecommendationsFor == track.id) {
            _recommendations.value.filter { it.id != track.id }
        } else {
            emptyList()
        }
        if (mine.isNotEmpty()) {
            play(mine.first(), mine, album = null)
            return
        }
        viewModelScope.launch {
            _streamState.value = StreamState.Resolving
            val similar = RecommendationClient.similar(track.id)
            _recommendations.value = similar
            val next = similar.firstOrNull { it.id != track.id } ?: return@launch
            play(next, similar, album = null)
        }
    }

    /**
     * Перемешать: включает режим и сразу уводит на случайный трек альбома.
     *
     * Перемешивание само по себе ничего не меняет, пока не выбран трек —
     * поэтому сразу прыгаем, иначе нажатие выглядит как «ничего не сделал».
     */
    fun toggleShuffle() {
        if (albumContext == null) return          // мешать нечего
        _shuffle.value = !_shuffle.value
        if (!_shuffle.value) return               // выключили: остаёмся на текущем
        val album = albumContext ?: return
        if (album.tracks.size < 2) return
        var next = album.index
        while (next == album.index) next = Random.nextInt(album.tracks.size)
        playAlbumTrack(next)
    }

    /** Режим повтора: OFF → ALL → ONE → OFF. */
    fun cycleRepeat() {
        val next = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        // В альбоме очередь ведём сами, поэтому «всё» отдаём ViewModel, а не ExoPlayer:
        // иначе тот зациклит единственный MediaItem и до второго трека не дойдёт.
        val handOver = albumContext != null && next == Player.REPEAT_MODE_ALL
        PlaybackService.setRepeatMode(
            getApplication(),
            if (handOver) Player.REPEAT_MODE_OFF else next,
        )
        _repeatMode.value = next
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

    /**
     * Можно ли перемешивать: треков в альбоме должно быть больше одного.
     * Отдельно от контекста воспроизведения — кнопка должна отвечать на
     * «открыт ли альбом», а не на «что сейчас играет».
     */
    val shuffleAvailable: StateFlow<Boolean> = _albumTracks
        .map { it.size > 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    /** Как только включили трек — подгружаем похожее. */
    private var lastRecommendationsFor: String? = null

    private fun loadRecommendations(track: Track) {
        if (lastRecommendationsFor == track.id) return
        lastRecommendationsFor = track.id
        _recommendationsLoading.value = true
        viewModelScope.launch {
            _recommendations.value = RecommendationClient.similar(track.id)
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
        // Ищем позицию в открытом альбоме: по ней «вперёд» пойдёт по треклисту.
        val tracks = _albumTracks.value
        val index = tracks.indexOfFirst {
            it.number == catalogTrack.number && it.title == catalogTrack.title
        }
        val context = if (index >= 0) AlbumContext(tracks, index) else null
        // Прямое нажатие на трек важнее любого поиска, который уже летит:
        // помечаем его тем же счётчиком, иначе он перебьёт свежий запрос.
        val myGeneration = ++pendingAlbumLookups
        viewModelScope.launch {
            _streamState.value = StreamState.Resolving
            val found = findCatalogTrack(catalogTrack)
            if (myGeneration != pendingAlbumLookups) return@launch   // нажали другое
            if (found == null) {
                _streamState.value = StreamState.Error(
                    getApplication<Application>().getString(
                        R.string.error_not_found_on_youtube, catalogTrack.title,
                    ),
                )
                return@launch
            }
            // Очередь альбома ведём сами, поэтому результаты поиска в неё не идут.
            play(found, emptyList(), context)
        }
    }

    /**
     * Подбор трека альбома в выдаче YouTube: возвращает лучшее совпадение
     * или null, если совпадений нет.
     *
     * Порог обязателен: без него при любом запросе возвращался «лучший из
     * плохих» — то есть чужой трек вместо нужного, и человек не понимал,
     * что произошло. Сейчас это единственная причина подмены трека.
     */
    /**
     * Одноразовое уведомление для пользователя (например, «в каталоге трека
     * нет, ищем в видео»). Отдельный канал, а не ошибка плеера: ошибка
     * показывает кнопку «Повторить», а тут всё уже играет.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun clearNotice() {
        _notice.value = null
    }

    /**
     * Ищет трек каталога, а если в YouTube Music его нет — ищет в видео.
     *
     * Строгий [CatalogMatch.pick] отказывается брать чужой трек: так вместо
     * «Атлантиды» от Noize MC играла одноимённая песня группы Atlantida
     * Project. Но и тишины быть не должно, поэтому вторым шагом идём в
     * обычный YouTube и берём там лучшее совпадение по названию, предупредив
     * пользователя, что это неподтверждённый вариант.
     *
     * Возвращает найденный трек либо null, если не нашлось ничего.
     */
    private suspend fun findCatalogTrack(
        catalogTrack: MusicCatalog.CatalogTrack,
    ): Track? {
        val strict = runCatching {
            repository.doSearch(catalogTrack.searchQuery, SearchMode.Tracks)
            CatalogMatch.pick(catalogTrack.title, catalogTrack.artistName, repository.tracks.value)
        }.getOrNull()
        if (strict != null) return strict

        // В каталоге трека нет: ищем в видео, где найдётся клип или запись.
        com.volna.player.LogBuffer.w(
            "PlayerViewModel",
            "в YouTube Music нет «${catalogTrack.title}», ищу в видео",
        )
        val loose = runCatching {
            repository.doSearch(catalogTrack.searchQuery, SearchMode.Videos)
            CatalogMatch.pickLoose(catalogTrack.title, repository.tracks.value)
        }.getOrNull()
        if (loose != null) {
            // Предупреждаем: это может быть не тот исполнитель.
            _notice.value = getApplication<Application>().getString(
                R.string.notice_not_in_music, catalogTrack.title,
            )
        }
        return loose
    }

    /** Повторная попытка после обрыва: ссылка могла протухнуть. */
    fun retryStream() {
        val track = _nowPlaying.value ?: return
        retryAttempts = 0
        // Контекст альбома сохраняем: обрыв не должен сбрасывать треклист.
        play(track, emptyList(), albumContext)
    }
}

/** Состояние подготовки потока. */
/** Треклист альбома, по которому идёт воспроизведение, и текущая позиция. */
private data class AlbumContext(
    val tracks: List<MusicCatalog.CatalogTrack>,
    val index: Int,
)

/**
 * Куда «вперёд»/«назад» ведёт внутри альбома.
 *
 * Вынесено отдельно от ViewModel, чтобы можно было проверить тестами: правило
 * «вперёд идёт по треклисту, а похожее — только после конца альбома» — это ровно
 * тот баг, из-за которого кнопка «вперёд» уводила на чужой трек.
 */
internal object AlbumOrder {

    sealed interface Target {
        /** Трек альбома под указанным индексом. */
        data class Track(val index: Int) : Target

        /** Продолжить радио YouTube Music. */
        data object Radio : Target

        /** Начать текущий трек сначала. */
        data object Restart : Target
    }

    /**
     * [size] — сколько треков в альбоме, [index] — текущий (0-based),
     * [delta] — +1 «вперёд» или -1 «назад», [repeatMode] — Player.REPEAT_MODE_*.
     */
    fun target(size: Int, index: Int, delta: Int, repeatMode: Int): Target {
        if (size <= 0) return Target.Restart
        val next = index + delta
        if (next in 0 until size) return Target.Track(next)
        if (repeatMode == Player.REPEAT_MODE_ALL) {
            // Репит альбома: с конца — на начало, с начала — в конец.
            return Target.Track(if (delta > 0) 0 else size - 1)
        }
        return if (delta > 0) Target.Radio else Target.Restart
    }

    /**
     * Нужно ли переключаться, когда трек доиграл сам.
     *
     * При [Player.REPEAT_MODE_ONE] переключаться нельзя: ExoPlayer повторяет
     * трек сам, и наш шаг вперёд его прервал бы. Это отдельный вопрос от
     * [target] — раньше проверяли только target, поэтому повтор одного трека
     * ломал автопереход незаметно.
     */
    fun shouldAdvance(repeatMode: Int): Boolean = repeatMode != Player.REPEAT_MODE_ONE

    /**
     * Свежее ли событие «трек доиграл».
     *
     * Событие приходит из плеера с небольшим запозданием. Если за это время
     * пользователь нажал другой трек, переключаться нельзя: так нажатие на
     * трек альбома уводило в ранее открытый альбом — слышно было будто
     * включилось не то.
     *
     * [finishedId] == null — плеер не смог определить трек, тогда доверяем
     * событию: отказ от перехода сломал бы обычное доигрывание.
     */
    fun isFresh(finishedId: String?, currentId: String?): Boolean =
        finishedId == null || currentId == null || finishedId == currentId
}

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
