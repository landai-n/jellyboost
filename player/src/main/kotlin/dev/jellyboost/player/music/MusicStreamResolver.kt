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
 * Decides what one track will be played from, and mints the session id it is reported under.
 *
 * The video resolver is deliberately not reused (docs/notes/music-m13-plan.md, key decision 4).
 * Everything here is local: the downloads lookup, and otherwise a URL built from a container list
 * this device can name without asking anyone. There is no `PlaybackInfo` round trip per track,
 * which is the property the whole queue design rests on — a fifty-track album becomes fifty
 * strings and one `setMediaItems`.
 *
 * ### Offline first
 * A completed download wins over the server every time, exactly as [dev.jellyboost.player.resolve.
 * LocalPlaybackResolver] does for video: the `file://` URI the provider hands back, `DIRECT_PLAY`
 * by construction, and **no play session** — nothing was negotiated with the server, so there is
 * nothing for it to key a session on and nothing to report (M8's rule, unchanged).
 *
 * ### The play method is inferred, not told
 * The universal endpoint answers with bytes, not with a description of what it decided, and the
 * queue cannot afford a round trip per track to find out. So the inference is made from the same
 * fact the server is deciding on: **we send the container list, so a track whose container is in
 * it direct-plays and anything else is transcoded.** It is the honest reading of the request we
 * made, and it is only used for reporting — playback itself is unaffected either way. An item
 * whose container is unknown (a lean list response that carried no `container`) is reported as
 * direct play: [DIRECT_CONTAINERS] covers essentially every container a music library holds, so a
 * missing field is far more likely than an exotic codec. The dashboard check in the M13 device DoD
 * is what confirms all of this against a real server.
 *
 * The server can still transcode a track in the list for a *different* reason — a bitrate above
 * [MAX_STREAMING_BITRATE], or more channels than we asked for — and the report would then say
 * direct play for a transcode. It costs an inaccurate dashboard row and nothing else: the
 * transcode is torn down by `stopEncodingProcess`, which keys on the play session id rather than
 * on the method.
 */
@Singleton
internal class MusicStreamResolver
    @Inject
    constructor(
        private val downloads: DownloadedMediaProvider,
        private val urls: AudioStreamUrlFactory,
    ) {
        /**
         * @return how to play [item], or `null` when it does not have a usable id — the caller
         *   drops it from the queue and says so.
         */
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

            // One per queue entry, not one per queue: the server keys a session on it, and two
            // tracks sharing one would make the second's start report close the first's session.
            val playSessionId = UUID.randomUUID().toString().replace("-", "")
            return MusicStream(
                itemId = itemId,
                uri =
                    urls.audioUniversalUrl(
                        AudioStreamRequest(
                            itemId = itemId,
                            containers = DIRECT_CONTAINERS,
                            // The server picks the source; naming one would only pin the wrong one
                            // on a track that has more than one.
                            mediaSourceId = null,
                            playSessionId = playSessionId,
                            audioCodec = TRANSCODE_AUDIO_CODEC,
                            transcodingContainer = TRANSCODE_CONTAINER,
                            maxStreamingBitrate = MAX_STREAMING_BITRATE,
                        ),
                    ),
                playSessionId = playSessionId,
                playMethod = item.container.toPlayMethod(),
                // The reports carry the item's own id as the media source, which is what
                // jellyfin-web sends when it did not pin one either.
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
            /**
             * What this device plays without the server's help.
             *
             * ExoPlayer with the bundled ffmpeg decoder handles all of them; `flac` is on the list
             * on purpose, because a lossless library transcoded to AAC would be the single most
             * visible way to get music wrong.
             */
            val DIRECT_CONTAINERS =
                listOf("opus", "mp3", "aac", "m4a", "flac", "webma", "webm", "wav", "ogg")

            /** What a transcode is re-encoded to — universally decodable, and small. */
            const val TRANSCODE_AUDIO_CODEC = "aac"

            /** Delivered as an HLS `ts` segment stream; see [AudioStreamUrlFactory]. */
            const val TRANSCODE_CONTAINER = "ts"

            /**
             * The transcode ceiling, the same number `DeviceProfileBuilder` already advertises for
             * audio (its `MAX_MUSIC_TRANSCODING_BITRATE`). Restated rather than imported: that one
             * is a private detail of the device profile, and coupling the two would make a profile
             * change silently re-negotiate every queue URL.
             */
            const val MAX_STREAMING_BITRATE = 384_000
        }
    }

/**
 * One track, resolved.
 *
 * @param playSessionId `null` for a downloaded file — there is no server session behind it, so
 *   nothing is reported and there is no encoder to stop (M8's rule).
 * @param runTimeTicks what a completed track's stop report says it reached; the player's own
 *   duration is not trusted for that, because an HLS transcode's is an estimate until the last
 *   segment.
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
