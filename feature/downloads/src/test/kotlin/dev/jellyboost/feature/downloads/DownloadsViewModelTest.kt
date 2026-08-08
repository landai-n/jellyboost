package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.StorageUsage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
        coEvery { downloads.pauseAll(any()) } returns AppResult.Success(Unit)
        coEvery { downloads.resumeAll(any()) } returns AppResult.Success(Unit)
        coEvery { downloads.deleteAll(any()) } returns AppResult.Success(0L)
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
            // The tab is no longer written straight into the state: it rides the same combine as
            // the projection, so it lands on the next dispatch rather than in the tap itself.
            advanceUntilIdle()

            model.uiState.value.selectedTab shouldBe DownloadsTab.QUEUE
        }

    @Test
    fun `pause, resume and delete reach the repository`() =
        runTest(dispatcher) {
            val row = item("1", "Arrival", status = DownloadStatus.DOWNLOADING)
            items.value = listOf(row)
            val model = viewModel()
            advanceUntilIdle()

            model.pause(row.itemId)
            model.resume(row.itemId)
            model.delete(row.itemId)
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
            model.delete(row.itemId)
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
            model.delete(row.itemId)
            advanceUntilIdle()
            model.consumeMessage()
            advanceUntilIdle()

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

    // ---- queue-wide actions ----------------------------------------------------------------------

    @Test
    fun `pause all pauses every pausable row and leaves the transcodes downloading`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Arrival", status = DownloadStatus.DOWNLOADING),
                    item("2", "Dune", status = DownloadStatus.QUEUED, position = 1),
                    // A transcode cannot be paused without discarding it (DownloadItem.isPausable),
                    // so *Pause all* must leave it alone rather than destroy its progress.
                    transcode("3", "Chestnut", status = DownloadStatus.DOWNLOADING, position = 2),
                    // Already paused, and a failure: neither is a pause target.
                    item("4", "Sicario", status = DownloadStatus.PAUSED, position = 3),
                    item("5", "Blade Runner", status = DownloadStatus.ERROR, position = 4),
                )

            val model = viewModel()
            advanceUntilIdle()
            model.pauseAll()
            advanceUntilIdle()

            coVerify { downloads.pauseAll(listOf("1", "2")) }
            coVerify(exactly = 0) { downloads.pause(any()) }
        }

    @Test
    fun `pause all says how many transcodes it deliberately left running`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Arrival", status = DownloadStatus.DOWNLOADING),
                    transcode("2", "Chestnut", status = DownloadStatus.QUEUED, position = 1),
                    transcode("3", "The Original", status = DownloadStatus.QUEUED, position = 2),
                )

            val model = viewModel()
            advanceUntilIdle()
            model.pauseAll()
            advanceUntilIdle()

            // Without this the queue visibly keeps moving after "Pause all" and reads as a bug.
            model.uiState.value.userMessage shouldBe
                DownloadsMessage.PausedKeepingTranscodes(pausedCount = 1, transcodingCount = 2)
        }

    @Test
    fun `pause all is silent when the whole queue could be paused`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Arrival", status = DownloadStatus.DOWNLOADING),
                    item("2", "Dune", status = DownloadStatus.QUEUED, position = 1),
                )

            val model = viewModel()
            advanceUntilIdle()
            model.pauseAll()
            advanceUntilIdle()

            // The rows themselves changed to "Paused"; a snackbar over them would say nothing more.
            model.uiState.value.userMessage shouldBe null
        }

    @Test
    fun `a queue with nothing pausable in it offers no Pause all, and pausing it does nothing`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    transcode("1", "Chestnut", status = DownloadStatus.DOWNLOADING),
                    item("2", "Dune", status = DownloadStatus.PAUSED, position = 1),
                )

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.canPauseAll shouldBe false

            model.pauseAll()
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.pauseAll(any()) }
        }

    @Test
    fun `a failed pause outranks the transcode message`() =
        runTest(dispatcher) {
            coEvery { downloads.pauseAll(any()) } returns AppResult.Failure(AppError.Storage())
            items.value =
                listOf(
                    item("1", "Arrival", status = DownloadStatus.DOWNLOADING),
                    transcode("2", "Chestnut", status = DownloadStatus.QUEUED, position = 1),
                )

            val model = viewModel()
            advanceUntilIdle()
            model.pauseAll()
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe DownloadsMessage.ActionFailed
        }

    @Test
    fun `resume all puts every paused and failed row back in the queue, and nothing else`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Arrival", status = DownloadStatus.PAUSED),
                    item("2", "Dune", status = DownloadStatus.ERROR, position = 1),
                    // A paused transcode is still resumable — it just costs the whole transfer again.
                    transcode("3", "Chestnut", status = DownloadStatus.PAUSED, position = 2),
                    item("4", "Sicario", status = DownloadStatus.DOWNLOADING, position = 3),
                    item("5", "Blade Runner", status = DownloadStatus.QUEUED, position = 4),
                )

            val model = viewModel()
            advanceUntilIdle()
            model.resumeAll()
            advanceUntilIdle()

            coVerify { downloads.resumeAll(listOf("1", "2", "3")) }
            coVerify(exactly = 0) { downloads.resume(any()) }
            model.uiState.value.userMessage shouldBe null
        }

    @Test
    fun `a queue with nothing paused or failed offers no Resume all, and resuming it does nothing`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADING))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.canResumeAll shouldBe false

            model.resumeAll()
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.resumeAll(any()) }
        }

    @Test
    fun `a failed bulk resume reports it`() =
        runTest(dispatcher) {
            coEvery { downloads.resumeAll(any()) } returns AppResult.Failure(AppError.Storage())
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.PAUSED))

            val model = viewModel()
            advanceUntilIdle()
            model.resumeAll()
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe DownloadsMessage.ActionFailed
        }

    @Test
    fun `cancel all asks before it removes anything`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADING))

            val model = viewModel()
            advanceUntilIdle()
            model.requestCancelAll()
            advanceUntilIdle()

            model.uiState.value.showCancelAllConfirmation shouldBe true
            coVerify(exactly = 0) { downloads.delete(any()) }
            coVerify(exactly = 0) { downloads.deleteAll(any()) }
        }

    @Test
    fun `dismissing the cancel-all dialog leaves the queue untouched`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADING))

            val model = viewModel()
            advanceUntilIdle()
            model.requestCancelAll()
            model.dismissCancelAll()
            advanceUntilIdle()

            model.uiState.value.showCancelAllConfirmation shouldBe false
            coVerify(exactly = 0) { downloads.delete(any()) }
            coVerify(exactly = 0) { downloads.deleteAll(any()) }
        }

    @Test
    fun `confirming cancel all empties the queue and never touches a finished download`() =
        runTest(dispatcher) {
            // The season-cancel rule (DECISIONS.md, 2026-07-29) applied to the whole queue: what is
            // already on the device survives, whatever state the queue rows are in.
            items.value =
                listOf(
                    item("1", "Arrival", status = DownloadStatus.DOWNLOADED),
                    item("2", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                    item("3", "Dune", status = DownloadStatus.DOWNLOADING, position = 1),
                    item("4", "Sicario", status = DownloadStatus.QUEUED, position = 2),
                    item("5", "The Original", status = DownloadStatus.PAUSED, position = 3),
                    item("6", "Blade Runner", status = DownloadStatus.ERROR, position = 4),
                )

            val model = viewModel()
            advanceUntilIdle()
            model.requestCancelAll()
            model.confirmCancelAll()
            advanceUntilIdle()

            coVerify { downloads.deleteAll(listOf("3", "4", "5", "6")) }
            coVerify(exactly = 0) { downloads.delete(any()) }
            model.uiState.value.showCancelAllConfirmation shouldBe false
        }

    @Test
    fun `a failed bulk delete raises the delete message`() =
        runTest(dispatcher) {
            coEvery { downloads.deleteAll(any()) } returns AppResult.Failure(AppError.Storage())
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADING))

            val model = viewModel()
            advanceUntilIdle()
            model.confirmCancelAll()
            advanceUntilIdle()

            model.uiState.value.userMessage shouldBe DownloadsMessage.DeleteFailed
        }

    @Test
    fun `an empty queue offers no bulk action at all`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADED))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.queue
                .isEmpty() shouldBe true
            model.uiState.value.canPauseAll shouldBe false
            model.uiState.value.canResumeAll shouldBe false

            model.requestCancelAll()
            model.pauseAll()
            model.resumeAll()
            advanceUntilIdle()

            // In particular the dialog cannot be opened over an empty queue.
            model.uiState.value.showCancelAllConfirmation shouldBe false
            coVerify(exactly = 0) { downloads.delete(any()) }
            coVerify(exactly = 0) { downloads.deleteAll(any()) }
            coVerify(exactly = 0) { downloads.pause(any()) }
            coVerify(exactly = 0) { downloads.pauseAll(any()) }
            coVerify(exactly = 0) { downloads.resume(any()) }
            coVerify(exactly = 0) { downloads.resumeAll(any()) }
        }

    // ---- reordering -----------------------------------------------------------------------------

    @Test
    fun `moving up asks for the position one nearer the front`() =
        runTest(dispatcher) {
            val second = item("2", "Second", status = DownloadStatus.QUEUED, position = 1)
            items.value = listOf(item("1", "First", status = DownloadStatus.QUEUED, position = 0), second)

            val model = viewModel()
            advanceUntilIdle()
            model.moveUp(second.itemId)
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
            model.moveUp(first.itemId)
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
            model.moveDown(last.itemId)
            advanceUntilIdle()

            coVerify(exactly = 0) { downloads.move(any(), any()) }
        }

    // ---- a collapsed projection (audit STAB-10) --------------------------------------------------

    /**
     * The worst failure this screen had: `isLoading` starts `true` and only a first emission clears
     * it, so a throw upstream — `SQLiteBlobTooBigException` on a corrupt dto blob is the real one —
     * left a spinner turning forever with nothing to tell the user and no way to retry.
     */
    @Test
    fun `an upstream failure leaves an error state, never a spinner`() =
        runTest(dispatcher) {
            every { downloads.observeDownloads() } returns flow { error("corrupt blob") }

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.isLoading shouldBe false
            model.uiState.value.loadFailed shouldBe true
        }

    /** A failure part-way through must not leave the last good rows on screen unmarked. */
    @Test
    fun `a failure after a good emission still raises the error state`() =
        runTest(dispatcher) {
            every { downloads.observeDownloads() } returns
                flow {
                    emit(listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADED)))
                    error("corrupt blob")
                }

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.isLoading shouldBe false
            model.uiState.value.loadFailed shouldBe true
        }

    /** A healthy projection must not be marked as failed — the flag is not just "not loading". */
    @Test
    fun `a healthy projection never sets the error state`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADED))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.isLoading shouldBe false
            model.uiState.value.loadFailed shouldBe false
        }

    // ---- what the projection costs while nobody is looking (audit PERF-03) -----------------------

    /**
     * The projection used to be launched in `init` and never unsubscribed, so from the first visit
     * until process death it kept pulling the download list (a full metadata join) and the storage
     * figures — with the screen off, and with a tab switch *saving* this screen rather than popping
     * it.
     */
    @Test
    fun `the download queries only run while something is collecting the state`() =
        runTest(dispatcher) {
            var subscriptions = 0
            var completions = 0
            every { downloads.observeDownloads() } returns
                items
                    .onStart { subscriptions++ }
                    .onCompletion { completions++ }

            val model =
                DownloadsViewModel(
                    downloads = downloads,
                    clock = FIXED_CLOCK,
                    defaultDispatcher = dispatcher,
                )
            advanceUntilIdle()

            subscriptions shouldBe 0

            val screen = launch { model.uiState.collect {} }
            advanceUntilIdle()
            subscriptions shouldBe 1

            screen.cancel()
            // Inside the grace window a rotation re-uses the running projection …
            advanceTimeBy(DownloadsViewModel.STOP_TIMEOUT_MS / 2)
            runCurrent()
            completions shouldBe 0

            // … and past it the queries stop.
            advanceUntilIdle()
            completions shouldBe 1
        }

    @Test
    fun `the last state stays readable after the projection stops`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADED))

            val model = viewModel()
            advanceUntilIdle()

            val screen = launch { model.uiState.collect {} }
            advanceUntilIdle()
            screen.cancel()
            advanceUntilIdle()

            // An action fired from a row the user can still see must not read an empty list back.
            model.uiState.value.downloaded
                .flatMap { it.items }
                .map { it.itemId } shouldContainExactly listOf("1")
        }

    @Test
    fun `the tab survives the projection stopping and starting again`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            model.selectTab(DownloadsTab.QUEUE)

            val screen = launch { model.uiState.collect {} }
            advanceUntilIdle()
            screen.cancel()
            advanceUntilIdle()

            model.uiState.value.selectedTab shouldBe DownloadsTab.QUEUE
        }

    // ---- helpers --------------------------------------------------------------------------------

    /**
     * A ViewModel with a live subscriber on its state.
     *
     * The subscriber is the point: the projection is shared with `WhileSubscribed`, so with nothing
     * collecting `uiState` there is deliberately no Room query and no state to assert on (audit
     * PERF-03). `backgroundScope` is what the screen's collection stands in for — it is cancelled
     * when the test ends, so the never-completing projection cannot hang `runTest`.
     */
    private fun TestScope.viewModel(): DownloadsViewModel =
        DownloadsViewModel(
            downloads = downloads,
            clock = FIXED_CLOCK,
            defaultDispatcher = dispatcher,
        ).also { model -> backgroundScope.launch { model.uiState.collect {} } }

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

    /** A queue row that is being re-encoded, which is what makes it unpausable. */
    private fun transcode(
        id: String,
        title: String,
        status: DownloadStatus,
        position: Int = 0,
    ) = item(id, title, status = status, position = position, quality = DownloadQuality.LOW)

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
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
    ) = DownloadItem(
        itemId = id,
        title = title,
        seriesName = series,
        status = status,
        bytesDownloaded = downloaded,
        bytesTotal = total,
        bytesOnDisk = onDisk,
        queuePosition = position,
        quality = quality,
    )

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-07-28T12:00:00Z"), ZoneOffset.UTC)
    }
}
