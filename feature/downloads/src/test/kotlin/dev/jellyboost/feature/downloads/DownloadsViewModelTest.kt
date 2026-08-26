package dev.jellyboost.feature.downloads

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.DownloadKind
import dev.jellyboost.data.downloads.model.StorageUsage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
// One class per action group would separate each from the repository mock and clock they share.
@Suppress("LargeClass")
class DownloadsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val downloads = mockk<DownloadRepository>(relaxUnitFun = true)

    private val items = MutableStateFlow<List<DownloadItem>>(emptyList())
    private val storage = MutableStateFlow(StorageUsage())
    private val wifiOnly = MutableStateFlow(true)

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(dispatcher)

    @BeforeEach
    fun setUp() {
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
                .rows()
                .map { it.itemId } shouldContainExactly listOf("1")
            model.uiState.value.queue
                .map { it.itemId } shouldContainExactly listOf("2", "3")
        }

    @Test
    fun `downloaded episodes are grouped under their series, series groups sorted alphabetically`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                    item("2", "Woodcutters in the Mist", series = "Fargo", status = DownloadStatus.DOWNLOADED),
                    item("3", "The Original", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val section =
                model.uiState.value.downloaded
                    .single()
            section.kind shouldBe DownloadKind.SERIES
            section.groups.map { it.title } shouldContainExactly listOf("Fargo", "Westworld")
            section.groups.all { it.isCollapsible } shouldBe true
            section.groups
                .first { it.title == "Westworld" }
                .items
                .map { it.itemId } shouldContainExactly listOf("1", "3")
        }

    @Test
    fun `downloaded tracks group under their album, in a music section of their own`() =
        runTest(dispatcher) {
            // Artists do not nest above albums; the album is the whole heading.
            items.value =
                listOf(
                    track("1", "Go Your Own Way", album = "Rumours"),
                    track("2", "Dreams", album = "Rumours"),
                    item("3", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val sections = model.uiState.value.downloaded
            sections.map { it.kind } shouldContainExactly listOf(DownloadKind.SERIES, DownloadKind.MUSIC)
            val album = sections.last().groups.single()
            album.title shouldBe "Rumours"
            album.isCollapsible shouldBe true
            album.items.map { it.itemId } shouldContainExactly listOf("2", "1") // Dreams, Go Your…
        }

    @Test
    fun `films share one headerless group that never folds`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Dune", status = DownloadStatus.DOWNLOADED),
                    item("2", "Arrival", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val films =
                model.uiState.value.downloaded
                    .single()
            films.kind shouldBe DownloadKind.MOVIE
            films.groups.single().isCollapsible shouldBe false
            films.groups.single().title shouldBe ""
            // A single kind on the tab needs no label above it.
            model.uiState.value.showKindHeaders shouldBe false
        }

    @Test
    fun `films come first as one section, series after them`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Dune", status = DownloadStatus.DOWNLOADED),
                    item("2", "Arrival", status = DownloadStatus.DOWNLOADED),
                    item("3", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                    item("4", "Woodcutters in the Mist", series = "Fargo", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            val sections = model.uiState.value.downloaded
            sections.map { it.kind } shouldContainExactly listOf(DownloadKind.MOVIE, DownloadKind.SERIES)

            val films = sections.first().groups.single()
            films.isCollapsible shouldBe false
            films.items.map { it.itemId } shouldContainExactly listOf("2", "1") // Arrival, Dune

            sections.last().groups.map { it.title } shouldContainExactly listOf("Fargo", "Westworld")
            model.uiState.value.showKindHeaders shouldBe true
        }

    @Test
    fun `two films sharing a title stay two rows`() =
        runTest(dispatcher) {
            // Mutation check: grouping by title merges the 1984 and 2021 Dune into one heading.
            items.value =
                listOf(
                    item("1", "Dune", status = DownloadStatus.DOWNLOADED),
                    item("2", "Dune", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.downloaded
                .single()
                .groups
                .single()
                .items
                .map { it.itemId } shouldContainExactly listOf("1", "2")
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
                .groups
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
            // The tab rides the same combine as the projection, so it lands on the next dispatch
            // rather than in the tap itself.
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
                    // A transcode cannot be paused without discarding it.
                    transcode("3", "Chestnut", status = DownloadStatus.DOWNLOADING, position = 2),
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

    // ---- a collapsed projection ------------------------------------------------------------------

    /** `isLoading` starts `true` and only a first emission clears it, so a throw would hang it. */
    @Test
    fun `an upstream failure leaves an error state, never a spinner`() =
        runTest(dispatcher) {
            every { downloads.observeDownloads() } returns flow { error("corrupt blob") }

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.isLoading shouldBe false
            model.uiState.value.loadFailed shouldBe true
        }

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

    @Test
    fun `a healthy projection never sets the error state`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Arrival", status = DownloadStatus.DOWNLOADED))

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.isLoading shouldBe false
            model.uiState.value.loadFailed shouldBe false
        }

    // ---- what the projection costs while nobody is looking ---------------------------------------

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
            advanceTimeBy(DownloadsViewModel.STOP_TIMEOUT_MS / 2)
            runCurrent()
            completions shouldBe 0

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

            model.uiState.value.downloaded
                .rows()
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

    // ---- folding a group ------------------------------------------------------------------------

    @Test
    fun `toggling a group unfolds exactly that one, and folds it again`() =
        runTest(dispatcher) {
            items.value =
                listOf(
                    item("1", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED),
                    item("2", "Woodcutters in the Mist", series = "Fargo", status = DownloadStatus.DOWNLOADED),
                )

            val model = viewModel()
            advanceUntilIdle()

            model.toggleGroup("SERIES:Westworld")
            advanceUntilIdle()
            model.uiState.value.expandedGroups shouldBe setOf("SERIES:Westworld")

            model.toggleGroup("SERIES:Westworld")
            advanceUntilIdle()
            model.uiState.value.expandedGroups shouldBe emptySet()
        }

    @Test
    fun `an unfolded group survives the projection stopping and starting again`() =
        runTest(dispatcher) {
            items.value = listOf(item("1", "Chestnut", series = "Westworld", status = DownloadStatus.DOWNLOADED))

            val model = viewModel()
            advanceUntilIdle()
            model.toggleGroup("SERIES:Westworld")

            val screen = launch { model.uiState.collect {} }
            advanceUntilIdle()
            screen.cancel()
            advanceUntilIdle()

            model.uiState.value.expandedGroups shouldBe setOf("SERIES:Westworld")
        }

    // ---- helpers --------------------------------------------------------------------------------

    private fun List<DownloadSection>.rows() = flatMap { section -> section.groups.flatMap { it.items } }

    /**
     * The subscriber is load-bearing: with nothing collecting `uiState` the `WhileSubscribed`
     * projection never runs. `backgroundScope` is cancelled with the test, so it cannot hang it.
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

            model.uiState.value.progress["1"] shouldBe 0.6f
        }

    private fun downloading(
        bytes: Long,
        total: Long,
    ) = item("1", "Chestnut", status = DownloadStatus.DOWNLOADING, downloaded = bytes, total = total)

    /** Re-encoded, which is what makes it unpausable. */
    private fun transcode(
        id: String,
        title: String,
        status: DownloadStatus,
        position: Int = 0,
    ) = item(id, title, status = status, position = position, quality = DownloadQuality.LOW)

    private fun track(
        id: String,
        title: String,
        album: String,
        onDisk: Long = 0L,
    ) = downloadItem(
        itemId = id,
        title = title,
        status = DownloadStatus.DOWNLOADED,
        bytesOnDisk = onDisk,
        itemType = ItemType.AUDIO,
        albumName = album,
    )

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
    ) = downloadItem(
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
