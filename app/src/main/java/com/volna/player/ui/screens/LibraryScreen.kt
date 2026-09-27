package com.volna.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volna.player.R
import com.volna.player.download.DownloadProgress
import com.volna.player.download.DownloadState
import com.volna.player.download.SavedTrack
import com.volna.player.library.Playlist
import com.volna.player.search.Track

/**
 * Медиатека: «нравится», свои плейлисты и скачанные треки.
 *
 * Отдельной вкладки «Треки» больше нет — в рельсе для неё не осталось места,
 * а по смыслу это часть медиатеки.
 *
 * Про то, откуда играть, решает вызывающий: [onPlaySaved] приходит по id трека,
 * и если он лежит на диске, ViewModel играет файл, а не идёт в сеть. Здесь
 * только проверка «скачан ли», чтобы показать отметку.
 */
@Composable
fun LibraryScreen(
    favorites: List<Track>,
    playlists: List<Playlist>,
    openPlaylist: Playlist?,
    playlistTracks: List<Track>,
    currentTrackId: String?,
    onPlay: (Track, List<Track>) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onClosePlaylist: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onRemoveFromPlaylist: (String) -> Unit,
    onMoveInPlaylist: (String, Int) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    modifier: Modifier = Modifier,
    progressMap: Map<String, DownloadProgress> = emptyMap(),
    saved: List<SavedTrack> = emptyList(),
    onPlaySaved: (String) -> Unit = { },
    onDeleteSaved: (SavedTrack) -> Unit = { },
) {
    var showCreate by remember { mutableStateOf(false) }
    val savedIds = remember(saved) { saved.mapTo(mutableSetOf()) { it.id } }

    fun playItem(track: Track, queue: List<Track>) {
        if (track.id in savedIds) onPlaySaved(track.id) else onPlay(track, queue)
    }

    fun downloadState(track: Track): DownloadProgress? =
        progressMap[track.id] ?: savedTrackStub(track.id in savedIds)

    if (openPlaylist != null) {
        PlaylistContent(
            playlist = openPlaylist,
            tracks = playlistTracks,
            currentTrackId = currentTrackId,
            onBack = onClosePlaylist,
            onPlay = ::playItem,
            onRemove = onRemoveFromPlaylist,
            onMove = onMoveInPlaylist,
            onToggleFavorite = onToggleFavorite,
            downloadState = ::downloadState,
            modifier = modifier,
        )
        return
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onConfirm = { name -> onCreatePlaylist(name); showCreate = false },
            onDismiss = { showCreate = false },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.favorites_title), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Filled.Add, stringResource(R.string.playlist_new))
                }
            }
        }
        if (favorites.isEmpty()) {
            item { EmptyNote(R.string.favorites_empty) }
        } else {
            item { PlayAllRow { onPlay(favorites.first(), favorites) } }
            itemsIndexed(favorites, key = { _, t -> "fav_" + t.id }) { index, track ->
                LibraryTrackRow(
                    track = track,
                    download = downloadState(track),
                    isCurrent = track.id == currentTrackId,
                    isFavorite = true,
                    onPlay = { playItem(track, favorites) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    showDivider = index < favorites.lastIndex,
                )
            }
        }

        if (saved.isNotEmpty()) {
            item { SectionTitle(R.string.downloads_title) }
            items(saved, key = { "dl_" + it.id }) { item ->
                SavedTrackRow(
                    item = item,
                    isCurrent = item.id == currentTrackId,
                    onPlay = { onPlaySaved(item.id) },
                    onDelete = { onDeleteSaved(item) },
                )
            }
        }

        item { SectionTitle(R.string.playlists_title) }
        if (playlists.isEmpty()) item { EmptyNote(R.string.playlists_empty) }
        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                onOpen = { onOpenPlaylist(playlist) },
                onDelete = { onDeletePlaylist(playlist) },
            )
        }
    }
}

