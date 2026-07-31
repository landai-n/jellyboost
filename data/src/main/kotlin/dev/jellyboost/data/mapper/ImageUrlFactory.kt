package dev.jellyboost.data.mapper

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
     * @param maxWidth width in pixels to have the server scale the image to before sending — see
     *   [ArtworkRequestWidths], which derives it from the dp size the surface draws at.
     * @return an absolute URL, or `null` when [tag] is `null`.
     */
    fun imageUrl(
        itemId: UUID,
        kind: ImageKind,
        tag: String?,
        maxWidth: Int? = null,
    ): String?
}
