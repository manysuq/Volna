package com.volna.player.download

/** Этап загрузки трека. */
enum class DownloadState { QUEUED, DOWNLOADING, DONE, FAILED }

/** Состояние загрузки одного трека. */
data class DownloadProgress(
    val trackId: String,
    val state: DownloadState = DownloadState.QUEUED,
    val fileName: String? = null,
    val downloaded: Long = 0L,
    val total: Long = 0L,
    val speed: Double = 0.0,
    val path: String? = null,
    val error: String? = null,
) {
    val percent: Int
        get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
}
