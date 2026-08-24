package dev.jellyboost.data.downloads.impl

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadEntity
import dev.jellyboost.core.database.entities.DownloadProgress
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadApi
import dev.jellyboost.data.downloads.DownloadFixtures
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.movie
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.engine.SiblingSeeder
import dev.jellyboost.data.downloads.storage.DownloadStorage
import dev.jellyboost.data.downloads.storage.StorageLocationManager
import dev.jellyboost.data.downloads.work.DownloadScheduler
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The cancel-then-redownload interleaving, played out through the real repository, deleter and
 * enqueuer.
 *
 * The sequence, in the order a user produces it:
 *
 * 1. an item is transferring and the user taps **Cancel**. `DownloadRepositoryImpl.deleteAll`
 *    claims the row (`demoteRunnable` → `CANCELLED`) and, because the live transfer was among the
 *    targets, calls `DownloadScheduler.stop()` — which waits for the worker, up to five seconds;
 * 2. the badge is already `NotDownloaded` (that mapping is asserted below, because it is what puts
 *    a **Download** button under the user's finger), so the user re-taps. `DownloadEnqueuer` writes
 *    a fresh `QUEUED` row;
 * 3. `stop()` returns and the delete cascade finally runs.
 *
 * Unguarded, step 3 reads the row it finds — the *new* one — and deletes it, its directory and its
 * metadata: no download, no error, nothing on screen. The re-enqueue is driven from inside the
 * `stop()` stub, which is exactly where it happens in life.
 *
 * Room is a map rather than a stack of per-call stubs, for `SeasonSeedingScenarioTest`'s reason:
 * the subject is a *sequence*, and the two statements it turns on (`demoteRunnable`'s legs and
 * `deleteUnlessRunnable`'s `status NOT IN ('QUEUED','DOWNLOADING')`) are modelled clause for
 * clause, so it is the SQL being tested and not a mock's memory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancelThenRedownloadScenarioTest {
    private val downloads = linkedMapOf<UUID, DownloadEntity>()
    private val items = linkedMapOf<UUID, ItemEntity>()
    private val prunedItemIds = mutableListOf<UUID>()

    private val api = mockk<DownloadApi>()
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>(relaxUnitFun = true)
    private val mapper = mockk<ItemEntityMapper>()
    private val storage = mockk<DownloadStorage>(relaxed = true)
    private val locations = mockk<StorageLocationManager>(relaxUnitFun = true)
    private val preferences = mockk<AppPreferences>(relaxUnitFun = true)
    private val sessionRepository = mockk<SessionRepository>()
    private val scheduler = mockk<DownloadScheduler>(relaxUnitFun = true)
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        givenTheServer()
        givenTheDatabase()
        every { sessionRepository.sessionState } returns MutableStateFlow(LOGGED_IN)
        every { preferences.downloadQuality } returns MutableStateFlow(DownloadQuality.ORIGINAL)
        every { locations.selectedVolumeId } returns MutableStateFlow<String?>(null)
        every { storage.deleteItemDirectory(any()) } returns FILE_BYTES
    }

    @Test
    fun `a cancelled row reads as not-downloaded, which is what offers Download again`() =
        runTest {
            // Not decoration: this mapping is the reason the interleaving below is reachable at all.
            DownloadProgress(ITEM, DownloadStatus.CANCELLED, 0L, 0L).toDownloadState() shouldBe
                DownloadState.NotDownloaded
        }

    @Test
    fun `re-downloading during the cancel's stop keeps the new row, its files and its metadata`() =
        runTest {
            givenTransferring()
            val repository = repository()
            // The user's second tap, landing while `stop()` is still waiting for the worker.
            coEvery { scheduler.stop() } coAnswers { repository.enqueue(ITEM.toString()) }

            repository.delete(ITEM.toString()) shouldBe AppResult.Success(0L)

            // The row the user asked for is still there, queued, and nothing freed any bytes: the
            // cascade recognised that the item it had claimed is not the item it found.
            downloads.getValue(ITEM).status shouldBe DownloadStatus.QUEUED
            verify(exactly = 0) { storage.deleteItemDirectory(any()) }
            // …and the metadata the new row's drain will need is untouched, so it cannot fail with
            // `MissingMetadataException`.
            items[ITEM].shouldNotBeNull()
            prunedItemIds shouldBe emptyList()
        }

    @Test
    fun `a cancel nobody interrupts still deletes everything`() =
        runTest {
            // The other half of the guard: with no re-enqueue in the window the cascade is
            // unchanged — row, files and orphaned metadata all go.
            givenTransferring()

            repository().delete(ITEM.toString()) shouldBe AppResult.Success(FILE_BYTES)

            downloads[ITEM] shouldBe null
            verify(exactly = 1) { storage.deleteItemDirectory(DIRECTORY) }
            prunedItemIds shouldBe listOf(ITEM)
        }

    @Test
    fun `the re-enqueued row keeps the bytes already on disk`() =
        runTest {
            // The end state the fix produces is a *resume*, not a restart: the partial file was
            // never unlinked, and the fresh row carries the byte count that describes it.
            givenTransferring(bytesDownloaded = 900_000_000L)
            val repository = repository()
            coEvery { scheduler.stop() } coAnswers { repository.enqueue(ITEM.toString()) }

            repository.delete(ITEM.toString())

            downloads.getValue(ITEM).bytesDownloaded shouldBe 900_000_000L
        }

    // ---- the world --------------------------------------------------------------------------------

    private fun givenTheServer() {
        coEvery { api.getFullItems(listOf(ITEM)) } returns AppResult.Success(listOf(movie(id = ITEM)))
    }

    private fun givenTransferring(bytesDownloaded: Long = 0L) {
        downloads[ITEM] =
            DownloadFixtures.download(
                itemId = ITEM,
                status = DownloadStatus.DOWNLOADING,
                bytesDownloaded = bytesDownloaded,
                directoryName = DIRECTORY,
            )
        items[ITEM] = itemRow(ITEM)
    }

    /** Room, as far as these three collaborators can tell. */
    private fun givenTheDatabase() {
        every { mapper.toEntity(any<BaseItemDto>(), any<ItemSource>(), any<Instant>()) } answers {
            itemRow(firstArg<BaseItemDto>().id)
        }
        coEvery { itemDao.upsert(any()) } answers {
            firstArg<List<ItemEntity>>().forEach { items[it.id] = it }
        }
        coEvery { itemDao.getParentRefs(any()) } returns emptyList()
        coEvery { itemDao.deleteDownloadsNotIn(any(), any()) } answers {
            val keep = firstArg<List<UUID>>().toSet()
            val gone = items.keys.filterNot { it in keep }
            prunedItemIds += gone
            gone.forEach(items::remove)
            gone.size
        }

        coEvery { downloadDao.upsert(any()) } answers { downloads[firstArg<DownloadEntity>().itemId] = firstArg() }
        coEvery { downloadDao.get(any()) } answers { downloads[firstArg()] }
        coEvery { downloadDao.getAll(any()) } answers { firstArg<List<UUID>>().mapNotNull { downloads[it] } }
        coEvery { downloadDao.allItemIds() } answers { downloads.keys.toList() }
        coEvery { downloadDao.maxQueuePosition() } answers { downloads.values.maxOfOrNull { it.queuePosition } }
        coEvery { downloadDao.pending() } answers {
            downloads.values.filter { it.status != DownloadStatus.DOWNLOADED }
        }
        coEvery { downloadDao.completedSiblings(any(), any(), any()) } returns emptyList()

        // `demoteRunnable`'s own body over its two guarded legs, both modelled clause for clause.
        coEvery { downloadDao.demoteRunnable(any(), any(), any()) } coAnswers { callOriginal() }
        coEvery { downloadDao.setStatusIfDownloading(any(), any(), any()) } answers {
            demote(arg(0), DownloadStatus.DOWNLOADING, arg(1))
        }
        coEvery { downloadDao.setStatusIfQueued(any(), any(), any()) } answers {
            demote(arg(0), DownloadStatus.QUEUED, arg(1))
        }

        // The guard this whole test is about: `status NOT IN ('QUEUED', 'DOWNLOADING')`.
        coEvery { downloadDao.deleteUnlessRunnable(any()) } answers {
            val itemId = firstArg<UUID>()
            val runnable = downloads[itemId]?.status in RUNNABLE
            if (downloads.containsKey(itemId) && !runnable) {
                downloads.remove(itemId)
                1
            } else {
                0
            }
        }
    }

    private fun demote(
        itemIds: List<UUID>,
        from: DownloadStatus,
        to: DownloadStatus,
    ): Int {
        val moved = itemIds.filter { downloads[it]?.status == from }
        moved.forEach { id -> downloads[id] = downloads.getValue(id).copy(status = to) }
        return moved.size
    }

    // ---- collaborators ----------------------------------------------------------------------------

    private fun TestScope.repository() =
        DownloadRepositoryImpl(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = mapper,
            enqueuer = enqueuer(),
            deleter = deleter(),
            scheduler = scheduler,
            storage = storage,
            locations = locations,
            preferences = preferences,
            sessionRepository = sessionRepository,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            appScope = backgroundScope,
        )

    private fun deleter() =
        DownloadDeleter(
            downloadDao = downloadDao,
            itemDao = itemDao,
            storage = storage,
            transactionRunner = DownloadFixtures.directTransactionRunner,
        )

    private fun enqueuer() =
        DownloadEnqueuer(
            api = api,
            itemDao = itemDao,
            downloadDao = downloadDao,
            deleter = deleter(),
            mapper = mapper,
            appPreferences = preferences,
            seeder = SiblingSeeder(downloadDao = downloadDao, itemDao = itemDao, clock = clock),
            transactionRunner = DownloadFixtures.directTransactionRunner,
            clock = clock,
        )

    private fun itemRow(id: UUID) =
        ItemEntity(
            id = id,
            name = "Arrival",
            sortName = "Arrival",
            type = ItemType.MOVIE,
            source = ItemSource.DOWNLOAD,
            cachedAt = NOW,
            dto = "{}",
        )

    private companion object {
        val ITEM: UUID = uuid(1)
        val LOGGED_IN =
            SessionState.LoggedIn(
                serverId = uuid(50),
                userId = uuid(99),
                userName = "casey",
                serverName = "home",
                serverVersion = "10.11.0",
            )
        val RUNNABLE = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING)
        const val DIRECTORY = "Arrival (2016)"
        const val FILE_BYTES = 2_100_000_000L
    }
}
