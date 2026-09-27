package com.volna.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.volna.player.R
import com.volna.player.download.SavedTrack

/**
 * Скачанные треки.
 *
 * Экран появился вместе с переносом файлов в общую папку Music/Volna: до
 * этого скачивание былоwrite-only — файл уходил во внутреннее хранилище,
 * приложение его не читало, а пользователь не находил нигде.
 */
@Composable
fun DownloadsScreen(
    saved: List<SavedTrack>,
    currentTrackId: String?,
    onPlay: (SavedTrack) -> Unit,
    onDelete: (SavedTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (saved.isEmpty()) {
        Hint(
            title = stringResource(R.string.downloads_empty_title),
            subtitle = stringResource(R.string.downloads_empty_subtitle),
            icon = { Icon(Icons.Filled.LibraryMusic, null, modifier = Modifier.size(56.dp)) },
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        items(saved, key = { it.id }) { item ->
            SavedTrackRow(
                item = item,
                isCurrent = item.id == currentTrackId,
                onPlay = { onPlay(item) },
                onDelete = { onDelete(item) },
            )
        }
    }
}

@Composable
fun SavedTrackRow(
    item: SavedTrack,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = item.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artist.ifBlank { item.album },
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.downloads_delete),
            )
        }
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.FilledTonalButton(onClick = onPlay) {
                Text(
                    text = stringResource(R.string.downloads_play),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
