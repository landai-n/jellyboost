package dev.jellyboost.player.api

import java.util.UUID

/**
 * `GET /Audio/{itemId}/universal` decides direct-play-versus-transcode *server side* from the container list
 * the client sends, so a whole queue is locally-built strings with no `PlaybackInfo` round trip each.
 */
interface AudioStreamUrlFactory {
    fun audioUniversalUrl(request: AudioStreamRequest): String
}

/**
 * @param containers what the server direct-plays; anything else is transcoded, which is what makes the play
 *   method inferable client-side.
 * @param playSessionId one per queue entry. The SDK builder has **no** `playSessionId` parameter; jellyfin-web
 *   appends it as a plain extra query parameter and the server binds it anyway.
 * @param transcodingContainer paired with HLS by the implementation: the mp3-over-HTTP alternative cannot be
 *   seeked and lands in the video resolver's HLS-only gate.
 * @param maxStreamingBitrate the **direct-play** ceiling, not the transcode's quality ([audioBitRate]);
 *   confusing the two once sent every flac through the encoder.
 */
data class AudioStreamRequest(
    val itemId: UUID,
    val containers: List<String>,
    val mediaSourceId: String?,
    val playSessionId: String,
    val audioCodec: String,
    val transcodingContainer: String,
    val maxStreamingBitrate: Int,
    val audioBitRate: Int,
)
