package dev.jellyboost.data

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.data.cache.BrowseCacheWriter
import dev.jellyboost.data.mapper.FakeImageUrlFactory
import dev.jellyboost.data.mapper.ItemMapper
import dev.jellyboost.data.music.MusicApi
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
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.LibraryApi
import org.jellyfin.sdk.api.operations.PlaylistsApi
import org.jellyfin.sdk.api.operations.TvShowsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.api.operations.UserViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LyricDto
import org.jellyfin.sdk.model.api.LyricLine
import org.jellyfin.sdk.model.api.LyricMetadata
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetEpisodesRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetPlaylistItemsRequest
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
    private val playlistsApi = mockk<PlaylistsApi>()
    private val browseCache = mockk<BrowseCacheWriter>(relaxed = true)
    private val musicApi = mockk<MusicApi>()

    private val repository =
        OnlineJellyfinRepository(
            apiClient = apiClient,
            mapper = ItemMapper(FakeImageUrlFactory()),
            browseCache = browseCache,
            musicApi = musicApi,
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
        every { apiClient.playlistsApi } returns playlistsApi
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ---- getUserViews -----------------------------------------------------------------------

    @Test
    fun `getUserViews returns movie, tv and music libraries as domain models`() =
        runTest {
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } returns
                queryResponse(
                    listOf(
                        libraryDto(moviesLibraryId, "Movies", CollectionType.MOVIES),
                        libraryDto(UUID.randomUUID(), "Shows", CollectionType.TVSHOWS),
                        libraryDto(UUID.randomUUID(), "Music", CollectionType.MUSIC),
                        // Photos never joined SUPPORTED; music did.
                        libraryDto(UUID.randomUUID(), "Photos", CollectionType.PHOTOS),
                    ),
                )
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns countResponse(0)

            val result = repository.getUserViews()

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            val libraries = (result as AppResult.Success).value
            libraries.map { it.name } shouldContainExactly listOf("Movies", "Shows", "Music")
            libraries.map { it.collectionType } shouldContainExactly
                listOf(CollectionKind.MOVIES, CollectionKind.TVSHOWS, CollectionKind.MUSIC)
        }

    @Test
    fun `getUserViews counts each library's titles instead of trusting ChildCount`() =
        runTest {
            val showsLibraryId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } returns
                queryResponse(
                    listOf(
                        // Measured: 3 media folders for 177 movies, 6 for 20 series. `ChildCount`
                        // counts folders, never titles.
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
                queryResponse(listOf(libraryDto(UUID.randomUUID(), "Photos", CollectionType.PHOTOS)))

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

    /**
     * `mediaTypes` is the server's to honour, so this is the client keeping its own promise: the row
     * that says *Continue watching* must not be able to draw a track, whatever came back.
     */
    @Test
    fun `getResumeItems drops audio the server returned anyway, and caches only what it keeps`() =
        runTest {
            val cached = slot<List<BaseItemDto>>()
            coEvery { itemsApi.getResumeItems(any<GetResumeItemsRequest>()) } returns
                queryResponse(
                    listOf(
                        itemDto(BaseItemKind.AUDIO, "Fake Plastic Trees"),
                        // Both members of AUDIO_RESUME_KINDS: the set shrinking to just AUDIO
                        // must turn this test red, not only the doc claim false.
                        itemDto(BaseItemKind.AUDIO_BOOK, "Project Hail Mary"),
                        itemDto(BaseItemKind.EPISODE, "Trompe L'Oeil"),
                        itemDto(BaseItemKind.MOVIE, "Arrival"),
                    ),
                )

            val result = repository.getResumeItems()

            (result as AppResult.Success).value.map { it.name } shouldContainExactly
                listOf("Trompe L'Oeil", "Arrival")
            verify { browseCache.cacheItems(capture(cached), full = false) }
            cached.captured.map { it.name } shouldContainExactly listOf("Trompe L'Oeil", "Arrival")
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

    // ---- item detail --------------------------------------------------------------------------

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

            // The one endpoint serialising the complete field set, so the one write allowed to
            // overwrite a download's blob — and the only thing that can repair a gutted row.
            verify(exactly = 1) { browseCache.cacheItems(any(), full = true) }
        }

    @Test
    fun `a list read caches its response as a lean write, so a downloaded item keeps its blob`() =
        runTest {
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } returns
                queryResponse(listOf(itemDto(BaseItemKind.MOVIE, "Arrival")))

            repository.getItems(ItemQuery(parentId = moviesLibraryId.toString()))

            // A list request draws only list fields; writing it through would gut a download's
            // overview and genres.
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

    // ---- music --------------------------------------------------------------------------------

    @Test
    fun `a music library's count asks only for albums, unlike a movie or TV library`() =
        runTest {
            val musicLibraryId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
            coEvery { userViewsApi.getUserViews(any(), any(), any(), any()) } returns
                queryResponse(
                    listOf(
                        libraryDto(moviesLibraryId, "Movies", CollectionType.MOVIES),
                        libraryDto(musicLibraryId, "Music", CollectionType.MUSIC),
                    ),
                )
            val requests = mutableListOf<GetItemsRequest>()
            coEvery { itemsApi.getItems(capture(requests)) } returns countResponse(0)

            val libraries = (repository.getUserViews() as AppResult.Success).value

            libraries.map { it.collectionType } shouldContainExactly listOf(CollectionKind.MOVIES, CollectionKind.MUSIC)
            // Unaffected by a music library sharing the same count path.
            requests.first().includeItemTypes shouldContainExactly listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
            requests.last().includeItemTypes shouldContainExactly listOf(BaseItemKind.MUSIC_ALBUM)
        }

    @Test
    fun `getAlbumTracks asks for the album's audio children in disc-track order`() =
        runTest {
            val albumId = UUID.randomUUID()
            val request = slot<GetItemsRequest>()
            coEvery { itemsApi.getItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.AUDIO, "Fake Plastic Trees")))

            val result = repository.getAlbumTracks(albumId.toString())

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Fake Plastic Trees")
            request.captured.parentId shouldBe albumId
            request.captured.includeItemTypes shouldContainExactly listOf(BaseItemKind.AUDIO)
            request.captured.recursive shouldBe true
            request.captured.sortBy shouldContainExactly
                listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME)
        }

    @Test
    fun `getArtistAlbums asks by albumArtistIds, newest first`() =
        runTest {
            val artistId = UUID.randomUUID()
            val request = slot<GetItemsRequest>()
            coEvery { itemsApi.getItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.MUSIC_ALBUM, "The Bends")))

            val result = repository.getArtistAlbums(artistId.toString())

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("The Bends")
            request.captured.albumArtistIds shouldContainExactly listOf(artistId)
            request.captured.includeItemTypes shouldContainExactly listOf(BaseItemKind.MUSIC_ALBUM)
            request.captured.recursive shouldBe true
            request.captured.sortBy shouldContainExactly
                listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
            request.captured.sortOrder shouldContainExactly
                listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING)
        }

    @Test
    fun `getArtistTopTracks asks by artistIds, sorted by play count and capped at the limit`() =
        runTest {
            val artistId = UUID.randomUUID()
            val request = slot<GetItemsRequest>()
            coEvery { itemsApi.getItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.AUDIO, "Creep")))

            val result = repository.getArtistTopTracks(artistId.toString(), limit = 10)

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Creep")
            request.captured.artistIds shouldContainExactly listOf(artistId)
            request.captured.includeItemTypes shouldContainExactly listOf(BaseItemKind.AUDIO)
            request.captured.sortBy shouldContainExactly listOf(ItemSortBy.PLAY_COUNT)
            request.captured.sortOrder shouldContainExactly listOf(SortOrder.DESCENDING)
            request.captured.limit shouldBe 10
        }

    @Test
    fun `getPlaylistItems goes through the dedicated playlists endpoint, preserving order`() =
        runTest {
            val playlistId = UUID.randomUUID()
            val request = slot<GetPlaylistItemsRequest>()
            coEvery { playlistsApi.getPlaylistItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.AUDIO, "Track 2"), itemDto(BaseItemKind.AUDIO, "Track 1")))

            val result = repository.getPlaylistItems(playlistId.toString())

            // Server order preserved exactly as returned — no client-side re-sort.
            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Track 2", "Track 1")
            request.captured.playlistId shouldBe playlistId
        }

    @Test
    fun `getPlaylistItems drops a mixed playlist's non-audio members`() =
        runTest {
            // A playlist may legally mix in episodes and films, and the audio-only resolver would
            // build /Audio universal URLs for them.
            coEvery { playlistsApi.getPlaylistItems(any<GetPlaylistItemsRequest>()) } returns
                queryResponse(
                    listOf(
                        itemDto(BaseItemKind.AUDIO, "Track 1"),
                        itemDto(BaseItemKind.EPISODE, "Stray Episode"),
                        itemDto(BaseItemKind.MOVIE, "Stray Film"),
                        itemDto(BaseItemKind.AUDIO, "Track 2"),
                    ),
                )

            val result = repository.getPlaylistItems(UUID.randomUUID().toString())

            (result as AppResult.Success).value.map { it.name } shouldContainExactly
                listOf("Track 1", "Track 2")
        }

    @Test
    fun `getAlbumTracks maps a transport failure onto Network`() =
        runTest {
            coEvery { itemsApi.getItems(any<GetItemsRequest>()) } throws IOException("reset")

            val result = repository.getAlbumTracks(UUID.randomUUID().toString())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    // ---- getResumeAudioItems --------------------------------------------------------------------

    @Test
    fun `getResumeAudioItems mirrors getResumeItems but narrows mediaTypes to audio`() =
        runTest {
            val request = slot<GetResumeItemsRequest>()
            coEvery { itemsApi.getResumeItems(capture(request)) } returns
                queryResponse(listOf(itemDto(BaseItemKind.AUDIO, "Fake Plastic Trees")))

            val result = repository.getResumeAudioItems(limit = 8)

            (result as AppResult.Success).value.map { it.name } shouldContainExactly
                listOf("Fake Plastic Trees")
            request.captured.limit shouldBe 8
            request.captured.enableUserData shouldBe true
            request.captured.mediaTypes shouldContainExactly listOf(MediaType.AUDIO)
        }

    /** The mirror of the *Continue watching* guard: neither row may draw the other's kind. */
    @Test
    fun `getResumeAudioItems drops video the server returned anyway, and caches only what it keeps`() =
        runTest {
            val cached = slot<List<BaseItemDto>>()
            coEvery { itemsApi.getResumeItems(any<GetResumeItemsRequest>()) } returns
                queryResponse(
                    listOf(
                        itemDto(BaseItemKind.MOVIE, "Arrival"),
                        itemDto(BaseItemKind.AUDIO, "Fake Plastic Trees"),
                        itemDto(BaseItemKind.AUDIO_BOOK, "Project Hail Mary"),
                    ),
                )

            val result = repository.getResumeAudioItems()

            (result as AppResult.Success).value.map { it.name } shouldContainExactly
                listOf("Fake Plastic Trees", "Project Hail Mary")
            verify { browseCache.cacheItems(capture(cached), full = false) }
            cached.captured.map { it.name } shouldContainExactly
                listOf("Fake Plastic Trees", "Project Hail Mary")
        }

    @Test
    fun `getResumeAudioItems maps an IO failure onto Network`() =
        runTest {
            coEvery { itemsApi.getResumeItems(any<GetResumeItemsRequest>()) } throws IOException("socket closed")

            val result = repository.getResumeAudioItems()

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    // ---- getInstantMix / getLyrics ---------------------------------------------------------------

    @Test
    fun `getInstantMix asks the music api by item id and limit, and maps the result`() =
        runTest {
            val seedId = UUID.randomUUID()
            coEvery { musicApi.getInstantMix(seedId, 25) } returns listOf(itemDto(BaseItemKind.AUDIO, "Creep"))

            val result = repository.getInstantMix(seedId.toString(), limit = 25)

            (result as AppResult.Success).value.map { it.name } shouldContainExactly listOf("Creep")
            coVerify(exactly = 1) { musicApi.getInstantMix(seedId, 25) }
        }

    @Test
    fun `getInstantMix maps a transport failure onto Network`() =
        runTest {
            coEvery { musicApi.getInstantMix(any(), any()) } throws IOException("reset")

            val result = repository.getInstantMix(UUID.randomUUID().toString())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    @Test
    fun `getLyrics maps a synced LyricDto onto domain lines carrying their start ticks`() =
        runTest {
            val trackId = UUID.randomUUID()
            coEvery { musicApi.getLyrics(trackId) } returns
                LyricDto(
                    metadata = LyricMetadata(isSynced = true),
                    lyrics = listOf(LyricLine(text = "Fake plastic trees", start = 123_0000L)),
                )

            val result = repository.getLyrics(trackId.toString())

            val lyrics = (result as AppResult.Success).value
            lyrics.isSynced shouldBe true
            lyrics.lines.map { it.text } shouldContainExactly listOf("Fake plastic trees")
        }

    @Test
    fun `getLyrics maps a 404 onto NotFound`() =
        runTest {
            coEvery { musicApi.getLyrics(any()) } throws InvalidStatusException(status = 404)

            val result = repository.getLyrics(UUID.randomUUID().toString())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.NotFound>()
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
