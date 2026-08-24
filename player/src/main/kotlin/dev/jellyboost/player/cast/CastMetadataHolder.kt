package dev.jellyboost.player.cast

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cast metadata (title, episode line, artwork) published by `PlayerViewModel` for [CastPlayerHandle]
 * to read: a `PlaybackInfo` response carries codecs and URLs but nothing that names the item.
 *
 * Keyed by media id because a `@Singleton` outlives every session: on a mismatch it must return
 * nothing (receiver shows its idle backdrop) rather than the previous item's title.
 *
 * Names no `com.google.android.gms` type, so a ViewModel test constructs one with no Cast stack.
 */
@Singleton
internal class CastMetadataHolder
    @Inject
    constructor() {
        @Volatile
        private var entry: Entry? = null

        /** Published as soon as the item arrives, casting or not: a receiver can connect mid-film. */
        fun publish(
            mediaId: String,
            metadata: CastMetadata,
        ) {
            entry = Entry(mediaId, metadata)
        }

        /**
         * Case-insensitive: the ids arrive by different routes (nav argument vs `UUID.toString()` of
         * the resolved source) and a UUID differing only in case is the same UUID.
         */
        fun metadataFor(mediaId: String): CastMetadata {
            val held = entry ?: return CastMetadata()
            return if (held.mediaId.equals(mediaId, ignoreCase = true)) held.metadata else CastMetadata()
        }

        private data class Entry(
            val mediaId: String,
            val metadata: CastMetadata,
        )
    }
