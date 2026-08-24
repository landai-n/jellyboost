package dev.jellyboost.player.music

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.player.PlayMethod
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a resolved track into the plain description the player is handed.
 *
 * Pure, and it produces a [MusicQueueEntry] rather than a Media3 `MediaItem` — the same split
 * `PlaybackMediaItemSpec` and `session/MediaItems.kt` already make on the video side. The Android
 * types are confined to one conversion inside [ExoMusicPlayerAdapter], which is what lets the
 * queue's metadata be asserted in a plain JVM unit test instead of on a device.
 *
 * The metadata is not decoration: `MediaItem.mediaMetadata` is where the media notification and
 * the lock screen get their title, artist and artwork, so this class is the whole of "the
 * notification shows the right thing".
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
                // The item id, so a media-item transition names the track the reports are keyed
                // on without an index lookup — the timeline is the queue, but a queue can be
                // reordered underneath a transition that is already in flight.
                mediaId = item.id,
                uri = stream.uri,
                title = item.name,
                // Every credited performer, which is what a track's card already shows; the album
                // artist alone would drop the featured names a listener is looking for.
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

/**
 * One queue entry: what to play, what to draw for it, and what to report it under.
 *
 * Deliberately flat and free of Android and SDK types — it crosses from the pure resolve/spec side
 * into the adapter, and it is what the controller's unit tests build by hand.
 */
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
