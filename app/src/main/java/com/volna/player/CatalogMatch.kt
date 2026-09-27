package com.volna.player

import com.volna.player.search.Track
import java.util.Locale

/**
 * Подбор трека каталога (iTunes) в выдаче YouTube.
 *
 * Смысл в том, чтобы iTunes-трек «Спящая красавица» превратился в видео
 * именно с этой песней, а не в «лучшее из похожих».
 *
 * Правила, каждое из которых закрывает свой случай подмены:
 *
 *  1. **Точное совпадение названия решает всё.** Если есть трек, чьё название
 *     совпадает с каталожным, берём только его. Раньше побеждал «лучший по
 *     сумме очков», и клип со сходным названием от чужого канала обгонял
 *     официальный трек.
 *  2. **Порог отсекает мусор.** Если совпадений нет — возвращаем null, и
 *     приложение скажет «не нашёл», вместо того чтобы включить чужое.
 *  3. **Исполнитель важнее мелких совпадений.** Среди равных по названию
 *     предпочитаем того, у кого исполнитель совпал.
 */
internal object CatalogMatch {

    /**
     * Ниже этого числа совпадение не считается найденным.
     *
     * 4 — официальный трек с совпавшим исполнителем; 2 — совпадение только
     * по исполнителю. Всё, что ниже, — это «случайно похожее».
     */
    private const val MIN_SCORE = 4

    /**
     * Слова, по которым понятно, что это другая версия трека.
     *
     * Заглушка на них: «Noize MC - Молчанка (slowed & reverb)» — не песня, а
     * её замедленная обработка фанатом (147 просмотров, 317 секунд против
     * 280 у оригинала). Раньше такое видео выигрывало, потому что в названии
     * есть имя исполнителя, и включалось вместо трека.
     */
    private val VARIANT_MARKERS = listOf(
        "slowed", "slow", "reverb", "remix", "cover", "nightcore", "spedup",
        "sped", "bootleg", "flip", "mashup", "акустик", "ремикс",
        "кавер", "замедл", "ускорен", "переврат",
    )

    /**
     * Насколько длительность кандидата должна совпадать с каталожной.
     *
     * Допуск широкий, потому что у разных релизов длительность отличается на
     * секунды-десятки. Замедленная версия на полминуты длиннее — уже мимо.
     */
    private const val DURATION_TOLERANCE = 0.15

    fun pick(
        wantedTitle: String,
        wantedArtist: String,
        candidates: List<Track>,
        wantedDurationMs: Long = 0L,
    ): Track? {
        val playable = candidates
            .filter { !it.isLive }
            // Версии-переделки отсекаем до подбора: иначе «slowed & reverb»
            // побеждает оригинал по совпадению исполнителя в названии.
            .filterNot { isVariantOfAnother(it, wantedTitle) }
            .filter { matchesDuration(it, wantedDurationMs) }
        if (playable.isEmpty()) return null

        val wantedTitleTokens = tokens(wantedTitle)
        val wantedArtistTokens = tokens(wantedArtist)

        val scored = playable.map { track ->
            val title = tokens(track.title)
            val artist = tokens(track.channel) + tokens(track.musicArtist)
            Candidate(
                track = track,
                score = score(track, wantedTitleTokens, wantedArtistTokens),
                // Совпал ли исполнитель: в канале, в подписи или прямо в названии.
                artistHit = wantedArtistTokens.isNotEmpty() &&
                    wantedArtistTokens.any { it in artist || it in title },
                exactTitle = isExactTitle(track.title, wantedTitle),
                // Совпало ли хоть одно слово из каталожного названия.
                titleHit = wantedTitleTokens.isNotEmpty() &&
                    wantedTitleTokens.any { it in title },
            )
        }

        // 1. Сначала сужаем к кандидатам, у которых совпало НАЗВАНИЕ.
        //
        //    Без этого «Молчанка» превращалась в «Безмозглая музыка»: трек
        //    того же исполнителя, 6 очков за канал, и он выигрывал, потому что
        //    совпадал только исполнитель, а названия не было общего ни одного
        //    слова. Совпадение исполнителя допустимо как дополнение, но не как
        //    единственный признак.
        val withTitle = scored.filter { it.titleHit || it.exactTitle }
        if (withTitle.isEmpty()) {
            LogBuffer.d(TAG, "не нашлось ничего с названием «$wantedTitle»")
            return null
        }

        // 2. Совпадение названия само по себе не доказывает, что это нужный трек.
        //
        //    Одноимённые песни разных исполнителей обычны: «Молчанка» есть и у
        //    Noize MC, и у «творожного бизнеса». Каталожная «Молчанка» — это
        //    вторая, а играть первую означает подменить трек, то есть ровно то,
        //    на что жалуется пользователь. Поэтому если исполнитель известен,
        //    требуем совпадения и с ним: подходящего кандидата нет — честно
        //    возвращаем null, и приложение уходит искать в видео.
        val sameArtist = if (wantedArtistTokens.isEmpty()) {
            withTitle
        } else {
            withTitle.filter { it.artistHit }
        }
        if (sameArtist.isEmpty()) {
            LogBuffer.d(
                TAG,
                "название «$wantedTitle» есть, но не у «$wantedArtist»: " +
                    withTitle.joinToString { "«${it.track.title}» (${it.track.musicArtist.ifBlank { it.track.channel }})" },
            )
            return null
        }

        // 3. Среди оставшихся решает точность названия: «Атлантида» от Noize MC
        //    предпочтительнее «Атлантида - Noize MC [КЛИП]».
        val best = sameArtist
            .sortedWith(
                compareByDescending<Candidate> { it.exactTitle }
                    .thenByDescending { it.score },
            )
            .first()

        // 4. Порог остаётся: совпадения названия бывают случайными.
        if (best.score < MIN_SCORE) {
            LogBuffer.d(
                TAG,
                "совпадений нет (лучшее «${best.track.title}» = ${best.score}), " +
                    "прошу «$wantedTitle»",
            )
            return null
        }
        LogBuffer.d(
            TAG,
            "подобрано «${best.track.title}» " +
                "(${best.track.musicArtist.ifBlank { best.track.channel }}, " +
                "очки ${best.score}, исполнитель ${if (best.artistHit) "совпал" else "нет"})" +
                " на «$wantedTitle»",
        )
        return best.track
    }

