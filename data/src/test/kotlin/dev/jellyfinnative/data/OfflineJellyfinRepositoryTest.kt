package dev.jellyfinnative.data

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.core.common.model.CollectionKind
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.SortOrder
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.dao.LibraryViewDao
import dev.jellyfinnative.core.database.dao.UserDataDao
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.database.entities.LibraryViewEntity
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.data.cache.CacheFixtures.MOVIES_LIBRARY
import dev.jellyfinnative.data.cache.CacheFixtures.NOW
import dev.jellyfinnative.data.cache.CacheFixtures.SHOWS_LIBRARY
import dev.jellyfinnative.data.cache.CacheFixtures.USER_ID
import dev.jellyfinnative.data.cache.CacheFixtures.entity
import dev.jellyfinnative.data.cache.CacheFixtures.episodeDto
import dev.jellyfinnative.data.cache.CacheFixtures.mapper
import dev.jellyfinnative.data.cache.CacheFixtures.movieDto
import dev.jellyfinnative.data.cache.CacheFixtures.seasonDto
import dev.jellyfinnative.data.cache.CacheFixtures.seriesDto
import dev.jellyfinnative.data.cache.CacheFixtures.userData
import dev.jellyfinnative.data.cache.CacheFixtures.uuid
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

/**
 * Unit tests for [OfflineJellyfinRepository] — the shape of every screen with no server.
 *
 * The download pipeline is M7, so nothing writes `source = DOWNLOAD` rows on a real device yet.
 * These tests seed them directly, which is the only thing that can pin the offline behaviour
 * before the pipeline exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineJellyfinRepositoryTest {
    private val itemDao = mockk<ItemDao>()
    private val libraryViewDao = mockk<LibraryViewDao>()
    private val userDataDao = mockk<UserDataDao>()
    private val sessionRepository = mockk<SessionRepository>()

    private val repository =
        OfflineJellyfinRepository(
            itemDao = itemDao,
            libraryViewDao = libraryViewDao,
            userDataDao = userDataDao,
            mapper = mapper,
            sessionRepository = sessionRepository,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    @BeforeEach
    fun setUp() {
        every { sessionRepository.sessionState } returns MutableStateFlow(loggedIn())
        // Every list scoped to a library resolves that library's kind, so the cache is always read.
        coEvery { libraryViewDao.getAll() } returns
            listOf(
                libraryRow(MOVIES_LIBRARY, "Films", CollectionKind.MOVIES, sortIndex = 0),
                libraryRow(SHOWS_LIBRARY, "Séries", CollectionKind.TVSHOWS, sortIndex = 1),
            )
        coEvery { userDataDao.getUserDataFor(any(), any()) } returns emptyList()
        coEvery { userDataDao.getUserData(any(), any()) } returns null
    }

    // ---- My Media -----------------------------------------------------------------------------

    @Test
    fun `serves the cached libraries in the order the server returned them`() =
        runTest {
            coEvery { libraryViewDao.getAll() } returns
                listOf(
                    libraryRow(MOVIES_LIBRARY, "Films", CollectionKind.MOVIES, sortIndex = 0),
                    libraryRow(SHOWS_LIBRARY, "Séries", CollectionKind.TVSHOWS, sortIndex = 1),
                )

            val views = repository.getUserViews().getOrNull()!!

            views.map { it.name } shouldContainExactly listOf("Films", "Séries")
            views.first().collectionType shouldBe CollectionKind.MOVIES
        }

    // ---- Continue watching --------------------------------------------------------------------

    @Test
    fun `continue watching lists downloads with a resume position`() =
        runTest {
            val movie = movieDto(uuid(1), "Arrival")
            coEvery { itemDao.resumeDownloaded(ItemSource.DOWNLOAD, USER_ID, 12) } returns
                listOf(entity(movie))
            coEvery { userDataDao.getUserDataFor(listOf(uuid(1)), USER_ID) } returns
                listOf(userData(uuid(1), positionTicks = 30_000_000_000L))

            val items = repository.getResumeItems(limit = 12).getOrNull()!!

            items.single().name shouldBe "Arrival"
            // The local position wins: it may have been written with no network at all.
            items.single().userData.playbackPositionTicks shouldBe 30_000_000_000L
        }

    @Test
    fun `continue watching is empty when nobody is signed in`() =
        runTest {
            every { sessionRepository.sessionState } returns MutableStateFlow(SessionState.LoggedOut)

            repository.getResumeItems(limit = 12).getOrNull()!!.shouldBeEmpty()

            coVerify(exactly = 0) { itemDao.resumeDownloaded(any(), any(), any()) }
        }

    // ---- Next up ------------------------------------------------------------------------------

    @Test
    fun `next up takes the first unwatched downloaded episode of each series`() =
        runTest {
            val gotSeries = uuid(10)
            val gotSeason = uuid(11)
            val dragonSeries = uuid(20)
            val dragonSeason = uuid(21)
            coEvery {
                itemDao.unwatchedDownloadedEpisodes(ItemSource.DOWNLOAD, USER_ID, ItemType.EPISODE, null)
            } returns
                listOf(
                    entity(episodeDto(uuid(12), "Winter Is Coming", gotSeries, "Thrones", gotSeason, 1, 1)),
                    entity(episodeDto(uuid(13), "The Kingsroad", gotSeries, "Thrones", gotSeason, 1, 2)),
                    entity(episodeDto(uuid(22), "The Heirs", dragonSeries, "Dragon", dragonSeason, 1, 1)),
                )

            val items = repository.getNextUp(limit = 24).getOrNull()!!

            items.map { it.name } shouldContainExactly listOf("Winter Is Coming", "The Heirs")
        }

    @Test
    fun `next up honours the row limit`() =
        runTest {
            coEvery {
                itemDao.unwatchedDownloadedEpisodes(any(), any(), any(), null)
            } returns
                (1..5).map { index ->
                    entity(
                        episodeDto(
                            id = uuid(100 + index),
                            name = "E$index",
                            seriesId = uuid(200 + index),
                            seriesName = "S$index",
                            seasonId = uuid(300 + index),
                            seasonNumber = 1,
                            episodeNumber = 1,
                        ),
                    )
                }

            repository.getNextUp(limit = 2).getOrNull()!! shouldHaveNames listOf("E1", "E2")
        }

    // ---- Latest -------------------------------------------------------------------------------

    @Test
    fun `latest lists the most recent downloads of one library`() =
        runTest {
            coEvery {
                itemDao.latestDownloaded(ItemSource.DOWNLOAD, any(), 16)
            } returns listOf(entity(movieDto(uuid(1), "Dune")), entity(movieDto(uuid(2), "Arrival")))

            repository.getLatestMedia(MOVIES_LIBRARY.toString(), limit = 16).getOrNull()!! shouldHaveNames
                listOf("Dune", "Arrival")
        }

    @Test
    fun `latest is empty for a library id that is not a uuid`() =
        runTest {
            repository.getLatestMedia("not-a-uuid", limit = 16).getOrNull()!!.shouldBeEmpty()
        }

    @Test
    fun `latest shows a downloaded film that has no parent linkage at all`() =
        runTest {
            // The M7 device bug: both downloaded films were stored with `parentId NULL`, and the
            // row's library was decided by a `parentId = <library>` predicate, so the offline home
            // had no Latest row at all.
            val types = mutableListOf<List<ItemType>>()
            coEvery { itemDao.latestDownloaded(ItemSource.DOWNLOAD, capture(types), 16) } returns
                listOf(entity(movieDto(uuid(1), "The Body", parentId = null)))

            repository.getLatestMedia(MOVIES_LIBRARY.toString(), limit = 16).getOrNull()!! shouldHaveNames
                listOf("The Body")
            // A film library's Latest row is scoped by kind instead.
            types.single() shouldContainExactly listOf(ItemType.MOVIE)
        }

    @Test
    fun `a TV library's latest row is scoped to shows and episodes`() =
        runTest {
            val types = mutableListOf<List<ItemType>>()
            coEvery { itemDao.latestDownloaded(ItemSource.DOWNLOAD, capture(types), 16) } returns emptyList()

            repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 16)

            types.single() shouldContainExactly listOf(ItemType.SERIES, ItemType.EPISODE)
        }

    // ---- library grid & search ----------------------------------------------------------------

    @Test
    fun `the library grid pages downloaded items with the query's offset and direction`() =
        runTest {
            coEvery {
                itemDao.pagingDownloaded(
                    source = ItemSource.DOWNLOAD,
                    types = listOf(ItemType.MOVIE),
                    descending = true,
                    limit = 50,
                    offset = 100,
                )
            } returns listOf(entity(movieDto(uuid(1), "Arrival")))

            val result =
                repository.getItems(
                    ItemQuery(
                        parentId = MOVIES_LIBRARY.toString(),
                        itemTypes = listOf(ItemType.MOVIE),
                        sortOrder = SortOrder.DESCENDING,
                        startIndex = 100,
                        limit = 50,
                    ),
                )

            result.getOrNull()!! shouldHaveNames listOf("Arrival")
        }

    @Test
    fun `the films grid lists a downloaded film whose parentId is NULL`() =
        runTest {
            // "Nothing to show here." on a device with a fully downloaded film was the symptom.
            val types = mutableListOf<List<ItemType>>()
            coEvery {
                itemDao.pagingDownloaded(any(), capture(types), any(), any(), any())
            } returns listOf(entity(movieDto(uuid(1), "The Body", parentId = null)))

            val result =
                repository.getItems(
                    ItemQuery(
                        parentId = MOVIES_LIBRARY.toString(),
                        itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES),
                        limit = 50,
                    ),
                )

            result.getOrNull()!! shouldHaveNames listOf("The Body")
            // The grid asks for both kinds whatever the library; a film library shows films.
            types.single() shouldContainExactly listOf(ItemType.MOVIE)
        }

    @Test
    fun `a TV library's grid is narrowed to shows`() =
        runTest {
            val types = mutableListOf<List<ItemType>>()
            coEvery {
                itemDao.pagingDownloaded(any(), capture(types), any(), any(), any())
            } returns emptyList()

            repository.getItems(
                ItemQuery(
                    parentId = SHOWS_LIBRARY.toString(),
                    itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES),
                    limit = 50,
                ),
            )

            types.single() shouldContainExactly listOf(ItemType.SERIES)
        }

    @Test
    fun `an unknown library narrows nothing rather than showing nothing`() =
        runTest {
            val types = mutableListOf<List<ItemType>>()
            coEvery {
                itemDao.pagingDownloaded(any(), capture(types), any(), any(), any())
            } returns emptyList()

            repository.getItems(
                ItemQuery(
                    parentId = uuid(77).toString(),
                    itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES),
                    limit = 50,
                ),
            )

            types.single() shouldContainExactly listOf(ItemType.MOVIE, ItemType.SERIES)
        }

    @Test
    fun `a search term routes to the downloaded-items search instead of the grid query`() =
        runTest {
            coEvery {
                itemDao.searchDownloaded(ItemSource.DOWNLOAD, any(), "arri", 50)
            } returns listOf(entity(movieDto(uuid(1), "Arrival")))

            repository.getItems(ItemQuery(searchTerm = " arri ", limit = 50)).getOrNull()!! shouldHaveNames
                listOf("Arrival")

            coVerify(exactly = 0) {
                itemDao.pagingDownloaded(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `an empty type list falls back to the kinds an offline list can contain`() =
        runTest {
            val types = mutableListOf<List<ItemType>>()
            coEvery {
                itemDao.pagingDownloaded(any(), capture(types), any(), any(), any())
            } returns emptyList()

            repository.getItems(ItemQuery(limit = 50))

            types.single() shouldContainExactly listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)
        }

    @Test
    fun `filter facets are computed from what is actually downloaded`() =
        runTest {
            coEvery { itemDao.allBySource(ItemSource.DOWNLOAD, any()) } returns
                listOf(
                    entity(movieDto(uuid(1), "Arrival", genres = listOf("Drama"), productionYear = 2016)),
                    entity(
                        movieDto(
                            uuid(2),
                            "Dune",
                            genres = listOf("Science Fiction", "Drama"),
                            productionYear = 2021,
                            officialRating = "PG-13",
                        ),
                    ),
                )

            val facets = repository.getFilterFacets(null, emptyList()).getOrNull()!!

            facets.genres shouldContainExactly listOf("Drama", "Science Fiction")
            facets.years shouldContainExactly listOf(2021, 2016)
            facets.officialRatings shouldContainExactly listOf("PG-13")
        }

    // ---- item detail --------------------------------------------------------------------------

    @Test
    fun `getItem serves a downloaded item`() =
        runTest {
            coEvery { itemDao.getItem(uuid(1)) } returns entity(movieDto(uuid(1), "Arrival"))

            val item = repository.getItem(uuid(1).toString()).getOrNull()!!

            item.name shouldBe "Arrival"
            item.available shouldBe true
        }

    @Test
    fun `getItem also serves a browse-cached parent so a downloaded episode's series still opens`() =
        runTest {
            coEvery { itemDao.getItem(uuid(10)) } returns
                entity(seriesDto(uuid(10), "Thrones"), source = ItemSource.BROWSE_CACHE)

            val item = repository.getItem(uuid(10).toString()).getOrNull()!!

            item.name shouldBe "Thrones"
            item.type shouldBe ItemType.SERIES
        }

    @Test
    fun `getItem answers with an unavailable item rather than an error for something we do not have`() =
        runTest {
            coEvery { itemDao.getItem(any()) } returns null

            val result = repository.getItem(uuid(99).toString())

            result.shouldBeInstanceOf<AppResult.Success<JellyfinItem>>()
            result.value.available shouldBe false
            result.value.id shouldBe uuid(99).toString()
        }

    @Test
    fun `getItem answers with an unavailable item for a malformed id`() =
        runTest {
            val result = repository.getItem("not-a-uuid")

            (result as AppResult.Success).value.available shouldBe false
        }

    @Test
    fun `getItem answers with an unavailable item when the cached blob is unreadable`() =
        runTest {
            coEvery { itemDao.getItem(uuid(1)) } returns
                entity(movieDto(uuid(1), "Arrival")).copy(dto = "not json")

            (repository.getItem(uuid(1).toString()) as AppResult.Success).value.available shouldBe false
        }

    @Test
    fun `getItem overlays this device's playback state`() =
        runTest {
            coEvery { itemDao.getItem(uuid(1)) } returns entity(movieDto(uuid(1), "Arrival"))
            coEvery { userDataDao.getUserData(uuid(1), USER_ID) } returns
                userData(uuid(1), played = true)

            repository
                .getItem(uuid(1).toString())
                .getOrNull()!!
                .userData.played shouldBe true
        }

    @Test
    fun `seasons and episodes come from the downloaded rows`() =
        runTest {
            coEvery { itemDao.seasonsOfSeries(ItemSource.DOWNLOAD, uuid(10), ItemType.SEASON) } returns
                listOf(entity(seasonDto(uuid(11), "Season 1", uuid(10), 1)))
            coEvery { itemDao.episodesOfSeason(ItemSource.DOWNLOAD, uuid(11), ItemType.EPISODE) } returns
                listOf(entity(episodeDto(uuid(12), "Winter Is Coming", uuid(10), "Thrones", uuid(11), 1, 1)))

            repository.getSeasons(uuid(10).toString()).getOrNull()!! shouldHaveNames listOf("Season 1")
            repository.getEpisodes(uuid(10).toString(), uuid(11).toString()).getOrNull()!! shouldHaveNames
                listOf("Winter Is Coming")
        }

    @Test
    fun `next up for a series is its first unwatched downloaded episode`() =
        runTest {
            coEvery {
                itemDao.unwatchedDownloadedEpisodes(ItemSource.DOWNLOAD, USER_ID, ItemType.EPISODE, uuid(10))
            } returns
                listOf(
                    entity(episodeDto(uuid(13), "The Kingsroad", uuid(10), "Thrones", uuid(11), 1, 2)),
                    entity(episodeDto(uuid(14), "Lord Snow", uuid(10), "Thrones", uuid(11), 1, 3)),
                )

            repository.getNextUpForSeries(uuid(10).toString()).getOrNull()!!.name shouldBe "The Kingsroad"
        }

    @Test
    fun `next up for a fully watched series is null, not an error`() =
        runTest {
            coEvery { itemDao.unwatchedDownloadedEpisodes(any(), any(), any(), any()) } returns emptyList()

            val result = repository.getNextUpForSeries(uuid(10).toString())

            result.shouldBeInstanceOf<AppResult.Success<JellyfinItem?>>()
            result.value.shouldBeNull()
        }

    @Test
    fun `similar items are always empty offline`() =
        runTest {
            repository.getSimilarItems(uuid(1).toString(), limit = 12).getOrNull()!!.shouldBeEmpty()
        }

    // ---- failure modes ------------------------------------------------------------------------

    @Test
    fun `a database failure is reported as a storage error`() =
        runTest {
            coEvery { libraryViewDao.getAll() } throws IOException("database gone")

            val result = repository.getUserViews()

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Storage>()
        }

    // ---- helpers ------------------------------------------------------------------------------

    private infix fun List<JellyfinItem>.shouldHaveNames(names: List<String>) {
        map { it.name } shouldContainExactly names
    }

    private fun libraryRow(
        id: UUID,
        name: String,
        kind: CollectionKind,
        sortIndex: Int,
    ) = LibraryViewEntity(
        id = id,
        name = name,
        collectionType = kind.name,
        sortIndex = sortIndex,
        cachedAt = NOW,
    )

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.randomUUID(),
            userId = USER_ID,
            userName = "casey",
            serverName = "test-server",
            serverVersion = "10.11.11",
        )
}
