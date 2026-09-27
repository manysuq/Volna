package com.volna.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.volna.player.catalog.MusicCatalog
import com.volna.player.player.PlaybackService
import com.volna.player.search.Track
import com.volna.player.ui.screens.AlbumScreen
import com.volna.player.ui.screens.AlbumsScreen
import com.volna.player.ui.screens.AppTab
import com.volna.player.ui.screens.ArtistScreen
import com.volna.player.ui.screens.FullPlayerScreen
import com.volna.player.ui.screens.LibraryScreen
import com.volna.player.ui.screens.MiniPlayer
import com.volna.player.ui.screens.RecommendationsScreen
import com.volna.player.ui.screens.SearchScreen
import com.volna.player.ui.screens.SettingsScreen
import com.volna.player.ui.screens.TabOrder
import com.volna.player.ui.screens.SideNavigation
import com.volna.player.ui.theme.AppLanguage
import com.volna.player.ui.theme.ThemeMode
import com.volna.player.ui.theme.VolnaTheme
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

/** Главная активность: поиск, стриминг, каталог и плеер. */
class MainActivity : AppCompatActivity() {

    // registerForActivityResult обязан вызваться до onStart, поэтому лаунчер
    // создаётся сразу при инициализации, а не внутри askNotificationPermission.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* не критично */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        askNotificationPermission()
        // Поднимаем сервис сразу, а не на первом нажатии play.
        // Иначе первый тап ждал бы создание ExoPlayer, а ссылка на стрим,
        // полученная за это время, успевала устареть — и слышно было три
        // переподключения подряд, как будто игрок нажимал play несколько раз.
        PlaybackService.create(this)
        // Язык применяем до первого кадра: иначе интерфейс на секунду
        // показывается на предыдущем, и переключение выглядит как «не сработало».
        AppLanguage.init(this)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.load(this@MainActivity)) }
            val isDark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            VolnaTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PlayerContent(
                        isDarkTheme = isDark,
                        onToggleTheme = {
                            val next = if (isDark) ThemeMode.Light else ThemeMode.Dark
                            ThemeMode.save(this@MainActivity, next)
                            themeMode = next
                        },
                    )
                }
            }
        }
    }
    /**
     * Просит разрешение на уведомления — без него на Android 13+ уведомление
     * не показывается вообще, и в шторке не появляется плеер.
     *
     * Само разрешение объявлено в манифесте, но начиная с Android 13 его
     * нужно выдать и в рантайме, поэтому здесь именно запрос.
     */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Куда смотрим внутри раздела «Альбомы». */
private sealed interface CatalogRoute {
    data object Artists : CatalogRoute
    data class Artist(val artist: MusicCatalog.Artist) : CatalogRoute
    data class Album(val album: MusicCatalog.Album) : CatalogRoute
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerContent(
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    viewModel: PlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    var tabOrder by remember { mutableStateOf(TabOrder.load(context)) }
    var tab by rememberSaveable { mutableStateOf(AppTab.Search) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var catalogRoute by remember { mutableStateOf<CatalogRoute>(CatalogRoute.Artists) }
    var lastArtist by remember { mutableStateOf<MusicCatalog.Artist?>(null) }
    // Настройки — отдельный экран поверх разделов: так возврат понятен
    // пользователю, а не «внезапно пропал нижний плеер».
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var language by remember { mutableStateOf(AppLanguage.load(context)) }

    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val searchMode by viewModel.searchMode.collectAsStateWithLifecycle()
    val progress by viewModel.downloads.progress.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()
    val shuffle by viewModel.shuffle.collectAsStateWithLifecycle()
    val shuffleAvailable by viewModel.shuffleAvailable.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    // Уведомление о запасном поиске: трека нет в YouTube Music, и мы ищем
    // в видео. Показывается один раз и не мешает слушать.
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearNotice()
        }
    }

    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val recLoading by viewModel.recommendationsLoading.collectAsStateWithLifecycle()
    val artistQuery by viewModel.artistQuery.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val artistsLoading by viewModel.artistsLoading.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val albumTracks by viewModel.albumTracks.collectAsStateWithLifecycle()
    val catalogLoading by viewModel.catalogLoading.collectAsStateWithLifecycle()
    val savedTracks by viewModel.savedTracks.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val openPlaylist by viewModel.openPlaylist.collectAsStateWithLifecycle()
    val playlistTracks by viewModel.playlistTracks.collectAsStateWithLifecycle()

