package dev.jellyboost.data

import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.FilterOptions
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LibraryViewEntity
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.CacheFixtures.MOVIES_LIBRARY
import dev.jellyboost.data.cache.CacheFixtures.NOW
import dev.jellyboost.data.cache.CacheFixtures.SHOWS_LIBRARY
import dev.jellyboost.data.cache.CacheFixtures.USER_ID
import dev.jellyboost.data.cache.CacheFixtures.entity
import dev.jellyboost.data.cache.CacheFixtures.mapper
import dev.jellyboost.data.cache.CacheFixtures.movieDto
import dev.jellyboost.data.cache.CacheFixtures.uuid
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for the **filters** half of [OfflineJellyfinRepository] — what the library grid's
 * filter sheet offers offline, and what applying it actually does.
 *
 * Apart from [OfflineJellyfinRepositoryTest] because the two answer different questions: that class
 * pins the *shape* of every offline screen, this one pins one behaviour that was missing altogether
 * — offline, `query.filters` was read by nothing while the grid still drew an active-filter badge
 * (docs/notes/audit-2026-07.md, ARCH-01).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineJellyfinRepositoryFilterTest {
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
        every { sessionRepository.sessionState } returns
            MutableStateFlow(
                SessionState.LoggedIn(
                    serverId = UUID.randomUUID(),
                    userId = USER_ID,
                    userName = "casey",
                    serverName = "test-server",
                    serverVersion = "10.11.11",
                ),
            )
        coEvery { libraryViewDao.getAll() } returns
            listOf(
                LibraryViewEntity(MOVIES_LIBRARY, "Films", CollectionKind.MOVIES.name, 0, NOW),
                LibraryViewEntity(SHOWS_LIBRARY, "Séries", CollectionKind.TVSHOWS.name, 1, NOW),
            )
        coEvery { userDataDao.getUserDataFor(any(), any()) } returns emptyList()
    }

    // ---- offline filters (audit ARCH-01) ------------------------------------------------------

    @Test
    fun `a genre filter narrows the offline grid instead of being ignored`() =
        runTest {
            // The bug: every filter was dropped on the floor while the grid still drew a
            // "1 active" badge over the identical unfiltered list.
            stubGrid(
                itemDao,
                listOf(
                    entity(movieDto(uuid(1), "Arrival", genres = listOf("Science Fiction"))),
                    entity(movieDto(uuid(2), "Sicario", genres = listOf("Thriller"))),
                ),
            )

            val result =
                repository.getItems(
                    ItemQuery(limit = 50, filters = FilterOptions(genres = listOf("Thriller"))),
                )

            result.getOrNull()!! shouldHaveNames listOf("Sicario")
        }

    @Test
    fun `a row matching any one of the selected genres survives`() =
        runTest {
            stubGrid(
                itemDao,
                listOf(
                    entity(movieDto(uuid(1), "Dune", genres = listOf("Science Fiction", "Drama"))),
                    entity(movieDto(uuid(2), "Sicario", genres = listOf("Thriller"))),
                ),
            )

            val result =
                repository.getItems(
                    ItemQuery(
                        limit = 50,
                        filters = FilterOptions(genres = listOf("Drama", "Western")),
                    ),
                )

            result.getOrNull()!! shouldHaveNames listOf("Dune")
        }

    @Test
    fun `two different facets both have to be satisfied`() =
        runTest {
            stubGrid(
                itemDao,
                listOf(
                    entity(movieDto(uuid(1), "Arrival", genres = listOf("Drama"), productionYear = 2016)),
                    entity(movieDto(uuid(2), "Dune", genres = listOf("Drama"), productionYear = 2021)),
                ),
            )

            val result =
                repository.getItems(
                    ItemQuery(
                        limit = 50,
                        filters = FilterOptions(genres = listOf("Drama"), years = listOf(2021)),
                    ),
                )

            result.getOrNull()!! shouldHaveNames listOf("Dune")
        }

    @Test
    fun `the official-rating filter narrows the grid`() =
        runTest {
            stubGrid(
                itemDao,
                listOf(
                    entity(movieDto(uuid(1), "Arrival", officialRating = "PG-13")),
                    entity(movieDto(uuid(2), "Sicario", officialRating = "R")),
                ),
            )

            val result =
                repository.getItems(
                    ItemQuery(limit = 50, filters = FilterOptions(officialRatings = listOf("R"))),
                )

            result.getOrNull()!! shouldHaveNames listOf("Sicario")
        }

    @Test
    fun `unwatched means the rows this user has no playback row for as well`() =
        runTest {
            stubGrid(
                itemDao,
                listOf(entity(movieDto(uuid(1), "Arrival")), entity(movieDto(uuid(2), "Dune"))),
                played = setOf(uuid(2)),
            )

            repository
                .getItems(ItemQuery(limit = 50, filters = FilterOptions(isPlayed = false)))
                .getOrNull()!! shouldHaveNames listOf("Arrival")
            repository
                .getItems(ItemQuery(limit = 50, filters = FilterOptions(isPlayed = true)))
                .getOrNull()!! shouldHaveNames listOf("Dune")
        }

    @Test
    fun `the favourites filter narrows the grid`() =
        runTest {
            stubGrid(
                itemDao,
                listOf(entity(movieDto(uuid(1), "Arrival")), entity(movieDto(uuid(2), "Dune"))),
                favorites = setOf(uuid(1)),
            )

            repository
                .getItems(ItemQuery(limit = 50, filters = FilterOptions(isFavorite = true)))
                .getOrNull()!! shouldHaveNames listOf("Arrival")
        }

    @Test
    fun `paging counts the rows a filter kept, not the rows the table holds`() =
        runTest {
            // Filtering after the page had been cut would return a short page, and a short page is
            // how `ItemPagingSource` recognises the end of the library — the grid would stop early.
            stubGrid(
                itemDao,
                (1..10).map { index ->
                    entity(
                        movieDto(
                            uuid(index),
                            "Film $index",
                            genres = if (index % 2 == 0) listOf("Drama") else listOf("Thriller"),
                        ),
                    )
                },
            )

            val result =
                repository.getItems(
                    ItemQuery(
                        limit = 2,
                        startIndex = 2,
                        filters = FilterOptions(genres = listOf("Drama")),
                    ),
                )

            // Dramas are films 2, 4, 6, 8, 10; the third and fourth of them are 6 and 8.
            result.getOrNull()!! shouldHaveNames listOf("Film 6", "Film 8")
        }

    @Test
    fun `an empty filter set changes nothing`() =
        runTest {
            stubGrid(itemDao, listOf(entity(movieDto(uuid(1), "Arrival")), entity(movieDto(uuid(2), "Dune"))))

            repository.getItems(ItemQuery(limit = 50)).getOrNull()!! shouldHaveNames
                listOf("Arrival", "Dune")
        }

    @Test
    fun `the grid reads the blobs of one page, not of the whole library`() =
        runTest {
            val ids = mutableListOf<List<UUID>>()
            stubGrid(itemDao, (1..10).map { entity(movieDto(uuid(it), "Film $it")) }, readIds = ids)

            repository.getItems(ItemQuery(limit = 2))

            ids.single().size shouldBe 2
        }

    @Test
    fun `a film library's facets do not offer the genres of downloaded television`() =
        runTest {
            // The sheet used to ignore its `parentId` entirely, so filtering a film library by a
            // genre only its downloaded TV carried could only ever empty the grid.
            val types = mutableListOf<List<ItemType>>()
            coEvery { itemDao.allBySource(ItemSource.DOWNLOAD, capture(types)) } returns emptyList()

            repository.getFilterFacets(MOVIES_LIBRARY.toString(), emptyList())

            types.single() shouldContainExactly listOf(ItemType.MOVIE)
        }

    private infix fun List<JellyfinItem>.shouldHaveNames(names: List<String>) {
        map { it.name } shouldContainExactly names
    }
}
