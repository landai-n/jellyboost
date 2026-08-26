package dev.jellyboost.data.mapper

import dev.jellyboost.core.common.model.ArtistRef
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.Person
import dev.jellyboost.core.common.model.PersonKind
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.network.toSdkInstant
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.UserItemDataDto
import javax.inject.Inject
import javax.inject.Singleton
import org.jellyfin.sdk.model.api.PersonKind as SdkPersonKind

/**
 * The boundary that keeps a DTO from crossing downstream of the repositories. Image URLs follow
 * jellyfin-web's artwork fallback chain — own image → series image → parent image — so an episode
 * with no artwork of its own does not degrade into a placeholder.
 *
 * @param widths defaulted so unit tests can build a mapper without a display; Hilt supplies the real
 *   one.
 */
@Singleton
internal class ItemMapper
    @Inject
    constructor(
        private val imageUrls: ImageUrlFactory,
        private val widths: ArtworkRequestWidths = ArtworkRequestWidths.Default,
    ) {
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
                // Detail-only fields: a lean list request leaves them null/empty, which is already
                // the domain default.
                taglines = dto.taglines.orEmpty(),
                childCount = dto.childCount,
                premiereDate = dto.premiereDate?.toSdkInstant(),
                studios = dto.studios.orEmpty().mapNotNull { it.name },
                people = dto.people.orEmpty().map { it.toDomain() },
                sizeBytes = dto.mediaSources?.firstOrNull()?.size,
                userData = dto.userData.toDomain(),
                album = dto.album,
                albumId = dto.albumId?.toString(),
                albumArtist = dto.albumArtists?.firstOrNull()?.name,
                artists = dto.artists.orEmpty(),
                // An album carries no `artistItems` of its own, hence the `albumArtists` fallback.
                artistRefs = (dto.artistItems?.takeIf { it.isNotEmpty() } ?: dto.albumArtists).toArtistRefs(),
                // A `PlaybackInfo`-shaped response leaves this null and names the container on the
                // media source instead.
                container = dto.container ?: dto.mediaSources?.firstOrNull()?.container,
            )

        /** Preserves server order — the rows are already sorted server-side. */
        fun toDomain(dtos: List<BaseItemDto>): List<JellyfinItem> = dtos.map(::toDomain)

        /**
         * `null` for libraries outside [CollectionKind.SUPPORTED], so callers can `mapNotNull`.
         *
         * [LibraryView.itemCount] is left unset: `getUserViews` only carries `ChildCount`, which
         * counts a collection folder's direct media folders, not its titles. The real number comes
         * from `OnlineJellyfinRepository.getUserViews`' recursive query.
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

        fun toLibraryViews(dtos: List<BaseItemDto>): List<LibraryView> = dtos.mapNotNull(::toLibraryView)

        // ---- people ---------------------------------------------------------------------------

        /** A member, not a file-level function: a headshot needs the same [ImageUrlFactory]. */
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

/** The one folding of `BaseItemKind`: the Room cache mapper and the download row must agree on it. */
fun BaseItemKind.toItemType(): ItemType =
    when (this) {
        BaseItemKind.MOVIE -> ItemType.MOVIE
        BaseItemKind.SERIES -> ItemType.SERIES
        BaseItemKind.SEASON -> ItemType.SEASON
        BaseItemKind.EPISODE -> ItemType.EPISODE
        BaseItemKind.AUDIO -> ItemType.AUDIO
        BaseItemKind.MUSIC_ALBUM -> ItemType.MUSIC_ALBUM
        BaseItemKind.MUSIC_ARTIST -> ItemType.MUSIC_ARTIST
        BaseItemKind.PLAYLIST -> ItemType.PLAYLIST
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> ItemType.COLLECTION_FOLDER
        BaseItemKind.FOLDER -> ItemType.FOLDER
        // AUDIO_BOOK stays UNKNOWN: audiobooks are out of scope.
        else -> ItemType.UNKNOWN
    }

private fun List<NameGuidPair>?.toArtistRefs(): List<ArtistRef> =
    this.orEmpty().mapNotNull { pair ->
        val name = pair.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ArtistRef(id = pair.id.toString(), name = name)
    }

private fun SdkPersonKind?.toPersonKind(): PersonKind =
    when (this) {
        SdkPersonKind.ACTOR -> PersonKind.ACTOR
        SdkPersonKind.DIRECTOR -> PersonKind.DIRECTOR
        SdkPersonKind.WRITER -> PersonKind.WRITER
        SdkPersonKind.PRODUCER -> PersonKind.PRODUCER
        SdkPersonKind.GUEST_STAR -> PersonKind.GUEST_STAR
        else -> PersonKind.OTHER
    }

/** Internal so it is directly unit-testable, matching [toItemType]. */
internal fun CollectionType?.toCollectionKind(): CollectionKind =
    when (this) {
        CollectionType.MOVIES -> CollectionKind.MOVIES
        CollectionType.TVSHOWS -> CollectionKind.TVSHOWS
        CollectionType.MUSIC -> CollectionKind.MUSIC
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