/**
 * Отметка «уже скачано» для трека, лежащего на диске.
 *
 * Реестр загрузок живёт в памяти и после перезапуска пуст, поэтому медиатека
 * сама не знала, что трек скачан. Отметка восстанавливает это знание.
 */
private fun savedTrackStub(isSaved: Boolean): DownloadProgress? =
    if (isSaved) DownloadProgress(trackId = "", state = DownloadState.DONE) else null

@Composable
private fun SectionTitle(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun PlayAllRow(onPlay: () -> Unit) {
    TextButton(onClick = onPlay, modifier = Modifier.padding(horizontal = 12.dp)) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.playlist_play_all),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun EmptyNote(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PlaylistRow(playlist: Playlist, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.QueueMusic, null, Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, stringResource(R.string.playlist_delete))
        }
    }
}

/** Содержимое плейлиста: порядок задаётся кнопками со стрелками. */
@Composable
private fun PlaylistContent(
    playlist: Playlist,
    tracks: List<Track>,
    currentTrackId: String?,
    onBack: () -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    downloadState: (Track) -> DownloadProgress?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.playlist_back),
                )
            }
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            if (tracks.isNotEmpty()) {
                IconButton(onClick = { onPlay(tracks.first(), tracks) }) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.playlist_play_all))
                }
            }
        }

        if (tracks.isEmpty()) {
            EmptyNote(R.string.playlist_empty)
            return
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            itemsIndexed(tracks) { index, track ->
                LibraryTrackRow(
                    track = track,
                    download = downloadState(track),
                    isCurrent = track.id == currentTrackId,
                    isFavorite = false,
                    onPlay = { onPlay(track, tracks) },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onMoveUp = if (index > 0) ({ onMove(track.id, -1) }) else null,
                    onMoveDown = if (index < tracks.lastIndex) ({ onMove(track.id, 1) }) else null,
                    onRemoveFromList = { onRemove(track.id) },
                    showDivider = index < tracks.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text(stringResource(R.string.playlist_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel)) }
        },
    )
}

/**
 * Строка трека в медиатеке.
 *
 * Название и исполнитель сверху, кнопки под ними, между треками — тонкая
 * черта. Раньше всё было в одну горизонтальную строку, и кнопки по 40dp
 * вместе с обложкой съедали ширину: на само название оставалось около 58dp,
 * то есть в списке читалось только «А» и многоточие.
 */
@Composable
private fun LibraryTrackRow(
    track: Track,
    download: DownloadProgress?,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onRemoveFromList: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    val isDownloading = download?.state == DownloadState.DOWNLOADING ||
        download?.state == DownloadState.QUEUED

    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrackThumbnail(track, Modifier.size(52.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.musicArtist.ifBlank { track.channel },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryAction(
                onClick = onPlay,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.library_play),
                    modifier = Modifier.size(20.dp),
                )
            }
            LibraryAction(
                onClick = onToggleFavorite,
                container = if (isFavorite) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                content = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = stringResource(R.string.favorite_toggle),
                    modifier = Modifier.size(20.dp),
                )
            }
            if (onMoveUp != null) {
                LibraryAction(
                    onClick = onMoveUp,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.playlist_move_up),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (onMoveDown != null) {
                LibraryAction(
                    onClick = onMoveDown,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.playlist_move_down),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (onRemoveFromList != null) {
                LibraryAction(
                    onClick = onRemoveFromList,
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.playlist_remove),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (isDownloading) {
                CircularProgressIndicator(
                    progress = { (download?.percent ?: 0) / 100f },
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )
            } else if (download?.state == DownloadState.DONE) {
                Icon(
                    Icons.Filled.CloudDone,
                    contentDescription = stringResource(R.string.download_done),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Круглая кнопка под треком: компактнее, чем в горизонтальной строке поиска. */
@Composable
private fun LibraryAction(
    onClick: () -> Unit,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    icon: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(36.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) { icon() }
}
