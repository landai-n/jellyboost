package dev.jellyfinnative.feature.settings

import app.cash.turbine.test
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.StorageUsage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val forceOffline = MutableStateFlow(false)
    private val storage = MutableStateFlow(StorageUsage())
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
        every { appPreferences.forceOffline } returns forceOffline
        every { sessionRepository.sessionState } returns sessionState
        every { downloads.observeStorage() } returns storage
        every { downloads.observeDownloads() } returns items
        coEvery { downloads.delete(any()) } returns AppResult.Success(0L)
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

    // ---- helpers ----------------------------------------------------------------------------------

    private fun viewModel() =
        SettingsViewModel(
            appPreferences = appPreferences,
            sessionRepository = sessionRepository,
            downloads = downloads,
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
