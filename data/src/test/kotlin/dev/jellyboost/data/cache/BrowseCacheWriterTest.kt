package dev.jellyboost.data.cache

import android.database.sqlite.SQLiteException
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.ItemCacheKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.database.entities.LibraryViewEntity
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.CacheFixtures.MOVIES_LIBRARY
import dev.jellyboost.data.cache.CacheFixtures.NOW
import dev.jellyboost.data.cache.CacheFixtures.USER_ID
import dev.jellyboost.data.cache.CacheFixtures.entity
import dev.jellyboost.data.cache.CacheFixtures.mapper
import dev.jellyboost.data.cache.CacheFixtures.movieDto
import dev.jellyboost.data.cache.CacheFixtures.uuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Pins [BrowseCacheWriter]'s three rules, each of which has cost data before:
 *
 * - **A browse write never downgrades a download** — a scroll past a downloaded film would make its
 *   row evictable and orphan gigabytes on disk. Its blob is protected from a *lean* write only; a
 *   `full` one must replace it, or a row an older build gutted stays bare forever.
 * - **A server read refreshes `user_data` unless the row is pending** — otherwise the next
 *   `setPosition` pushes a stale local row straight back over another client's change.
 * - **The merge decides on a snapshot it holds** — `DownloadEnqueuer` upserts `DOWNLOAD` rows to the
 *   same DAO and can overtake a read-think-write split across three statements.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseCacheWriterTest {
    private val itemDao = mockk<ItemDao>()
    private val libraryViewDao = mockk<LibraryViewDao>()
    private val userDataDao = mockk<UserDataDao>()
    private val sessionRepository = mockk<SessionRepository>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val transactionRunner = RecordingTransactionRunner()

    private val upserted = slot<List<ItemEntity>>()
    private val userDataRows = slot<List<UserDataEntity>>()

    @BeforeEach
    fun setUp() {
        coEvery { itemDao.getCacheKeys(any()) } returns emptyList()
        coEvery { itemDao.getItems(any()) } returns emptyList()
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { libraryViewDao.upsert(any()) } just Runs
        coEvery { libraryViewDao.deleteExcept(any()) } just Runs
        coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns emptyList()
        coEvery { userDataDao.upsertAll(capture(userDataRows)) } just Runs
        every { sessionRepository.sessionState } returns MutableStateFlow(loggedIn())
    }

    private fun TestScope.writer() =
        BrowseCacheWriter(
            itemDao = itemDao,
            libraryViewDao = libraryViewDao,
            userDataDao = userDataDao,
            sessionRepository = sessionRepository,
            mapper = mapper,
            maintenance = CacheFixtures.maintenance(this, itemDao, clock),
            clock = clock,
            transactionRunner = transactionRunner,
            scope = this,
        )

    // ---- the merge rule -----------------------------------------------------------------------

    @Test
    fun `a browsed item that is not cached yet becomes a browse-cache row`() =
        runTest {
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            upserted.captured.single().source shouldBe ItemSource.BROWSE_CACHE
            upserted.captured.single().cachedAt shouldBe NOW
        }

    @Test
    fun `browsing past a downloaded item never demotes it to browse cache`() =
        runTest {
            val downloadedAt = Instant.parse("2026-07-01T08:00:00Z")
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, downloadedAt))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            upserted.captured.single().source shouldBe ItemSource.DOWNLOAD
        }

    @Test
    fun `browsing past a downloaded item does not reshuffle the recently-downloaded rows`() =
        runTest {
            val downloadedAt = Instant.parse("2026-07-01T08:00:00Z")
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, downloadedAt))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            // The offline "Latest" rows order by cachedAt: bumping it reorders them on every online
            // home-screen load.
            upserted.captured.single().cachedAt shouldBe downloadedAt
        }

    @Test
    fun `still refreshes the metadata of a downloaded item`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, NOW))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival (Director's Cut)")))

            upserted.captured.single().name shouldBe "Arrival (Director's Cut)"
        }

    @Test
    fun `an existing browse-cache row is refreshed in place`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.BROWSE_CACHE, NOW.minusSeconds(3_600)))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            upserted.captured.single().source shouldBe ItemSource.BROWSE_CACHE
            upserted.captured.single().cachedAt shouldBe NOW
        }

    @Test
    fun `browsing past a downloaded item keeps its rich blob, not the lean list DTO`() =
        runTest {
            val richDto =
                movieDto(uuid(1), "Arrival", genres = listOf("Science Fiction", "Drama"))
                    .copy(overview = "A linguist deciphers an alien language.")
            val storedEntity = entity(richDto, source = ItemSource.DOWNLOAD, cachedAt = NOW.minusSeconds(3_600))

            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, storedEntity.cachedAt))
            coEvery { itemDao.getItems(listOf(uuid(1))) } returns listOf(storedEntity)

            // What a list read sends: only PRIMARY_IMAGE_ASPECT_RATIO, so no overview or genres.
            val leanDto = BaseItemDto(id = uuid(1), type = BaseItemKind.MOVIE, name = "Arrival")

            writer().writeItems(listOf(leanDto), full = false)

            val row = upserted.captured.single()
            row.source shouldBe ItemSource.DOWNLOAD
            val rebuilt = mapper.toDtoOrNull(row)
            rebuilt?.overview shouldBe "A linguist deciphers an alien language."
            rebuilt?.genres shouldContainExactly listOf("Science Fiction", "Drama")
        }

    @Test
    fun `a full detail read replaces a downloaded item's blob instead of preserving it`() =
        runTest {
            val stored =
                entity(
                    movieDto(uuid(1), "Arrival", genres = listOf("Drama"))
                        .copy(overview = "The synopsis the server has since corrected."),
                    source = ItemSource.DOWNLOAD,
                    cachedAt = NOW.minusSeconds(3_600),
                )
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, stored.cachedAt))
            coEvery { itemDao.getItems(listOf(uuid(1))) } returns listOf(stored)

            val fullDto =
                movieDto(uuid(1), "Arrival", genres = listOf("Science Fiction"))
                    .copy(overview = "A linguist deciphers an alien language.")

            writer().writeItems(listOf(fullDto), full = true)

            val row = upserted.captured.single()
            val rebuilt = mapper.toDtoOrNull(row)
            rebuilt?.overview shouldBe "A linguist deciphers an alien language."
            rebuilt?.genres shouldContainExactly listOf("Science Fiction")
            // A download keeps both whatever the write: its source, and its place in the offline
            // "recently downloaded" order.
            row.source shouldBe ItemSource.DOWNLOAD
            row.cachedAt shouldBe stored.cachedAt
        }

    @Test
    fun `a full detail read repairs a downloaded item whose blob an older build gutted`() =
        runTest {
            // Once a lean write has gutted the blob, every later browse write preserves *that*, so
            // opening the item online has to be able to undo it.
            val gutted =
                entity(
                    BaseItemDto(id = uuid(1), type = BaseItemKind.MOVIE, name = "Arrival"),
                    source = ItemSource.DOWNLOAD,
                    cachedAt = NOW.minusSeconds(86_400),
                )
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, gutted.cachedAt))
            coEvery { itemDao.getItems(listOf(uuid(1))) } returns listOf(gutted)

            val fullDto =
                movieDto(uuid(1), "Arrival", genres = listOf("Science Fiction", "Drama"))
                    .copy(overview = "A linguist deciphers an alien language.")

            writer().writeItems(listOf(fullDto), full = true)

            val rebuilt = mapper.toDtoOrNull(upserted.captured.single())
            rebuilt?.overview shouldBe "A linguist deciphers an alien language."
            rebuilt?.genres shouldContainExactly listOf("Science Fiction", "Drama")
        }

    @Test
    fun `a full detail read does not read the stored blobs back at all`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, NOW))

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")), full = true)

            // About to overwrite every one of them, so fetching the blobs first is pure waste.
            coVerify(exactly = 0) { itemDao.getItems(any()) }
        }

    @Test
    fun `merges each item against its own stored row`() =
        runTest {
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(2), ItemSource.DOWNLOAD, NOW))

            writer().writeItems(
                listOf(movieDto(uuid(1), "Arrival"), movieDto(uuid(2), "Dune"), movieDto(uuid(3), "Sicario")),
            )

            upserted.captured.map { it.source } shouldContainExactly
                listOf(ItemSource.BROWSE_CACHE, ItemSource.DOWNLOAD, ItemSource.BROWSE_CACHE)
        }

    // ---- the merge is atomic ------------------------------------------------------------------

    /** The downgrade race: `DownloadEnqueuer.write` could commit between the read and the write. */
    @Test
    fun `the source snapshot, the blob read and the upsert all happen in one transaction`() =
        runTest {
            val depths = mutableListOf<Int>()
            coEvery { itemDao.getCacheKeys(any()) } answers {
                depths += transactionRunner.depth
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, NOW))
            }
            coEvery { itemDao.getItems(any()) } answers {
                depths += transactionRunner.depth
                emptyList()
            }
            coEvery { itemDao.upsert(capture(upserted)) } answers {
                depths += transactionRunner.depth
            }

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            transactionRunner.opened shouldBe 1
            // Every one of them saw an open transaction — a zero here is the race.
            depths shouldContainExactly listOf(1, 1, 1)
        }

    @Test
    fun `a download enqueued while the merge is deciding cannot be downgraded by it`() =
        runTest {
            // BROWSE_CACHE when the snapshot was taken, DOWNLOAD by the time the write lands.
            val stored = ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, NOW.minusSeconds(3_600))

            val rows =
                writer().mergeRows(
                    dtos = listOf(movieDto(uuid(1), "Arrival")),
                    existing = mapOf(uuid(1) to stored),
                    richBlobs = emptyMap(),
                    now = NOW,
                )

            rows.single().source shouldBe ItemSource.DOWNLOAD
            rows.single().cachedAt shouldBe stored.cachedAt
        }

    @Test
    fun `a failed merge write rolls the whole block back rather than half-writing it`() =
        runTest {
            coEvery { itemDao.upsert(any()) } throws SQLiteException("disk full")

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            // Swallowed — a broken cache never fails a read — but it must still reach the runner,
            // which is what makes Room roll back.
            transactionRunner.rolledBack shouldBe 1
        }

    // ---- the user-data refresh rule -------------------------------------------------------------

    @Test
    fun `a stale synced row is refreshed from what the server just said`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns emptyList()

            writer().writeItems(
                listOf(movieDto(uuid(1), "Citizen Vigilante").withUserData(played = false)),
            )

            val row = userDataRows.captured.single()
            row.itemId shouldBe uuid(1)
            row.userId shouldBe USER_ID
            row.played shouldBe false
            row.toBeSynced shouldBe false
        }

    @Test
    fun `a row waiting to be pushed is left completely untouched`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns listOf(uuid(1))

            writer().writeItems(
                listOf(movieDto(uuid(1), "Citizen Vigilante").withUserData(played = false)),
            )

            // A pending row is the only copy of an unaccepted change; reconciling is the sync
            // worker's job, not a cache write's.
            coVerify(exactly = 0) { userDataDao.upsertAll(any()) }
        }

    @Test
    fun `an item with no local row at all gets one from the server`() =
        runTest {
            writer().writeItems(
                listOf(
                    movieDto(uuid(1), "Arrival").withUserData(
                        played = true,
                        isFavorite = true,
                        positionTicks = 42L,
                        lastPlayedDate = PLAYED_AT,
                    ),
                ),
            )

            val row = userDataRows.captured.single()
            row.played shouldBe true
            row.isFavorite shouldBe true
            row.playbackPositionTicks shouldBe 42L
            row.lastPlayedDate shouldBe PLAYED_AT.atZone(ZoneId.systemDefault()).toInstant()
        }

    @Test
    fun `adopting server state records when the mirror was refreshed, not a newer-than-server write`() =
        runTest {
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData()))

            val row = userDataRows.captured.single()
            row.updatedAt shouldBe NOW
            // `lastPlayedDate` stays the server's own value — never invented from the read.
            row.lastPlayedDate.shouldBeNull()
            row.toBeSynced shouldBe false
        }

    @Test
    fun `a response carrying no user data writes no user-data rows`() =
        runTest {
            // `enableUserData` off, or an endpoint that omits the block: silence is not "unwatched".
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))

            coVerify(exactly = 0) { userDataDao.getPendingSyncIds(any(), any()) }
            coVerify(exactly = 0) { userDataDao.upsertAll(any()) }
        }

    @Test
    fun `refreshes every non-pending item of a mixed page`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } returns listOf(uuid(2))

            writer().writeItems(
                listOf(
                    movieDto(uuid(1), "Arrival").withUserData(played = true),
                    movieDto(uuid(2), "Dune").withUserData(played = true),
                    movieDto(uuid(3), "Sicario"),
                    movieDto(uuid(4), "Prisoners").withUserData(played = false),
                ),
            )

            userDataRows.captured.map { it.itemId } shouldContainExactly listOf(uuid(1), uuid(4))
            // The pending row's id is still asked about — that is how it gets excluded.
            coVerify(exactly = 1) {
                userDataDao.getPendingSyncIds(listOf(uuid(1), uuid(2), uuid(4)), USER_ID)
            }
        }

    @Test
    fun `the pending filter and the user-data write happen in one transaction`() =
        runTest {
            // Without one transaction a local write landing between them is overwritten flag and
            // all, so `UserDataSyncWorker` never sees it and the change is gone for good.
            val depths = mutableListOf<Int>()
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } answers {
                depths += transactionRunner.depth
                emptyList()
            }
            coEvery { userDataDao.upsertAll(capture(userDataRows)) } answers {
                depths += transactionRunner.depth
            }

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            transactionRunner.opened shouldBe 2
            depths shouldContainExactly listOf(1, 1)
        }

    @Test
    fun `a failed user-data refresh rolls its own block back without touching the item merge`() =
        runTest {
            coEvery { userDataDao.upsertAll(any()) } throws SQLiteException("disk full")

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            // Swallowed, but it must reach the runner; the item merge's transaction already
            // committed.
            transactionRunner.opened shouldBe 2
            transactionRunner.rolledBack shouldBe 1
        }

    @Test
    fun `writes no user data when nobody is signed in`() =
        runTest {
            every { sessionRepository.sessionState } returns MutableStateFlow(SessionState.LoggedOut)

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            // No userId to key rows on. The metadata half still runs.
            coVerify(exactly = 0) { userDataDao.upsertAll(any()) }
            coVerify(exactly = 1) { itemDao.upsert(any()) }
        }

    @Test
    fun `a failing user-data refresh still leaves the metadata cached`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } throws SQLiteException("disk full")

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            coVerify(exactly = 1) { itemDao.upsert(any()) }
        }

    @Test
    fun `a failing metadata write still refreshes the user data`() =
        runTest {
            coEvery { itemDao.upsert(any()) } throws SQLiteException("disk full")

            writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))

            userDataRows.captured.single().played shouldBe true
        }

    // ---- resilience ---------------------------------------------------------------------------

    @Test
    fun `writes nothing at all for an empty response`() =
        runTest {
            writer().cacheItems(emptyList())

            coVerify(exactly = 0) { itemDao.upsert(any()) }
            coVerify(exactly = 0) { itemDao.getCacheKeys(any()) }
        }

    @Test
    fun `a failed cache write is swallowed, never surfaced to the caller`() =
        runTest {
            coEvery { itemDao.upsert(any()) } throws SQLiteException("disk full")

            // The caller already has its data; a broken cache must not fail their read.
            writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))
        }

    /**
     * The guards run on the never-cancelled application scope today, but swallowing a cancellation
     * would turn any future structured cancellation into a silent success.
     */
    @Test
    fun `a cancelled item write propagates instead of being logged as a failure`() =
        runTest {
            coEvery { itemDao.upsert(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                writer().writeItems(listOf(movieDto(uuid(1), "Arrival")))
            }
        }

    @Test
    fun `a cancelled user-data refresh propagates`() =
        runTest {
            coEvery { userDataDao.getPendingSyncIds(any(), any()) } throws
                CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                writer().writeItems(listOf(movieDto(uuid(1), "Arrival").withUserData(played = true)))
            }
        }

    @Test
    fun `a cancelled library-view write propagates`() =
        runTest {
            coEvery { libraryViewDao.upsert(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                writer().writeViews(listOf(library(MOVIES_LIBRARY, "Films", CollectionType.MOVIES)))
            }
        }

    // ---- library views ------------------------------------------------------------------------

    @Test
    fun `caches supported libraries in server order and drops the rest`() =
        runTest {
            val rows = slot<List<LibraryViewEntity>>()
            coEvery { libraryViewDao.upsert(capture(rows)) } just Runs

            writer().writeViews(
                listOf(
                    library(MOVIES_LIBRARY, "Films", CollectionType.MOVIES),
                    // Photos are not in `CollectionKind.SUPPORTED`; music is.
                    library(uuid(50), "Photos", CollectionType.PHOTOS),
                    library(uuid(51), "Séries", CollectionType.TVSHOWS),
                ),
            )

            rows.captured.map { it.name } shouldContainExactly listOf("Films", "Séries")
            // The *response* position, so the offline list matches the online one despite the drop.
            rows.captured.map { it.sortIndex } shouldContainExactly listOf(0, 2)
        }

    @Test
    fun `music libraries are cached too, since M13 Phase 2`() =
        runTest {
            val rows = slot<List<LibraryViewEntity>>()
            coEvery { libraryViewDao.upsert(capture(rows)) } just Runs

            writer().writeViews(listOf(library(uuid(52), "Musique", CollectionType.MUSIC)))

            rows.captured.map { it.name } shouldContainExactly listOf("Musique")
            rows.captured.single().collectionType shouldBe CollectionKind.MUSIC.name
        }

    @Test
    fun `prunes libraries the server no longer reports`() =
        runTest {
            writer().writeViews(listOf(library(MOVIES_LIBRARY, "Films", CollectionType.MOVIES)))

            coVerify(exactly = 1) { libraryViewDao.deleteExcept(listOf(MOVIES_LIBRARY)) }
        }

    @Test
    fun `never wipes the cached libraries when nothing supported came back`() =
        runTest {
            writer().writeViews(listOf(library(uuid(50), "Photos", CollectionType.PHOTOS)))

            coVerify(exactly = 0) { libraryViewDao.deleteExcept(any()) }
            coVerify(exactly = 0) { libraryViewDao.upsert(any()) }
        }

    private fun library(
        id: UUID,
        name: String,
        collectionType: CollectionType,
    ): BaseItemDto =
        BaseItemDto(
            id = id,
            type = BaseItemKind.COLLECTION_FOLDER,
            name = name,
            collectionType = collectionType,
        )

    private fun BaseItemDto.withUserData(
        played: Boolean = false,
        isFavorite: Boolean = false,
        positionTicks: Long = 0L,
        lastPlayedDate: LocalDateTime? = null,
    ): BaseItemDto =
        copy(
            userData =
                UserItemDataDto(
                    playbackPositionTicks = positionTicks,
                    playCount = if (played) 1 else 0,
                    isFavorite = isFavorite,
                    played = played,
                    lastPlayedDate = lastPlayedDate,
                    key = id.toString(),
                    itemId = id,
                ),
        )

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.randomUUID(),
            userId = USER_ID,
            userName = "casey",
            serverName = "home",
            serverVersion = "10.11.0",
        )

    private companion object {
        /** The SDK reads its date fields in the device's zone, so this is a local wall-clock time. */
        val PLAYED_AT: LocalDateTime = LocalDateTime.of(2026, 7, 20, 21, 30)
    }
}
