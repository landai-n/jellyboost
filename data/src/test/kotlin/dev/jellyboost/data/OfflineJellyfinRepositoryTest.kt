package dev.jellyboost.data

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemQuery
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.SortOrder
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.FacetKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LatestDownloadKey
import dev.jellyboost.core.database.entities.LibraryViewEntity
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.CacheFixtures.MOVIES_LIBRARY
import dev.jellyboost.data.cache.CacheFixtures.NOW
import dev.jellyboost.data.cache.CacheFixtures.SHOWS_LIBRARY
import dev.jellyboost.data.cache.CacheFixtures.USER_ID
import dev.jellyboost.data.cache.CacheFixtures.albumDto
import dev.jellyboost.data.cache.CacheFixtures.audioDto
import dev.jellyboost.data.cache.CacheFixtures.entity
import dev.jellyboost.data.cache.CacheFixtures.episodeDto
import dev.jellyboost.data.cache.CacheFixtures.mapper
import dev.jellyboost.data.cache.CacheFixtures.movieDto
import dev.jellyboost.data.cache.CacheFixtures.seasonDto
import dev.jellyboost.data.cache.CacheFixtures.seriesDto
import dev.jellyboost.data.cache.CacheFixtures.userData
import dev.jellyboost.data.cache.CacheFixtures.uuid
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.util.UUID

