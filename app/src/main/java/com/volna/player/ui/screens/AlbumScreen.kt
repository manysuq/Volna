@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.volna.player.catalog.MusicCatalog
import androidx.compose.ui.res.stringResource

/**
 * Треклист альбома. Сами файлы лежат на YouTube, поэтому по кнопке
 * запускается поиск трека и стрим найденной версии.
 */
@Composable
fun AlbumScreen(
    album: MusicCatalog.Album,
    tracks: List<MusicCatalog.CatalogTrack>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlay: (MusicCatalog.CatalogTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AsyncImage(
                    model = album.artworkUrl,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(140.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = onBack,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.player_previous), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        stringResource(R.string.albums_title)
                    }
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${album.artistName} · ${album.year}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.LoadingIndicator(Modifier.size(48.dp)) }
            }
        } else {
            itemsIndexed(tracks) { index, track ->
                AlbumTrackRow(index + 1, track) { onPlay(track) }
            }
        }
    }
}

/** androidx LazyListScope.itemsIndexed для списка треков. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexed(
    tracks: List<MusicCatalog.CatalogTrack>,
    content: @Composable (Int, MusicCatalog.CatalogTrack) -> Unit,
) {
    items(count = tracks.size, key = { tracks[it].number }) { index ->
        content(index, tracks[index])
    }
}

@Composable
private fun AlbumTrackRow(
    number: Int,
    track: MusicCatalog.CatalogTrack,
    onPlay: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatTime(track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = onPlay,
            shape = CircleShape,
            contentPadding = PaddingValues(10.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.download_listen))
        }
    }
}
