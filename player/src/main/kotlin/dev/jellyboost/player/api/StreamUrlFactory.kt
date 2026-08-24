package dev.jellyboost.player.api

import java.util.UUID

internal interface StreamUrlFactory {
    /** `static=true` is what makes the server skip remuxing; without it the same endpoint silently direct-streams. */
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

    fun absoluteUrl(path: String): String

    /**
     * For fetchers outside this app only — a Cast receiver has none of `JellyfinAuthInterceptor` in its network
     * stack, so its URLs must carry the token as an `ApiKey` parameter.
     *
     * **Must stay idempotent**: server-returned `TranscodingUrl`/`DeliveryUrl` already carry `ApiKey`, while
     * locally-built direct-play URLs do not, and callers apply this to all of them.
     */
    fun withApiKey(url: String): String = url

    /**
     * Fetched by the *image* loader, not ExoPlayer, so it cannot rely on `JellyfinAuthInterceptor` and must
     * carry its own credentials.
     *
     * @param width the resolution the sheets were generated at; the server holds one set per width.
     * @param mediaSourceId `null` for the item's default source.
     */
    fun trickplayTileUrl(
        itemId: UUID,
        width: Int,
        tileIndex: Int,
        mediaSourceId: String? = null,
    ): String
}
