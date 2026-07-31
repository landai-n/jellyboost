package dev.jellyboost.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.jellyboost.core.common.AppErrorException
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * Paging 3 source over an offset/limit item query — the library grid's data spine
 * (docs/PLAN.md, "Screens" → LibraryGrid).
 *
 * **Keys are item offsets, not page numbers.** Jellyfin's `getItems` is paged by
 * `startIndex`/`limit`, so making the key *be* the `startIndex` keeps the arithmetic exact even
 * when Paging asks for a load size other than [pageSize] (which it does for the initial load
 * unless `initialLoadSize` is pinned). That exactness is what the M3 definition of done —
 * ">500-item library scrolls clean, one request per page" — actually rests on: one [load] call
 * produces exactly one server request, and no offset is ever requested twice or skipped.
 *
 * The end of the list is detected by a short page (`items.size < loadSize`) rather than by a total
 * record count, which is why the request can leave `enableTotalRecordCount` off and save the
 * server a COUNT per page.
 *
 * @param pageSize the [androidx.paging.PagingConfig] page size, used to step keys backwards and to
 *   recover a refresh key.
 * @param loadItems fetches `limit` items starting at `startIndex`.
 */
class ItemPagingSource(
    private val pageSize: Int,
    private val loadItems: suspend (startIndex: Int, limit: Int) -> AppResult<List<JellyfinItem>>,
) : PagingSource<Int, JellyfinItem>() {
    init {
        require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JellyfinItem> {
        val startIndex = params.key ?: 0
        val loadSize = params.loadSize

        return when (val result = loadItems(startIndex, loadSize)) {
            // Paging's LoadResult.Error is typed on Throwable, so the domain error travels wrapped
            // and the screen unwraps it back into the same copy its non-paged siblings show.
            is AppResult.Failure -> LoadResult.Error(AppErrorException(result.error))

            is AppResult.Success -> {
                val items = result.value
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
