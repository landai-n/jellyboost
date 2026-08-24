package dev.jellyboost.data.downloads.impl

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadProgress
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.cache.ItemEntityMapper
import dev.jellyboost.data.downloads.DownloadFixtures.NOW
import dev.jellyboost.data.downloads.DownloadFixtures.uuid
import dev.jellyboost.data.downloads.storage.DownloadStorage
import dev.jellyboost.data.downloads.storage.DownloadVolume
import dev.jellyboost.data.downloads.storage.StorageLocationManager
import dev.jellyboost.data.downloads.storage.StorageSelection
import dev.jellyboost.data.downloads.work.DownloadScheduler
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/**
 * The storage half of [DownloadRepositoryImpl]: volume selection, and what the storage walk is
 * keyed on.
 *
 * Split from [DownloadRepositoryImplTest] purely for size — the fixture is the same shape; the
 * subject is the same class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryStorageTest {
    private val downloadDao = mockk<DownloadDao>(relaxUnitFun = true)
    private val itemDao = mockk<ItemDao>()
    private val itemMapper = mockk<ItemEntityMapper>()
    private val enqueuer = mockk<DownloadEnqueuer>()
    private val deleter = mockk<DownloadDeleter>()
    private val scheduler = mockk<DownloadScheduler>(relaxUnitFun = true)
    private val storage = mockk<DownloadStorage>(relaxed = true)
    private val locations = mockk<StorageLocationManager>(relaxUnitFun = true)
    private val preferences = mockk<AppPreferences>(relaxUnitFun = true)
    private val sessionRepository = mockk<SessionRepository>()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val selectedVolumeId = MutableStateFlow<String?>(null)

    @BeforeEach
    fun setUp() {
        every { sessionRepository.sessionState } returns MutableStateFlow(LOGGED_IN)
        every { preferences.downloadOverWifiOnly } returns flowOf(true)
        every { locations.selectedVolumeId } returns selectedVolumeId
        every { locations.resolve(any()) } answers { selectionFor(firstArg()) }
        // The tests that switch volume start out writing to the primary one.
        every { locations.activeVolume() } returns PRIMARY_VOLUME
        coEvery { downloadDao.allItemIds() } returns emptyList()
        coEvery { scheduler.ensureRunning() } returns Unit
        coEvery { scheduler.restart() } returns Unit
        coEvery { deleter.delete(any()) } returns 0L
        coEvery { deleter.deleteAll(any()) } returns 0L
        coEvery { downloadDao.pending() } returns emptyList()
        coEvery { downloadDao.get(any()) } returns null
        coEvery { downloadDao.demoteRunnable(any(), any(), any()) } returns false
    }

    // ---- storage location -------------------------------------------------------------------------

    @Test
    fun `the picker sees every mounted volume, which is active, and what stands in the way`() =
        runTest {
            every { downloadDao.observeProgress() } returns
                flowOf(listOf(progress(uuid(1), DownloadStatus.DOWNLOADED)))
            selectedVolumeId.value = CARD_ID

            val locations = repository(UnconfinedTestDispatcher(testScheduler)).observeStorageLocations().first()

            locations.volumes.map { it.id } shouldBe listOf(PRIMARY_ID, CARD_ID)
            locations.activeVolumeId shouldBe CARD_ID
            locations.selectedVolumeMissing shouldBe false
            // What the confirmation dialog counts, and what the switch guard enforces.
            locations.downloadCount shouldBe 1
        }

    @Test
    fun `an ejected card is reported as a fallback rather than shown as the selection`() =
        runTest {
            every { downloadDao.observeProgress() } returns flowOf(emptyList())
            selectedVolumeId.value = "a-card-that-is-not-here"

            val locations = repository(UnconfinedTestDispatcher(testScheduler)).observeStorageLocations().first()

            locations.activeVolumeId shouldBe PRIMARY_ID
            locations.selectedVolumeMissing shouldBe true
        }

    @Test
    fun `switching volume with nothing downloaded takes effect at once`() =
        runTest {
            val result = repository().setStorageLocation(CARD_ID, deleteExistingDownloads = false)

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerify { locations.select(CARD_ID) }
            coVerify(exactly = 0) { deleter.deleteAll(any()) }
        }

    @Test
    fun `switching volume while downloads exist is refused unless the caller agrees to lose them`() =
        runTest {
            // The policy: nothing moves files yet, and a finished download's file
            // rows hold absolute paths on the old volume that nothing rewrites.
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(1))

            val result = repository().setStorageLocation(CARD_ID, deleteExistingDownloads = false)

            result.shouldBeInstanceOf<AppResult.Failure>()
            coVerify(exactly = 0) { locations.select(any()) }
            coVerify(exactly = 0) { deleter.deleteAll(any()) }
        }

    @Test
    fun `delete-all-and-switch empties the device before the root moves`() =
        runTest {
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(1), uuid(2))

            val result = repository().setStorageLocation(CARD_ID, deleteExistingDownloads = true)

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerifyOrder {
                // Claim the rows first — the cascade only deletes what is out of the queue's reach
                // (`DownloadDao.deleteUnlessRunnable`), so a QUEUED row reaching it unclaimed would
                // survive the switch and point at files on the volume the user just left. Then stop,
                // so the downloader cannot hold a handle to a file being unlinked, and delete before
                // the root moves or the cascade looks on the wrong volume for them.
                downloadDao.demoteRunnable(listOf(uuid(1), uuid(2)), DownloadStatus.CANCELLED, NOW)
                scheduler.stop()
                deleter.deleteAll(listOf(uuid(1), uuid(2)))
                locations.select(CARD_ID)
            }
        }

    @Test
    fun `re-affirming the volume already in force keeps the downloads that are on it`() =
        runTest {
            // What clearing a stale choice looks like: the card is out, the fallback is already
            // writing to internal storage, and the user taps it. Nothing moves, so nothing is lost.
            coEvery { downloadDao.allItemIds() } returns listOf(uuid(1))
            every { locations.activeVolume() } returns PRIMARY_VOLUME

            val result = repository().setStorageLocation(PRIMARY_ID, deleteExistingDownloads = false)

            result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            coVerify { locations.select(PRIMARY_ID) }
            coVerify(exactly = 0) { deleter.deleteAll(any()) }
        }

    @Test
    fun `switching to a volume that is not mounted changes nothing`() =
        runTest {
            val result = repository().setStorageLocation("a-card-that-is-not-here", deleteExistingDownloads = true)

            result.shouldBeInstanceOf<AppResult.Failure>()
            result.error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { locations.select(any()) }
        }

    @Test
    fun `the storage header re-reads when the volume changes, not only when a download does`() =
        runTest {
            // Switching with an empty queue changes no download row, so a projection keyed only on
            // progress would keep reporting the old volume's free space.
            every { downloadDao.observeProgress() } returns MutableStateFlow(emptyList())
            every { storage.usedBytes() } returnsMany listOf(10L, 20L)
            every { storage.availableBytes() } returnsMany listOf(90L, 180L)

            val emissions =
                async(UnconfinedTestDispatcher(testScheduler)) {
                    repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().take(2).toList()
                }
            selectedVolumeId.value = CARD_ID

            emissions.await().map { it.usedBytes } shouldBe listOf(10L, 20L)
        }

    // ---- what the storage walk is keyed on ---------------------------------------------------------

    @Test
    fun `progress writes alone do not re-walk the downloads tree`() =
        runTest {
            // `usedBytes()` is a stat() of every file under the root; a transfer writes progress
            // twice a second for its whole length, so the walk must not be keyed on it.
            val rows = MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            every { downloadDao.observeProgress() } returns rows
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()
                walks shouldBe 1

                repeat(PROGRESS_WRITES) { written ->
                    rows.value =
                        listOf(
                            progress(
                                uuid(1),
                                DownloadStatus.DOWNLOADING,
                                downloaded = (written + 1) * 1_000L,
                                total = 100_000L,
                            ),
                        )
                }

                // Same items, same statuses: nothing that changes which files are on disk.
                walks shouldBe 1
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a status change re-walks, because that is what adds and removes files`() =
        runTest {
            val rows = MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            every { downloadDao.observeProgress() } returns rows
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()

                rows.value = listOf(progress(uuid(1), DownloadStatus.DOWNLOADED))
                walks shouldBe 2

                rows.value = emptyList()
                walks shouldBe 3
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a long transfer still creeps, on a slow tick rather than on every write`() =
        runTest {
            // The file grows without any row's *shape* changing, so the header would otherwise sit
            // still for the length of an episode.
            every { downloadDao.observeProgress() } returns
                MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()
                walks shouldBe 1

                testScheduler.advanceTimeBy(DownloadRepositoryImpl.STORAGE_WALK_INTERVAL * TICKS + 1.milliseconds)

                walks shouldBe 1 + TICKS
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `nothing downloading means no ticking either`() =
        runTest {
            every { downloadDao.observeProgress() } returns
                MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADED)))
            var walks = 0
            every { storage.usedBytes() } answers {
                walks++
                0L
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorage().test {
                awaitItem()

                testScheduler.advanceTimeBy(DownloadRepositoryImpl.STORAGE_WALK_INTERVAL * TICKS + 1.milliseconds)

                walks shouldBe 1
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- what observeStorageLocations is keyed on -------------------------------------------------

    @Test
    fun `progress writes alone do not re-resolve the storage locations`() =
        runTest {
            // `locations.resolve()` re-scans the mounted volumes, so it must not be keyed on raw
            // `observeProgress()` — the same 2/s hot path the storage walk stays off.
            // Only the download *count* is read here, and that does not move on a byte-count tick.
            val rows = MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            every { downloadDao.observeProgress() } returns rows
            var resolves = 0
            every { locations.resolve(any()) } answers {
                resolves++
                selectionFor(firstArg())
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorageLocations().test {
                awaitItem().downloadCount shouldBe 1
                resolves shouldBe 1

                repeat(PROGRESS_WRITES) { written ->
                    rows.value =
                        listOf(
                            progress(
                                uuid(1),
                                DownloadStatus.DOWNLOADING,
                                downloaded = (written + 1) * 1_000L,
                                total = 100_000L,
                            ),
                        )
                }

                // Same item, same status: the count `downloadCount` reports has not changed either.
                resolves shouldBe 1
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a status change re-resolves, because the download count can have changed`() =
        runTest {
            val rows = MutableStateFlow(listOf(progress(uuid(1), DownloadStatus.DOWNLOADING)))
            every { downloadDao.observeProgress() } returns rows
            var resolves = 0
            every { locations.resolve(any()) } answers {
                resolves++
                selectionFor(firstArg())
            }

            repository(UnconfinedTestDispatcher(testScheduler)).observeStorageLocations().test {
                awaitItem()

                rows.value =
                    listOf(progress(uuid(1), DownloadStatus.DOWNLOADED), progress(uuid(2), DownloadStatus.QUEUED))
                awaitItem().downloadCount shouldBe 2
                resolves shouldBe 2
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- helpers --------------------------------------------------------------------------------

    /** Two mounted volumes; anything else the caller asks for falls back to the primary one. */
    private fun selectionFor(selectedVolumeId: String?): StorageSelection {
        val volumes = listOf(PRIMARY_VOLUME, CARD_VOLUME)
        val chosen = volumes.firstOrNull { it.id == selectedVolumeId }
        return StorageSelection(
            volumes = volumes,
            active = chosen ?: PRIMARY_VOLUME,
            selectionMissing = selectedVolumeId != null && chosen == null,
        )
    }

    // The dispatcher is a parameter only so the storage-location tests can share `runTest`'s
    // scheduler: they collect a projection that never completes, which needs the two in step.
    //
    // `TestScope.repository`: `observeStates()` shares a `stateIn` over `@ApplicationScope`, and
    // `backgroundScope` is `runTest`'s stand-in for it — none of these tests collect
    // `observeStates()`, but the constructor still needs a real `CoroutineScope` to hand it. The
    // default dispatcher ties to the same `testScheduler` `backgroundScope` uses, matching every
    // explicit `UnconfinedTestDispatcher(testScheduler)` below — coroutines-test throws the moment
    // two different schedulers are exercised in one hierarchy.
    private fun TestScope.repository(ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        DownloadRepositoryImpl(
            downloadDao = downloadDao,
            itemDao = itemDao,
            itemMapper = itemMapper,
            enqueuer = enqueuer,
            deleter = deleter,
            scheduler = scheduler,
            storage = storage,
            locations = locations,
            preferences = preferences,
            sessionRepository = sessionRepository,
            clock = clock,
            ioDispatcher = ioDispatcher,
            appScope = backgroundScope,
        )

    private fun progress(
        itemId: java.util.UUID,
        status: DownloadStatus,
        downloaded: Long = 0L,
        total: Long = 0L,
    ) = DownloadProgress(itemId = itemId, status = status, bytesDownloaded = downloaded, bytesTotal = total)

    private companion object {
        const val PRIMARY_ID = DownloadVolume.PRIMARY_ID
        const val CARD_ID = "1A2B-3C4D"

        /** Ten seconds of a real transfer at the throttle's two writes a second. */
        const val PROGRESS_WRITES = 20

        /** How many walk intervals the ticking tests step over. */
        const val TICKS = 3

        val PRIMARY_VOLUME =
            DownloadVolume(
                id = PRIMARY_ID,
                isPrimary = true,
                isRemovable = false,
                description = "Internal shared storage",
                directory = File("/storage/emulated/0/Android/data/app/files"),
            )

        val CARD_VOLUME =
            DownloadVolume(
                id = CARD_ID,
                isPrimary = false,
                isRemovable = true,
                description = "SD card",
                directory = File("/storage/$CARD_ID/Android/data/app/files"),
            )

        val LOGGED_IN =
            SessionState.LoggedIn(
                serverId = uuid(50),
                userId = uuid(99),
                userName = "casey",
                serverName = "home",
                serverVersion = "10.11.0",
            )
    }
}
