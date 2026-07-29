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
    fun `downloaded episodes are grouped under their series, series groups sorted alphabetically`() =
        runTest(dispatcher) {
            // Only series on the tab — nothing for a film row to be confused with, so no Movies
            // section is expected here either.
            items.value =
                listOf(
                    item("1", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                    item("2", "Woodcutters in the Mist", series = "Fargo", status = DownloadStatus.DOWNLOADED),
                    item("3", "The Original", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val groups = model.uiState.value.downloaded
            groups.map { it.title } shouldContainExactly listOf("Fargo", "Westworld")
            groups.none { it.isMoviesSection } shouldBe true
            groups.first { it.title == "Westworld" }.items.map { it.itemId } shouldContainExactly listOf("1", "3")
        }

    @Test
    fun `a lone film gets no heading of its own name`() =
        runTest(dispatcher) {
            // The M9 bug (docs/POLISH.md): every film was drawn under a group header reading its
            // own title, so the name appeared twice, one line apart. Only films on the tab here —
            // see the next test for what happens once a series is also present.
            items.value =
                listOf(
                    item("1", "Dune", status = DownloadStatus.DOWNLOADED),
                    item("2", "Arrival", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val groups = model.uiState.value.downloaded
            groups.single { it.title == "Dune" }.isSeries shouldBe false
            groups.none { it.isMoviesSection } shouldBe true
        }

    @Test
    fun `a film alongside a series is gathered under the shared Movies heading, after every series group`() =
        runTest(dispatcher) {
            // The bug this fixes: a bare film row right after a series' last episode, at the same
            // indentation and with nothing marking the boundary, read as part of that series.
            items.value =
                listOf(
                    item("1", "Dune", status = DownloadStatus.DOWNLOADED),
                    item("2", "Arrival", status = DownloadStatus.DOWNLOADED),
                    item("3", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                    item("4", "Woodcutters in the Mist", series = "Fargo", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val groups = model.uiState.value.downloaded

            // Series groups first, alphabetically, each with its own heading …
            groups.dropLast(1).map { it.title } shouldContainExactly listOf("Fargo", "Westworld")
            groups.dropLast(1).all { it.isSeries } shouldBe true

            // … then one shared Movies group, last, holding every film in alphabetical order.
            val moviesSection = groups.last()
            moviesSection.isMoviesSection shouldBe true
            moviesSection.isSeries shouldBe false
            moviesSection.items.map { it.itemId } shouldContainExactly listOf("2", "1") // Arrival, Dune
        }

    @Test
    fun `two films sharing a title stay two rows`() =
        runTest(dispatcher) {
            // Grouping by title would have merged the 1984 and the 2021 Dune into one heading with
            // two identical rows under it.
            items.value =
                listOf(
                    item("1", "Dune", status = DownloadStatus.DOWNLOADED),
                    item("2", "Dune", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.downloaded
                .map { it.items.single().itemId } shouldContainExactly listOf("1", "2")
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

    // ---- the ratcheted progress the rows draw (schema v6) ----------------------------------------

    @Test
    fun `the state carries a ratcheted progress for every row`() =
        runTest(dispatcher) {
            items.value = listOf(downloading(bytes = 300L, total = 1_000L))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.progress["1"] shouldBe 0.3f
        }

    @Test
    fun `a growing projection cannot make a row's progress go backwards`() =
        runTest(dispatcher) {
            // 60 % against a 500-byte projection, then the projection is corrected up to 1 000.
            items.value = listOf(downloading(bytes = 300L, total = 500L))
            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.progress["1"] shouldBe 0.6f

            items.value = listOf(downloading(bytes = 300L, total = 1_000L))
            advanceUntilIdle()

            // The raw fraction is now 30 %, and not one byte was lost — see DownloadProgressRatchet.
            model.uiState.value.progress["1"] shouldBe 0.6f
        }

    private fun downloading(
        bytes: Long,
        total: Long,
    ) = item("1", "Chestnut", status = DownloadStatus.DOWNLOADING, downloaded = bytes, total = total)

    @Suppress("LongParameterList")
    private fun item(
        id: String,
        title: String,
        series: String? = null,
        status: DownloadStatus,
        position: Int = 0,
        onDisk: Long = 0L,
        downloaded: Long = 0L,
        total: Long = 0L,
    ) = DownloadItem(
        itemId = id,
        title = title,
        seriesName = series,
        status = status,
        bytesDownloaded = downloaded,
        bytesTotal = total,
        bytesOnDisk = onDisk,
        queuePosition = position,
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC)
    }
}
