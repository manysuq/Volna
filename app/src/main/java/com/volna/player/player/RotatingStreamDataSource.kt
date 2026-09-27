package com.volna.player.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Источник данных, который сам меняет ссылку на поток.
 *
 * Зачем: ссылка выдаётся с ограничением по объёму — на живых ссылках 512 КБ
 * отдаются, а запрос на мегабайт получает 403. Проверка ссылки чинит только
 * начало трека, а обрыв приходит в середине. Раньше это означало, что музыка
 * прерывалась, приложение ловило ошибку и начинало трек заново: слышно было
 * и паузу, и повтор.
 *
 * Здесь обрыв не доходит до плеера. Источник сам берёт свежую ссылку и
 * продолжает с той же байтовой позиции, поэтому в аудио нет ничего, кроме
 * ожидания ответа YouTube.
 *
 * Если свежую ссылку взять не удалось, поведение прежнее: ошибка уходит
 * наружу, и ViewModel переподключается как раньше. Поэтому при отсутствии
 * [StreamSession] источник просто читает то, что есть.
 */
internal class RotatingStreamDataSource(
    /** Создаёт источник под конкретный адрес. */
    private val createFor: (String) -> DataSource,
    private val session: StreamSession?,
) : DataSource {

    private var upstream: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeUpstream()

        // Заранее обновляем ссылку, если отработан её объём. Это и есть
        // главное отличие от прежнего поведения: отказа не будет, потому
        // что к моменту, когда старая кончится, новая уже готова.
        session?.let { it.refreshIfNeeded() }

        var failure: IOException? = null

        val first = session?.url
        if (first != null) {
            val opened = tryOpen(dataSpec, first)
            if (opened != null) return opened
            failure = lastFailure
        }

        // Не открылось. Ссылка битая — берём новую и пробуем ещё раз, не
        // отдавая ошибку наружу: на улице слышно был бы обрыв.
        session?.let { it.refreshNow() }
        val second = session?.url
        if (second != null && second != first) {
            val opened = tryOpen(dataSpec, second)
            if (opened != null) return opened
            failure = lastFailure ?: failure
        }

        // Причину отказа сохраняем: без неё Media3 отдаёт наружу
        // ERROR_CODE_IO_UNSPECIFIED, и в отчёте не остаётся НИЧЕГО — ни
        // кода ответа, ни адреса. Из-за этого шесть одинаковых строк в логе
        // приходилось читать как загадку вместо ответа.
        val reason = failure?.message ?: "без причины"
        com.volna.player.LogBuffer.e(
            TAG,
            "поток не открылся: $reason (испытано ссылок: ${if (second == first) 1 else 2})",
        )
        throw IOException("Свежая ссылка на поток не открылась: $reason", failure)
    }

    /** Последняя причина, по которой адрес не открылся. */
    private var lastFailure: IOException? = null

    private companion object {
        const val TAG = "PlaybackService"
    }

    /** Возвращает позицию начала или null, если адрес не открылся. */
    private fun tryOpen(dataSpec: DataSpec, url: String): Long? = try {
        val source = createFor(url)
        upstream = source
        source.open(dataSpec.buildUpon().setUri(url).build()).also {
            lastFailure = null
            session?.onOpened(url)
        }
    } catch (e: IOException) {
        // Адрес не годен. Сбрасываем источник и пробуем следующий.
        // Причину запоминаем: она и есть ответ, что именно сломалось.
        lastFailure = e
        closeUpstream()
        session?.onFailed(url)
        null
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = upstream ?: throw IOException("Источник не открыт")
        val read = source.read(buffer, offset, length)
        // Считаем, сколько взято с текущей ссылки: по этому решается, когда
        // пора за новой.
        if (read > 0) session?.onRead(read.toLong())
        return read
    }

    override fun getUri(): Uri? = upstream?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        closeUpstream()
    }

    private fun closeUpstream() {
        try {
            upstream?.close()
        } catch (e: IOException) {
            // Закрытие не должно ронять воспроизведение: поток уже не нужен.
        }
        upstream = null
    }
}
