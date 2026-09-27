package com.volna.player.ui.screens

import com.volna.player.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.volna.player.StreamState
import com.volna.player.download.DownloadState
import com.volna.player.search.Track
import com.volna.player.ui.theme.PlaceholderBottom
import com.volna.player.ui.theme.PlaceholderTop
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.ui.res.stringResource

/**
 * Полноэкранный плеер.
 *
 * Три вещи, которые здесь принципиальны:
 *  - верхняя панель уходит в safeDrawing, иначе вырез камеры накрывает стрелку;
 *  - плеер сворачивается свайпом вниз (не только кнопкой);
 *  - полоса прогресса своя, с перемоткой: тащишь — время едет, отпускаешь —seek.
 */
@Composable
fun FullPlayerScreen(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    streamState: StreamState,
    positionMs: Long,
    durationMs: Long,
    repeatMode: Int,
    isShuffled: Boolean,
    canShuffle: Boolean,
    downloadState: DownloadState?,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBy: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isResolving = streamState is StreamState.Resolving
    val isError = streamState is StreamState.Error
    val errorText = (streamState as? StreamState.Error)?.message

    // Свайп вниз закрывает плеер: тянем — экран едет за пальцем
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var collapsing by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(
        targetValue = if (collapsing) 1_400f else dragOffset,
        animationSpec = tween(if (collapsing) 220 else 260),
        label = "playerOffset",
    )
    LaunchedEffect(collapsing) {
        if (collapsing) {
            delay(180)
            onCollapse()
        }
    }

    val topInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PlaceholderTop, MaterialTheme.colorScheme.background)))
            .offset { IntOffset(0, offset.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragOffset > 110f) collapsing = true else dragOffset = 0f
                    },
                    onDragCancel = { dragOffset = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                    },
                )
            },
    ) {
        // Обложка на фоне — как в нормальных плеерах, только приглушённая
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.22f,
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                        0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                        1f to MaterialTheme.colorScheme.background,
                    )
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(topInsets)
                .padding(horizontal = 24.dp)
                .padding(bottom = bottomInset + 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(onCollapse = onCollapse, onShare = onShare)

            Cover(
                track = track,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )

            TrackHeader(track)

            Spacer(Modifier.height(14.dp))

            WavySeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                isLive = track.isLive,
                enabled = durationMs > 0 && !isResolving,
                onSeek = onSeek,
            )

            Spacer(Modifier.height(10.dp))

            PlaybackControls(
                isPlaying = isPlaying,
                isBusy = isResolving || isBuffering,
                onTogglePlay = onTogglePlay,
                onSkipBy = onSkipBy,
                onNext = onNext,
                onPrevious = onPrevious,
            )

            Spacer(Modifier.height(14.dp))

            SecondaryActions(
                repeatMode = repeatMode,
                isShuffled = isShuffled,
                canShuffle = canShuffle,
                downloadState = downloadState,
                onToggleRepeat = onToggleRepeat,
                onToggleShuffle = onToggleShuffle,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
            )

            Spacer(Modifier.height(10.dp))

            StatusLine(
                streamState = streamState,
                isBuffering = isBuffering,
                errorText = errorText,
                onRetry = onRetry,
            )
        }
    }
}

/** Верхняя панель: стрелка вверх + «сейчас играет» + кнопка «поделиться». */
@Composable
private fun TopBar(onCollapse: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.player_collapse))
        }
        Text(
            text = stringResource(R.string.player_now_playing),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.player_share))
        }
    }
}

/**
 * Обложка по центру — квадрат, как в нормальных плеерах.
 *
 * Высота задавалась фиксированной, и вертикальные обложки (а их на YouTube
 * много) выходили с чёрными полосами: картинка не могла заполнить прямоугольник.
 * Квадрат + Crop режет лишнее и всегда заполняет карточку.
 */
@Composable
private fun Cover(track: Track, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .aspectRatio(1f)                    // всегда квадрат
                .widthIn(max = 380.dp)
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(PlaceholderTop, PlaceholderBottom))),
        )
    }
}

/** Название, исполнитель и пометка «трек с YouTube Music». */
@Composable
private fun TrackHeader(track: Track) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (track.isOfficialMusic) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = track.musicArtist.ifBlank { track.channel },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Полоса прогресса с перемоткой: статичная волна, drag + tap по любой точке.
 *
 * Волна не анимируется намеренно — анимированный индикатор дёргался и мешал
 * попасть пальцем в нужный момент. Амплитуда маленькая, чтобы линия читалась
 * как «звук», а не как график.
 */
