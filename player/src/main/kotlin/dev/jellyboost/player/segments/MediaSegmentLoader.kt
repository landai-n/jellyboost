package dev.jellyboost.player.segments

import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.model.ticksToMillis
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches the intro/outro ranges for a playing item (docs/PLAN.md, "Playback pipeline" →
 * "Media segments (M9): `getItemSegments(INTRO/OUTRO)` → skip button; per-type pref; server-only").
 *
 * **Server-only, and silent about it.** A local source is never asked — the plan scopes the feature
 * to the server and the download pipeline stores no segments, so offline the feature is not
 * degraded, it is absent. So is the case the plan does not name but every real deployment has: the
 * Media Segments API arrived in Jellyfin 10.10 and only answers with anything when a detection
 * plugin is installed, so a 404 or an empty answer has to leave the UI exactly as it was rather
 * than surface an error the user cannot act on. Every failure therefore ends at "no segments".
 */
@Singleton
internal class MediaSegmentLoader
    @Inject
    constructor(
        private val api: PlayerApi,
    ) {
        /** @return the item's intro and outro ranges, or an empty list when there are none to have. */
        @Suppress("TooGenericExceptionCaught")
        suspend fun load(source: PlaybackMediaSource): List<MediaSegment> =
            when (source) {
                is LocalPlaybackMediaSource -> emptyList()

                is RemotePlaybackMediaSource ->
                    try {
                        api
                            .getMediaSegments(source.itemId, REQUESTED_TYPES)
                            .mapNotNull(MediaSegmentDto::toSegment)
                            .sortedBy { it.startMs }
                            .also { Timber.d("Loaded %d media segment(s) for %s", it.size, source.itemId) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.d(error, "No media segments available for %s", source.itemId)
                        emptyList()
                    }
            }

        private companion object {
            /** Only what the app can act on; asking for more would be data with no behaviour. */
            val REQUESTED_TYPES = listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO)
        }
    }

/**
 * The server's segment in the player's own vocabulary, or `null` when it is not one we act on.
 *
 * A zero- or negative-length segment is dropped rather than carried: it can never be entered, so it
 * would be a skip button that appears for a single frame or not at all.
 */
private fun MediaSegmentDto.toSegment(): MediaSegment? {
    val kind =
        when (type) {
            MediaSegmentType.INTRO -> MediaSegmentKind.INTRO
            MediaSegmentType.OUTRO -> MediaSegmentKind.OUTRO
            else -> return null
        }
    val start = startTicks.ticksToMillis()
    val end = endTicks.ticksToMillis()
    if (end <= start) return null
    return MediaSegment(kind = kind, startMs = start, endMs = end)
}
