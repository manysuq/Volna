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
import androidx.compose.material.icons.filled.Delete
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
            items(favorites, key = { "fav_" + it.id }) { track ->
                TrackRow(
                    track = track,
                    download = downloadState(track),
                    isCurrent = track.id == currentTrackId,
                    isFavorite = true,
                    onPlay = { playItem(track, favorites) },
                    onPlayButton = { playItem(track, favorites) },
                    onDownload = { },
                    onCancel = { },
                    onToggleFavorite = { onToggleFavorite(track) },
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
                TrackRow(
                    track = track,
                    download = downloadState(track),
                    isCurrent = track.id == currentTrackId,
                    isFavorite = false,
                    onPlay = { onPlay(track, tracks) },
                    onPlayButton = { onPlay(track, tracks) },
                    onDownload = { },
                    onCancel = { },
                    onToggleFavorite = { onToggleFavorite(track) },
                    onMoveUp = if (index > 0) ({ onMove(track.id, -1) }) else null,
                    onMoveDown = if (index < tracks.lastIndex) ({ onMove(track.id, 1) }) else null,
                    onRemoveFromList = { onRemove(track.id) },
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
