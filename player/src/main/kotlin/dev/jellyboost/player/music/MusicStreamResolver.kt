package dev.jellyboost.player.music

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.data.downloads.offline.DownloadedMediaProvider
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.api.AudioStreamRequest
import dev.jellyboost.player.api.AudioStreamUrlFactory
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves one track. Deliberately **no** `PlaybackInfo` round trip per track — the queue design
 * rests on it: a fifty-track album becomes fifty strings and one `setMediaItems`.
 *
 * The universal endpoint never says what it decided, so [PlayMethod] is *inferred* from the
 * container list we sent, and is used for reporting only. A track in the list can still be
 * transcoded for another reason (bitrate, channels); that costs an inaccurate dashboard row and
 * nothing else, since `stopEncodingProcess` keys on the play session id.
 */
@Singleton
internal class MusicStreamResolver
    @Inject
    constructor(
        private val downloads: DownloadedMediaProvider,
        private val urls: AudioStreamUrlFactory,
    ) {
        /** @return `null` when [item] has no usable id; the caller drops it from the queue. */
        suspend fun resolve(item: JellyfinItem): MusicStream? {
            val itemId = runCatching { UUID.fromString(item.id) }.getOrNull()
            if (itemId == null) {
                Timber.w("Cannot play %s: %s is not an item id", item.name, item.id)
                return null
            }

            downloads.get(itemId)?.let { downloaded ->
                Timber.d("Playing %s from local storage", itemId)
                return MusicStream(
                    itemId = itemId,
                    uri = downloaded.mediaUri,
                    playSessionId = null,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    mediaSourceId = downloaded.mediaSourceId,
                    runTimeTicks = downloaded.runTimeTicks,
                )
            }

            // One per queue entry: two tracks sharing a session id would have the second's start
            // report close the first's session.
            val playSessionId = UUID.randomUUID().toString().replace("-", "")
            return MusicStream(
                itemId = itemId,
                uri =
                    urls.audioUniversalUrl(
                        AudioStreamRequest(
                            itemId = itemId,
                            containers = DIRECT_CONTAINERS,
                            // Naming a source would pin the wrong one on a track that has several.
                            mediaSourceId = null,
                            playSessionId = playSessionId,
                            audioCodec = TRANSCODE_AUDIO_CODEC,
                            transcodingContainer = TRANSCODE_CONTAINER,
                            maxStreamingBitrate = MAX_STREAMING_BITRATE,
                            audioBitRate = TRANSCODE_AUDIO_BITRATE,
                        ),
                    ),
                playSessionId = playSessionId,
                playMethod = item.container.toPlayMethod(),
                // The item's own id, which is what jellyfin-web sends when it did not pin a source.
                mediaSourceId = item.id,
                runTimeTicks = item.runTimeTicks ?: 0L,
            )
        }

        private fun String?.toPlayMethod(): PlayMethod =
            when {
                this == null -> PlayMethod.DIRECT_PLAY
                lowercase().split(',').any { it.trim() in DIRECT_CONTAINERS } -> PlayMethod.DIRECT_PLAY
                else -> PlayMethod.TRANSCODE
            }

        companion object {
            /** ExoPlayer plus the bundled ffmpeg decoder handles all of these; keep `flac` on it. */
            val DIRECT_CONTAINERS =
                listOf("opus", "mp3", "aac", "m4a", "flac", "webma", "webm", "wav", "ogg")

            const val TRANSCODE_AUDIO_CODEC = "aac"

            /** Delivered as an HLS `ts` segment stream; see [AudioStreamUrlFactory]. */
            const val TRANSCODE_CONTAINER = "ts"

            /**
             * The **direct-play** ceiling, not the transcode's quality: above it the server refuses
             * to direct-play. Kept generous so high-rate flac direct-plays and the [toPlayMethod]
             * inference stays honest — the transcode bitrate here once forced lossless through the
             * encoder.
             */
            const val MAX_STREAMING_BITRATE = 120_000_000

            /**
             * The separate `audioBitRate` parameter. Restated rather than imported from
             * `DeviceProfileBuilder`: coupling them would let a profile change re-negotiate every URL.
             */
            const val TRANSCODE_AUDIO_BITRATE = 384_000
        }
    }

/**
 * @param playSessionId `null` for a downloaded file: nothing to report, no encoder to stop.
 * @param runTimeTicks what a completed track's stop report says it reached — the player's own
 *   duration is an estimate until an HLS transcode's last segment, so it is not used.
 */
internal data class MusicStream(
    val itemId: UUID,
    val uri: String,
    val playSessionId: String?,
    val playMethod: PlayMethod,
    val mediaSourceId: String,
    val runTimeTicks: Long,
) {
    /** `true` for a downloaded file: nothing to report, nothing to tear down. */
    val isLocal: Boolean get() = playSessionId == null
}
