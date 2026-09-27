@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.volna.player.download.DownloadProgress
import com.volna.player.search.Track
import com.volna.player.search.TrackState
import androidx.compose.ui.res.stringResource

/**
 * Экран поиска: поле запроса, состояние загрузки, список найденного.
 */
@Composable
fun SearchScreen(
    query: String,
    state: TrackState,
    progressMap: Map<String, DownloadProgress>,
    currentTrackId: String?,
    onQueryChange: (String) -> Unit,
    onDownload: (Track) -> Unit,
    onCancelDownload: (String) -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize()) {
        SearchField(
            query = query,
            isLoading = state.isLoading,
            onQueryChange = onQueryChange,
            onSearch = { keyboard?.hide() },
        )

        when {
            state.isLoading -> LoadingBlock()
            state.error != null -> ErrorBlock(state.error)
            state.tracks.isEmpty() -> EmptyBlock()
            else -> TrackList(
                tracks = state.tracks,
                progressMap = progressMap,
                currentTrackId = currentTrackId,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onPlay = onPlay,
            )
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        placeholder = { stringResource(R.string.search_hint) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun LoadingBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WavyLoadingIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.search_loading), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Индикатор загрузки в стиле Material 3 Expressive. */
@Composable
private fun WavyLoadingIndicator() {
    androidx.compose.material3.LoadingIndicator(modifier = Modifier.size(64.dp))
}

@Composable
private fun ErrorBlock(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.search_error_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyBlock() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.search_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.search_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    progressMap: Map<String, DownloadProgress>,
    currentTrackId: String?,
    onDownload: (Track) -> Unit,
    onCancelDownload: (String) -> Unit,
    onPlay: (Track, List<Track>) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                download = progressMap[track.id],
                isCurrent = track.id == currentTrackId,
                onDownload = { onDownload(track) },
                onCancel = { onCancelDownload(track.id) },
                // тап по строке или кнопка — слушаем сразу
                onPlay = { onPlay(track, tracks) },
            )
        }
    }
}