    /** Кандидат с посчитанными признаками: что важнее при выборе. */
    private class Candidate(
        val track: Track,
        val score: Int,
        val artistHit: Boolean,
        val exactTitle: Boolean,
        val titleHit: Boolean = false,
    )
    /**
     * Подбор без требования к исполнителю — для запасного поиска в видео.
     *
     * Нужен, когда в каталоге трека нет (например, «Атлантида» от Noize MC
     * не отдаётся YouTube Music из-за гео-блокировки). Тогда ищем в обычном
     * YouTube, где строгие правила [pick] не оставили бы вообще ничего.
     */
    fun pickLoose(wantedTitle: String, candidates: List<Track>): Track? {
        val playable = candidates.filter { !it.isLive }
        if (playable.isEmpty()) return null
        val wanted = tokens(wantedTitle)
        return playable
            .map { it to wanted.count { word -> tokens(it.title).contains(word) } }
            .maxByOrNull { (_, hits) -> hits }
            ?.takeIf { (_, hits) -> hits > 0 }
            ?.first
    }



    /**
     * Явная другая версия: в названии есть «slowed & reverb», «ремикс» и т. п.
     *
     * Сравниваем с каталожным названием: если метка есть в кандидате, но её
     * не было в запросе, это не та версия.
     */
    private fun isVariantOfAnother(track: Track, wantedTitle: String): Boolean {
        val candidate = track.title.lowercase(Locale.ROOT)
        val wanted = wantedTitle.lowercase(Locale.ROOT)
        if (VARIANT_MARKERS.none { it in candidate }) return false
        // Метка есть и в каталожном названии — значит человек именно это и искал.
        return VARIANT_MARKERS.none { it in wanted }
    }

    /**
     * Похоже ли длительность.
     *
     * Данных о длительности в выдаче поиска нет (там `lengthText` пуст), поле
     * заполняется при разборе ответа плеера. Если длительность неизвестна —
     * проверку пропускаем, иначе отсеялось бы всё подряд.
     */
    private fun matchesDuration(track: Track, wantedDurationMs: Long): Boolean {
        if (wantedDurationMs <= 0L) return true
        if (track.durationSeconds <= 0) return true
        val wantedSeconds = wantedDurationMs / 1000.0
        val allowed = wantedSeconds * DURATION_TOLERANCE
        return kotlin.math.abs(track.durationSeconds - wantedSeconds) <= allowed
    }

    private fun score(track: Track, wantedTitle: Set<String>, wantedArtist: Set<String>): Int {
        val title = tokens(track.title)
        val artist = tokens(track.channel) + tokens(track.musicArtist)
        var score = 0
        // YouTube сам пометил видео как трек — сильный признак.
        if (track.isOfficialMusic) score += 4
        score += wantedTitle.count { it in title } * 3
        score += wantedArtist.count { it in artist } * 2
        // Исполнитель прямо в названии видео.
        if (wantedArtist.any { it in title }) score += 2
        // Совпадение длинного слова целиком.
        if (wantedTitle.any { it.length > 3 && title.contains(it) }) score += 2
        return score
    }

    /**
     * Совпадают ли названия по сути.
     *
     * Сравниваются только значимые слова: «Атлантида» и «Атлантида (Live)»
     * — одно и то же, а «Пчёлы слов» и «Спящая красавица» — нет.
     */
    private fun isExactTitle(candidate: String, wanted: String): Boolean {
        val a = tokens(candidate)
        val b = tokens(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        // Допускаем одну лишнюю скобку вроде «(Live)», но не половину названия.
        return b.all { it in a } && a.count { it in b } >= (a.size * 2) / 3
    }

    /** Слова длиннее двух букв: «the», «и», «feat» нам не нужны. */
    private fun tokens(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .split(Regex("[^a-zа-я0-9]+"))
            .filter { it.length > 2 }
            .toSet()

    private const val TAG = "CatalogMatch"
}