@Composable
private fun WavySeekBar(
    positionMs: Long,
    durationMs: Long,
    isLive: Boolean,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(1L)
    var scrub by remember(durationMs) { mutableFloatStateOf(-1f) }
    var trackWidth by remember { mutableFloatStateOf(1f) }

    val fraction = ((if (scrub >= 0f) scrub else positionMs.toFloat() / duration) * 1f)
        .coerceIn(0f, 1f)

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { 14.dp.toPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            // contentAlignment НЕ Center: иначе thumb смещается от центра
            // и уезжает вправо дальше, чем отыграл звук
            contentAlignment = Alignment.TopStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { trackWidth = it.width.toFloat().coerceAtLeast(1f) }
                .then(
                    if (!enabled) Modifier
                    else Modifier
                        .pointerInput(duration) {
                            detectTapGestures { offset ->
                                onSeek(((offset.x / trackWidth).coerceIn(0f, 1f) * duration).toLong())
                            }
                        }
                        .pointerInput(duration) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    scrub = (offset.x / trackWidth).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    if (scrub >= 0f) onSeek((scrub * duration).toLong())
                                    scrub = -1f
                                },
                                onDragCancel = { scrub = -1f },
                                onHorizontalDrag = { change, _ ->
                                    scrub = (change.position.x / trackWidth).coerceIn(0f, 1f)
                                },
                            )
                        },
                ),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .align(Alignment.Center),
            ) {
                drawWaveTrack(fraction, activeColor, inactiveColor)
            }
            if (durationMs > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset {
                            IntOffset(
                                (fraction * trackWidth - thumbPx / 2f).roundToInt().coerceAtLeast(0),
                                0,
                            )
                        }
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(activeColor),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(if (scrub >= 0f) (scrub * duration).toLong() else positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (isLive) "LIVE" else formatTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Рисует две волны: пройденную (цветная) и оставшуюся (серая). Волна статична. */
private fun DrawScope.drawWaveTrack(
    fraction: Float,
    active: Color,
    inactive: Color,
) {
    val centerY = size.height / 2f
    val amplitude = 2.5.dp.toPx()          // маленькая амплитуда, без « горбов »
    val wavelength = 44.dp.toPx()
    val thickness = 2.5.dp.toPx()
    val step = 3f

    fun buildPath(endX: Float): Path = Path().apply {
        var x = 0f
        moveTo(0f, centerY)
        while (x <= endX) {
            val y = centerY + sin(x / wavelength * 2f * PI.toFloat()) * amplitude
            lineTo(x, y)
            x += step
        }
    }

    drawPath(
        path = buildPath(size.width),
        color = inactive,
        style = Stroke(width = thickness, cap = StrokeCap.Round),
    )
    drawPath(
        path = buildPath(size.width * fraction),
        color = active,
        style = Stroke(width = thickness, cap = StrokeCap.Round),
    )
}

/** Основные кнопки: назад 15, play/pause, вперёд 15, плюс переключение трека. */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isBusy: Boolean,
    onTogglePlay: () -> Unit,
    onSkipBy: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_previous))
        }
        IconButton(onClick = { onSkipBy(-15_000L) }, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Replay10,
                contentDescription = stringResource(R.string.player_back_15),
                modifier = Modifier.size(30.dp),
            )
        }

        Box(contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = onTogglePlay,
                enabled = !isBusy,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }

        IconButton(onClick = { onSkipBy(15_000L) }, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Forward10,
                contentDescription = stringResource(R.string.player_forward_15),
                modifier = Modifier.size(30.dp),
            )
        }
        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next))
        }
    }
}

/** Нижний ряд: скачать, перемешать, повтор, ошибка/статус. */
@Composable
private fun SecondaryActions(
    repeatMode: Int,
    isShuffled: Boolean,
    canShuffle: Boolean,
    downloadState: DownloadState?,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isDownloading = downloadState == DownloadState.DOWNLOADING ||
            downloadState == DownloadState.QUEUED
        IconButton(
            onClick = if (isDownloading) onCancelDownload else onDownload,
            modifier = Modifier.size(48.dp),
        ) {
            when (downloadState) {
                DownloadState.DONE -> Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = stringResource(R.string.download_done),
                    tint = MaterialTheme.colorScheme.primary,
                )

                DownloadState.DOWNLOADING -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                )

                else -> Icon(Icons.Filled.Download, contentDescription = stringResource(R.string.download_track))
            }
        }

        IconButton(
            onClick = onToggleRepeat,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = stringResource(R.string.player_repeat),
                tint = if (repeatMode == Player.REPEAT_MODE_OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        // Перемешать можно только внутри альбома: вне его мешать нечего,
        // поэтому кнопка остаётся видимой, но неактивной — так понятнее,
        // что режим существует, чем если её просто нет.
        IconButton(
            onClick = onToggleShuffle,
            enabled = canShuffle,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(R.string.player_shuffle),
                tint = when {
                    !canShuffle -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    isShuffled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Статус под controls: буферизация, ошибка с кнопкой «Повторить» или подпись. */
@Composable
private fun StatusLine(
    streamState: StreamState,
    isBuffering: Boolean,
    errorText: String?,
    onRetry: () -> Unit,
) {
    when {
        errorText != null -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.player_retry),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        streamState is StreamState.Resolving -> Text(
            text = stringResource(R.string.player_connecting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        isBuffering -> Text(
            text = stringResource(R.string.player_connecting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        else -> Text(
            text = stringResource(R.string.player_streaming),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
