@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
 * Профиль исполнителя: обложка, имя и альбомы.
 * Нажатие на альбом открывает его треклист.
 */
@Composable
fun ArtistScreen(
    artist: MusicCatalog.Artist,
    albums: List<MusicCatalog.Album>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onOpenAlbum: (MusicCatalog.Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        AsyncImage(
                            model = artist.artworkUrl,
                            contentDescription = artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Button(
                            onClick = onBack,
                            shape = CircleShape,
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.player_previous))
                        }
                    }
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (artist.genre.isNotEmpty()) {
                        Text(
                            text = artist.genre,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = stringResource(R.string.albums_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.LoadingIndicator(Modifier.size(48.dp)) }
            }
        } else if (albums.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.albums_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albums, key = { it.id }) { album ->
                        AlbumCard(album) { onOpenAlbum(album) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCard(album: MusicCatalog.Album, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        AsyncImage(
            model = album.artworkUrl,
            contentDescription = album.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album.year,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
