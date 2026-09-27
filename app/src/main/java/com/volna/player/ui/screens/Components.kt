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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.volna.player.download.DownloadProgress
import com.volna.player.download.DownloadState
import com.volna.player.search.Track
import com.volna.player.ui.theme.PlaceholderBottom
import com.volna.player.ui.theme.PlaceholderTop
import androidx.compose.ui.res.stringResource

/** Обложка трека с запасным фоном. */
@Composable
fun TrackThumbnail(
    track: Track,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Brush.linearGradient(listOf(PlaceholderTop, PlaceholderBottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (track.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * Строка результата: обложка, название, автор и кнопки.
 * Основное действие — слушать сразу, без скачивания.
 */
@Composable
fun TrackRow(
    track: Track,
    download: DownloadProgress?,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDownloading = download?.state == DownloadState.DOWNLOADING ||
        download?.state == DownloadState.QUEUED

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box {
            TrackThumbnail(track, Modifier.size(56.dp))
            if (isDownloading) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { (download?.percent ?: 0) / 100f },
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.channel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                isDownloading -> Text(
                    text = stringResource(R.string.download_progress, download?.percent ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                download?.state == DownloadState.DONE -> Text(
                    text = stringResource(R.string.download_done),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (isDownloading) {
                SmallRoundButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.download_cancel))
                }
            } else {
                SmallRoundButton(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.download_action))
                }
            }

            Button(
                onClick = onPlay,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.download_listen))
            }
        }
    }
}

@Composable
private fun SmallRoundButton(
    onClick: () -> Unit,
    colors: androidx.compose.material3.ButtonColors,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(40.dp),
        contentPadding = PaddingValues(0.dp),
        colors = colors,
    ) { content() }
}
