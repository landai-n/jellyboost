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
 * The Media Segments API arrived in Jellyfin 10.10 and only answers when a detection plugin is
 * installed, so a 404 or an empty answer is the everyday case: every failure ends at "no segments",
 * never at an error the user cannot act on.
 */
@Singleton
internal class MediaSegmentLoader
    @Inject
    constructor(
        private val api: PlayerApi,
    ) {
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
            val REQUESTED_TYPES = listOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO)
        }
    }

/** A zero- or negative-length segment is dropped: it can never be entered, only flicker a button. */
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
