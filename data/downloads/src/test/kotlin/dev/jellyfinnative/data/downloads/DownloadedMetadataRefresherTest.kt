package dev.jellyfinnative.data.downloads

import android.database.sqlite.SQLiteException
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.database.dao.DownloadDao
import dev.jellyfinnative.core.database.dao.ItemDao
import dev.jellyfinnative.core.database.entities.ItemCacheKey
import dev.jellyfinnative.core.database.entities.ItemEntity
import dev.jellyfinnative.core.database.entities.ItemSource
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.core.network.session.SessionGate
import dev.jellyfinnative.data.cache.ItemEntityMapper
import dev.jellyfinnative.data.downloads.DownloadFixtures.NOW
import dev.jellyfinnative.data.downloads.DownloadFixtures.episode
import dev.jellyfinnative.data.downloads.DownloadFixtures.movie
import dev.jellyfinnative.data.downloads.DownloadFixtures.season
import dev.jellyfinnative.data.downloads.DownloadFixtures.series
import dev.jellyfinnative.data.downloads.DownloadFixtures.uuid
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [DownloadedMetadataRefresher].
 *
 * The class is an **ongoing** sync — a download's metadata is written once at enqueue time and would
 * otherwise never track the server's copy again, so a retitled film or new artwork stays wrong
 * offline forever — and its first pass on a device that upgraded across the lean-write bug also
 * happens to heal a whole table of gutted rows at once. Both readings want the same two things
 * pinned above all: that it fires at the moments nothing else would (app start online, and the
 * return of the connection), and that what it writes is what `DownloadEnqueuer` writes for a fresh
 * download — `source = DOWNLOAD`, parents included — **except** for `cachedAt`, which a bulk pass
 * must leave alone or every sync reshuffles the offline "recently downloaded" rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedMetadataRefresherTest {
    private val state = MutableStateFlow(ConnectionState.ONLINE)
    private val connectionState =
        mockk<ConnectionStateProvider> {
            every { this@mockk.state } returns this@DownloadedMetadataRefresherTest.state
        }
    private val sessionGate = mockk<SessionGate>()
    private val api = mockk<DownloadApi>()
    private val downloadDao = mockk<DownloadDao>()
    private val itemDao = mockk<ItemDao>(relaxUnitFun = true)
    private val mapper = mockk<ItemEntityMapper>()
    private val sidecars = mockk<SubtitleSidecarTopUp>()
    private val clock = Clock.fixed(REFRESHED_AT, ZoneOffset.UTC)

    private val upserted = slot<List<ItemEntity>>()

    @BeforeEach
    fun setUp() {
        coEvery { sessionGate.ensureSession() } returns true
        coEvery { downloadDao.allItemIds() } returns emptyList()
        coEvery { itemDao.getCacheKeys(any()) } returns emptyList()
        coEvery { itemDao.upsert(capture(upserted)) } just Runs
        coEvery { sidecars.topUp(any()) } returns 0
        // `toEntity` is overloaded (items and library views), so the argument types are explicit.
        every { mapper.toEntity(any<BaseItemDto>(), any<ItemSource>(), any<Instant>()) } answers {
            entity(firstArg(), secondArg(), thirdArg())
        }
    }

    // ---- when it fires ---------------------------------------------------------------------------

    @Test
    fun `an app start while online refreshes the downloaded items`() =
        runTest {
            givenDownloads(uuid(1))

            refresher().start()
            runCurrent()

            // The state flow replays its current value, so the first collection *is* the app-start
            // check — the same one-code-path shape UserDataSyncTrigger uses.
            coVerify(exactly = 1) { api.getFullItems(listOf(uuid(1))) }
        }

    @Test
    fun `coming back online refreshes the downloaded items`() =
        runTest {
            givenDownloads(uuid(1))
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            refresher().start()
            runCurrent()
            state.value = ConnectionState.ONLINE
            runCurrent()

            coVerify(exactly = 1) { api.getFullItems(listOf(uuid(1))) }
        }

    @Test
    fun `starting offline touches the server not at all`() =
        runTest {
            givenDownloads(uuid(1))
            state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE

            refresher().start()
            runCurrent()

            coVerify(exactly = 0) { api.getFullItems(any()) }
        }

    @Test
    fun `swapping between two offline reasons refreshes nothing`() =
        runTest {
            givenDownloads(uuid(1))
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            refresher().start()
            runCurrent()
            state.value = ConnectionState.OFFLINE_FORCED
            runCurrent()

            coVerify(exactly = 0) { api.getFullItems(any()) }
        }

    @Test
    fun `one online stretch is refreshed once, however many times it is asked`() =
        runTest {
            givenDownloads(uuid(1))
            val refresher = refresher()

            refresher.start()
            runCurrent()
            refresher.refresh()
            refresher.refresh()

            coVerify(exactly = 1) { api.getFullItems(any()) }
        }

    @Test
    fun `losing the connection re-arms the refresh for the next online stretch`() =
        runTest {
            givenDownloads(uuid(1))
            val refresher = refresher()

            refresher.start()
            runCurrent()
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            runCurrent()
            state.value = ConnectionState.ONLINE
            runCurrent()

            // Cheap enough to repeat, and the only way a pass that failed while the server was
            // half-reachable ever gets a second chance.
            coVerify(exactly = 2) { api.getFullItems(listOf(uuid(1))) }
        }

    @Test
    fun `nothing downloaded means nothing is asked of the server`() =
        runTest {
            refresher().start()
            runCurrent()

            coVerify(exactly = 0) { api.getFullItems(any()) }
            coVerify(exactly = 0) { itemDao.upsert(any()) }
            // Not even a session restore: an empty downloads table has nothing to sync.
            coVerify(exactly = 0) { sessionGate.ensureSession() }
        }

    // ---- what it writes --------------------------------------------------------------------------

    @Test
    fun `the refreshed items are written as downloads, never as browse cache`() =
        runTest {
            givenDownloads(uuid(1))

            refresher().refresh()

            // A BROWSE_CACHE row is evictable, and evicting it would orphan the files on disk.
            upserted.captured.map { it.source }.distinct() shouldContainExactlyInAnyOrder
                listOf(ItemSource.DOWNLOAD)
        }

    @Test
    fun `a downloaded episode has its series and season refreshed too`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(2))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
                AppResult.Success(listOf(series(), season()))

            refresher().refresh()

            // The same bug gutted the parents, and they are what the offline "walk up to the show"
            // path reads.
            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(2), uuid(10), uuid(11))
        }

    @Test
    fun `a parent that is itself downloaded is not fetched twice`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(2), uuid(11))
            coEvery { api.getFullItems(listOf(uuid(2), uuid(11))) } returns
                AppResult.Success(listOf(episode(), season()))
            coEvery { api.getFullItems(listOf(uuid(10))) } returns AppResult.Success(listOf(series()))

            refresher().refresh()

            coVerify(exactly = 1) { api.getFullItems(listOf(uuid(10))) }
            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(2), uuid(10), uuid(11))
        }

    @Test
    fun `an existing row keeps the timestamp it was downloaded at`() =
        runTest {
            givenDownloads(uuid(1))
            coEvery { itemDao.getCacheKeys(listOf(uuid(1))) } returns
                listOf(ItemCacheKey(uuid(1), ItemSource.DOWNLOAD, DOWNLOADED_AT))

            refresher().refresh()

            // `cachedAt` orders the offline "recently downloaded" rows, so stamping `now` onto every
            // download at once would reshuffle the offline home into refresh order.
            upserted.captured.single().cachedAt shouldBe DOWNLOADED_AT
        }

    @Test
    fun `a row this pass creates is stamped with the current time`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(2))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
                AppResult.Success(listOf(series(), season()))
            coEvery { itemDao.getCacheKeys(any()) } returns
                listOf(ItemCacheKey(uuid(2), ItemSource.DOWNLOAD, DOWNLOADED_AT))

            refresher().refresh()

            // The episode was already cached; its series and season had never been written at all.
            upserted.captured.associate { it.id to it.cachedAt } shouldBe
                mapOf(uuid(2) to DOWNLOADED_AT, uuid(10) to REFRESHED_AT, uuid(11) to REFRESHED_AT)
        }

    @Test
    fun `more items than one request can carry are fetched in batches`() =
        runTest {
            val ids = (1..120).map { uuid(it) }
            coEvery { downloadDao.allItemIds() } returns ids
            coEvery { api.getFullItems(any()) } answers {
                AppResult.Success(firstArg<List<UUID>>().map { movie(id = it) })
            }

            refresher().refresh()

            coVerify(exactly = 1) { api.getFullItems(ids.subList(0, 50)) }
            coVerify(exactly = 1) { api.getFullItems(ids.subList(50, 100)) }
            coVerify(exactly = 1) { api.getFullItems(ids.subList(100, 120)) }
            upserted.captured.size shouldBe 120
        }

    // ---- the file top-up -------------------------------------------------------------------------

    @Test
    fun `each pass offers its fresh DTOs to the sidecar top-up`() =
        runTest {
            givenDownloads(uuid(1))

            refresher().refresh()

            // The DTOs, not the ids: the top-up plans from them, and a stale cached blob is exactly
            // what would plan the wrong stream set.
            coVerify(exactly = 1) { sidecars.topUp(match { items -> items.map { it.id } == listOf(uuid(1)) }) }
        }

    @Test
    fun `parents are not offered to the top-up, having no files of their own`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(2))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns
                AppResult.Success(listOf(series(), season()))

            refresher().refresh()

            coVerify { sidecars.topUp(match { items -> items.map { it.id } == listOf(uuid(2)) }) }
        }

    @Test
    fun `a failing fetch tops up nothing either`() =
        runTest {
            givenDownloads(uuid(1))
            coEvery { api.getFullItems(any()) } returns AppResult.Failure(AppError.Network())

            refresher().refresh()

            coVerify(exactly = 0) { sidecars.topUp(any()) }
        }

    @Test
    fun `a failing top-up does not cost the metadata pass its write`() =
        runTest {
            givenDownloads(uuid(1))
            coEvery { sidecars.topUp(any()) } throws IOException("no storage volume")

            refresher().refresh()

            // The write already happened; a small optional file failing must not undo it, and the
            // next connectivity edge tries the top-up again anyway.
            coVerify(exactly = 1) { itemDao.upsert(any()) }
        }

    // ---- what it survives ------------------------------------------------------------------------

    @Test
    fun `a failing fetch writes nothing and does not throw`() =
        runTest {
            givenDownloads(uuid(1))
            coEvery { api.getFullItems(any()) } returns AppResult.Failure(AppError.Network())

            refresher().refresh()

            coVerify(exactly = 0) { itemDao.upsert(any()) }
        }

    @Test
    fun `one failing batch does not cost the others their update`() =
        runTest {
            val ids = (1..60).map { uuid(it) }
            coEvery { downloadDao.allItemIds() } returns ids
            coEvery { api.getFullItems(ids.subList(0, 50)) } returns
                AppResult.Success(ids.subList(0, 50).map { movie(id = it) })
            coEvery { api.getFullItems(ids.subList(50, 60)) } returns AppResult.Failure(AppError.Network())

            refresher().refresh()

            upserted.captured.size shouldBe 50
        }

    @Test
    fun `an item the server no longer knows about leaves its local row alone`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(1), uuid(2))
            // `getItems(ids = …)` simply omits ids it does not recognise, so a remotely deleted item
            // is absent rather than an error — and deleting the download is not this class's call.
            coEvery { api.getFullItems(listOf(uuid(1), uuid(2))) } returns AppResult.Success(listOf(movie()))

            refresher().refresh()

            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(1))
        }

    @Test
    fun `a failing parent fetch still refreshes the items themselves`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(2))
            coEvery { api.getFullItems(listOf(uuid(2))) } returns AppResult.Success(listOf(episode()))
            coEvery { api.getFullItems(listOf(uuid(10), uuid(11))) } returns AppResult.Failure(AppError.Network())

            refresher().refresh()

            upserted.captured.map { it.id } shouldContainExactlyInAnyOrder listOf(uuid(2))
        }

    @Test
    fun `a session that cannot be restored parks the refresh`() =
        runTest {
            givenDownloads(uuid(1))
            coEvery { sessionGate.ensureSession() } returns false

            refresher().refresh()

            coVerify(exactly = 0) { api.getFullItems(any()) }
        }

    @Test
    fun `an unreadable downloads table is not allowed to bring the app down at startup`() =
        runTest {
            coEvery { downloadDao.allItemIds() } throws SQLiteException("disk")

            refresher().start()
            runCurrent()

            coVerify(exactly = 0) { api.getFullItems(any()) }
        }

    @Test
    fun `a failing write is swallowed`() =
        runTest {
            givenDownloads(uuid(1))
            coEvery { itemDao.upsert(any()) } throws SQLiteException("disk full")

            refresher().refresh()

            coVerify(exactly = 1) { itemDao.upsert(any()) }
        }

    /**
     * The two guards above tolerate everything a disk or a server can do, and must keep tolerating
     * it — but not a cancellation, which is the scope shutting the refresher down rather than a
     * failure to work around (audit STAB-06).
     */
    @Test
    fun `a cancelled table read propagates instead of degrading to an empty list`() =
        runTest {
            coEvery { downloadDao.allItemIds() } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { refresher().refresh() }

            coVerify(exactly = 0) { api.getFullItems(any()) }
        }

    @Test
    fun `a cancelled write propagates instead of being swallowed`() =
        runTest {
            givenDownloads(uuid(1))
            coEvery { itemDao.upsert(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { refresher().refresh() }
        }

    // ---- helpers ---------------------------------------------------------------------------------

    /** One downloaded movie per id, which the server answers for in a single batch. */
    private fun givenDownloads(vararg ids: UUID) {
        coEvery { downloadDao.allItemIds() } returns ids.toList()
        coEvery { api.getFullItems(ids.toList()) } returns AppResult.Success(ids.map { movie(id = it) })
    }

    /**
     * The refresher collects a never-completing `StateFlow`, so it is given `runTest`'s
     * [TestScope.backgroundScope] — the application scope's stand-in, cancelled when the test ends
     * instead of keeping the test coroutine alive forever.
     */
    private fun TestScope.refresher() =
        DownloadedMetadataRefresher(
            connectionState = connectionState,
            sessionGate = sessionGate,
            api = api,
            downloadDao = downloadDao,
            itemDao = itemDao,
            mapper = mapper,
            sidecars = sidecars,
            clock = clock,
            scope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

    private fun entity(
        dto: BaseItemDto,
        source: ItemSource,
        cachedAt: Instant,
    ) = ItemEntity(
        id = dto.id,
        name = dto.name.orEmpty(),
        sortName = dto.name.orEmpty(),
        type = ItemType.MOVIE,
        source = source,
        cachedAt = cachedAt,
        dto = "{}",
    )

    private companion object {
        /** When the items were originally downloaded — the offline "recently downloaded" key. */
        val DOWNLOADED_AT: Instant = NOW

        /** Two days later: a refresh pass must be invisible to that ordering. */
        val REFRESHED_AT: Instant = NOW.plusSeconds(2 * 24 * 60 * 60)
    }
}
