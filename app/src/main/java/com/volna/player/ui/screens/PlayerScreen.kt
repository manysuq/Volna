@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.volna.player.StreamState
import com.volna.player.search.Track
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.ui.res.stringResource

/**
 * Плавающий плеер: обложка, название, кнопка play/pause и полоса
 * прогресса с перемоткой. Стримит напрямую, без скачивания.
 */
@Composable
fun MiniPlayer(
    current: Track?,
    isPlaying: Boolean,
    streamState: StreamState,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = current != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val track = current ?: return@AnimatedVisibility
        val isResolving = streamState is StreamState.Resolving

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .clickable(onClick = onExpand)
                    .padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TrackThumbnail(track, Modifier.size(52.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = when (streamState) {
                                is StreamState.Resolving -> stringResource(R.string.player_connecting)
                                is StreamState.Error -> streamState.message
                                else -> track.channel
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (streamState is StreamState.Error)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FilledIconButton(onClick = onTogglePlay, enabled = !isResolving) {
                        if (isResolving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                            )
                        }
                    }
                }

                if (durationMs > 0 && !isResolving) {
                    ProgressBar(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onSeek = onSeek,
                    )
                }
            }
        }
    }
}

/** Полоса прогресса с перемоткой по касанию. */
@Composable
private fun ProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var scrubPosition by remember { mutableFloatStateOf(-1f) }
    val duration = durationMs.coerceAtLeast(1L)
    val fraction = if (scrubPosition >= 0f) scrubPosition else positionMs.toFloat() / duration

    Column(modifier = Modifier.padding(top = 4.dp)) {
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = { scrubPosition = it },
            onValueChangeFinished = {
                if (scrubPosition >= 0f) {
                    onSeek((scrubPosition * duration).toLong())
                }
                scrubPosition = -1f
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 0:00 или 1:02:03. */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
