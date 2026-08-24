package dev.jellyboost.data.paging

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppErrorException
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ItemPagingSource]'s page arithmetic.
 *
 * The requirement — a >500-item library scrolling cleanly with one request per page — lives or
 * dies on these offsets, so every boundary (first page, middle page, last short page, empty
 * library) is pinned here rather than left to a device scroll.
 *
 * The total-record-count block pins the other half of that promise: the header's "N items" costs
 * exactly one server-side count, on the source's first load, and never one per page.
 */
class ItemPagingSourceTest {
    private val requestedRanges = mutableListOf<Pair<Int, Int>>()

    /** Whether each load asked its source for the total record count, in load order. */
    private val totalCountRequests = mutableListOf<Boolean>()

    /** Every total the source reported to its owner. */
    private val reportedTotals = mutableListOf<Int>()

    /** A 520-item library: 10 full pages of 50 plus a 20-item tail. */
    private val library = List(TOTAL_ITEMS) { index -> item(index) }

    private fun source(
        pageSize: Int = PAGE_SIZE,
        items: List<JellyfinItem> = library,
    ) = ItemPagingSource(
        pageSize = pageSize,
        onTotalCount = { reportedTotals += it },
    ) { startIndex, limit, withTotalCount ->
        requestedRanges += startIndex to limit
        totalCountRequests += withTotalCount
        AppResult.Success(
            ItemPage(
                items = items.drop(startIndex).take(limit),
                // What a server that was asked to count answers; `null` when it was not asked.
                totalCount = items.size.takeIf { withTotalCount },
            ),
        )
    }

    // ---- first page -------------------------------------------------------------------------

    @Test
    fun `the first load starts at offset zero and has no previous page`() =
        runTest {
            val result = source().load(refresh(key = null))

            val page = result.shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
            page.data.map { it.name } shouldContainExactly (0 until PAGE_SIZE).map { "Item $it" }
            page.prevKey.shouldBeNull()
            page.nextKey shouldBe PAGE_SIZE
            requestedRanges shouldContainExactly listOf(0 to PAGE_SIZE)
        }

    @Test
    fun `one load makes exactly one request`() =
        runTest {
            source().load(refresh(key = null))

            requestedRanges.size shouldBe 1
        }

    // ---- the total record count ---------------------------------------------------------------

    @Test
    fun `the first load asks for the total and reports it`() =
        runTest {
            source().load(refresh(key = null))

            totalCountRequests shouldContainExactly listOf(true)
            reportedTotals shouldContainExactly listOf(TOTAL_ITEMS)
        }

    @Test
    fun `an anchored refresh still asks for the total, since it is the source's first load`() =
        runTest {
            source().load(refresh(key = 100))

            totalCountRequests shouldContainExactly listOf(true)
            reportedTotals shouldContainExactly listOf(TOTAL_ITEMS)
        }

    @Test
    fun `appending a page never asks the server to count`() =
        runTest {
            source().load(append(key = PAGE_SIZE))

            totalCountRequests shouldContainExactly listOf(false)
            reportedTotals.shouldBeEmpty()
        }

    @Test
    fun `walking the whole library costs exactly one count, on the first page`() =
        runTest {
            val source = source()
            var key: Int? = null

            do {
                val page =
                    source
                        .load(if (key == null) refresh(null) else append(key))
                        .shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
                key = page.nextKey
            } while (key != null)

            totalCountRequests.count { it } shouldBe 1
            reportedTotals shouldContainExactly listOf(TOTAL_ITEMS)
        }

    @Test
    fun `a source whose loader reports no total says nothing`() =
        runTest {
            val countless =
                ItemPagingSource(
                    pageSize = PAGE_SIZE,
                    onTotalCount = { reportedTotals += it },
                ) { startIndex, limit, _ ->
                    AppResult.Success(ItemPage(items = library.drop(startIndex).take(limit)))
                }

            countless.load(refresh(key = null))

            reportedTotals.shouldBeEmpty()
        }

    // ---- middle page ------------------------------------------------------------------------

    @Test
    fun `a middle page steps the keys by exactly one page in both directions`() =
        runTest {
            val result = source().load(append(key = 100))

            val page = result.shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
            page.data.first().name shouldBe "Item 100"
            page.data.last().name shouldBe "Item 149"
            page.prevKey shouldBe 50
            page.nextKey shouldBe 150
            requestedRanges shouldContainExactly listOf(100 to PAGE_SIZE)
        }

    @Test
    fun `paging through the whole library covers every item exactly once`() =
        runTest {
            val source = source()
            val seen = mutableListOf<JellyfinItem>()
            var key: Int? = null

            do {
                val page =
                    source
                        .load(if (key == null) refresh(null) else append(key))
                        .shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
                seen += page.data
                key = page.nextKey
            } while (key != null)

            seen.map { it.id } shouldContainExactly library.map { it.id }
            // 520 items at 50 per page = 10 full pages + a short one; not one request more.
            requestedRanges.size shouldBe 11
        }

    // ---- last page --------------------------------------------------------------------------

