package dev.jellyboost.player.music

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.player.PlayMethod
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The metadata here becomes `MediaItem.mediaMetadata`, which is where the media notification and the
 * lock screen read their title, artist and artwork.
 */
@Singleton
internal class MusicQueueSpecFactory
    @Inject
    constructor() {
        fun create(
            item: JellyfinItem,
            stream: MusicStream,
        ): MusicQueueEntry =
            MusicQueueEntry(
                itemId = stream.itemId,
                // The item id, not a queue index: a queue can be reordered underneath a transition
                // that is already in flight.
                mediaId = item.id,
                uri = stream.uri,
                title = item.name,
                // Every credited performer, matching the track card; the album artist alone would
                // drop featured names.
                artist = item.artists.joinToString(", ").ifEmpty { item.albumArtist },
                albumTitle = item.album,
                artworkUri = item.primaryImageUrl,
                trackNumber = item.indexNumber,
                discNumber = item.parentIndexNumber,
                playSessionId = stream.playSessionId,
                playMethod = stream.playMethod,
                mediaSourceId = stream.mediaSourceId,
                runTimeTicks = stream.runTimeTicks,
            )
    }

/** Deliberately free of Android and SDK types: the controller's unit tests build these by hand. */
internal data class MusicQueueEntry(
    val itemId: UUID,
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val albumTitle: String?,
    val artworkUri: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val playSessionId: String?,
    val playMethod: PlayMethod,
    val mediaSourceId: String,
    val runTimeTicks: Long,
)
