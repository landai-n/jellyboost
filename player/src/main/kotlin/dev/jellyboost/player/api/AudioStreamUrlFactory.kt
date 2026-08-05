package dev.jellyboost.player.api

import java.util.UUID

/**
 * Builds the one URL music streaming needs: `GET /Audio/{itemId}/universal`.
 *
 * Separate from [StreamUrlFactory] rather than a member on it, and that is deliberate twice over.
 * [StreamUrlFactory] is `videosApi`-only by construction — every method on it names a `/Videos`
 * endpoint — and the universal audio endpoint is not a video URL under another name: it decides
 * direct-play-versus-transcode *server side*, from a container list the client sends, which is the
 * whole property the music queue is built on (docs/notes/music-m13-plan.md, key decision 4).
 * Keeping the two interfaces apart also keeps the video path's three existing test doubles from
 * having to grow a member none of them can meaningfully answer (DECISIONS.md 2026-08-05).
 *
 * ### Why one URL is enough for a whole album
 * The video path asks the server how to play each item (`PlaybackInfo`) before it can build a URL.
 * The universal endpoint inverts that: the client states what it can play and the server picks,
 * so a fifty-track queue is fifty locally-built strings and no round trips — which is what lets
 * `setMediaItems` take the queue in one call.
 */
interface AudioStreamUrlFactory {
    /** The universal audio URL for [request]. */
    fun audioUniversalUrl(request: AudioStreamRequest): String
}

/**
 * What the client tells the server about one track it wants to hear.
 *
 * A value object rather than seven parameters, because the terms travel together and are decided
 * in one place ([dev.jellyboost.player.music.MusicStreamResolver]'s companion): a call site that
 * could get their *order* wrong is a call site that could silently ask for a 384-bit stream in the
 * `aac` container.
 *
 * @param containers what this device can play without help. The server direct-plays a track whose
 *   container is in the list and transcodes anything else — which is also what makes the reported
 *   play method inferable client-side.
 * @param playSessionId minted by the caller, one per queue entry. The endpoint has **no**
 *   `playSessionId` parameter in the SDK's builder; jellyfin-web appends it as a plain extra query
 *   parameter and the server binds it anyway, so the implementation does the same. It is what ties
 *   this stream to the start/progress/stop reports and to `stopEncodingProcess`.
 * @param audioCodec what a transcode should produce.
 * @param transcodingContainer the container a transcode is delivered in; paired with HLS by the
 *   implementation, because the alternative — the device profile's mp3-over-HTTP audio transcoding
 *   profile — cannot be seeked and lands in the video resolver's HLS-only gate.
 * @param maxStreamingBitrate the ceiling a transcode is encoded to.
 */
data class AudioStreamRequest(
    val itemId: UUID,
    val containers: List<String>,
    val mediaSourceId: String?,
    val playSessionId: String,
    val audioCodec: String,
    val transcodingContainer: String,
    val maxStreamingBitrate: Int,
)
