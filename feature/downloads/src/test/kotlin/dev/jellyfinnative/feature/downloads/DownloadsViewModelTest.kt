package dev.jellyfinnative.feature.downloads

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.StorageUsage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for [DownloadsViewModel].
 *
 * The screen holds no state of its own — it is a projection of three Flows — so what is worth
 * pinning is the split into the two tabs, the grouping the *Downloaded* tab shows, and the fact
 * that every action reports its failure instead of silently doing nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val downloads = mockk<DownloadRepository>(relaxUnitFun = true)

    private val items = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val storage = MutableStateFlow(StorageUsage())
    private val wifiOnly = MutableStateFlow(true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { downloads.observeDownloads() } returns items
        every { downloads.observeStorage() } returns storage
        every { downloads.wifiOnly } returns wifiOnly
        coEvery { downloads.pause(any()) } returns AppResult.Success(Unit)
        coEvery { downloads.resume(any()) } returns AppResult.Success(Unit)
        coEvery { downloads.delete(any()) } returns AppResult.Success(0L)
        coEvery { downloads.move(any(), any()) } returns AppResult.Success(Unit)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- the two tabs ---------------------------------------------------------------------------

    @Test
    fun `finished downloads go to the Downloaded tab and everything else to the Queue`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Arrival", status = DownloadStatus.DOWNLOADED),
                    item("2", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADING),
                    item("3", "Dissonance Theory", series = "Westworld", status = DownloadStatus.QUEUED),
                )

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.downloaded
                .flatMap { it.items }
                .map { it.itemId } shouldContainExactly listOf("1")
            model.uiState.value.queue
                .map { it.itemId } shouldContainExactly listOf("2", "3")
        }

    @Test
    fun `downloaded episodes are grouped under their series`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                    item("2", "Arrival", status = DownloadStatus.DOWNLOADED),
                    item("3", "The Original", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val groups = model.uiState.value.downloaded
            groups.map { it.title } shouldContainExactly listOf("Arrival", "Westworld")
            groups.first { it.title == "Westworld" }.items.map { it.itemId } shouldContainExactly listOf("1", "3")
        }

    @Test
    fun `a group reports the bytes its items occupy on disk`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED, onDisk = 300L),
                    item("2", "The Original", series = "Westworld", status = DownloadStatus.DOWNLOADED, onDisk = 700L),
                )

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.downloaded
                .single()
                .bytesOnDisk shouldBe 1_000L
        }

    @Test
    fun `the queue is ordered by queue position, not by arrival`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Second", status = DownloadStatus.QUEUED, position = 5),
                    item("2", "First", status = DownloadStatus.QUEUED, position = 1),
                )

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.queue
                .map { it.itemId } shouldContainExactly listOf("2", "1")
        }

    @Test
    fun `the screen leaves the loading state once Room answers`() =
        runTest(dispatcher) {
            val model = viewModel()
            model.uiState.value.isLoading shouldBe true

            advanceUntilIdle()

            model.uiState.value.isLoading shouldBe false
            model.uiState.value.isEmpty shouldBe true
        }

    @Test
    fun `storage and the Wi-Fi preference reach the state`() =
        runTest(dispatcher) {
            storage.value = StorageUsage(usedBytes = 100L, availableBytes = 900L, rootPath = "/sdcard")
            wifiOnly.value = false

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.storage.usedBytes shouldBe 100L
            model.uiState.value.wifiOnly shouldBe false
        }

    // ---- actions --------------------------------------------------------------------------------

    @Test
    fun `switching tab changes nothing but the tab`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.selectTab(DownloadsTab.QUEUE)

            model.uiState.value.selectedTab shouldBe DownloadsTab.QUEUE
        }

    @Test
    fun `pause, resume and delete reach the repository`() =
        runTest(dispatcher) {
            val row = item("1", "Arrival", status = DownloadStatus.DOWNLOADING)
            items.value = listOf(row)
            val model = viewModel()
            advanceUntilIdle()

            model.pause(row)
            model.resume(row)
            model.delete(row)
            advanceUntilIdle()

            coVerify { downloads.pause("1") }
            coVerify { downloads.resume("1") }
            coVerify { downloads.delete("1") }
        }

    @Test
    fun `a failed delete raises a message`() =
        runTest(dispatcher) {
            val row = item("1", "Arrival", status = DownloadStatus.DOWNLOADED)
            items.value = listOf(row)
            coEvery { downloads.delete(any()) } returns AppResult.Failure(AppError.Storage())

            val model = viewModel()
            advanceUntilIdle()
            model.delete(row)
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe DownloadsMessage.DeleteFailed
        }

    @Test
    fun `a consumed message is cleared`() =
        runTest(dispatcher) {
            val row = item("1", "Arrival", status = DownloadStatus.DOWNLOADED)
            items.value = listOf(row)
            coEvery { downloads.delete(any()) } returns AppResult.Failure(AppError.Storage())

            val model = viewModel()
            advanceUntilIdle()
            model.delete(row)
            advanceUntilIdle()
            model.consumeMessage()

            model.uiState.value.userMessage shouldBe null
        }

    @Test
    fun `the Wi-Fi toggle writes through to the repository`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.setWifiOnly(false)
            advanceUntilIdle()

            coVerify { downloads.setWifiOnly(false) }
        }

    // ---- reordering -----------------------------------------------------------------------------

    @Test
    fun `moving up asks for the position one nearer the front`() =
        runTest(dispatcher) {
            val second = item("2", "Second", status = DownloadStatus.QUEUED, position = 1)
            items.value = listOf(item("1", "First", status = DownloadStatus.QUEUED, position = 0), second)

            val model = viewModel()
            advanceUntilIdle()
            model.moveUp(second)
            advanceUntilIdle()

            coVerify { downloads.move("2", 0) }
        }

    @Test
    fun `moving the first item up does nothing`() =
        runTest(dispatcher) {
            val first = item("1", "First", status = DownloadStatus.QUEUED, position = 0)
            items.value = listOf(first, item("2", "Second", status = DownloadStatus.QUEUED, position = 1))

            val model = viewModel()
            advanceUntilIdle()
            model.moveUp(first)
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.move(any(), any()) }
        }

    @Test
    fun `moving the last item down does nothing`() =
        runTest(dispatcher) {
            val last = item("2", "Second", status = DownloadStatus.QUEUED, position = 1)
            items.value = listOf(item("1", "First", status = DownloadStatus.QUEUED, position = 0), last)

            val model = viewModel()
            advanceUntilIdle()
            model.moveDown(last)
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.move(any(), any()) }
        }

    // ---- helpers --------------------------------------------------------------------------------

    private fun viewModel() = DownloadsViewModel(downloads = downloads, clock = FIXED_CLOCK)

    @Suppress("LongParameterList")
    private fun item(
        id: String,
        title: String,
        series: String? = null,
        status: DownloadStatus,
        position: Int = 0,
        onDisk: Long = 0L,
    ) = DownloadItem(
        itemId = id,
        title = title,
        seriesName = series,
        status = status,
        bytesDownloaded = 0L,
        bytesTotal = 0L,
        bytesOnDisk = onDisk,
        queuePosition = position,
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC)
    }
}
