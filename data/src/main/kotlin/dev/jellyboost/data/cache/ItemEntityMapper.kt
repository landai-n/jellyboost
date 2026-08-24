package dev.jellyboost.data.cache

import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LibraryViewEntity
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.toSdkInstant
import dev.jellyboost.data.mapper.ArtworkRequestWidths
import dev.jellyboost.data.mapper.ImageKind
import dev.jellyboost.data.mapper.ImageUrlFactory
import dev.jellyboost.data.mapper.ItemMapper
import dev.jellyboost.data.mapper.toItemType
import dev.jellyboost.data.userdata.toDomain
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two rules shape this class:
 *
 * 1. **The blob is the source of truth for reading** — deserialised and run through the same
 *    [ItemMapper] the online path uses, so a cached item and a fetched one are indistinguishable.
 * 2. **The columns are for querying only.** Nothing reconstructs an item from them.
 *
 * An undecodable blob is treated as absent rather than crashing the caller — see [toDomainOrNull].
 */
@Singleton
class ItemEntityMapper
    @Inject
    internal constructor(
        private val itemMapper: ItemMapper,
        private val imageUrls: ImageUrlFactory,
        private val widths: ArtworkRequestWidths = ArtworkRequestWidths.Default,
    ) {
        /**
         * @param source browse-cache callers must go through [BrowseCacheWriter], which enforces
         *   that a download is never demoted.
         * @param cachedAt also the "recently downloaded" ordering key.
         */
        fun toEntity(
            dto: BaseItemDto,
            source: ItemSource,
            cachedAt: Instant,
        ): ItemEntity =
            ItemEntity(
                id = dto.id,
                name = dto.name.orEmpty(),
                // Fall back to the display name: some servers send no explicit sort name.
                sortName = dto.sortName?.takeIf { it.isNotBlank() } ?: dto.name.orEmpty(),
                type = dto.type.toItemType(),
                source = source,
                cachedAt = cachedAt,
                productionYear = dto.productionYear,
                premiereDate = dto.premiereDate?.toSdkInstant(),
                communityRating = dto.communityRating,
                officialRating = dto.officialRating,
                runTimeTicks = dto.runTimeTicks,
                indexNumber = dto.indexNumber,
                parentIndexNumber = dto.parentIndexNumber,
                parentId = dto.parentId,
                seriesId = dto.seriesId,
                seriesName = dto.seriesName,
                seasonId = dto.seasonId,
                genres = dto.genres.orEmpty(),
                primaryImageTag = dto.imageTags?.get(ImageType.PRIMARY),
                backdropImageTag = dto.backdropImageTags?.firstOrNull(),
                thumbImageTag = dto.imageTags?.get(ImageType.THUMB),
                logoImageTag = dto.imageTags?.get(ImageType.LOGO),
                primaryImageAspectRatio = dto.primaryImageAspectRatio,
                albumId = dto.albumId,
                albumArtistId = dto.albumArtists?.firstOrNull()?.id,
                dto = json.encodeToString(BaseItemDto.serializer(), dto),
            )

        /**
         * @param userData offline this is *authoritative* over the blob, which carries only what the
         *   server said when the item was cached.
         */
        fun toDomainOrNull(
            entity: ItemEntity,
            userData: UserDataEntity? = null,
        ): JellyfinItem? {
            val dto = toDtoOrNull(entity) ?: return null
            val item = itemMapper.toDomain(dto)
            return if (userData == null) item else item.copy(userData = userData.toDomain())
        }

        /**
         * Almost everything wants [toDomainOrNull]: `BaseItemDto` does not cross a repository
         * boundary. The download pipeline is the one exception — its file plan needs `mediaSources`,
         * `mediaStreams` and `trickplay`, which `JellyfinItem` deliberately omits.
         */
        fun toDtoOrNull(entity: ItemEntity): BaseItemDto? =
            try {
                json.decodeFromString(BaseItemDto.serializer(), entity.dto)
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Exception,
            ) {
                Timber.w(error, "Unreadable cached item %s; treating it as not cached", entity.id)
                null
            }

        /**
         * Fallback for the offline *Latest* grouping when a series' own cached row is missing (the
         * pipeline's parent fetch is best effort). Artwork comes from the episode's
         * `seriesPrimaryImageTag`/`seriesThumbImageTag` — the *series'* images, not an episode still.
         */
        @Suppress(
            "ReturnCount",
        )
        fun toSeriesCardOrNull(entity: ItemEntity): JellyfinItem? {
            val dto = toDtoOrNull(entity) ?: return null
            val seriesId = dto.seriesId ?: return null
            val name = dto.seriesName?.takeIf { it.isNotBlank() } ?: return null
            return JellyfinItem(
                id = seriesId.toString(),
                name = name,
                type = ItemType.SERIES,
                primaryImageUrl =
                    imageUrls.imageUrl(
                        seriesId,
                        ImageKind.PRIMARY,
                        dto.seriesPrimaryImageTag,
                        widths.poster,
                    ),
                thumbImageUrl =
                    imageUrls.imageUrl(
                        seriesId,
                        ImageKind.THUMB,
                        dto.seriesThumbImageTag,
                        widths.thumb,
                    ),
            )
        }

        /** Maps a whole page, dropping rows whose blob is unreadable. */
        fun toDomain(
            entities: List<ItemEntity>,
            userData: Map<UUID, UserDataEntity> = emptyMap(),
        ): List<JellyfinItem> = entities.mapNotNull { toDomainOrNull(it, userData[it.id]) }

        // ---- library views --------------------------------------------------------------------

        /** `null` for an unsupported library kind — the same filter the online path applies. */
        fun toEntity(
            dto: BaseItemDto,
            sortIndex: Int,
            cachedAt: Instant,
        ): LibraryViewEntity? {
            val view = itemMapper.toLibraryView(dto) ?: return null
            return LibraryViewEntity(
                id = dto.id,
                name = view.name,
                collectionType = view.collectionType.name,
                sortIndex = sortIndex,
                cachedAt = cachedAt,
                primaryImageTag = dto.imageTags?.get(ImageType.PRIMARY),
                thumbImageTag = dto.imageTags?.get(ImageType.THUMB),
            )
        }

        /**
         * Image URLs are re-derived from the stored tags, never stored whole: the base URL changes
         * whenever the reachability probe rotates to another server address.
         *
         * [LibraryView.itemCount] stays `null` — the offline cache holds downloads, not the library,
         * so it could not answer honestly. Tiles hide the line instead.
         */
        fun toDomain(entity: LibraryViewEntity): LibraryView =
            LibraryView(
                id = entity.id.toString(),
                name = entity.name,
                collectionType =
                    CollectionKind.entries.firstOrNull { it.name == entity.collectionType }
                        ?: CollectionKind.OTHER,
                primaryImageUrl =
                    imageUrls.imageUrl(
                        entity.id,
                        ImageKind.PRIMARY,
                        entity.primaryImageTag,
                        widths.poster,
                    ),
                thumbImageUrl =
                    imageUrls.imageUrl(
                        entity.id,
                        ImageKind.THUMB,
                        entity.thumbImageTag,
                        widths.thumb,
                    ),
            )

        private companion object {
            /**
             * `ignoreUnknownKeys` lets a blob from an older build survive an SDK field rename;
             * `encodeDefaults = false` keeps it to what the server sent (`BaseItemDto` has 100+).
             */
            val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                }
        }
    }
