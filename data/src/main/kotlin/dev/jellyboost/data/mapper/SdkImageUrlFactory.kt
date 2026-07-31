package dev.jellyboost.data.mapper

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ImageUrlFactory] backed by the SDK's `imageApi` URL builders, so the server base URL, path
 * template and query encoding all stay the SDK's responsibility.
 *
 * The `tag` query parameter is what makes these URLs safely cacheable: it changes whenever the
 * artwork changes, so Coil's disk cache never serves a stale poster.
 */
@Singleton
class SdkImageUrlFactory
    @Inject
    constructor(
        private val apiClient: ApiClient,
    ) : ImageUrlFactory {
        override fun imageUrl(
            itemId: UUID,
            kind: ImageKind,
            tag: String?,
            maxWidth: Int?,
        ): String? {
            if (tag == null) return null
            return apiClient.imageApi.getItemImageUrl(
                itemId = itemId,
                imageType = kind.toSdkImageType(),
                tag = tag,
                maxWidth = maxWidth,
            )
        }

        private fun ImageKind.toSdkImageType(): ImageType =
            when (this) {
                ImageKind.PRIMARY -> ImageType.PRIMARY
                ImageKind.BACKDROP -> ImageType.BACKDROP
                ImageKind.THUMB -> ImageType.THUMB
                ImageKind.LOGO -> ImageType.LOGO
            }
    }
