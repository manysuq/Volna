package com.volna.player.player

import com.volna.player.catalog.MusicCatalog
import kotlin.random.Random

/**
 * Порядок треков для «слушать всё» по исполнителю.
 *
 * Отдельный объём, потому что правило одно, а проверять его удобнее без
 * ViewModel: порядок выстраивается в список, который потом уходит в
 * _albumTracks, и «вперёд» идёт ровно по нему.
 */
internal object ArtistQueue {

    /**
     * Все треки всех альбомов, перемешанные целиком.
     *
     * Раньше перемешивался только стартовый трек, а сам список оставался в
     * порядке альбомов: трек 1 альбома A, трек 2 альбома A, трек 1 альбома B…
     * То есть «слушать всё» у исполнителя звучало как «слушать альбомы по
     * очереди», а не как случайные треки. Перемешивать нужно весь список, и
     * тогда альбомы в нём перемежаются.
     *
     * Дубли схлопываются по названию до перемешивания: один и тот же трек
     * часто лежит в нескольких релизах (сингл и альбом), и без этого он играл
     * бы дважды.
     */
    fun build(
        tracksByAlbum: List<List<MusicCatalog.CatalogTrack>>,
        random: Random = Random.Default,
    ): List<MusicCatalog.CatalogTrack> =
        tracksByAlbum
            .flatten()
            .distinctBy { it.title.trim().lowercase() }
            .shuffled(random)
}
