package dev.jellyboost.data.mapper

import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import org.jellyfin.sdk.model.api.SortOrder as SdkSortOrder

/**
 * Translates the domain [ItemQuery] into the SDK's `getItems` parameters.
 *
 * Kept out of the repository so the exact wire shape of a library/search request is unit-testable
 * on its own: the M3 definition of done ("one request per page") is as much about *what* is asked
 * for as about how often.
 *
 * The request stays deliberately lean — the same card-sized field set the home rows use. Full
 * field sets belong to the detail and playback paths (docs/PLAN.md, "Screens" → ItemDetail).
 *
 * @param fields extra `ItemFields` the cards need (`PRIMARY_IMAGE_ASPECT_RATIO`).
 * @param imageTypes artwork kinds worth asking the server to resolve.
 */
internal fun ItemQuery.toGetItemsRequest(
    fields: List<ItemFields>,
    imageTypes: List<ImageType>,
): GetItemsRequest =
    GetItemsRequest(
        parentId = parentId?.let(UUID::fromString),
        includeItemTypes = itemTypes.mapNotNull { it.toBaseItemKind() },
        searchTerm = searchTerm?.takeIf { it.isNotBlank() },
        recursive = recursive,
        sortBy = listOf(sortBy.toSdk()),
        sortOrder = listOf(sortOrder.toSdk()),
        genres = filters.genres,
        years = filters.years,
        officialRatings = filters.officialRatings,
        isPlayed = filters.isPlayed,
        isFavorite = filters.isFavorite,
        startIndex = startIndex,
        limit = limit,
        fields = fields,
        enableImageTypes = imageTypes,
        imageTypeLimit = 1,
        enableUserData = true,
        // Off unless the caller explicitly asked: placeholders are off in the grid's PagingConfig,
        // so nothing on screen needs a per-page total — and computing it costs the server an extra
        // COUNT on every single page. The library grid's *first* page opts in, for the "N items"
        // line in its header (DECISIONS.md 2026-08-01).
        enableTotalRecordCount = includeTotalCount,
    )

/** Maps a domain item type onto its SDK kind; `null` for kinds the server has no name for. */
internal fun ItemType.toBaseItemKind(): BaseItemKind? =
    when (this) {
        ItemType.MOVIE -> BaseItemKind.MOVIE
        ItemType.SERIES -> BaseItemKind.SERIES
        ItemType.SEASON -> BaseItemKind.SEASON
        ItemType.EPISODE -> BaseItemKind.EPISODE
        ItemType.COLLECTION_FOLDER -> BaseItemKind.COLLECTION_FOLDER
        ItemType.FOLDER -> BaseItemKind.FOLDER
        ItemType.UNKNOWN -> null
    }

/**
 * Maps a domain sort key onto the SDK's.
 *
 * `SORT_NAME` rather than `NAME` deliberately: it is the server-side sort name, which strips
 * leading articles ("The Expanse" sorts under E), exactly as jellyfin-web sorts a library.
 */
internal fun SortBy.toSdk(): ItemSortBy =
    when (this) {
        SortBy.SORT_NAME -> ItemSortBy.SORT_NAME
        SortBy.DATE_CREATED -> ItemSortBy.DATE_CREATED
        SortBy.PREMIERE_DATE -> ItemSortBy.PREMIERE_DATE
        SortBy.COMMUNITY_RATING -> ItemSortBy.COMMUNITY_RATING
        SortBy.RUNTIME -> ItemSortBy.RUNTIME
        SortBy.RANDOM -> ItemSortBy.RANDOM
    }

/** Maps the domain sort direction onto the SDK's. */
internal fun SortOrder.toSdk(): SdkSortOrder =
    when (this) {
        SortOrder.ASCENDING -> SdkSortOrder.ASCENDING
        SortOrder.DESCENDING -> SdkSortOrder.DESCENDING
    }
