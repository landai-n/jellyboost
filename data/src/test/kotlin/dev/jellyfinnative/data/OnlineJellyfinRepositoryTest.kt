package dev.jellyfinnative.data

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.data.mapper.FakeImageUrlFactory
import dev.jellyfinnative.data.mapper.ItemMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.TvShowsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.api.operations.UserViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
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

    private val repository =
        OnlineJellyfinRepository(apiClient, ItemMapper(FakeImageUrlFactory()), UnconfinedTestDispatcher())

    private val moviesLibraryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    @BeforeEach
    fun setUp() {
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.userViewsApi } returns userViewsApi
        every { apiClient.itemsApi } returns itemsApi
        every { apiClient.tvShowsApi } returns tvShowsApi
        every { apiClient.userLibraryApi } returns userLibraryApi
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

            val result = repository.getUserViews()

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            val libraries = (result as AppResult.Success).value
            libraries.map { it.name } shouldContainExactly listOf("Movies", "Shows")
            libraries.map { it.collectionType } shouldContainExactly
                listOf(CollectionKind.MOVIES, CollectionKind.TVSHOWS)
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

    private fun libraryDto(
        id: UUID,
        name: String,
        collectionType: CollectionType,
    ) = BaseItemDto(
        id = id,
        type = BaseItemKind.COLLECTION_FOLDER,
        name = name,
        collectionType = collectionType,
    )

    private fun itemDto(
        kind: BaseItemKind,
        name: String,
    ) = BaseItemDto(id = UUID.randomUUID(), type = kind, name = name)
}
