package dev.jellyboost.data.mapper

import java.util.UUID

/**
 * Deterministic [ImageUrlFactory] for mapper tests.
 *
 * Encodes every input into the URL so assertions can prove *which* item and tag the mapper picked
 * — that is the whole point of the artwork fallback chain.
 */
internal class FakeImageUrlFactory : ImageUrlFactory {
    override fun imageUrl(
        itemId: UUID,
        kind: ImageKind,
        tag: String?,
        maxWidth: Int?,
    ): String? = tag?.let { "https://server/Items/$itemId/Images/$kind?tag=$it&maxWidth=$maxWidth" }
}
