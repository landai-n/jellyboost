package dev.jellyboost.data

import androidx.paging.testing.asSnapshot
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.SortBy
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.data.mapper.FakeImageUrlFactory
import dev.jellyboost.data.mapper.ItemMapper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.operations.FilterApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.QueryFiltersLegacy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.io.IOException
import java.util.UUID
import org.jellyfin.sdk.model.api.SortOrder as SdkSortOrder

/**
 * Unit tests for the M3 (library grid + search) surface of [OnlineJellyfinRepository].
 *
 * The paged tests drive the real `Pager` through `asSnapshot`, so the assertions about how many
 * server requests a scroll costs are the actual Paging behaviour rather than a restatement of the
 * `PagingConfig` — that is the M3 definition of done ("one request per page").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineJellyfinRepositoryPagingTest {
    private val apiClient = mockk<ApiClient>()
    private val itemsApi = mockk<ItemsApi>()
    private val filterApi = mockk<FilterApi>()

    private val repository =
        OnlineJellyfinRepository(
            apiClient = apiClient,
            mapper = ItemMapper(FakeImageUrlFactory()),
            browseCache = mockk(relaxed = true),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private val moviesLibraryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    /** A 520-item library, so paging has ten full pages and a short tail to walk through. */
    private val library = List(TOTAL_ITEMS) { index -> itemDto(BaseItemKind.MOVIE, "Item $index") }

    private val requests = mutableListOf<GetItemsRequest>()

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    @BeforeEach
    fun setUp() {
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.itemsApi } returns itemsApi
        every { apiClient.filterApi } returns filterApi
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ---- getItems ---------------------------------------------------------------------------

    @Test
    fun `getItems translates the domain query into one lean server request`() =
        runTest {
            val request = slot<GetItemsRequest>()
            coEvery { itemsApi.getItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.MOVIE, "Dune")))

            val result =
                repository.getItems(
                    ItemQuery(
                        parentId = moviesLibraryId.toString(),
                        itemTypes = listOf(ItemType.MOVIE),
                        sortBy = SortBy.DATE_CREATED,
                        sortOrder = SortOrder.DESCENDING,
                        filters = FilterOptions(genres = listOf("Thriller"), years = listOf(2021)),
                        startIndex = 50,
                        limit = 50,
                    ),
                )

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Dune")
            request.captured.parentId shouldBe moviesLibraryId
            request.captured.includeItemTypes shouldContainExactly listOf(BaseItemKind.MOVIE)
            request.captured.sortBy shouldContainExactly listOf(ItemSortBy.DATE_CREATED)
            request.captured.sortOrder shouldContainExactly listOf(SdkSortOrder.DESCENDING)
            request.captured.genres shouldContainExactly listOf("Thriller")
            request.captured.years shouldContainExactly listOf(2021)
            request.captured.startIndex shouldBe 50
            request.captured.limit shouldBe 50
            request.captured.imageTypeLimit shouldBe 1
            request.captured.enableTotalRecordCount shouldBe false
        }

    @Test
    fun `getItems carries a search term for the search screen`() =
        runTest {
            val request = slot<GetItemsRequest>()
            coEvery { itemsApi.getItems(capture(request)) } returns queryResponse(emptyList())

            repository.getItems(
                ItemQuery(
                    searchTerm = "dune",
                    itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE),
                    limit = 50,
                ),
            )

            request.captured.searchTerm shouldBe "dune"
            request.captured.includeItemTypes shouldContainExactly
                listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE)
            request.captured.recursive shouldBe true
            request.captured.limit shouldBe 50
        }

    @Test
    fun `getItems maps a transport failure onto Network`() =
        runTest {
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } throws IOException("socket closed")

            val result = repository.getItems(ItemQuery())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    // ---- getItemsPaged ----------------------------------------------------------------------

    @Test
    fun `the first page costs exactly one request of fifty items`() =
        runTest {
            stubPagedLibrary()

            val firstPage = repository.getItemsPaged(libraryQuery()).asSnapshot()

            firstPage.map { it.name } shouldContainExactly (0 until PAGE_SIZE).map { "Item $it" }
            requests.size shouldBe 1
            requests.single().startIndex shouldBe 0
            requests.single().limit shouldBe PAGE_SIZE
        }

    @Test
    fun `scrolling deep into a large library costs one request per page`() =
        runTest {
            stubPagedLibrary()

            val loaded =
                repository.getItemsPaged(libraryQuery()).asSnapshot {
                    // Far enough in to have crossed two page boundaries.
                    scrollTo(index = 120)
                }

            loaded.size shouldBe 3 * PAGE_SIZE
            requests.map { it.startIndex } shouldContainExactly listOf(0, PAGE_SIZE, 2 * PAGE_SIZE)
            requests.all { it.limit == PAGE_SIZE } shouldBe true
        }

    @Test
    fun `scrolling to the end stops at the short last page`() =
        runTest {
            stubPagedLibrary()

            val loaded =
                repository.getItemsPaged(libraryQuery()).asSnapshot {
                    scrollTo(index = TOTAL_ITEMS - 1)
                }

            loaded.size shouldBe TOTAL_ITEMS
            // Ten full pages plus the 20-item tail — and not one request more.
            requests.size shouldBe 11
            requests.last().startIndex shouldBe 500
        }

    @Test
    fun `every page carries the sort and filters the grid picked`() =
        runTest {
            stubPagedLibrary()

            repository
                .getItemsPaged(
                    libraryQuery().copy(
                        sortBy = SortBy.PREMIERE_DATE,
                        sortOrder = SortOrder.DESCENDING,
                        filters = FilterOptions(genres = listOf("Thriller"), isPlayed = false),
                    ),
                ).asSnapshot { scrollTo(index = 60) }

            requests.size shouldBe 2
            requests.all { it.sortBy == listOf(ItemSortBy.PREMIERE_DATE) } shouldBe true
            requests.all { it.sortOrder == listOf(SdkSortOrder.DESCENDING) } shouldBe true
            requests.all { it.genres == listOf("Thriller") } shouldBe true
            requests.all { it.isPlayed == false } shouldBe true
        }

    @Test
    fun `an empty library pages to nothing`() =
        runTest {
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns queryResponse(emptyList())

            repository.getItemsPaged(libraryQuery()).asSnapshot().shouldBeEmpty()
        }

    // ---- the total record count ---------------------------------------------------------------

    @Test
    fun `only the first page asks the server to count the library`() =
        runTest {
            stubPagedLibrary()

            repository.getItemsPaged(libraryQuery()).asSnapshot { scrollTo(index = 60) }

            requests.size shouldBe 2
            // The header's "N items" costs one COUNT for the whole scroll, not one per page
            // (DECISIONS.md 2026-08-01).
            requests.first().enableTotalRecordCount shouldBe true
            requests.drop(1).none { it.enableTotalRecordCount == true } shouldBe true
        }

    @Test
    fun `the total the first page carries reaches the caller exactly once`() =
        runTest {
            // Unlike `stubPagedLibrary`, this server reports the size of the *library* rather than
            // of the page it is answering with — which is what a real total record count is.
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } answers {
                val request = firstArg<GetItemsRequest>()
                requests += request
                val start = request.startIndex ?: 0
                val limit = request.limit ?: PAGE_SIZE
                queryResponse(library.drop(start).take(limit), totalRecordCount = TOTAL_ITEMS)
            }
            val totals = mutableListOf<Int>()

            repository
                .getItemsPaged(libraryQuery()) { totals += it }
                .asSnapshot { scrollTo(index = 60) }

            totals shouldContainExactly listOf(TOTAL_ITEMS)
        }

    // ---- getFilterFacets --------------------------------------------------------------------

    @Test
    fun `getFilterFacets asks the server for one library's facets`() =
        runTest {
            val parentId = slot<UUID>()
            val includeItemTypes = slot<Collection<BaseItemKind>>()
            coEvery {
                filterApi.getQueryFiltersLegacy(any(), capture(parentId), capture(includeItemTypes), any())
            } returns
                filtersResponse(
                    QueryFiltersLegacy(
                        genres = listOf("Drama", "Science Fiction"),
                        officialRatings = listOf("PG-13", "R"),
                        years = listOf(2016, 2021, 1999),
                    ),
                )

            val result =
                repository.getFilterFacets(
                    parentId = moviesLibraryId.toString(),
                    itemTypes = listOf(ItemType.MOVIE),
                )

            val facets = (result as AppResult.Success).value
            facets.genres shouldContainExactly listOf("Drama", "Science Fiction")
            facets.officialRatings shouldContainExactly listOf("PG-13", "R")
            // Newest first — the years a user filters by are almost always recent ones.
            facets.years shouldContainExactly listOf(2021, 2016, 1999)
            parentId.captured shouldBe moviesLibraryId
            includeItemTypes.captured shouldContainExactly listOf(BaseItemKind.MOVIE)
        }

    @Test
    fun `getFilterFacets tolerates a server with nothing to filter by`() =
        runTest {
            coEvery { filterApi.getQueryFiltersLegacy(any(), any(), any(), any()) } returns
                filtersResponse(QueryFiltersLegacy())

            val result = repository.getFilterFacets(parentId = null, itemTypes = emptyList())

            (result as AppResult.Success).value.isEmpty shouldBe true
        }

    @Test
    fun `getFilterFacets maps a server error`() =
        runTest {
            coEvery { filterApi.getQueryFiltersLegacy(any(), any(), any(), any()) } throws
                InvalidStatusException(status = 500)

            val result = repository.getFilterFacets(parentId = null, itemTypes = emptyList())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Server>()
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun libraryQuery() =
        ItemQuery(
            parentId = moviesLibraryId.toString(),
            itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES),
        )

    /** Serves [library] as pages, recording every request so the test can count them. */
    private fun stubPagedLibrary() {
        coEvery { itemsApi.getItems(any<GetItemsRequest>()) } answers {
            val request = firstArg<GetItemsRequest>()
            requests += request
            val start = request.startIndex ?: 0
            val limit = request.limit ?: PAGE_SIZE
            queryResponse(library.drop(start).take(limit))
        }
    }

    private fun queryResponse(
        items: List<BaseItemDto>,
        totalRecordCount: Int = items.size,
    ) = Response(
        content =
            BaseItemDtoQueryResult(
                items = items,
                totalRecordCount = totalRecordCount,
                startIndex = 0,
            ),
        status = 200,
        headers = emptyMap(),
    )

    private fun filtersResponse(filters: QueryFiltersLegacy) =
        Response(content = filters, status = 200, headers = emptyMap())

    private fun itemDto(
        kind: BaseItemKind,
        name: String,
    ) = BaseItemDto(id = UUID.randomUUID(), type = kind, name = name)

    private companion object {
        const val PAGE_SIZE = 50
        const val TOTAL_ITEMS = 520
    }
}