    // Реестр читается при показе вкладки: иначе он оставался бы пустым после
    // скачивания до перезапуска.
    LaunchedEffect(tab) {
        if (tab == AppTab.Library) viewModel.refreshSaved()
        if (tab == AppTab.Library) viewModel.refreshLibrary()
    }

    // Системный «назад» должен закрывать верхний слой, а не выбрасывать из
    // приложения. Порядок в списке — это приоритет: верхний слой первый,
    // то есть полный плеер, потом настройки, потом вкладка альбома или
    // исполнителя. Если открыт ровно один слой, BackHandler не перехватывает
    // жест вовсе — тогда нажатие действительно закрывает приложение.
    val openLayers = listOfNotNull(
        fullPlayer.takeIf { it }?.let { "player" },
        showSettings.takeIf { it }?.let { "settings" },
        openPlaylist?.let { "playlist" },
        (catalogRoute as? CatalogRoute.Album)?.let { "album" },
        (catalogRoute as? CatalogRoute.Artist)?.let { "artist" },
    )
    BackHandler(enabled = openLayers.isNotEmpty()) {
        when (openLayers.first()) {
            "player" -> fullPlayer = false
            "settings" -> showSettings = false
            "playlist" -> viewModel.closePlaylist()
            else -> catalogRoute = CatalogRoute.Artists
        }
    }

    // Позиция и состояние плеера
    LaunchedEffect(Unit) {
        while (true) {
            PlaybackService.player(context)?.let {
                viewModel.onProgress(it.currentPosition, it.duration)
                viewModel.onPlaybackStateChanged(it.isPlaying)
            }
            delay(500)
        }
    }

    // Основной экран рисуется всегда, даже когда поверх него открыт полный
    // плеер. Раньше он был за `return`, и под анимацией сворачивания была
    // видна только заливка фона — выглядело как чёрный прямоугольник вместо
    // того экрана, куда плеер как раз и возвращается.
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Повёрнутая боковая навигация
            SideNavigation(
                current = tab,
                onSelect = { tab = it },
                order = tabOrder,
                onReorder = { newOrder ->
                    tabOrder = newOrder
                    TabOrder.save(context, newOrder)
                },
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                onOpenSettings = { showSettings = true },
            )

