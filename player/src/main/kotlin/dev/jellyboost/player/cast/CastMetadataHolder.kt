package dev.jellyboost.player.cast

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the receiver should put on the television, waiting for the load that will carry it.
 *
 * The channel between the two halves of a cast start that never meet. A `MediaInfo`'s metadata is
 * the *item's* — its name, its episode line, its artwork — and none of it appears in a
 * `PlaybackInfo` response: the negotiation answers with codecs, URLs and stream indices, so
 * `CastPlayerHandle.prepare` has everything needed to *play* the film and nothing to *name* it.
 * The one place that does know is `PlayerViewModel`, which fetches the item for the top bar and the
 * backdrop anyway (DECISIONS.md 2026-07-31, "the casting artwork is fetched with the title").
 *
 * Shaped exactly like [CastStatusHolder], and for the same reasons: it breaks a dependency that
 * would otherwise have to run backwards — the handle cannot ask a ViewModel anything — and it names
 * no `com.google.android.gms` type, so a ViewModel test constructs one on a machine with no Cast
 * stack at all.
 *
 * ### Why it is keyed by media id
 * A `@Singleton` outlives every session, and a queue that advances (a SyncPlay group moving to the
 * next episode, a receiver reused for a second film) publishes a second item over the first. Reading
 * the metadata back under the id it was published for is what makes a stale entry impossible: the
 * mismatch case returns nothing and the receiver shows its own idle backdrop, which is a cosmetic
 * loss, where the wrong title on the television is a lie the user cannot correct from here.
 *
 * Written on the main thread by `PlayerViewModel`, read on the main thread by [CastPlayerHandle]
 * through `PlaybackSessionController.open`; `@Volatile` because Hilt may construct either off it and
 * the cost of being sure is a single field read.
 */
@Singleton
internal class CastMetadataHolder
    @Inject
    constructor() {
        @Volatile
        private var entry: Entry? = null

        /**
         * Records what [mediaId] should be announced as.
         *
         * Called by `PlayerViewModel` as soon as the item arrives — including when nothing is being
         * cast, because a receiver can be connected mid-film and the load that follows must not have
         * to wait for a second fetch.
         */
        fun publish(
            mediaId: String,
            metadata: CastMetadata,
        ) {
            entry = Entry(mediaId, metadata)
        }

        /**
         * The metadata published for [mediaId], or an empty one if what is held is another item's.
         *
         * The comparison ignores case because the two ids reach here by different routes — the
         * navigation argument on one side, `UUID.toString()` of the resolved source on the other —
         * and a UUID that differs only in case is the same UUID.
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
