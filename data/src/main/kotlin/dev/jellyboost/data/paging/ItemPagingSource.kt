package dev.jellyboost.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.jellyboost.core.common.AppErrorException
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * One page of a paged query: the items, and — when this page asked for it — how many items match
 * the query in total.
 *
 * @param totalCount `null` when the page did not ask (every page but the first), and when the
 *   source cannot answer: the offline grid pages the downloaded items out of Room and deliberately
 *   reports no total, since the number of items *on the device* is not the number of items in the
 *   library.
 */
internal data class ItemPage(
    val items: List<JellyfinItem>,
    val totalCount: Int? = null,
)

/**
 * Paging 3 source over an offset/limit item query — the library grid's data spine.
 *
 * **Keys are item offsets, not page numbers.** Jellyfin's `getItems` is paged by
 * `startIndex`/`limit`, so making the key *be* the `startIndex` keeps the arithmetic exact even
 * when Paging asks for a load size other than [pageSize] (which it does for the initial load
 * unless `initialLoadSize` is pinned). That exactness is what makes ">500-item library scrolls
 * clean, one request per page" possible: one [load] call produces exactly one server request, and
 * no offset is ever requested twice or skipped.
 *
 * The end of the list is detected by a short page (`items.size < loadSize`) rather than by a total
 * record count, so **appending** a page never asks the server to count anything. The *first* load
 * does ask, once, because the grid's header shows "N items" and no cheaper source for that number
 * exists. "First load" is expressed as `params is LoadParams.Refresh`: a paging source is created
 * fresh for every refresh and invalidated after it, so a `Refresh` is always this instance's first
 * load — including the anchored one [getRefreshKey] resumes from, whose key is not zero.
 *
 * @param pageSize the [androidx.paging.PagingConfig] page size, used to step keys backwards and to
 *   recover a refresh key.
 * @param onTotalCount called with the total whenever a load came back carrying one — at most once
 *   per source. Defaults to doing nothing, which is what every caller that has nowhere to put the
 *   number passes.
 * @param loadItems fetches `limit` items starting at `startIndex`; `withTotalCount` asks it to
 *   report [ItemPage.totalCount] as well, and is `true` only for the first load.
 */
internal class ItemPagingSource(
    private val pageSize: Int,
    private val onTotalCount: (Int) -> Unit = {},
    private val loadItems: suspend (startIndex: Int, limit: Int, withTotalCount: Boolean) -> AppResult<ItemPage>,
) : PagingSource<Int, JellyfinItem>() {
    init {
        require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JellyfinItem> {
        val startIndex = params.key ?: 0
        val loadSize = params.loadSize

        return when (val result = loadItems(startIndex, loadSize, params is LoadParams.Refresh)) {
            // Paging's LoadResult.Error is typed on Throwable, so the domain error travels wrapped
            // and the screen unwraps it back into the same copy its non-paged siblings show.
            is AppResult.Failure -> LoadResult.Error(AppErrorException(result.error))

            is AppResult.Success -> {
                val items = result.value.items
                result.value.totalCount?.let(onTotalCount)
                LoadResult.Page(
                    data = items,
                    prevKey = if (startIndex == 0) null else (startIndex - pageSize).coerceAtLeast(0),
                    // A page shorter than asked for is the last one; so is an empty one.
                    nextKey = if (items.size < loadSize) null else startIndex + items.size,
                )
            }
        }
    }

    /**
     * Restarts a refresh at the offset the user is currently looking at, so re-sorting or
     * invalidation does not throw them back to the top of a 500-item library.
     */
    override fun getRefreshKey(state: PagingState<Int, JellyfinItem>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(pageSize) ?: anchorPage.nextKey?.minus(pageSize)
    }
}
