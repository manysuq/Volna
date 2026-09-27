@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.volna.player.search.Track
import androidx.compose.ui.res.stringResource

/**
 * Похожие треки: рекомендации YouTube к текущему треку.
 * Без ничего играющего показываем подсказку.
 */
@Composable
fun RecommendationsScreen(
    current: Track?,
    recommendations: List<Track>,
    isLoading: Boolean,
    currentTrackId: String?,
    onPlay: (Track, List<Track>) -> Unit,
    onDownload: (Track) -> Unit,
    onCancelDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: (String) -> Boolean = { false },
    onToggleFavorite: (Track) -> Unit = { },
) {
    when {
        current == null -> Hint(
            title = stringResource(R.string.similar_hint_title),
            subtitle = stringResource(R.string.similar_hint_subtitle),
            icon = { Icon(Icons.Filled.Explore, null, modifier = Modifier.size(56.dp)) },
            modifier = modifier,
        )

        isLoading -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.LoadingIndicator(modifier = Modifier.size(64.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(16.dp))
                Text(stringResource(R.string.similar_loading), style = MaterialTheme.typography.bodyMedium)
            }
        }

        recommendations.isEmpty() -> Hint(
            title = stringResource(R.string.similar_empty_title),
            subtitle = stringResource(R.string.similar_empty_subtitle),
            icon = { Icon(Icons.Filled.Explore, null, modifier = Modifier.size(56.dp)) },
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            items(recommendations, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    download = null,
                    isCurrent = track.id == currentTrackId,
                    onPlay = { onPlay(track, recommendations) },
                    onDownload = { onDownload(track) },
                    onCancel = { onCancelDownload(track.id) },
                    isFavorite = isFavorite(track.id),
                    onToggleFavorite = { onToggleFavorite(track) },
                )
            }
        }
    }
}

@Composable
fun Hint(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
