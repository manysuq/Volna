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
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val patched = dataSpec.buildUpon()
            .setHttpRequestHeaders(StreamHeaders.withRange(dataSpec.httpRequestHeaders))
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