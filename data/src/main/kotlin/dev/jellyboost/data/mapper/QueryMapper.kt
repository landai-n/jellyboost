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
 * Kept out of the repository so the exact wire shape of a library/search request is unit-testable.
 * Deliberately lean — the card-sized field set; full field sets belong to detail and playback.
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
        // Off unless asked: a per-page total costs the server an extra COUNT, and placeholders are
        // off in the grid's PagingConfig. Only the grid's first page opts in, for its "N items".
        enableTotalRecordCount = includeTotalCount,
    )

/** `null` for kinds the server has no name for. */
internal fun ItemType.toBaseItemKind(): BaseItemKind? =
    when (this) {
        ItemType.MOVIE -> BaseItemKind.MOVIE
        ItemType.SERIES -> BaseItemKind.SERIES
        ItemType.SEASON -> BaseItemKind.SEASON
        ItemType.EPISODE -> BaseItemKind.EPISODE
        ItemType.AUDIO -> BaseItemKind.AUDIO
        ItemType.MUSIC_ALBUM -> BaseItemKind.MUSIC_ALBUM
        ItemType.MUSIC_ARTIST -> BaseItemKind.MUSIC_ARTIST
        ItemType.PLAYLIST -> BaseItemKind.PLAYLIST
        ItemType.COLLECTION_FOLDER -> BaseItemKind.COLLECTION_FOLDER
        ItemType.FOLDER -> BaseItemKind.FOLDER
        ItemType.UNKNOWN -> null
    }

/**
 * `SORT_NAME` rather than `NAME` deliberately: the server-side sort name strips leading articles
 * ("The Expanse" sorts under E), exactly as jellyfin-web sorts a library.
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

internal fun SortOrder.toSdk(): SdkSortOrder =
    when (this) {
        SortOrder.ASCENDING -> SdkSortOrder.ASCENDING
        SortOrder.DESCENDING -> SdkSortOrder.DESCENDING
    }
