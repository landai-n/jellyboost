package dev.jellyfinnative.data.mapper

import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.common.model.UserData
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the SDK's `BaseItemDto` into the domain models the rest of the app uses.
 *
 * This is the boundary the plan's hard rule protects: nothing downstream of the repositories ever
 * sees a DTO (docs/PLAN.md, "Data layer"). Image URLs are resolved here, following the same
 * artwork fallback chain jellyfin-web uses (own image → series image → parent image), so rows do
 * not degrade into placeholders for episodes that carry no artwork of their own.
 */
@Singleton
class ItemMapper
    @Inject
    constructor(
        private val imageUrls: ImageUrlFactory,
    ) {
        /** Maps one item. */
        fun toDomain(dto: BaseItemDto): JellyfinItem =
            JellyfinItem(
                id = dto.id.toString(),
                name = dto.name.orEmpty(),
                type = dto.type.toItemType(),
                overview = dto.overview,
                productionYear = dto.productionYear,
                runTimeTicks = dto.runTimeTicks,
                communityRating = dto.communityRating,
                officialRating = dto.officialRating,
                genres = dto.genres.orEmpty(),
                indexNumber = dto.indexNumber,
                parentIndexNumber = dto.parentIndexNumber,
                seriesId = dto.seriesId?.toString(),
                seriesName = dto.seriesName,
                seasonId = dto.seasonId?.toString(),
                seasonName = dto.seasonName,
                parentId = dto.parentId?.toString(),
                primaryImageUrl = dto.primaryImageUrl(),
                backdropImageUrl = dto.backdropImageUrl(),
                thumbImageUrl = dto.thumbImageUrl(),
                logoImageUrl = dto.logoImageUrl(),
                primaryImageAspectRatio = dto.primaryImageAspectRatio,
                userData = dto.userData.toDomain(),
            )

        /** Maps a list of items, preserving server order (the rows are already sorted server-side). */
        fun toDomain(dtos: List<BaseItemDto>): List<JellyfinItem> = dtos.map(::toDomain)

        /**
         * Maps a `getUserViews` entry into a [LibraryView].
         *
         * Returns `null` for libraries outside v1 scope (music, live TV, photos …) so callers can
         * simply `mapNotNull`.
         */
        fun toLibraryView(dto: BaseItemDto): LibraryView? {
            val kind = dto.collectionType.toCollectionKind()
            if (kind !in CollectionKind.SUPPORTED) return null
            return LibraryView(
                id = dto.id.toString(),
                name = dto.name.orEmpty(),
                collectionType = kind,
                primaryImageUrl = dto.primaryImageUrl(),
                thumbImageUrl = dto.thumbImageUrl(),
            )
        }

        /** Maps `getUserViews` results, dropping every library kind v1 does not support. */
        fun toLibraryViews(dtos: List<BaseItemDto>): List<LibraryView> = dtos.mapNotNull(::toLibraryView)

        // ---- artwork ------------------------------------------------------------------------

        private fun BaseItemDto.primaryImageUrl(): String? {
            imageUrls
                .imageUrl(id, ImageKind.PRIMARY, imageTags?.get(ImageType.PRIMARY), POSTER_WIDTH)
                ?.let { return it }
            seriesId?.let { series ->
                imageUrls
                    .imageUrl(series, ImageKind.PRIMARY, seriesPrimaryImageTag, POSTER_WIDTH)
                    ?.let { return it }
            }
            return parentPrimaryImageItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.PRIMARY, parentPrimaryImageTag, POSTER_WIDTH)
            }
        }

        private fun BaseItemDto.backdropImageUrl(): String? {
            imageUrls
                .imageUrl(id, ImageKind.BACKDROP, backdropImageTags?.firstOrNull(), BACKDROP_WIDTH)
                ?.let { return it }
            return parentBackdropItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.BACKDROP, parentBackdropImageTags?.firstOrNull(), BACKDROP_WIDTH)
            }
        }

        private fun BaseItemDto.thumbImageUrl(): String? {
            imageUrls
                .imageUrl(id, ImageKind.THUMB, imageTags?.get(ImageType.THUMB), THUMB_WIDTH)
                ?.let { return it }
            seriesId?.let { series ->
                imageUrls.imageUrl(series, ImageKind.THUMB, seriesThumbImageTag, THUMB_WIDTH)?.let { return it }
            }
            return parentThumbItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.THUMB, parentThumbImageTag, THUMB_WIDTH)
            }
        }

        private fun BaseItemDto.logoImageUrl(): String? {
            imageUrls.imageUrl(id, ImageKind.LOGO, imageTags?.get(ImageType.LOGO), THUMB_WIDTH)?.let { return it }
            return parentLogoItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.LOGO, parentLogoImageTag, THUMB_WIDTH)
            }
        }

        private companion object {
            const val POSTER_WIDTH = ImageUrlFactory.POSTER_MAX_WIDTH
            const val THUMB_WIDTH = ImageUrlFactory.THUMB_MAX_WIDTH
            const val BACKDROP_WIDTH = ImageUrlFactory.BACKDROP_MAX_WIDTH
        }
    }

private fun BaseItemKind.toItemType(): ItemType =
    when (this) {
        BaseItemKind.MOVIE -> ItemType.MOVIE
        BaseItemKind.SERIES -> ItemType.SERIES
        BaseItemKind.SEASON -> ItemType.SEASON
        BaseItemKind.EPISODE -> ItemType.EPISODE
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> ItemType.COLLECTION_FOLDER
        BaseItemKind.FOLDER -> ItemType.FOLDER
        else -> ItemType.UNKNOWN
    }

private fun CollectionType?.toCollectionKind(): CollectionKind =
    when (this) {
        CollectionType.MOVIES -> CollectionKind.MOVIES
        CollectionType.TVSHOWS -> CollectionKind.TVSHOWS
        else -> CollectionKind.OTHER
    }

private fun UserItemDataDto?.toDomain(): UserData =
    if (this == null) {
        UserData()
    } else {
        UserData(
            played = played,
            isFavorite = isFavorite,
            playbackPositionTicks = playbackPositionTicks,
            playedPercentage = playedPercentage,
            playCount = playCount,
            lastPlayedDate = lastPlayedDate?.toInstant(ZoneOffset.UTC),
        )
    }
