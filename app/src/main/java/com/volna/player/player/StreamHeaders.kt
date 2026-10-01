package com.volna.player.player

/**
 * Заголовки сетевого запроса к аудиопотоку.
 *
 * Вынесено отдельно от [RangeDataSource], чтобы правило можно было проверить
 * тестом без Android-классов.
 */
internal object StreamHeaders {

    const val RANGE = "Range"

    const val USER_AGENT = "User-Agent"

    /**
     * Размер одного куска потока.
     *
     * Замеры на живых ссылках решили всё: запрос с открытым концом `bytes=0-`
     * всегда даёт 403, а ограниченный `bytes=0-1048575` (1 МБ) — 206. Граница
     * отказа где-то между 1 и 2 МБ (`bytes=0-2010000` уже 403). Поэтому всегда
     * просим ограниченный кусок, и берём его с запасом ниже границы.
     *
     * Это и было корнем всей беды: RangeDataSource слал `bytes=0-`, а проверка
     * ссылки слала `bytes=0-1023` — проверка проходила, а плеер получал отказ
     * на том же самом адресе. Раньше думали, что «у ссылки бюджет на объём»,
     * но бюджет тут ни при чём: дело в открытом конце диапазона.
     */
    const val CHUNK_BYTES = 1048576L

    /**
     * Диапазон для запроса с позиции.
     *
     * Всегда ограниченный, всегда отсюда: продолжение трека с позиции N
     * обязано просить с N, а не с нуля — иначе сервер отдаст начало, а плеер
     * ждёт середину, и поток не соберётся.
     */
    fun rangeFor(position: Long): String =
        "bytes=$position-${position + CHUNK_BYTES - 1}"

    /**
     * Добавляет [RANGE], если его ещё нет.
     *
     * Нужен из-за особенности YouTube: на запрос **без** Range он отвечает 403,
     * на запрос с Range — 206. ExoPlayer при первом открытии файла Range не
     * формирует (`buildRangeRequestHeader` для позиции 0 возвращает null),
     * а при продолжении с позиции N — формирует открытый `bytes=N-`, который
     * тоже даёт 403. Поэтому подставляем свой ограниченный кусок.
     *
     * Если ExoPlayer уже задал свой диапазон (перемотка), его не трогаем.
     */
    fun withRange(headers: Map<String, String>, position: Long = 0L): Map<String, String> {
        val existing = headers.keys.firstOrNull { it.equals(RANGE, ignoreCase = true) }
        if (existing != null) {
            // Свой диапазон ExoPlayer задаёт редко (перемотка), и он бывает
            // открытым `bytes=N-`, а открытый конец даёт 403. Поэтому открытый
            // конец ужимаем до своего куска с той же позиции.
            val value = headers[existing] ?: return headers
            val open = Regex("^bytes=(\\d+)-$").find(value.trim())
            if (open == null) return headers
            val from = open.groupValues[1].toLongOrNull() ?: return headers
            return headers + (existing to rangeFor(from))
        }
        return headers + (RANGE to rangeFor(position))
    }
}