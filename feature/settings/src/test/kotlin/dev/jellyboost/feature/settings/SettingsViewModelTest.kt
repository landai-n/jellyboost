package dev.jellyboost.feature.settings

import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.StorageLocations
import dev.jellyboost.data.downloads.model.StorageUsage
import dev.jellyboost.data.downloads.model.StorageVolumeOption
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [SettingsViewModel].
 *
 * The screen is a projection, so what is worth pinning is that each control writes the key it
 * claims to and reads the same key back, that the Account section degrades rather than crashes when
 * there is no session, and — the one piece of real behaviour here — that sign-out deletes the
 * downloads *before* it clears the credentials.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val introSkipMode = MutableStateFlow(SegmentSkipMode.SHOW_BUTTON)
    private val outroSkipMode = MutableStateFlow(SegmentSkipMode.SHOW_BUTTON)
    private val pipOnLeave = MutableStateFlow(true)
    private val downloadOverWifiOnly = MutableStateFlow(true)
    private val downloadQuality = MutableStateFlow(DownloadQuality.ORIGINAL)
    private val forceOffline = MutableStateFlow(false)
    private val storage = MutableStateFlow(StorageUsage())
    private val storageLocations = MutableStateFlow(StorageLocations())
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Unknown)
    private val items = MutableStateFlow<List<DownloadItem>>(emptyList())

    private val appPreferences = mockk<AppPreferences>(relaxUnitFun = true)
    private val sessionRepository = mockk<SessionRepository>(relaxUnitFun = true)
    private val downloads = mockk<DownloadRepository>(relaxUnitFun = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { appPreferences.introSkipMode } returns introSkipMode
        every { appPreferences.outroSkipMode } returns outroSkipMode
        every { appPreferences.pipOnLeave } returns pipOnLeave
        every { appPreferences.downloadOverWifiOnly } returns downloadOverWifiOnly
        every { appPreferences.downloadQuality } returns downloadQuality
        every { appPreferences.forceOffline } returns forceOffline
        every { sessionRepository.sessionState } returns sessionState
        every { downloads.observeStorage() } returns storage
        every { downloads.observeStorageLocations() } returns storageLocations
        every { downloads.observeDownloads() } returns items
        coEvery { downloads.delete(any()) } returns AppResult.Success(0L)
        coEvery { downloads.setStorageLocation(any(), any()) } returns AppResult.Success(Unit)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- preferences read back --------------------------------------------------------------------

    @Test
    fun `every preference reaches the state`() =
        runTest(dispatcher) {
            introSkipMode.value = SegmentSkipMode.AUTO_SKIP
            outroSkipMode.value = SegmentSkipMode.OFF
            pipOnLeave.value = false
            downloadOverWifiOnly.value = false
            downloadQuality.value = DownloadQuality.LOW
            forceOffline.value = true
            storage.value = StorageUsage(usedBytes = 100L, availableBytes = 900L, rootPath = "/sdcard")

            viewModel().uiState.test {
                // The first item is `stateIn`'s placeholder; the second is the store's answer.
                skipItems(1)
                val state = awaitItem()

                state.introSkipMode shouldBe SegmentSkipMode.AUTO_SKIP
                state.outroSkipMode shouldBe SegmentSkipMode.OFF
                state.pipOnLeave shouldBe false
                state.downloadOverWifiOnly shouldBe false
                state.downloadQuality shouldBe DownloadQuality.LOW
                state.forceOffline shouldBe true
                state.storage.usedBytes shouldBe 100L
                state.storage.rootPath shouldBe "/sdcard"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a preference changed upstream is picked up while the screen is open`() =
        runTest(dispatcher) {
            viewModel().uiState.test {
                awaitItem().pipOnLeave shouldBe true

                pipOnLeave.value = false

                awaitItem().pipOnLeave shouldBe false
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- preferences write through ----------------------------------------------------------------

    @Test
    fun `the intro skip picker writes through to the preference store`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setIntroSkipMode(SegmentSkipMode.AUTO_SKIP)
            advanceUntilIdle()

            coVerify(exactly = 1) { appPreferences.setIntroSkipMode(SegmentSkipMode.AUTO_SKIP) }
        }

    @Test
    fun `the download quality picker writes through to the preference store`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setDownloadQuality(DownloadQuality.MEDIUM)
            advanceUntilIdle()

            coVerify(exactly = 1) { appPreferences.setDownloadQuality(DownloadQuality.MEDIUM) }
        }

    @Test
    fun `a download quality changed upstream is picked up while the screen is open`() =
        runTest(dispatcher) {
            viewModel().uiState.test {
                awaitItem().downloadQuality shouldBe DownloadQuality.ORIGINAL

                downloadQuality.value = DownloadQuality.HIGH

                awaitItem().downloadQuality shouldBe DownloadQuality.HIGH
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the outro skip picker writes through to the preference store`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setOutroSkipMode(SegmentSkipMode.OFF)
            advanceUntilIdle()

            coVerify(exactly = 1) { appPreferences.setOutroSkipMode(SegmentSkipMode.OFF) }
        }

    @Test
    fun `the picture-in-picture switch writes through to the preference store`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setPipOnLeave(false)
            advanceUntilIdle()

            coVerify(exactly = 1) { appPreferences.setPipOnLeave(false) }
        }

    @Test
    fun `the Wi-Fi-only switch writes through to the preference store`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setDownloadOverWifiOnly(false)
            advanceUntilIdle()

            coVerify(exactly = 1) { appPreferences.setDownloadOverWifiOnly(false) }
        }

    @Test
    fun `the offline-mode switch writes through to the preference store`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setForceOffline(true)
            advanceUntilIdle()

            coVerify(exactly = 1) { appPreferences.setForceOffline(true) }
        }

    // ---- storage location -------------------------------------------------------------------------

    @Test
    fun `the volumes downloads can live on reach the state`() =
        runTest(dispatcher) {
            storageLocations.value =
                StorageLocations(
                    volumes = listOf(volumeOption("primary"), volumeOption("1A2B-3C4D")),
                    activeVolumeId = "1A2B-3C4D",
                    selectedVolumeMissing = false,
                    downloadCount = 2,
                )

            viewModel().uiState.test {
                skipItems(1)
                val locations = awaitItem().storageLocations

                locations.volumes.map { it.id } shouldBe listOf("primary", "1A2B-3C4D")
                locations.activeVolumeId shouldBe "1A2B-3C4D"
                locations.downloadCount shouldBe 2
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a card removed while the screen is open is reflected without reopening it`() =
        runTest(dispatcher) {
            viewModel().uiState.test {
                awaitItem().storageLocations.selectedVolumeMissing shouldBe false

                storageLocations.value = StorageLocations(selectedVolumeMissing = true)

                awaitItem().storageLocations.selectedVolumeMissing shouldBe true
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `picking a volume with nothing downloaded switches without deleting anything`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setStorageLocation("1A2B-3C4D", deleteExistingDownloads = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.setStorageLocation("1A2B-3C4D", false) }
        }

    @Test
    fun `a confirmed switch passes the user's agreement to lose the downloads through`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.setStorageLocation("1A2B-3C4D", deleteExistingDownloads = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { downloads.setStorageLocation("1A2B-3C4D", true) }
        }

    @Test
    fun `a refused switch is survived rather than crashed on`() =
        runTest(dispatcher) {
            // The repository refuses when downloads appeared between the dialog and the confirm.
            coEvery { downloads.setStorageLocation(any(), any()) } returns AppResult.Failure(AppError.Storage())
            val model = viewModel()

            model.setStorageLocation("1A2B-3C4D", deleteExistingDownloads = false)
            advanceUntilIdle()

            model.uiState.value.storageLocations shouldBe StorageLocations()
        }

    // ---- account ----------------------------------------------------------------------------------

    @Test
    fun `a live session becomes the account section's user and server`() =
        runTest(dispatcher) {
            sessionState.value = LOGGED_IN

            viewModel().uiState.test {
                skipItems(1)
                val account = awaitItem().account

                account?.userName shouldBe "casey"
                account?.serverName shouldBe "Living Room"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `no session leaves the account section empty rather than crashing`() =
        runTest(dispatcher) {
            sessionState.value = SessionState.LoggedOut

            viewModel().uiState.test {
                awaitItem().account shouldBe null
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ---- sign out ---------------------------------------------------------------------------------

    @Test
    fun `signing out with delete removes every download before clearing the session`() =
        runTest(dispatcher) {
            items.value = listOf(item("1"), item("2"))
            val model = viewModel()

            model.signOut(deleteDownloads = true)
            advanceUntilIdle()

            coVerifyOrder {
                downloads.delete("1")
                downloads.delete("2")
                sessionRepository.signOut()
            }
        }

    @Test
    fun `signing out without delete leaves the files alone`() =
        runTest(dispatcher) {
            items.value = listOf(item("1"), item("2"))
            val model = viewModel()

            model.signOut(deleteDownloads = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.delete(any()) }
            coVerify(exactly = 1) { sessionRepository.signOut() }
        }

    @Test
    fun `a delete that fails does not keep the user signed in`() =
        runTest(dispatcher) {
            items.value = listOf(item("1"), item("2"))
            coEvery { downloads.delete("1") } returns AppResult.Failure(AppError.Storage())
            val model = viewModel()

            model.signOut(deleteDownloads = true)
            advanceUntilIdle()

            coVerifyOrder {
                downloads.delete("1")
                downloads.delete("2")
                sessionRepository.signOut()
            }
        }

    // ---- a collapsed projection (audit STAB-10) --------------------------------------------------

    /**
     * The crash, pinned where it happens. `stateIn` runs the projection in `viewModelScope`, whose
     * `SupervisorJob` isolates siblings but *handles* nothing — so an upstream throw escapes the
     * scope entirely, and on a device that is the process dying. It escapes here too: without the
     * guard this test does not read a wrong value, it fails with `storage gone`.
     *
     * The clock is advanced **inside** the Turbine block on purpose. `WhileSubscribed` only starts
     * the projection while someone is collecting, so advancing after the collector has gone means
     * the upstream never runs at all and the test passes for the wrong reason.
     */
    @Test
    fun `an upstream failure never escapes the ViewModel scope`() =
        runTest(dispatcher) {
            every { downloads.observeStorage() } returns flow { error("storage gone") }

            val model = viewModel()
            model.uiState.test {
                awaitItem() shouldBe SettingsUiState()
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }

            // The defaults are what a screen with no readable sources honestly shows.
            model.uiState.value shouldBe SettingsUiState()
        }

    /** And the screen stays usable: every write path is independent of the failed projection. */
    @Test
    fun `a screen whose projection failed can still write preferences`() =
        runTest(dispatcher) {
            every { downloads.observeStorageLocations() } returns flow { error("volumes gone") }

            val model = viewModel()
            model.uiState.test {
                awaitItem() shouldBe SettingsUiState()
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }

            model.setPipOnLeave(false)
            advanceUntilIdle()

            coVerify(exactly = 1) { appPreferences.setPipOnLeave(false) }
        }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun viewModel() =
        SettingsViewModel(
            appPreferences = appPreferences,
            sessionRepository = sessionRepository,
            downloads = downloads,
        )

    private fun volumeOption(id: String) =
        StorageVolumeOption(
            id = id,
            description = null,
            isRemovable = id != "primary",
            path = "/storage/$id/Android/data/app/files",
            availableBytes = 1_000L,
        )

    private fun item(id: String) =
        DownloadItem(
            itemId = id,
            title = "Item $id",
            seriesName = null,
            status = DownloadStatus.DOWNLOADED,
            bytesDownloaded = 0L,
            bytesTotal = 0L,
            bytesOnDisk = 0L,
            queuePosition = 0,
        )

    private companion object {
        val LOGGED_IN =
            SessionState.LoggedIn(
                serverId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                userId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                userName = "casey",
                serverName = "Living Room",
                serverVersion = "10.11.0",
            )
    }
}
