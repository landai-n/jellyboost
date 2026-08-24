package dev.jellyboost.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.jellyboost.core.common.AppErrorException
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.JellyfinItem

/**
 * @param totalCount `null` when the page did not ask (every page but the first) and when the source
 *   cannot answer — the offline grid reports none, since the item count *on the device* is not the
 *   library's.
 */
internal data class ItemPage(
    val items: List<JellyfinItem>,
    val totalCount: Int? = null,
)

/**
 * **Keys are item offsets, not page numbers**, because `getItems` is paged by `startIndex`/`limit`:
 * the arithmetic then stays exact even when Paging asks for a load size other than [pageSize], so no
 * offset is ever requested twice or skipped.
 *
 * The end of the list is a short page, not a total record count, so appending never asks the server
 * to count. Only the *first* load asks, for the grid header's "N items"; "first" is
 * `params is LoadParams.Refresh`, since a source is created fresh per refresh and invalidated after
 * — including the anchored refresh [getRefreshKey] resumes from, whose key is not zero.
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
            // `LoadResult.Error` is typed on Throwable, so the domain error travels wrapped and the
            // screen unwraps it back into the copy its non-paged siblings show.
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

    /** Restarts at the offset in view, so a re-sort does not throw the user to the top. */
    override fun getRefreshKey(state: PagingState<Int, JellyfinItem>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition) ?: return null
        return anchorPage.prevKey?.plus(pageSize) ?: anchorPage.nextKey?.minus(pageSize)
    }
}
