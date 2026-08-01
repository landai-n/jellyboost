package dev.jellyboost.data

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.data.cache.BrowseCacheWriter
import dev.jellyboost.data.mapper.FakeImageUrlFactory
import dev.jellyboost.data.mapper.ItemMapper
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.LibraryApi
import org.jellyfin.sdk.api.operations.TvShowsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.api.operations.UserViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for [OnlineJellyfinRepository].
 *
 * The SDK exposes its operation groups as extension properties on `ApiClient`, so the extension
 * file's static holder is mocked to hand back stubbed operation objects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineJellyfinRepositoryTest {
    private val apiClient = mockk<ApiClient>()
    private val userViewsApi = mockk<UserViewsApi>()
    private val itemsApi = mockk<ItemsApi>()
    private val tvShowsApi = mockk<TvShowsApi>()
    private val userLibraryApi = mockk<UserLibraryApi>()
    private val libraryApi = mockk<LibraryApi>()
    private val browseCache = mockk<BrowseCacheWriter>(relaxed = true)

    private val repository =
        OnlineJellyfinRepository(
            apiClient = apiClient,
            mapper = ItemMapper(FakeImageUrlFactory()),
            browseCache = browseCache,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private val moviesLibraryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    @BeforeEach
    fun setUp() {
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.userViewsApi } returns userViewsApi
        every { apiClient.itemsApi } returns itemsApi
        every { apiClient.tvShowsApi } returns tvShowsApi
        every { apiClient.userLibraryApi } returns userLibraryApi
        every { apiClient.libraryApi } returns libraryApi
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ---- getUserViews -----------------------------------------------------------------------

    @Test
    fun `getUserViews returns only movie and tv libraries as domain models`() =
        runTest {
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } returns
                queryResponse(
                    listOf(
                        libraryDto(moviesLibraryId, "Movies", CollectionType.MOVIES),
                        libraryDto(UUID.randomUUID(), "Shows", CollectionType.TVSHOWS),
                        libraryDto(UUID.randomUUID(), "Music", CollectionType.MUSIC),
                    ),
                )
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns countResponse(0)

            val result = repository.getUserViews()

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            val libraries = (result as AppResult.Success).value
            libraries.map { it.name } shouldContainExactly listOf("Movies", "Shows")
            libraries.map { it.collectionType } shouldContainExactly
                listOf(CollectionKind.MOVIES, CollectionKind.TVSHOWS)
        }

    @Test
    fun `getUserViews counts each library's titles instead of trusting ChildCount`() =
        runTest {
            val showsLibraryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } returns
                queryResponse(
                    listOf(
                        // What the dev server actually reports: 3 media folders for 177 movies,
                        // 6 for 20 series. `ChildCount` counts folders, never titles.
                        libraryDto(moviesLibraryId, "Movies", CollectionType.MOVIES, childCount = 3),
                        libraryDto(showsLibraryId, "Shows", CollectionType.TVSHOWS, childCount = 6),
                    ),
                )
            val requests = mutableListOf<GetItemsRequest>()
            coEvery { itemsApi.getItems(capture(requests)) } answers
                {
                    countResponse(if (firstArg<GetItemsRequest>().parentId == moviesLibraryId) 177 else 20)
                }

            val libraries = (repository.getUserViews() as AppResult.Success).value

            libraries.map { it.itemCount } shouldContainExactly listOf(177, 20)
            requests.map { it.parentId } shouldContainExactly listOf(moviesLibraryId, showsLibraryId)
            requests.forEach { request ->
                request.includeItemTypes shouldContainExactly
                    listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
                request.recursive shouldBe true
                // A pure COUNT: the server reports the total and serialises no items.
                request.limit shouldBe 0
                request.enableTotalRecordCount shouldBe true
            }
        }

    @Test
    fun `getUserViews leaves the count unset when a library's count request fails`() =
        runTest {
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } returns
                queryResponse(listOf(libraryDto(moviesLibraryId, "Movies", CollectionType.MOVIES, childCount = 3)))
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } throws IOException("reset")

            val result = repository.getUserViews()

            // The row still loads — a missing subtitle beats a home screen that failed over a count.
            val libraries = (result as AppResult.Success).value
            libraries.map { it.name } shouldContainExactly listOf("Movies")
            libraries.single().itemCount.shouldBeNull()
        }

    @Test
    fun `getUserViews asks for no counts when there are no supported libraries`() =
        runTest {
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } returns
                queryResponse(listOf(libraryDto(UUID.randomUUID(), "Music", CollectionType.MUSIC)))

            (repository.getUserViews() as AppResult.Success).value.shouldBeEmpty()

            coVerify(exactly = 0) { itemsApi.getItems(any<GetItemsRequest>()) }
        }

    @Test
    fun `getUserViews maps a 401 onto Unauthorized`() =
        runTest {
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } throws
                InvalidStatusException(status = 401)

            val result = repository.getUserViews()

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Unauthorized>()
        }

    @Test
    fun `getUserViews maps a transport failure onto Network`() =
        runTest {
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } throws
                TimeoutException("timed out")

            val result = repository.getUserViews()

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    @Test
    fun `getUserViews maps a 500 onto Server with the status code`() =
        runTest {
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } throws
                InvalidStatusException(status = 500)

            val result = repository.getUserViews()

            val error = (result as AppResult.Failure).error
            error.shouldBeInstanceOf<AppError.Server>()
            error.statusCode shouldBe 500
        }

    // ---- getResumeItems ---------------------------------------------------------------------

    @Test
    fun `getResumeItems asks the server for the requested number of lean cards`() =
        runTest {
            val request = slot<GetResumeItemsRequest>()
            coEvery { itemsApi.getResumeItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.EPISODE, "Trompe L'Oeil")))

            val result = repository.getResumeItems(limit = 20)

            (result as AppResult.Success).value.map { it.name } shouldContainExactly
                listOf("Trompe L'Oeil")
            request.captured.limit shouldBe 20
            request.captured.enableUserData shouldBe true
            request.captured.imageTypeLimit shouldBe 1
            request.captured.enableTotalRecordCount shouldBe false
            request.captured.fields shouldContainExactly listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)
            request.captured.mediaTypes shouldContainExactly listOf(MediaType.VIDEO)
        }

    @Test
    fun `getResumeItems maps an IO failure onto Network`() =
        runTest {
            coEvery { itemsApi.getResumeItems(any<GetResumeItemsRequest>()) } throws IOException("socket closed")

            val result = repository.getResumeItems()

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    // ---- getNextUp --------------------------------------------------------------------------

    @Test
    fun `getNextUp forwards the limit and returns domain items`() =
        runTest {
            val request = slot<GetNextUpRequest>()
            coEvery { tvShowsApi.getNextUp(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.EPISODE, "Chestnut")))

            val result = repository.getNextUp(limit = 7)

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Chestnut")
            request.captured.limit shouldBe 7
            request.captured.enableUserData shouldBe true
        }

    @Test
    fun `getNextUp mirrors the jellyfin-web home filters`() =
        runTest {
            val request = slot<GetNextUpRequest>()
            coEvery { tvShowsApi.getNextUp(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.EPISODE, "Chestnut")))

            repository.getNextUp()

            // In-progress episodes belong to Continue Watching, not Next Up.
            request.captured.enableResumable shouldBe false
            // Series untouched for over a year drop out (web's default "Days in Next Up").
            val cutoff = request.captured.nextUpDateCutoff.shouldNotBeNull()
            val expected = LocalDateTime.now().minusDays(365)
            cutoff.isAfter(expected.minusHours(1)) shouldBe true
            cutoff.isBefore(expected.plusHours(1)) shouldBe true
        }

    // ---- getLatestMedia ---------------------------------------------------------------------

    @Test
    fun `getLatestMedia scopes the request to the requested library`() =
        runTest {
            val request = slot<GetLatestMediaRequest>()
            coEvery { userLibraryApi.getLatestMedia(capture(request)) } returns
                Response(
                    content = listOf(itemDto(BaseItemKind.MOVIE, "Dune")),
                    status = 200,
                    headers = emptyMap(),
                )

            val result = repository.getLatestMedia(parentId = moviesLibraryId.toString(), limit = 16)

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Dune")
            request.captured.parentId shouldBe moviesLibraryId
            request.captured.limit shouldBe 16
        }

    @Test
    fun `getLatestMedia reports a malformed library id as an Unknown failure`() =
        runTest {
            val result = repository.getLatestMedia(parentId = "not-a-uuid")

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Unknown>()
        }

    // ---- M4: item detail --------------------------------------------------------------------

    @Test
    fun `getItem re-fetches the single item and maps it`() =
        runTest {
            coEvery { userLibraryApi.getItem(any(), any()) } returns
                Response(
                    content = itemDto(BaseItemKind.MOVIE, "Arrival"),
                    status = 200,
                    headers = emptyMap(),
                )

            val result = repository.getItem(moviesLibraryId.toString())

            (result as AppResult.Success).value.name shouldBe "Arrival"
            coVerify(exactly = 1) { userLibraryApi.getItem(moviesLibraryId, any()) }
        }

    @Test
    fun `getItem caches its response as a full write, so a downloaded item's blob is replaced`() =
        runTest {
            coEvery { userLibraryApi.getItem(any(), any()) } returns
                Response(
                    content = itemDto(BaseItemKind.MOVIE, "Arrival"),
                    status = 200,
                    headers = emptyMap(),
                )

            repository.getItem(moviesLibraryId.toString())

            // `/Users/{userId}/Items/{itemId}` is the one endpoint that serialises the complete
            // field set, so it is the one write allowed to overwrite the rich blob a download
            // stored — and therefore the only thing that can repair a row an older build gutted.
            verify(exactly = 1) { browseCache.cacheItems(any(), full = true) }
        }

    @Test
    fun `a list read caches its response as a lean write, so a downloaded item keeps its blob`() =
        runTest {
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns
                queryResponse(listOf(itemDto(BaseItemKind.MOVIE, "Arrival")))

            repository.getItems(ItemQuery(parentId = moviesLibraryId.toString()))

            // A list request only asks for the fields the list draws; writing that through would
            // gut the overview and genres of anything downloaded.
            verify(exactly = 1) { browseCache.cacheItems(any(), full = false) }
        }

    @Test
    fun `getItem reports a malformed id as an Unknown failure`() =
        runTest {
            val result = repository.getItem("not-a-uuid")

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Unknown>()
        }

    @Test
    fun `getSeasons scopes the request to the series and drops missing seasons`() =
        runTest {
            val request = slot<GetSeasonsRequest>()
            coEvery { tvShowsApi.getSeasons(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.SEASON, "Season 1")))

            val result = repository.getSeasons(moviesLibraryId.toString())

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Season 1")
            request.captured.seriesId shouldBe moviesLibraryId
            request.captured.isMissing shouldBe false
            request.captured.enableUserData shouldBe true
        }

    @Test
    fun `getEpisodes filters the series request down to one season and asks for overviews`() =
        runTest {
            val seasonId = UUID.randomUUID()
            val request = slot<GetEpisodesRequest>()
            coEvery { tvShowsApi.getEpisodes(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.EPISODE, "The Original")))

            val result = repository.getEpisodes(moviesLibraryId.toString(), seasonId.toString())

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("The Original")
            request.captured.seriesId shouldBe moviesLibraryId
            request.captured.seasonId shouldBe seasonId
            request.captured.fields!! shouldContainExactly
                listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.OVERVIEW)
            request.captured.enableUserData shouldBe true
        }

    @Test
    fun `getNextUpForSeries asks for a single episode of that series`() =
        runTest {
            val request = slot<GetNextUpRequest>()
            coEvery { tvShowsApi.getNextUp(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.EPISODE, "Chestnut")))

            val result = repository.getNextUpForSeries(moviesLibraryId.toString())

            (result as AppResult.Success).value!!.name shouldBe "Chestnut"
            request.captured.seriesId shouldBe moviesLibraryId
            request.captured.limit shouldBe 1
        }

    @Test
    fun `getNextUpForSeries returns null for a fully watched series`() =
        runTest {
            coEvery { tvShowsApi.getNextUp(any<GetNextUpRequest>()) } returns queryResponse(emptyList())

            val result = repository.getNextUpForSeries(moviesLibraryId.toString())

            (result as AppResult.Success).value.shouldBeNull()
        }

    @Test
    fun `getSimilarItems forwards the limit`() =
        runTest {
            val request = slot<GetSimilarItemsRequest>()
            coEvery { libraryApi.getSimilarItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.MOVIE, "Sicario")))

            val result = repository.getSimilarItems(moviesLibraryId.toString(), limit = 5)

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Sicario")
            request.captured.itemId shouldBe moviesLibraryId
            request.captured.limit shouldBe 5
        }

    @Test
    fun `getSimilarItems maps a transport failure onto Network`() =
        runTest {
            coEvery { libraryApi.getSimilarItems(any<GetSimilarItemsRequest>()) } throws IOException("reset")

            val result = repository.getSimilarItems(moviesLibraryId.toString())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun queryResponse(items: List<BaseItemDto>) =
        Response(
            content =
                BaseItemDtoQueryResult(
                    items = items,
                    totalRecordCount = items.size,
                    startIndex = 0,
                ),
            status = 200,
            headers = emptyMap(),
        )

    /** What a `limit = 0` count request answers with: a total and no items at all. */
    private fun countResponse(total: Int) =
        Response(
            content =
                BaseItemDtoQueryResult(
                    items = emptyList(),
                    totalRecordCount = total,
                    startIndex = 0,
                ),
            status = 200,
            headers = emptyMap(),
        )

    private fun libraryDto(
        id: UUID,
        name: String,
        collectionType: CollectionType,
        childCount: Int? = null,
    ) = BaseItemDto(
        id = id,
        type = BaseItemKind.COLLECTION_FOLDER,
        name = name,
        collectionType = collectionType,
        childCount = childCount,
    )

    private fun itemDto(
        kind: BaseItemKind,
        name: String,
    ) = BaseItemDto(id = UUID.randomUUID(), type = kind, name = name)
}
