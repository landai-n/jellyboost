package dev.jellyfinnative.data

import androidx.paging.PagingData
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.FilterFacets
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.LibraryView
import kotlinx.coroutines.flow.Flow

/**
 * The one media-browsing surface the UI talks to.
 *
 * Both the online (SDK) and offline (Room) implementations satisfy this contract and return the
 * exact same domain models, which is what lets a single set of screens serve streamed and
 * downloaded media (docs/PLAN.md, "Data layer"). `BaseItemDto` and `ItemEntity` never appear here.
 *
 * M2 declares only the home-screen surface; the paged library grid, search and item detail extend
 * this interface in M3 and M4.
 */
interface JellyfinRepository {
    /**
     * The current user's libraries, filtered to the kinds v1 supports (movies and TV shows).
     *
     * Backs the home screen's *My Media* row and the Libraries tab.
     */
    suspend fun getUserViews(): AppResult<List<LibraryView>>

    /**
     * Partially-watched items, newest first — the *Continue watching* row.
     *
     * @param limit maximum number of items; matches jellyfin-web's home row size by default.
     */
    suspend fun getResumeItems(limit: Int = DEFAULT_RESUME_LIMIT): AppResult<List<JellyfinItem>>

    /**
     * The next unwatched episode of each series in progress — the *Next up* row.
     *
     * @param limit maximum number of items.
     */
    suspend fun getNextUp(limit: Int = DEFAULT_NEXT_UP_LIMIT): AppResult<List<JellyfinItem>>

    /**
     * Recently added items in one library — the *Latest &lt;library&gt;* rows, one per library.
     *
     * @param parentId id of the [LibraryView] to pull from.
     * @param limit maximum number of items.
     */
    suspend fun getLatestMedia(
        parentId: String,
        limit: Int = DEFAULT_LATEST_LIMIT,
    ): AppResult<List<JellyfinItem>>

    companion object {
        /** Row size jellyfin-web uses for *Continue watching* (DECISIONS.md 2026-07-28). */
        const val DEFAULT_RESUME_LIMIT = 12

        /** Row size jellyfin-web uses for *Next up* (DECISIONS.md 2026-07-28). */
        const val DEFAULT_NEXT_UP_LIMIT = 24

        /** Row size jellyfin-web uses for the per-library *Latest* rows. */
        const val DEFAULT_LATEST_LIMIT = 16
    }

    // ---- M3 — library & search ---------------------------------------------------------------

    /**
     * A paged stream of the items matching [query] — the library grid's source.
     *
     * The paging configuration (page size, prefetch distance, placeholders) belongs to the
     * implementation, not to the caller: the offline implementation pages Room behind the same
     * `Pager` in M6 and must be free to configure it differently.
     *
     * @param query everything the grid can vary: library, item types, sort and filters.
     *   [ItemQuery.startIndex] and [ItemQuery.limit] are overridden per page and can be left at
     *   their defaults.
     */
    fun getItemsPaged(query: ItemQuery): Flow<PagingData<JellyfinItem>>

    /**
     * One unpaged page of items — the search screen's source.
     *
     * Search deliberately does not page: a single capped request (50 results, split into type
     * sections) is what jellyfin-web's search does, and it keeps the debounced typing path to one
     * in-flight request.
     */
    suspend fun getItems(query: ItemQuery): AppResult<List<JellyfinItem>>

    /**
     * The values a library can be filtered by — what the filter sheet lists.
     *
     * @param parentId library to describe, or `null` for the whole user root.
     * @param itemTypes the item kinds the grid is showing; the facets are computed over those only.
     */
    suspend fun getFilterFacets(
        parentId: String?,
        itemTypes: List<ItemType>,
    ): AppResult<FilterFacets>
}
