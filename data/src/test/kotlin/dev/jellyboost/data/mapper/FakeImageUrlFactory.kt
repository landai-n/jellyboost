package dev.jellyboost.data.mapper

import java.util.UUID

/** Encodes every input into the URL so assertions can prove *which* item and tag was picked. */
internal class FakeImageUrlFactory : ImageUrlFactory {
    override fun imageUrl(
        itemId: UUID,
        kind: ImageKind,
        tag: String?,
        maxWidth: Int?,
    ): String? = tag?.let { "https://server/Items/$itemId/Images/$kind?tag=$it&maxWidth=$maxWidth" }
}
