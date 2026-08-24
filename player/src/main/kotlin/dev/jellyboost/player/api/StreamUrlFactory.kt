package dev.jellyboost.player.api

import java.util.UUID

/**
 * Builds the stream URLs ExoPlayer fetches.
 *
 * Mirrors `:data`'s `ImageUrlFactory`: URL construction is the data layer's job, and hiding it
 * behind an interface lets `ExoMediaSourceFactory` be tested against predictable strings instead
 * of an SDK client with a base URL and an access token.
 */
internal interface StreamUrlFactory {
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

    /**
     * The same URL with the access token on it, for a fetcher that is not this app.
     *
     * Every stream this app opens is authorised by `JellyfinAuthInterceptor`'s header. A Cast
     * receiver has its own network stack and none of ours in it, so its URLs have to carry the token
     * themselves — the same `ApiKey` query parameter the trickplay sheets already use for Coil.
     *
     * **Idempotent**, which is not a nicety: a transcode's `TranscodingUrl` and every
     * external-subtitle `DeliveryUrl` come back from the server with `ApiKey` already on them,
     * while a direct-play or direct-stream URL — built locally by the SDK — does not. Callers
     * apply this to all of them and let the ones that need nothing keep what they have.
     *
     * Defaults to the identity: a factory with no credentials to add (a downloaded item's `file://`
     * URIs, a test double) has nothing to do here, and the SDK-backed implementation is the only one
     * that holds a token.
     */
    fun withApiKey(url: String): String = url

    /**
     * `GET /Videos/{itemId}/Trickplay/{width}/{index}.jpg` — one scrubbing-thumbnail sprite sheet.
     *
     * Unlike every other URL on this interface the result is fetched by the *image* loader rather
     * than by ExoPlayer, so it cannot rely on `JellyfinAuthInterceptor` and has to carry its own
     * credentials.
     *
     * @param width the thumbnail resolution the sheets were generated at; the server holds one set
     *   of sheets per width and the path selects between them.
     * @param tileIndex which sheet, counting from zero in thumbnail order.
     * @param mediaSourceId the media source the sheets belong to, or `null` for the item's default.
     */
    fun trickplayTileUrl(
        itemId: UUID,
        width: Int,
        tileIndex: Int,
        mediaSourceId: String? = null,
    ): String
}
