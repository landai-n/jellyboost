package dev.jellyboost.data.mapper

import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.Person
import dev.jellyboost.core.common.model.PersonKind
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.data.toSdkInstant
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserItemDataDto
import javax.inject.Inject
import javax.inject.Singleton
import org.jellyfin.sdk.model.api.PersonKind as SdkPersonKind

/**
 * Turns the SDK's `BaseItemDto` into the domain models the rest of the app uses.
 *
 * This is the boundary the plan's hard rule protects: nothing downstream of the repositories ever
 * sees a DTO (docs/PLAN.md, "Data layer"). Image URLs are resolved here, following the same
 * artwork fallback chain jellyfin-web uses (own image → series image → parent image), so rows do
 * not degrade into placeholders for episodes that carry no artwork of their own.
 *
 * @param widths the pixel widths artwork is requested at, resolved from the device's display
 *   density. Defaulted so unit tests can build a mapper without a display; Hilt always supplies the
 *   real one (`DataModule.provideArtworkRequestWidths`).
 */
@Singleton
class ItemMapper
    @Inject
    constructor(
        private val imageUrls: ImageUrlFactory,
        private val widths: ArtworkRequestWidths = ArtworkRequestWidths.Default,
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
                // Detail-only fields (M4). A lean list request leaves them null/empty, which maps
                // straight onto the domain defaults — no branching needed here.
                taglines = dto.taglines.orEmpty(),
                childCount = dto.childCount,
                premiereDate = dto.premiereDate?.toSdkInstant(),
                studios = dto.studios.orEmpty().mapNotNull { it.name },
                people = dto.people.orEmpty().map { it.toDomain() },
                sizeBytes = dto.mediaSources?.firstOrNull()?.size,
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

        // ---- people (M4) --------------------------------------------------------------------

        /**
         * Maps one credit. Kept here rather than as a file-level function because a person's
         * headshot needs the same [ImageUrlFactory] the artwork chain uses.
         */
        private fun BaseItemPerson.toDomain(): Person =
            Person(
                id = id.toString(),
                name = name.orEmpty(),
                role = role?.takeIf { it.isNotBlank() },
                kind = type.toPersonKind(),
                primaryImageUrl = imageUrls.imageUrl(id, ImageKind.PRIMARY, primaryImageTag, widths.poster),
            )

        // ---- artwork ------------------------------------------------------------------------

        private fun BaseItemDto.primaryImageUrl(): String? {
            imageUrls
                .imageUrl(id, ImageKind.PRIMARY, imageTags?.get(ImageType.PRIMARY), widths.poster)
                ?.let { return it }
            seriesId?.let { series ->
                imageUrls
                    .imageUrl(series, ImageKind.PRIMARY, seriesPrimaryImageTag, widths.poster)
                    ?.let { return it }
            }
            return parentPrimaryImageItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.PRIMARY, parentPrimaryImageTag, widths.poster)
            }
        }

        private fun BaseItemDto.backdropImageUrl(): String? {
            imageUrls
                .imageUrl(id, ImageKind.BACKDROP, backdropImageTags?.firstOrNull(), widths.backdrop)
                ?.let { return it }
            return parentBackdropItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.BACKDROP, parentBackdropImageTags?.firstOrNull(), widths.backdrop)
            }
        }

        private fun BaseItemDto.thumbImageUrl(): String? {
            imageUrls
                .imageUrl(id, ImageKind.THUMB, imageTags?.get(ImageType.THUMB), widths.thumb)
                ?.let { return it }
            seriesId?.let { series ->
                imageUrls.imageUrl(series, ImageKind.THUMB, seriesThumbImageTag, widths.thumb)?.let { return it }
            }
            return parentThumbItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.THUMB, parentThumbImageTag, widths.thumb)
            }
        }

        private fun BaseItemDto.logoImageUrl(): String? {
            imageUrls.imageUrl(id, ImageKind.LOGO, imageTags?.get(ImageType.LOGO), widths.thumb)?.let { return it }
            return parentLogoItemId?.let { parent ->
                imageUrls.imageUrl(parent, ImageKind.LOGO, parentLogoImageTag, widths.thumb)
            }
        }
    }

/** Internal so the Room cache mapper folds `BaseItemKind` exactly the same way (M6). */
internal fun BaseItemKind.toItemType(): ItemType =
    when (this) {
        BaseItemKind.MOVIE -> ItemType.MOVIE
        BaseItemKind.SERIES -> ItemType.SERIES
        BaseItemKind.SEASON -> ItemType.SEASON
        BaseItemKind.EPISODE -> ItemType.EPISODE
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> ItemType.COLLECTION_FOLDER
        BaseItemKind.FOLDER -> ItemType.FOLDER
        else -> ItemType.UNKNOWN
    }

private fun SdkPersonKind?.toPersonKind(): PersonKind =
    when (this) {
        SdkPersonKind.ACTOR -> PersonKind.ACTOR
        SdkPersonKind.DIRECTOR -> PersonKind.DIRECTOR
        SdkPersonKind.WRITER -> PersonKind.WRITER
        SdkPersonKind.PRODUCER -> PersonKind.PRODUCER
        SdkPersonKind.GUEST_STAR -> PersonKind.GUEST_STAR
        // Composer, lyricist, penciller … — real credit kinds, none of them in v1's scope.
        else -> PersonKind.OTHER
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
            lastPlayedDate = lastPlayedDate?.toSdkInstant(),
        )
    }
