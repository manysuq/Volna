package com.volna.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.volna.player.ui.screens.MiniPlayer
import com.volna.player.ui.screens.RecommendationsScreen
import com.volna.player.ui.screens.SearchScreen
import com.volna.player.ui.screens.SideNavigation
import com.volna.player.ui.theme.ThemeMode
import com.volna.player.ui.theme.VolnaTheme
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

/** Главная активность: поиск, стриминг, каталог и плеер. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
    var tab by rememberSaveable { mutableStateOf(AppTab.Search) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var catalogRoute by remember { mutableStateOf<CatalogRoute>(CatalogRoute.Artists) }
    var lastArtist by remember { mutableStateOf<MusicCatalog.Artist?>(null) }

    val query by viewModel.query.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val progress by viewModel.downloads.progress.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val streamState by viewModel.streamState.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val isBuffering by viewModel.isBuffering.collectAsStateWithLifecycle()
    val repeatMode by viewModel.repeatMode.collectAsStateWithLifecycle()

    val recommendations by viewModel.recommendations.collectAsStateWithLifecycle()
    val recLoading by viewModel.recommendationsLoading.collectAsStateWithLifecycle()
    val artistQuery by viewModel.artistQuery.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val artistsLoading by viewModel.artistsLoading.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val albumTracks by viewModel.albumTracks.collectAsStateWithLifecycle()
    val catalogLoading by viewModel.catalogLoading.collectAsStateWithLifecycle()

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

    // Полноэкранный плеер поверх всего
    if (fullPlayer && nowPlaying != null) {
        val current = nowPlaying!!
        FullPlayerScreen(
            track = current,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            streamState = streamState,
            positionMs = position,
            durationMs = duration,
            repeatMode = repeatMode,
            downloadState = progress[current.id]?.state,
            onTogglePlay = viewModel::togglePlayPause,
            onSeek = viewModel::seekTo,
            onSkipBy = viewModel::skipBy,
            onNext = viewModel::nextTrack,
            onPrevious = viewModel::previousTrack,
            onToggleRepeat = viewModel::cycleRepeat,
            onDownload = { viewModel.download(current) },
            onCancelDownload = { viewModel.cancelDownload(current.id) },
            onShare = { shareTrack(context, current) },
            onRetry = viewModel::retryStream,
            onCollapse = { fullPlayer = false },
        )
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Повёрнутая боковая навигация
        SideNavigation(
            current = tab,
            onSelect = { tab = it },
            isDarkTheme = isDarkTheme,
            onToggleTheme = onToggleTheme,
        )

        Scaffold(
            modifier = Modifier.weight(1f),
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
                        onQueryChange = viewModel::onQueryChange,
                        onDownload = viewModel::download,
                        onCancelDownload = viewModel::cancelDownload,
                        onPlay = { track, all -> viewModel.playStream(track, all) },
                    )

                    AppTab.Recommendations -> RecommendationsScreen(
                        current = nowPlaying,
                        recommendations = recommendations,
                        isLoading = recLoading,
                        currentTrackId = nowPlaying?.id,
                        onPlay = { track, all -> viewModel.playStream(track, all) },
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
}

/** Отправляет ссылку на трек в системное меню «Поделиться». */
private fun shareTrack(context: Context, track: Track) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_TEXT,
            "${track.title} — ${track.musicArtist.ifBlank { track.channel }}\n${track.videoUrl}",
        )
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.player_share_chooser)))
}
