package com.volna.player.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Источник данных, который добавляет [StreamHeaders.RANGE] к каждому запросу.
 *
 * Зачем: YouTube отвечает 403 на запрос без Range, поэтому ExoPlayer, у которого
 * для первого открытия файла диапазон не формируется, получал отказ. Проверка
 * ссылки Range шла и проходила — расходились только заголовки у двух разных
 * HTTP-клиентов.
 *
 * Почему не `setDefaultRequestProperties`: там заголовок перетирается тем, что
 * ExoPlayer задаёт сам (в `DefaultHttpDataSource.open` сначала идут свойства
 * фабрики, потом свойства источника, и только затем вычисленный Range).
 * Заголовки из [DataSpec] применяются после них, поэтому Range отсюда
 * доживает до позиции 0.
 *
 * Диапазон добавляется только если его ещё нет: когда ExoPlayer перематывает,
 * он формирует свой, и подменять его было бы ошибкой.
 */
internal class RangeDataSource(
    private val upstream: DataSource,
    /**
     * Какой User-Agent сейчас открывать ссылки.
     *
     * Нужен потому, что подпись ссылки привязана к InnerTube-клиенту, который
     * её выдал. Пока User-Agent был зашит в DataSource один на всё приложение,
     * ссылка от другого клиента открывалась чужим — и получала 403.
     */
    private val userAgent: StreamUserAgent,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // Позицию берём из dataSpec: продолжение трека обязано просить с
        // нужного места. Раньше здесь всегда слался `bytes=0-`, и на замеры
        // живьём вышло, что открытый конец даёт 403, а ограниченный кусок —
        // 206. Теперь кусок ограничен всегда: `bytes=N..N+1МБ`.
        var headers = StreamHeaders.withRange(dataSpec.httpRequestHeaders, dataSpec.position)
        userAgent.value?.let { headers = headers + (StreamHeaders.USER_AGENT to it) }
        val patched = dataSpec.buildUpon()
            .setHttpRequestHeaders(headers)
            .build()
        return upstream.open(patched)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.getResponseHeaders()

    override fun close() {
        try {
            upstream.close()
        } catch (e: IOException) {
            // Закрытие не должно ронять воспроизведение: поток уже не нужен.
        }
    }
}