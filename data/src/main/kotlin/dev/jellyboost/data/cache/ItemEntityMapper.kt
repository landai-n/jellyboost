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
    internal constructor(
        private val itemMapper: ItemMapper,
        private val imageUrls: ImageUrlFactory,
        private val widths: ArtworkRequestWidths = ArtworkRequestWidths.Default,
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
            val dto = toDtoOrNull(entity) ?: return null
            val item = itemMapper.toDomain(dto)
            return if (userData == null) item else item.copy(userData = userData.toDomain())
        }

        /**
         * Reads the stored blob back as the SDK type it was written from, or `null` when it cannot
         * be decoded.
         *
         * Almost everything wants [toDomainOrNull] instead — the whole point of this class is that
         * `BaseItemDto` does not cross a repository boundary. The download pipeline (M7) is the one
         * exception: its file plan is built from `mediaSources`, `mediaStreams`, `trickplay` and
         * the image tags, which are SDK-shaped details deliberately absent from `JellyfinItem`. It
         * consumes them inside `:data:downloads` and hands the UI domain models like everyone else.
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
         * Rebuilds the **series** an episode row belongs to, as a card, or `null` when the row
         * names no series (or its blob is unreadable).
         *
         * This is the fallback half of the offline *Latest* grouping. Normally the series' own
         * cached row is the card — the download pipeline caches an episode's series and season
         * alongside it — but that fetch is best effort, and a failure there must not put bare
         * episodes back on a shelf that is supposed to show one poster per show.
         *
         * Artwork comes from the episode's `seriesPrimaryImageTag`/`seriesThumbImageTag`: those are
         * the *series'* images, so the card gets the show's poster rather than an episode still
         * stretched into a poster frame. Everything else is deliberately left at its default —
         * a synthesised card carries only what an episode actually knows about its show.
         */
        @Suppress(
            // A mapper that yields `null` on any missing field; the guards name which field was missing.
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
         *
         * [LibraryView.itemCount] is deliberately left `null`: the entity has no column for it and
         * adding one would be a migration for a number the offline cache could not honestly answer
         * anyway (it holds the downloaded items, not the library). Tiles hide the line instead.
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
