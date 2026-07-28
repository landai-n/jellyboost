package dev.jellyfinnative.player.api

import java.util.UUID

/**
 * Builds the stream URLs ExoPlayer fetches.
 *
 * Mirrors `:data`'s `ImageUrlFactory`: URL construction is the data layer's job, and hiding it
 * behind an interface lets `ExoMediaSourceFactory` be tested against predictable strings instead
 * of an SDK client with a base URL and an access token.
 */
interface StreamUrlFactory {
    /**
     * `GET /Videos/{itemId}/stream?static=true` — the untouched file, for direct play.
     *
     * `static=true` is what tells the server to skip remuxing entirely; without it the same
     * endpoint would silently direct-stream.
     */
    fun directPlayUrl(
        itemId: UUID,
        mediaSourceId: String,
        playSessionId: String,
    ): String

    /** `GET /Videos/{itemId}/stream.{container}` — remuxed into [container], no re-encode. */
    fun directStreamUrl(
        itemId: UUID,
        container: String,
        mediaSourceId: String,
        playSessionId: String,
    ): String

    /** Turns a server-relative path (a transcoding or subtitle delivery URL) into an absolute one. */
    fun absoluteUrl(path: String): String
}
