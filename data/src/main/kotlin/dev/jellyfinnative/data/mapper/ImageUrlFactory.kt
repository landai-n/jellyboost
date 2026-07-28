package dev.jellyfinnative.data.mapper

import java.util.UUID

/** The artwork kinds this client requests from the server. */
enum class ImageKind {
    PRIMARY,
    BACKDROP,
    THUMB,
    LOGO,
}

/**
 * Builds fully-qualified image URLs for the UI.
 *
 * Kept behind an interface so [ItemMapper] can be unit-tested without an
 * `org.jellyfin.sdk.api.client.ApiClient`, and so the offline path can substitute local file URIs
 * in M8 without touching the mapper.
 */
interface ImageUrlFactory {
    /**
     * @param itemId item that owns the image.
     * @param kind which artwork to build a URL for.
     * @param tag the server's image tag; `null` means the item has no such image, and the factory
     *   must return `null` rather than a URL that would 404.
     * @param maxWidth largest width the UI will ever draw this image at, so the server can scale
     *   it down before sending.
     * @return an absolute URL, or `null` when [tag] is `null`.
     */
    fun imageUrl(
        itemId: UUID,
        kind: ImageKind,
        tag: String?,
        maxWidth: Int? = null,
    ): String?

    companion object {
        /** Poster artwork is never drawn wider than this on a phone or tablet. */
        const val POSTER_MAX_WIDTH = 400

        /** Landscape card artwork (Continue watching / Next up / library tiles). */
        const val THUMB_MAX_WIDTH = 640

        /** Full-bleed backdrops behind detail headers. */
        const val BACKDROP_MAX_WIDTH = 1280
    }
}
