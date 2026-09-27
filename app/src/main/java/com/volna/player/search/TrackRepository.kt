package com.volna.player.search

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Стратегия поиска: сперва YouTube Music, затем обычный YouTube.
 *
 * YouTube Music отдаёт официальные треки с исполнителем и альбомом уже в
 * подписи, поэтому выдача заметно чище. Но он покрывает не всё: редких треков
 * в его каталоге нет, и запрос может не дать ни одной «Композиции». Тогда
 * берём обычный поиск YouTube — он найдёт больше, раз и сам отсеет мусор.
 */
internal class FallbackSearch(
    private val primary: YouTubeMusicSearch = YouTubeMusicSearch(),
    private val fallback: YouTubeSearch = YouTubeSearch(),
) {
    suspend fun search(query: String, limit: Int): List<Track> {
        val music = primary.search(query, limit)
        if (music.size >= MIN_RESULTS) return music

        // Мало результатов: YTM их не знает. Пробуем обычный YouTube и, если он
        // дал больше, берём его; найденное в YTM не теряем — доклеиваем в конец.
        val extra = fallback.search(query, limit)
        if (extra.size <= music.size) return music
        val merged = LinkedHashMap<String, Track>()
        (extra + music).forEach { merged.putIfAbsent(it.id, it) }
        Log.d(TAG, "YTM дал ${music.size}, YouTube — ${extra.size}, объединено ${merged.size}")
        return merged.values.toList()
    }

    private companion object {
        const val TAG = "TrackRepository"

        /** Столько треков от YouTube Music считаем достаточным, чтобы не дёргать YouTube. */
        const val MIN_RESULTS = 3
    }
}

/**
 * Состояние экрана поиска: идёт ли загрузка, какая ошибка и что нашли.
 */
/**
 * Состояние экрана поиска: идёт ли загрузка, какая ошибка и что нашли.
 */
data class TrackState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val tracks: List<Track> = emptyList(),
)

/**
 * Хранилище результатов поиска: выполняет запрос через [FallbackSearch] и
 * отдаёт наружу [StateFlow] для сбора в Compose.
 *
 * Экземпляр нужно создавать один (например, в ViewModel) и переиспользовать.
 * Дебаунга нет: [doSearch] сам вызывается на каждое изменение запроса.
 *
 * Последний вызов [doSearch] побеждает: если новый запрос стартовал раньше,
 * чем вернулся предыдущий, результат устаревшего запроса отбрасывается.
 */
internal class TrackRepository(
    private val searcher: FallbackSearch = FallbackSearch(),
    private val limit: Int = DEFAULT_LIMIT,
) {

    private val _state = MutableStateFlow(TrackState())

    /** Полное состояние: загрузка + ошибка + список треков. */
    val state: StateFlow<TrackState> = _state.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())

    /** Только список треков — для тех мест, кому не нужна ошибка и флаг загрузки. */
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    /** Счётчик запущенных поисков: защита от «гонки» между быстрыми запросами. */
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Выполняет поиск по [query] и обновляет [state].
     *
     * Не бросает исключений: ошибка попадает в [TrackState.error].
     * Отмена корутины (CancellationException) пробрасывается и при этом
     * снимает флаг загрузки.
     */
    suspend fun doSearch(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            clear()
            return
        }

        val myGeneration = generation.incrementAndGet()
        _state.update { it.copy(isLoading = true, error = null) }

        try {
            val result = searcher.search(cleanQuery, limit)
            if (generation.get() != myGeneration) {
                Log.d(TAG, "Результат устаревшего запроса «$cleanQuery» отброшен")
                return
            }
            update(isLoading = false, error = null, tracks = result)
        } catch (e: CancellationException) {
            if (generation.get() == myGeneration) {
                _state.update { it.copy(isLoading = false) }
            }
            throw e
        } catch (e: Exception) {
            if (generation.get() != myGeneration) return
            val message = e.message?.takeIf { it.isNotBlank() } ?: "Неизвестная ошибка поиска"
            Log.e(TAG, "Поиск «$cleanQuery» не удался", e)
            update(isLoading = false, error = message, tracks = emptyList())
        }
    }

    /** Сбрасывает состояние: пустой запрос, снят флаг загрузки, ошибка очищена. */
    fun clear() {
        generation.incrementAndGet()
        _tracks.value = emptyList()
        _state.value = TrackState()
    }

    /** Единственная точка записи состояния: [state] и [tracks] всегда согласованы. */
    private fun update(isLoading: Boolean, error: String?, tracks: List<Track>) {
        _tracks.value = tracks
        _state.value = TrackState(isLoading = isLoading, error = error, tracks = tracks)
    }

    private companion object {
        const val TAG = "TrackRepository"
        const val DEFAULT_LIMIT = 25
    }
}
