package dev.jellyfinnative.data.cache

import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.database.entities.LibraryViewEntity
import dev.jellyfinnative.core.database.entities.UserDataEntity
import dev.jellyfinnative.data.mapper.ImageKind
import dev.jellyfinnative.data.mapper.ImageUrlFactory
import dev.jellyfinnative.data.mapper.ItemMapper
import dev.jellyfinnative.data.mapper.toItemType
import dev.jellyfinnative.data.toSdkInstant
import dev.jellyfinnative.data.userdata.toDomain
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `BaseItemDto` ⇄ [ItemEntity] boundary — the Room half of the offline read path.
 *
 * Two rules shape this class:
 *
 * 1. **The blob is the source of truth for reading.** An entity is turned back into a domain item
 *    by deserialising [ItemEntity.dto] and running it through the very same [ItemMapper] the online
 *    path uses. That identity is not an optimisation, it *is* the plan's mechanism for one seamless
 *    UI (docs/PLAN.md, "Data layer"): a cached item and a freshly fetched one are indistinguishable
 *    downstream, artwork fallbacks and all.
 * 2. **The columns are for querying only.** Nothing reconstructs an item from them; they exist so
 *    the offline grid can sort, the offline search can match, and the offline home rows can group
 *    by series.
 *
 * A row whose blob cannot be decoded (a JSON shape from a much older build, a truncated write) is
 * treated as absent rather than crashing the caller — see [toDomainOrNull].
 */
@Singleton
class ItemEntityMapper
    @Inject
    constructor(
        private val itemMapper: ItemMapper,
        private val imageUrls: ImageUrlFactory,
    ) {
        /**
         * Serialises [dto] into a cache row.
         *
         * @param source why the row exists; callers writing a browse cache must go through
         *   [BrowseCacheWriter], which enforces that a download is never demoted.
         * @param cachedAt the write timestamp; also the "recently downloaded" ordering key.
         */
        fun toEntity(
            dto: BaseItemDto,
            source: ItemSource,
            cachedAt: Instant,
        ): ItemEntity =
            ItemEntity(
                id = dto.id,
                name = dto.name.orEmpty(),
                // Falling back to the display name keeps the grid's ordering sane for the items a
                // server has no explicit sort name for.
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
                dto = json.encodeToString(BaseItemDto.serializer(), dto),
            )

        /**
         * Rebuilds a domain item, or returns `null` when the stored blob cannot be read.
         *
         * @param userData local playback state to overlay, if this device has any. Offline it is
         *   *authoritative*: the blob carries whatever the server said when the item was cached,
         *   which is stale the moment the user watches anything without a connection.
         */
        fun toDomainOrNull(
            entity: ItemEntity,
            userData: UserDataEntity? = null,
        ): JellyfinItem? {
            val dto =
                try {
                    json.decodeFromString(BaseItemDto.serializer(), entity.dto)
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Exception,
                ) {
                    Timber.w(error, "Unreadable cached item %s; treating it as not cached", entity.id)
                    return null
                }

            val item = itemMapper.toDomain(dto)
            return if (userData == null) item else item.copy(userData = userData.toDomain())
        }

        /** Maps a whole page, dropping rows whose blob is unreadable. */
        fun toDomain(
            entities: List<ItemEntity>,
            userData: Map<UUID, UserDataEntity> = emptyMap(),
        ): List<JellyfinItem> = entities.mapNotNull { toDomainOrNull(it, userData[it.id]) }

        // ---- library views --------------------------------------------------------------------

        /**
         * Serialises a `getUserViews` entry, or returns `null` for a library kind v1 does not
         * support — the same filter [ItemMapper.toLibraryView] applies online.
         */
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
         * Rebuilds a library card.
         *
         * Image URLs are re-derived from the stored tags rather than stored whole: the base URL
         * changes whenever the reachability probe rotates to another server address.
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
                        ImageUrlFactory.POSTER_MAX_WIDTH,
                    ),
                thumbImageUrl =
                    imageUrls.imageUrl(
                        entity.id,
                        ImageKind.THUMB,
                        entity.thumbImageTag,
                        ImageUrlFactory.THUMB_MAX_WIDTH,
                    ),
            )

        private companion object {
            /**
             * `ignoreUnknownKeys` is what lets a blob written by an older build survive an SDK
             * upgrade that renamed or dropped a field, and `encodeDefaults = false` keeps the blob
             * to the fields the server actually sent — a `BaseItemDto` has well over a hundred.
             */
            val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = false
                }
        }
    }
