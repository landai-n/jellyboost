package dev.jellyboost.data.downloads.impl

import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
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
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

/**
 * The cancellation half of [DownloadRepositoryImpl]. Every mutation here runs in the **caller's**
 * coroutine — a ViewModel scope that dies with the screen — so a broad catch folding that into
 * `AppError.Storage` would log an ordinary back-press at E, answer "could not pause", and swallow the
 * cancellation the parent job is owed. Each test below is one such catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryCancellationTest {
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

    @BeforeEach
    fun setUp() {
        every { sessionRepository.sessionState } returns MutableStateFlow(LOGGED_IN)
        every { preferences.downloadOverWifiOnly } returns flowOf(true)
        every { locations.selectedVolumeId } returns MutableStateFlow<String?>(null)
        every { locations.resolve(any()) } answers { selectionFor(firstArg()) }
        every { locations.activeVolume() } returns PRIMARY_VOLUME
        coEvery { downloadDao.allItemIds() } returns emptyList()
        coEvery { downloadDao.demoteRunnable(any(), any(), any()) } returns false
        coEvery { downloadDao.pending() } returns emptyList()
        coEvery { downloadDao.get(any()) } returns null
        coEvery { deleter.deleteAll(any()) } returns 0L
    }

    @Test
    fun `a cancelled bulk pause propagates instead of being reported as a storage failure`() =
        runTest {
            // `mutateAll`, mid *Pause all*.
            coEvery {
                downloadDao.demoteRunnable(any(), any(), any())
            } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                repository().pauseAll(listOf(uuid(1).toString(), uuid(2).toString()))
            }
        }

    @Test
    fun `a cancelled bulk resume propagates instead of being reported as a storage failure`() =
        runTest {
            coEvery { downloadDao.requeueForUser(any(), any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { repository().resumeAll(listOf(uuid(1).toString())) }
        }

    @Test
    fun `a cancelled single mutation propagates instead of being reported as a storage failure`() =
        runTest {
            // `mutate`, the single-row sibling of the above.
            coEvery {
                downloadDao.demoteRunnable(any(), any(), any())
            } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { repository().pause(uuid(1).toString()) }
        }

    @Test
    fun `a cancelled delete propagates instead of being reported as a storage failure`() =
        runTest {
            coEvery { deleter.deleteAll(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { repository().deleteAll(listOf(uuid(1).toString())) }
        }

    @Test
    fun `a cancelled storage switch propagates instead of being reported as a storage failure`() =
        runTest {
            coEvery { downloadDao.allItemIds() } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> {
                repository().setStorageLocation(CARD_ID, deleteExistingDownloads = true)
            }
        }

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

    // The constructor needs a real `CoroutineScope` for the `observeStates()` projection even though
    // nothing here collects it, and the dispatcher ties to `runTest`'s scheduler so the two never diverge.
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

    private companion object {
        const val PRIMARY_ID = DownloadVolume.PRIMARY_ID
        const val CARD_ID = "1A2B-3C4D"

        val LOGGED_IN =
            SessionState.LoggedIn(
                serverId = uuid(50),
                userId = uuid(99),
                userName = "casey",
                serverName = "home",
                serverVersion = "10.11.0",
            )

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
    }
}