    @Test
    fun `a short last page ends the list`() =
        runTest {
            val result = source().load(append(key = 500))

            val page = result.shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
            page.data.size shouldBe TOTAL_ITEMS - 500
            page.prevKey shouldBe 450
            page.nextKey.shouldBeNull()
        }

    @Test
    fun `an exactly-full last page ends the list on the following empty one`() =
        runTest {
            val exactlyTwoPages = library.take(PAGE_SIZE * 2)
            val source = source(items = exactlyTwoPages)

            val secondPage =
                source
                    .load(append(key = PAGE_SIZE))
                    .shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
            // A full page cannot be known to be the last, so paging asks once more…
            secondPage.nextKey shouldBe PAGE_SIZE * 2

            val trailing =
                source
                    .load(append(key = PAGE_SIZE * 2))
                    .shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
            trailing.data.shouldContainExactly(emptyList())
            trailing.nextKey.shouldBeNull()
        }

    @Test
    fun `an empty library yields a single empty page`() =
        runTest {
            val result = source(items = emptyList()).load(refresh(key = null))

            val page = result.shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
            page.data.shouldContainExactly(emptyList())
            page.prevKey.shouldBeNull()
            page.nextKey.shouldBeNull()
        }

    @Test
    fun `the previous key never goes below zero`() =
        runTest {
            // A load size larger than the page size can leave a key that is not a page multiple.
            val result = source(pageSize = PAGE_SIZE).load(append(key = 10))

            val page = result.shouldBeInstanceOf<PagingSource.LoadResult.Page<Int, JellyfinItem>>()
            page.prevKey shouldBe 0
        }

    // ---- error propagation ------------------------------------------------------------------

    @Test
    fun `a repository failure becomes a paging error carrying the domain error`() =
        runTest {
            val failing =
                ItemPagingSource(pageSize = PAGE_SIZE) { _, _, _ ->
                    AppResult.Failure(AppError.Network())
                }

            val result = failing.load(refresh(key = null))

            val error = result.shouldBeInstanceOf<PagingSource.LoadResult.Error<Int, JellyfinItem>>()
            val wrapped = error.throwable.shouldBeInstanceOf<AppErrorException>()
            wrapped.error.shouldBeInstanceOf<AppError.Network>()
        }

    @Test
    fun `an append failure is reported without losing the requested offset`() =
        runTest {
            var requestedStart = -1
            val failing =
                ItemPagingSource(pageSize = PAGE_SIZE) { startIndex, _, _ ->
                    requestedStart = startIndex
                    AppResult.Failure(AppError.Server(statusCode = 503))
                }

            failing
                .load(append(key = 150))
                .shouldBeInstanceOf<PagingSource.LoadResult.Error<Int, JellyfinItem>>()

            requestedStart shouldBe 150
        }

    // ---- refresh key ------------------------------------------------------------------------

    @Test
    fun `refreshing resumes at the page the user is looking at`() =
        runTest {
            val anchoredInThirdPage =
                pagingState(
                    anchorPosition = 120,
                    page =
                        PagingSource.LoadResult.Page(
                            data = library.drop(100).take(PAGE_SIZE),
                            prevKey = 50,
                            nextKey = 150,
                        ),
                )

            source().getRefreshKey(anchoredInThirdPage) shouldBe 100
        }

    @Test
    fun `refreshing on the first page resumes at the top`() =
        runTest {
            val anchoredInFirstPage =
                pagingState(
                    anchorPosition = 3,
                    page =
                        PagingSource.LoadResult.Page(
                            data = library.take(PAGE_SIZE),
                            prevKey = null,
                            nextKey = PAGE_SIZE,
                        ),
                )

            source().getRefreshKey(anchoredInFirstPage) shouldBe 0
        }

    @Test
    fun `refreshing without an anchor starts from the beginning`() =
        runTest {
            val untouched =
                PagingState(
                    pages = emptyList<PagingSource.LoadResult.Page<Int, JellyfinItem>>(),
                    anchorPosition = null,
                    config = PagingConfig(pageSize = PAGE_SIZE),
                    leadingPlaceholderCount = 0,
                )

            source().getRefreshKey(untouched).shouldBeNull()
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun refresh(key: Int?) =
        PagingSource.LoadParams.Refresh(key = key, loadSize = PAGE_SIZE, placeholdersEnabled = false)

    private fun append(key: Int) =
        PagingSource.LoadParams.Append(key = key, loadSize = PAGE_SIZE, placeholdersEnabled = false)

    private fun pagingState(
        anchorPosition: Int,
        page: PagingSource.LoadResult.Page<Int, JellyfinItem>,
    ) = PagingState(
        pages = listOf(page),
        anchorPosition = anchorPosition,
        config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
        leadingPlaceholderCount = 0,
    )

    private fun item(index: Int) = JellyfinItem(id = "item-$index", name = "Item $index", type = ItemType.MOVIE)

    private companion object {
        const val PAGE_SIZE = 50
        const val TOTAL_ITEMS = 520
    }
}