/**
 * Unit tests for [OfflineJellyfinRepository] — the shape of every screen with no server.
 *
 * These tests seed `source = DOWNLOAD` rows directly, which is what lets them pin the offline
 * behaviour independently of anything a real device has actually downloaded.
 *
 * One class rather than one per member (`@Suppress("LargeClass")` below): the `HomeViewModelTest`/
 * `SyncPlayControllerTest` precedent — splitting it would scatter the shared mock setup across
 * files for one repository's own members, not distinct collaborators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass")
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

    /**
     * The home hero's online/offline parity, from the offline side.
     *
     * A download's cached blob is the **whole** item — the enqueue fetch asks for
     * `OVERVIEW, GENRES, PEOPLE, …` so the detail page works with no server — while the online
     * home rows are fetched with `CARD_FIELDS`, which asks for no overview at all (pinned by
     * `OnlineJellyfinRepositoryTest`: *getResumeItems asks the
     * server for the requested number of lean cards*). Read back raw, the same wide *Continue
     * watching* hero therefore drew a synopsis offline that an online user never sees, and the
     * extra paragraph pushed the resume button down over the row below the banner.
     */
    @Test
    fun `continue watching carries no overview offline either, matching the lean online card fields`() =
        runTest {
            val movie = movieDto(uuid(1), "Arrival").copy(overview = SYNOPSIS)
            coEvery { itemDao.resumeDownloaded(ItemSource.DOWNLOAD, USER_ID, 12) } returns
                listOf(entity(movie))

            val items = repository.getResumeItems(limit = 12).getOrNull()!!

            items.single().name shouldBe "Arrival"
            items.single().overview.shouldBeNull()
        }

    @Test
    fun `next up and latest are card-shaped offline too`() =
        runTest {
            val episode =
                episodeDto(uuid(12), "Winter Is Coming", uuid(10), "Thrones", uuid(11), 1, 1)
                    .copy(overview = SYNOPSIS)
            coEvery {
                itemDao.unwatchedDownloadedEpisodes(ItemSource.DOWNLOAD, USER_ID, ItemType.EPISODE, null)
            } returns listOf(entity(episode))
            seedLatest(listOf(entity(movieDto(uuid(1), "Dune").copy(overview = SYNOPSIS))))

            repository
                .getNextUp(limit = 24)
                .getOrNull()!!
                .single()
                .overview
                .shouldBeNull()
            repository
                .getLatestMedia(MOVIES_LIBRARY.toString(), limit = 16)
                .getOrNull()!!
                .single()
                .overview
                .shouldBeNull()
        }

    @Test
    fun `the offline detail page still reads the overview the download cached`() =
        runTest {
            // The other half of the rule above: the blob's synopsis is dropped from the *rows*, not
            // from the item — the offline detail screen has nowhere else to read it from.
            coEvery { itemDao.getItem(uuid(1)) } returns
                entity(movieDto(uuid(1), "Arrival").copy(overview = SYNOPSIS))

            repository.getItem(uuid(1).toString()).getOrNull()!!.overview shouldBe SYNOPSIS
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
            seedLatest(listOf(entity(movieDto(uuid(1), "Dune")), entity(movieDto(uuid(2), "Arrival"))))

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
            // Regression guard: a downloaded film may be stored with `parentId NULL`; scoping a
            // library's row by a `parentId = <library>` predicate instead of by kind would leave
            // the offline home with no Latest row for it at all.
            val types = seedLatest(listOf(entity(movieDto(uuid(1), "The Body", parentId = null))))

            repository.getLatestMedia(MOVIES_LIBRARY.toString(), limit = 16).getOrNull()!! shouldHaveNames
                listOf("The Body")
            // A film library's Latest row is scoped by kind instead.
            types.single() shouldContainExactly listOf(ItemType.MOVIE)
        }

    @Test
    fun `a TV library's latest row is scoped to shows and episodes`() =
        runTest {
            val types = seedLatest(emptyList())

            repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 16)

            types.single() shouldContainExactly listOf(ItemType.SERIES, ItemType.EPISODE)
        }

    // ---- Latest: episodes group into their series (like the server's `GroupItems`) -------------

    @Test
    fun `every downloaded episode of a series collapses into one card for the series`() =
        runTest {
            // The reported bug: a downloaded season filled the offline Latest shelf with its own
            // episodes, where the online row shows one poster for the show.
            val series = entity(seriesDto(THRONES, "Thrones"))
            seedLatest(
                listOf(
                    thronesEpisode(uuid(13), "The Kingsroad", episodeNumber = 2),
                    thronesEpisode(uuid(12), "Winter Is Coming", episodeNumber = 1),
                    series,
                ),
            )

            val items = repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 16).getOrNull()!!

            items shouldHaveNames listOf("Thrones")
            items.single().type shouldBe ItemType.SERIES
            // The tap target is the series, so the card opens the show's page and not an episode.
            items.single().id shouldBe THRONES.toString()
        }

    @Test
    fun `each series gets its own card, most recently downloaded first`() =
        runTest {
            seedLatest(
                listOf(
                    entity(episodeDto(uuid(22), "The Heirs", DRAGON, "Dragon", uuid(21), 1, 1)),
                    entity(seriesDto(DRAGON, "Dragon")),
                    thronesEpisode(uuid(12), "Winter Is Coming", episodeNumber = 1),
                    thronesEpisode(uuid(13), "The Kingsroad", episodeNumber = 2),
                    entity(seriesDto(THRONES, "Thrones")),
                ),
            )

            repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 16).getOrNull()!! shouldHaveNames
                listOf("Dragon", "Thrones")
        }

    @Test
    fun `the row limit counts series, not episodes`() =
        runTest {
            seedLatest(
                (1..5).map { number -> thronesEpisode(uuid(100 + number), "E$number", number) } +
                    entity(seriesDto(THRONES, "Thrones")) +
                    entity(episodeDto(uuid(22), "The Heirs", DRAGON, "Dragon", uuid(21), 1, 1)) +
                    entity(seriesDto(DRAGON, "Dragon")),
            )

            // Five episodes of one show would have consumed the whole shelf before this fix.
            repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 2).getOrNull()!! shouldHaveNames
                listOf("Thrones", "Dragon")
        }

    @Test
    fun `a mixed library keeps one card per film alongside one card per series`() =
        runTest {
            seedLatest(
                listOf(
                    entity(movieDto(uuid(1), "Dune")),
                    thronesEpisode(uuid(12), "Winter Is Coming", episodeNumber = 1),
                    thronesEpisode(uuid(13), "The Kingsroad", episodeNumber = 2),
                    entity(seriesDto(THRONES, "Thrones")),
                    entity(movieDto(uuid(2), "Arrival")),
                ),
            )

            repository.getLatestMedia(MOVIES_LIBRARY.toString(), limit = 16).getOrNull()!! shouldHaveNames
                listOf("Dune", "Thrones", "Arrival")
        }

    @Test
    fun `the series card is the cached series row, artwork and all`() =
        runTest {
            seedLatest(listOf(thronesEpisode(uuid(12), "Winter Is Coming", episodeNumber = 1)))
            coEvery { itemDao.getItems(any()) } answers {
                val ids = firstArg<List<UUID>>().toSet()
                listOf(
                    entity(seriesDto(THRONES, "Thrones", primaryImageTag = "series-tag")),
                    thronesEpisode(uuid(12), "Winter Is Coming", episodeNumber = 1),
                ).filter { it.id in ids }
            }

            val card = repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 16).getOrNull()!!.single()

            card.id shouldBe THRONES.toString()
            card.name shouldBe "Thrones"
            card.primaryImageUrl!! shouldContain "/Items/$THRONES/Images/PRIMARY?tag=series-tag"
        }

    @Test
    fun `a series whose own row was never cached still gets a series card built from the episode`() =
        runTest {
            // `DownloadEnqueuer` caches an episode's parents best effort; when that fetch failed
            // there is no series row to show, and the shelf must not fall back to bare episodes.
            val episodes =
                listOf(
                    thronesEpisode(uuid(12), "Winter Is Coming", 1, seriesImageTag = "series-tag"),
                    thronesEpisode(uuid(13), "The Kingsroad", 2, seriesImageTag = "series-tag"),
                )
            seedLatest(newestFirst = episodes, cached = episodes)

            val card = repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 16).getOrNull()!!.single()

            card.id shouldBe THRONES.toString()
            card.name shouldBe "Thrones"
            card.type shouldBe ItemType.SERIES
            // The show's poster, not the episode still.
            card.primaryImageUrl!! shouldContain "/Items/$THRONES/Images/PRIMARY?tag=series-tag"
        }

    @Test
    fun `an episode that names no series at all still appears, as itself`() =
        runTest {
            val orphan =
                entity(
                    episodeDto(uuid(12), "Winter Is Coming", THRONES, "Thrones", uuid(11), 1, 1)
                        .copy(seriesId = null, seriesName = null),
                )
            seedLatest(listOf(orphan))

            repository.getLatestMedia(SHOWS_LIBRARY.toString(), limit = 16).getOrNull()!! shouldHaveNames
                listOf("Winter Is Coming")
        }

    // ---- library grid & search ----------------------------------------------------------------

    @Test
    fun `the library grid pages downloaded items with the query's offset and direction`() =
        runTest {
            val grid = stubGrid(itemDao, (1..101).map { entity(movieDto(uuid(it), "Film $it")) })

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

            // Offset and page size are applied to the ordered set the statement answered with.
            result.getOrNull()!! shouldHaveNames listOf("Film 101")
            // The direction is still the statement's job — SQLite owns the NOCASE collation.
            grid.descending.single() shouldBe true
        }

    @Test
    fun `the films grid lists a downloaded film whose parentId is NULL`() =
        runTest {
            // "Nothing to show here." on a device with a fully downloaded film was the symptom.
            val grid = stubGrid(itemDao, listOf(entity(movieDto(uuid(1), "The Body", parentId = null))))

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
            grid.types.single() shouldContainExactly listOf(ItemType.MOVIE)
        }

    @Test
    fun `a TV library's grid is narrowed to shows`() =
        runTest {
            val grid = stubGrid(itemDao, emptyList())

            repository.getItems(
                ItemQuery(
                    parentId = SHOWS_LIBRARY.toString(),
                    itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES),
                    limit = 50,
                ),
            )

            grid.types.single() shouldContainExactly listOf(ItemType.SERIES)
        }

    @Test
    fun `an unknown library narrows nothing rather than showing nothing`() =
        runTest {
            val grid = stubGrid(itemDao, emptyList())

            repository.getItems(
                ItemQuery(
                    parentId = uuid(77).toString(),
                    itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES),
                    limit = 50,
                ),
            )

            grid.types.single() shouldContainExactly listOf(ItemType.MOVIE, ItemType.SERIES)
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
                itemDao.downloadedListKeys(any(), any(), any(), any())
            }
        }

    @Test
    fun `an empty type list falls back to the kinds an offline list can contain`() =
        runTest {
            val grid = stubGrid(itemDao, emptyList())

            repository.getItems(ItemQuery(limit = 50))

            grid.types.single() shouldContainExactly
                listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.EPISODE)
        }

    @Test
    fun `filter facets are computed from what is actually downloaded`() =
        runTest {
            // A three-column projection, not whole rows: a facet list has no LIMIT, so reading it
            // as entities would deserialise every downloaded item's `dto` blob.
            coEvery { itemDao.facetKeysBySource(ItemSource.DOWNLOAD, any()) } returns
                listOf(
                    FacetKey(genres = listOf("Drama"), productionYear = 2016, officialRating = null),
                    FacetKey(
                        genres = listOf("Science Fiction", "Drama"),
                        productionYear = 2021,
                        officialRating = "PG-13",
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

    // ---- music -----------------------------------------------------------------------------------

    @Test
    fun `getAlbumTracks reads the downloaded tracks of that album`() =
        runTest {
            val albumId = uuid(30)
            coEvery { itemDao.tracksOfAlbum(ItemSource.DOWNLOAD, albumId, ItemType.AUDIO) } returns
                listOf(entity(audioDto(uuid(31), "Track 1", albumId = albumId, trackNumber = 1)))

            repository.getAlbumTracks(albumId.toString()).getOrNull()!! shouldHaveNames listOf("Track 1")
        }

    @Test
    fun `getAlbumTracks is empty for a malformed album id`() =
        runTest {
            repository.getAlbumTracks("not-a-uuid").getOrNull()!!.shouldBeEmpty()
        }

    @Test
    fun `getArtistAlbums reads the downloaded albums of that artist`() =
        runTest {
            val artistId = uuid(40)
            coEvery { itemDao.albumsOfArtist(ItemSource.DOWNLOAD, artistId, ItemType.MUSIC_ALBUM) } returns
                listOf(entity(albumDto(uuid(41), "The Bends", albumArtistId = artistId)))

            repository.getArtistAlbums(artistId.toString()).getOrNull()!! shouldHaveNames listOf("The Bends")
        }

    @Test
    fun `getArtistTopTracks walks every downloaded album and ranks tracks by their cached play count`() =
        runTest {
            val artistId = uuid(50)
            val albumOne = uuid(51)
            val albumTwo = uuid(52)
            coEvery { itemDao.albumsOfArtist(ItemSource.DOWNLOAD, artistId, ItemType.MUSIC_ALBUM) } returns
                listOf(entity(albumDto(albumOne, "Album One")), entity(albumDto(albumTwo, "Album Two")))
            coEvery { itemDao.tracksOfAlbum(ItemSource.DOWNLOAD, albumOne, ItemType.AUDIO) } returns
                listOf(entity(audioDto(uuid(60), "Quiet Track", albumId = albumOne, playCount = 1)))
            coEvery { itemDao.tracksOfAlbum(ItemSource.DOWNLOAD, albumTwo, ItemType.AUDIO) } returns
                listOf(entity(audioDto(uuid(61), "Loud Track", albumId = albumTwo, playCount = 9)))

            val tracks = repository.getArtistTopTracks(artistId.toString(), limit = 10).getOrNull()!!

            // The track with the higher recorded play count leads, whichever album it came from.
            // No local `user_data` row exists for either (the default stub answers empty), so each
            // track's cached blob is what carries the count read here.
            tracks.map { it.name } shouldContainExactly listOf("Loud Track", "Quiet Track")
        }

    @Test
    fun `getArtistTopTracks honours the limit`() =
        runTest {
            val artistId = uuid(70)
            coEvery { itemDao.albumsOfArtist(ItemSource.DOWNLOAD, artistId, ItemType.MUSIC_ALBUM) } returns
                listOf(entity(albumDto(uuid(71), "Album")))
            coEvery { itemDao.tracksOfAlbum(ItemSource.DOWNLOAD, uuid(71), ItemType.AUDIO) } returns
                (1..5).map { entity(audioDto(uuid(80 + it), "Track $it", albumId = uuid(71))) }

            repository.getArtistTopTracks(artistId.toString(), limit = 2).getOrNull()!! shouldHaveSize 2
        }

    @Test
    fun `getPlaylistItems is always empty offline — no playlist-membership relation exists yet`() =
        runTest {
            repository.getPlaylistItems(uuid(1).toString()).getOrNull()!!.shouldBeEmpty()

            coVerify(exactly = 0) { itemDao.getItem(any()) }
        }

    // ---- Continue Listening -------------------------------------------------------------------

    @Test
    fun `getResumeAudioItems lists downloaded tracks this device has a resume position for`() =
        runTest {
            val track = audioDto(uuid(90), "Fake Plastic Trees")
            coEvery { itemDao.resumeDownloadedAudio(ItemSource.DOWNLOAD, USER_ID, ItemType.AUDIO, 12) } returns
                listOf(entity(track))
            coEvery { userDataDao.getUserDataFor(listOf(uuid(90)), USER_ID) } returns
                listOf(userData(uuid(90), positionTicks = 30_000_000L))

            val items = repository.getResumeAudioItems(limit = 12).getOrNull()!!

            items.single().name shouldBe "Fake Plastic Trees"
            items.single().userData.playbackPositionTicks shouldBe 30_000_000L
        }

    @Test
    fun `getResumeAudioItems is empty with no signed-in user`() =
        runTest {
            every { sessionRepository.sessionState } returns MutableStateFlow(SessionState.LoggedOut)

            repository.getResumeAudioItems(limit = 12).getOrNull()!!.shouldBeEmpty()

            coVerify(exactly = 0) { itemDao.resumeDownloadedAudio(any(), any(), any(), any()) }
        }

    // ---- Instant Mix & lyrics -----------------------------------------------------------------

    @Test
    fun `getInstantMix always refuses offline`() =
        runTest {
            val result = repository.getInstantMix(uuid(1).toString())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    @Test
    fun `getLyrics always refuses offline`() =
        runTest {
            val result = repository.getLyrics(uuid(1).toString())

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Network>()
        }

    // ---- failure modes ------------------------------------------------------------------------

    @Test
    fun `a database failure is reported as a storage error`() =
        runTest {
            coEvery { libraryViewDao.getAll() } throws SQLiteException("database gone")

            val result = repository.getUserViews()

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Storage>()
        }

    // ---- helpers ------------------------------------------------------------------------------

    private infix fun List<JellyfinItem>.shouldHaveNames(names: List<String>) {
        map { it.name } shouldContainExactly names
    }

    /**
     * Stands `latestDownloadedKeys` + `getItems` up over a list of rows.
     *
     * [newestFirst] is what the ordering query would answer — the rows in `cachedAt DESC` order —
     * and the grouping key is derived here exactly the way the SQL `CASE` does, so a test states
     * only what is in the table. [cached] is what `getItems` can find, which defaults to the same
     * rows and is narrowed by the one test where a series' own row is missing.
     *
     * @return the captured item-type lists the offline library scoping asked for.
     */
    private fun seedLatest(
        newestFirst: List<ItemEntity>,
        cached: List<ItemEntity> = newestFirst,
    ): List<List<ItemType>> {
        val types = mutableListOf<List<ItemType>>()
        val keys =
            newestFirst.map { row ->
                LatestDownloadKey(
                    id = row.id,
                    groupId = if (row.type == ItemType.EPISODE) row.seriesId ?: row.id else row.id,
                )
            }
        coEvery {
            itemDao.latestDownloadedKeys(ItemSource.DOWNLOAD, capture(types), ItemType.EPISODE)
        } returns keys
        coEvery { itemDao.getItems(any()) } answers {
            val ids = firstArg<List<UUID>>().toSet()
            cached.filter { it.id in ids }
        }
        return types
    }

    private fun thronesEpisode(
        id: UUID,
        name: String,
        episodeNumber: Int,
        seriesImageTag: String? = null,
    ) = entity(
        episodeDto(
            id = id,
            name = name,
            seriesId = THRONES,
            seriesName = "Thrones",
            seasonId = uuid(11),
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            seriesPrimaryImageTag = seriesImageTag,
        ),
    )

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

    private companion object {
        val THRONES: UUID = uuid(10)
        val DRAGON: UUID = uuid(20)

        /** A synopsis long enough to unbalance the home hero if it were not stripped. */
        const val SYNOPSIS =
            "A linguist is recruited by the military to communicate with alien lifeforms after " +
                "twelve mysterious spacecraft appear around the world."
    }

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.randomUUID(),
            userId = USER_ID,
            userName = "casey",
            serverName = "test-server",
            serverVersion = "10.11.11",
        )
}
