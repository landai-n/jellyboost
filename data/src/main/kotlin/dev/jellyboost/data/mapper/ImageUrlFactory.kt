package dev.jellyboost.data.mapper

import java.util.UUID

internal enum class ImageKind {
    PRIMARY,
    BACKDROP,
    THUMB,
    LOGO,
}

/**
 * Behind an interface so [ItemMapper] is unit-testable without an `ApiClient`, and so the offline
 * path can substitute local file URIs without touching the mapper.
 */
internal interface ImageUrlFactory {
    /**
     * @param tag `null` means the item has no such image; the factory must then return `null` rather
     *   than a URL that would 404.
     * @param maxWidth pixels for the server to scale to before sending — see [ArtworkRequestWidths].
     */
    fun imageUrl(
        itemId: UUID,
        kind: ImageKind,
        tag: String?,
        maxWidth: Int? = null,
    ): String?
}