            Scaffold(
                modifier = Modifier.weight(1f),
                snackbarHost = { SnackbarHost(snackbarHost) },
                bottomBar = {
                    MiniPlayer(
                        current = nowPlaying,
                        isPlaying = isPlaying,
                        streamState = streamState,
                        positionMs = position,
                        durationMs = duration,
                        onTogglePlay = viewModel::togglePlayPause,
                        onSeek = viewModel::seekTo,
                        onExpand = { fullPlayer = true },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        AppTab.Search -> SearchScreen(
                            query = query,
                            state = searchState,
                            progressMap = progress,
                            currentTrackId = nowPlaying?.id,
                            mode = searchMode,
                            onModeChange = viewModel::onSearchModeChange,
                            onQueryChange = viewModel::onQueryChange,
                            onDownload = viewModel::download,
                            onCancelDownload = viewModel::cancelDownload,
                            onPlay = { track, all -> viewModel.playStream(track, all) },
                            isFavorite = viewModel::isFavorite,
                            onToggleFavorite = viewModel::toggleFavorite,
                        )

                        AppTab.Recommendations -> RecommendationsScreen(
                            current = nowPlaying,
                            recommendations = recommendations,
                            isLoading = recLoading,
                            currentTrackId = nowPlaying?.id,
                            onPlay = { track, all -> viewModel.playStream(track, all) },
                            onDownload = viewModel::download,
                            onCancelDownload = viewModel::cancelDownload,
                            isFavorite = viewModel::isFavorite,
                            onToggleFavorite = viewModel::toggleFavorite,
                        )

                        AppTab.Library -> LibraryScreen(
                            favorites = favorites,
                            playlists = playlists,
                            openPlaylist = openPlaylist,
                            playlistTracks = playlistTracks,
                            currentTrackId = nowPlaying?.id,
                            onPlay = { track, all -> viewModel.playStream(track, all) },
                            onCreatePlaylist = viewModel::createPlaylist,
                            onOpenPlaylist = viewModel::openPlaylist,
                            onClosePlaylist = viewModel::closePlaylist,
                            onDeletePlaylist = viewModel::deletePlaylist,
                            onRemoveFromPlaylist = { viewModel.removeFromPlaylist(openPlaylist?.id ?: "", it) },
                            onMoveInPlaylist = { id, delta -> viewModel.moveInPlaylist(openPlaylist?.id ?: "", id, delta) },
                            onToggleFavorite = viewModel::toggleFavorite,
                            progressMap = progress,
                            saved = savedTracks,
                            onPlaySaved = { id ->
                                savedTracks.firstOrNull { it.id == id }?.let(viewModel::playSaved)
                            },
                            onDeleteSaved = { viewModel.deleteSaved(it.id) },
                        )

                        AppTab.Albums -> when (val route = catalogRoute) {
                            is CatalogRoute.Artists -> AlbumsScreen(
                                query = artistQuery,
                                artists = artists,
                                isLoading = artistsLoading,
                                onQueryChange = viewModel::onArtistQueryChange,
                                onOpenArtist = {
                                    viewModel.openArtist(it)
                                    lastArtist = it
                                    catalogRoute = CatalogRoute.Artist(it)
                                },
                            )

                            is CatalogRoute.Artist -> ArtistScreen(
                                artist = route.artist,
                                albums = albums,
                                isLoading = catalogLoading,
                                onBack = { catalogRoute = CatalogRoute.Artists },
                                onOpenAlbum = {
                                    viewModel.openAlbum(it)
                                    catalogRoute = CatalogRoute.Album(it)
                                },
                            )

                            is CatalogRoute.Album -> AlbumScreen(
                                album = route.album,
                                tracks = albumTracks,
                                isLoading = catalogLoading,
                                onBack = { catalogRoute = lastArtist?.let { CatalogRoute.Artist(it) } ?: CatalogRoute.Artists },
                                onPlay = viewModel::playCatalogTrack,
                            )
                        }
                    }
                }
            }
        }

        // Оверлеи рисуются после основного экрана, поэтому лежат поверх него.
        // Настройки — под полным плеером: открывать их оттуда незачем.
        if (showSettings) {
            SettingsScreen(
                themeMode = ThemeMode.load(context),
                onThemeChange = {
                    ThemeMode.save(context, it)
                    onToggleTheme()
                },
                language = language,
                onLanguageChange = {
                    language = it
                    // save сам применяет язык и пересоздаёт активности.
                    AppLanguage.save(context, it)
                },
                onBack = { showSettings = false },
            )
        }

        val current = nowPlaying
        if (fullPlayer && current != null) {
            FullPlayerScreen(
                track = current,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                streamState = streamState,
                positionMs = position,
                durationMs = duration,
                repeatMode = repeatMode,
                isShuffled = shuffle,
                canShuffle = shuffleAvailable,
                downloadState = progress[current.id]?.state,
                onTogglePlay = viewModel::togglePlayPause,
                onSeek = viewModel::seekTo,
                onSkipBy = viewModel::skipBy,
                onNext = viewModel::nextTrack,
                onPrevious = viewModel::previousTrack,
                onToggleRepeat = viewModel::cycleRepeat,
                onToggleShuffle = viewModel::toggleShuffle,
                onDownload = { viewModel.download(current) },
                onCancelDownload = { viewModel.cancelDownload(current.id) },
                onShare = { shareTrack(context, current) },
                onRetry = viewModel::retryStream,
                isFavorite = nowPlaying?.let { viewModel.isFavorite(it.id) } == true,
                onToggleFavorite = { nowPlaying?.let(viewModel::toggleFavorite) },
                onCollapse = { fullPlayer = false },
            )
        }
    }
}

/**
 * Ссылка, которую имеет смысл отдавать наружу.
 *
 * У локально сохранённого трека videoUrl — это content:// на файл в Music:
 * такую ссылку не откроет ни одно другое приложение, и получатель получит
 * бесполезный текст. Поэтому для них берём исходную ссылку на YouTube, а у
 * обычных треков — как есть.
 */
private fun shareUrl(track: Track): String {
    val url = track.videoUrl
    val isLocalFile = url.startsWith("content://") || url.startsWith("file://")
    return if (isLocalFile) "https://www.youtube.com/watch?v=${track.id}" else url
}

/** Отправляет ссылку на трек в системное меню «Поделиться». */
private fun shareTrack(context: Context, track: Track) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            "${track.title} — ${track.musicArtist.ifBlank { track.channel }}\n${shareUrl(track)}",
        )
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.player_share_chooser)))
}
