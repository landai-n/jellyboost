package dev.jellyboost.player.music

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.data.downloads.offline.DownloadedMediaProvider
import dev.jellyboost.player.report.MusicReportTarget
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.PlaybackHandover
import dev.jellyboost.player.session.PlaybackKind
import dev.jellyboost.player.syncplay.SyncPlayStatusHolder
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.RepeatMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [MusicPlaybackController], against a fake [MusicPlayerPort].
 *
 * What is worth pinning here is **ordering** — a queue is one server session per track, and a
 * transition has to close the outgoing one before it opens the incoming one, or the dashboard
 * shows this device twice. Reports are therefore captured as a transcript rather than counted:
 * "stop 1 then start 2" is the property, and only a sequence shows it.
 *
 * The controller's own scope is deliberately *not* the test scope: it runs a one-second ticker for
 * as long as anything is playing, which a scope the test framework waits on would never let
 * complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlaybackControllerTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val sessionScope = CoroutineScope(dispatcher + SupervisorJob())

    private val port = FakeMusicPlayerPort()
    private val downloads = mockk<DownloadedMediaProvider>()
    private val reporter = mockk<PlaybackReporter>()
    private val handover = PlaybackHandover()
    private val syncPlay = SyncPlayStatusHolder()

    /** Every report the controller issued, in order. */
    private val reports = mutableListOf<String>()

    @AfterEach
    fun tearDown() {
        sessionScope.cancel()
    }

    /**
     * `runTest`, with the controller's scope torn down inside the test body.
     *
     * Not in an `@AfterEach`: `runTest` drains the shared scheduler when the body returns, and the
     * controller's one-second ticker is registered on it — a scheduler with a repeating task on it
     * never goes idle, so the drain runs until the heap does.
     */
    private fun musicTest(body: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
        runTest(dispatcher) {
            try {
                body()
            } finally {
                sessionScope.cancel()
            }
        }

    // ---- queue lifecycle ------------------------------------------------------------------

    @Test
    fun `play hands the whole album to the player and opens a session for the starting track`() =
        musicTest {
            val controller = controller()

            controller.play(MusicFixtures.album(), startIndex = 2) shouldBe true
            runCurrent()

            port.queue.map { it.title } shouldContainExactly listOf("Track 1", "Track 2", "Track 3")
            port.calls shouldContainExactly
                listOf("setShuffleEnabled(false)", "setRepeatMode(OFF)", "setQueue(3, 2, 0, true)")
            reports shouldContainExactly listOf("start ${MusicFixtures.TRACK_IDS[2]} @0 paused=false NONE DEFAULT")

            val state = controller.state.value.shouldBeInstanceOf<MusicPlaybackState.Active>()
            state.queue shouldHaveSameIdsAs MusicFixtures.album()
            state.currentIndex shouldBe 2
            state.isPlaying shouldBe true
        }

    @Test
    fun `a transition stops the outgoing track's session before it starts the incoming one`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album(), startIndex = 0)
            runCurrent()
            reports.clear()

            port.emit(MusicPlayerEvent.ItemTransition(index = 1, mediaId = null, automatic = true))
            runCurrent()

            reports shouldContainExactly
                listOf(
                    "stop ${MusicFixtures.TRACK_IDS[0]} @0 ended=true",
                    "start ${MusicFixtures.TRACK_IDS[1]} @0 paused=false NONE DEFAULT",
                )
            controller.state.value.currentIndexOrNull() shouldBe 1
        }

    @Test
    fun `a skip reports the outgoing track as unfinished, at where the ticker last saw it`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album(), startIndex = 0)
            runCurrent()
            port.currentSnapshot = port.currentSnapshot.copy(positionMs = 30_000L, isPlaying = true)
            advanceTimeBy(1.seconds)
            runCurrent()
            reports.clear()

            port.emit(MusicPlayerEvent.ItemTransition(index = 1, mediaId = null, automatic = false))
            runCurrent()

            reports.first() shouldBe "stop ${MusicFixtures.TRACK_IDS[0]} @300000000 ended=false"
        }

    @Test
    fun `the playlist-changed echo of our own setQueue does not re-report the track`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album(), startIndex = 1)
            runCurrent()
            reports.clear()

            // Media3 fires a transition for the playlist change itself, at the index we just asked
            // for; the session for it is already open.
            port.emit(MusicPlayerEvent.ItemTransition(index = 1, mediaId = null, automatic = false))
            runCurrent()

            reports shouldContainExactly emptyList()
        }

    @Test
    fun `the queue running out leaves it Active and paused, not Idle`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album(), startIndex = 2)
            runCurrent()
            reports.clear()

            port.emit(MusicPlayerEvent.Ended)
            runCurrent()

            reports shouldContainExactly listOf("stop ${MusicFixtures.TRACK_IDS[2]} @0 ended=true")
            val state = controller.state.value.shouldBeInstanceOf<MusicPlaybackState.Active>()
            state.isPlaying shouldBe false
            state.currentIndex shouldBe 2
        }

    @Test
    fun `stop closes the session, stops the player and goes Idle`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()
            reports.clear()

            controller.stop()
            runCurrent()
            // A second stop must not reach the player: `stopAndRelease` takes down the *shared*
            // media session service, which a film may by then be using.
            port.calls.clear()
            controller.stop()
            runCurrent()

            reports shouldContainExactly listOf("stop ${MusicFixtures.TRACK_IDS[0]} @0 ended=false")
            port.stopped shouldBe true
            port.calls shouldContainExactly emptyList()
            controller.state.value shouldBe MusicPlaybackState.Idle
            handover.currentOwner shouldBe null
        }

    @Test
    fun `the transport verbs reach the player rather than recursing into the controller`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()
            port.calls.clear()

            controller.next()
            controller.previous()
            controller.seekTo(12_000L)
            controller.jumpTo(2)
            runCurrent()

            port.calls shouldContainExactly listOf("next", "previous", "seekTo(12000)", "seekToItem(2)")
        }

    @Test
    fun `pausing and resuming go to the player, and play on an exhausted queue restarts it`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()
            port.calls.clear()

            controller.togglePlayPause()
            runCurrent()
            port.emit(MusicPlayerEvent.IsPlayingChanged(false))
            runCurrent()
            port.calls.clear()
            controller.togglePlayPause()
            runCurrent()

            port.calls shouldContainExactly listOf("play")
        }

    // ---- shuffle and repeat ---------------------------------------------------------------

    @Test
    fun `a shuffled play starts the player shuffled and reports the shuffle order`() =
        musicTest {
            val controller = controller()

            controller.play(MusicFixtures.album(), startIndex = 0, shuffled = true)
            runCurrent()

            port.shuffleEnabled shouldBe true
            reports.single() shouldBe "start ${MusicFixtures.TRACK_IDS[0]} @0 paused=false NONE SHUFFLE"
        }

    @Test
    fun `cycling repeat walks OFF, ALL, ONE and maps each onto the server's vocabulary`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()
            reports.clear()

            controller.cycleRepeat()
            runCurrent()
            controller.cycleRepeat()
            runCurrent()

            port.repeatMode shouldBe MusicRepeatMode.ONE
            controller.state.value
                .shouldBeInstanceOf<MusicPlaybackState.Active>()
                .repeatMode shouldBe
                MusicRepeatMode.ONE
            reports shouldContainExactly
                listOf(
                    "progress ${MusicFixtures.TRACK_IDS[0]} @0 paused=false ALL DEFAULT",
                    "progress ${MusicFixtures.TRACK_IDS[0]} @0 paused=false ONE DEFAULT",
                )
        }

    @Test
    fun `turning shuffle on mid-queue tells the player and the server`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()
            reports.clear()

            controller.setShuffle(true)
            runCurrent()

            port.shuffleEnabled shouldBe true
            reports.single() shouldBe "progress ${MusicFixtures.TRACK_IDS[0]} @0 paused=false NONE SHUFFLE"
        }

    // ---- refusals and failures ------------------------------------------------------------

    @Test
    fun `music is refused in a SyncPlay group, with a message and no player call`() =
        musicTest {
            val controller = controller()
            syncPlay.setInGroup(true)
            val messages = mutableListOf<MusicMessage>()
            val collector = sessionScope.launchCollecting(controller.messages, messages)
            runCurrent()

            controller.play(MusicFixtures.album()) shouldBe false
            runCurrent()

            messages shouldContainExactly listOf(MusicMessage.RefusedInSyncPlayGroup)
            port.calls shouldContainExactly emptyList()
            controller.state.value shouldBe MusicPlaybackState.Idle
            collector.cancel()
        }

    @Test
    fun `unplayable tracks are dropped, and the start index follows the track that was tapped`() =
        musicTest {
            // The middle track has an id nothing can parse, so the resolver drops it.
            val album = MusicFixtures.album().toMutableList().also { it[1] = it[1].copy(id = "not-an-id") }
            val controller = controller()
            val messages = mutableListOf<MusicMessage>()
            val collector = sessionScope.launchCollecting(controller.messages, messages)
            runCurrent()

            controller.play(album, startIndex = 2) shouldBe true
            runCurrent()

            port.queue.map { it.itemId } shouldContainExactly
                listOf(MusicFixtures.TRACK_IDS[0], MusicFixtures.TRACK_IDS[2])
            // Track 3 was tapped; after the drop it is entry 1, and that is where playback starts.
            port.calls.last() shouldBe "setQueue(2, 1, 0, true)"
            messages shouldContainExactly listOf(MusicMessage.TrackUnavailable("Track 2"))
            collector.cancel()
        }

    @Test
    fun `a queue nothing in which can be resolved never reaches the player`() =
        musicTest {
            val album = MusicFixtures.album().map { it.copy(id = "not-an-id") }
            val controller = controller()
            val messages = mutableListOf<MusicMessage>()
            val collector = sessionScope.launchCollecting(controller.messages, messages)
            runCurrent()

            controller.play(album) shouldBe false
            runCurrent()

            port.calls shouldContainExactly emptyList()
            messages shouldContainExactly listOf(MusicMessage.QueueUnavailable)
            collector.cancel()
        }

    // ---- handover -------------------------------------------------------------------------

    @Test
    fun `video taking the player parks the queue as a paused snapshot and lets the player go`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album(), startIndex = 1)
            runCurrent()
            port.currentSnapshot = MusicPortSnapshot(currentItemIndex = 1, positionMs = 42_000L, isPlaying = true)
            reports.clear()

            handover.claim(PlaybackKind.VIDEO) {}
            runCurrent()

            // The stop report is issued by music's own relinquish, before the claim returns.
            reports shouldContainExactly listOf("stop ${MusicFixtures.TRACK_IDS[1]} @420000000 ended=false")
            port.released shouldBe true
            port.stopped shouldBe false
            val state = controller.state.value.shouldBeInstanceOf<MusicPlaybackState.Active>()
            state.isPlaying shouldBe false
            state.currentIndex shouldBe 1
            state.positionMs shouldBe 42_000L
        }

    @Test
    fun `resuming after a handover re-prepares the parked queue where it left off`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album(), startIndex = 1)
            runCurrent()
            port.currentSnapshot = MusicPortSnapshot(currentItemIndex = 1, positionMs = 42_000L, isPlaying = true)
            handover.claim(PlaybackKind.VIDEO) {}
            runCurrent()
            port.calls.clear()
            reports.clear()

            controller.togglePlayPause()
            runCurrent()

            port.calls shouldContainExactly
                listOf("setShuffleEnabled(false)", "setRepeatMode(OFF)", "setQueue(3, 1, 42000, true)")
            reports shouldContainExactly
                listOf("start ${MusicFixtures.TRACK_IDS[1]} @420000000 paused=false NONE DEFAULT")
            handover.currentOwner shouldBe PlaybackKind.MUSIC
        }

    // ---- queue editing --------------------------------------------------------------------

    @Test
    fun `removing an entry drops it from the queue the UI draws and from the player`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()

            controller.removeAt(1)
            runCurrent()

            port.queue.map { it.itemId } shouldContainExactly
                listOf(MusicFixtures.TRACK_IDS[0], MusicFixtures.TRACK_IDS[2])
            controller.state.value
                .shouldBeInstanceOf<MusicPlaybackState.Active>()
                .queue
                .map { it.id } shouldContainExactly
                listOf(MusicFixtures.TRACK_IDS[0].toString(), MusicFixtures.TRACK_IDS[2].toString())
        }

    @Test
    fun `emptying the queue ends the session`() =
        musicTest {
            val controller = controller()
            controller.play(listOf(MusicFixtures.track(0)))
            runCurrent()

            controller.removeAt(0)
            runCurrent()

            controller.state.value shouldBe MusicPlaybackState.Idle
        }

    @Test
    fun `reordering the queue moves it in both the state and the player`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()

            controller.moveItem(from = 0, to = 2)
            runCurrent()

            port.queue.map { it.itemId } shouldContainExactly
                listOf(MusicFixtures.TRACK_IDS[1], MusicFixtures.TRACK_IDS[2], MusicFixtures.TRACK_IDS[0])
        }

    // ---- ticking --------------------------------------------------------------------------

    @Test
    fun `the position ticks every second and a progress report goes out every ten`() =
        musicTest {
            val controller = controller()
            controller.play(MusicFixtures.album())
            runCurrent()
            reports.clear()
            port.currentSnapshot = MusicPortSnapshot(positionMs = 5_000L, durationMs = 240_000L, isPlaying = true)

            advanceTimeBy(9.seconds)
            runCurrent()
            val afterNine = controller.state.value.shouldBeInstanceOf<MusicPlaybackState.Active>()
            afterNine.positionMs shouldBe 5_000L
            afterNine.durationMs shouldBe 240_000L
            reports shouldContainExactly emptyList()

            advanceTimeBy(1.seconds)
            runCurrent()

            reports.single() shouldBe "progress ${MusicFixtures.TRACK_IDS[0]} @50000000 paused=false NONE DEFAULT"
        }

    // ---- plumbing -------------------------------------------------------------------------

    private fun controller(): MusicPlaybackController {
        coEvery { downloads.get(any()) } returns null
        stubReporter()
        return MusicPlaybackController(
            port = port,
            resolver = MusicStreamResolver(downloads, MusicFixtures.FakeAudioStreamUrlFactory()),
            specFactory = MusicQueueSpecFactory(),
            reporter = reporter,
            handover = handover,
            syncPlay = syncPlay,
            scope = sessionScope,
            mainDispatcher = dispatcher,
        ).also { scheduler.runCurrent() }
    }

    /** Records every report as one readable line, in the order the controller issued them. */
    private fun stubReporter() {
        coEvery {
            reporter.reportMusicStart(any(), any(), any(), any(), any())
        } answers {
            val target = firstArg<MusicReportTarget>()
            reports +=
                "start ${target.itemId} @${secondArg<Long>()} paused=${thirdArg<Boolean>()} " +
                "${arg<RepeatMode>(3).short()} ${arg<PlaybackOrder>(4).name}"
        }
        coEvery {
            reporter.reportMusicProgress(any(), any(), any(), any(), any())
        } answers {
            val target = firstArg<MusicReportTarget>()
            reports +=
                "progress ${target.itemId} @${secondArg<Long>()} paused=${thirdArg<Boolean>()} " +
                "${arg<RepeatMode>(3).short()} ${arg<PlaybackOrder>(4).name}"
        }
        coEvery { reporter.reportMusicStop(any(), any(), any()) } answers {
            val target = firstArg<MusicReportTarget>()
            reports += "stop ${target.itemId} @${secondArg<Long>()} ended=${thirdArg<Boolean>()}"
        }
    }
}

private fun RepeatMode.short(): String = name.removePrefix("REPEAT_")

private fun MusicPlaybackState.currentIndexOrNull(): Int? = (this as? MusicPlaybackState.Active)?.currentIndex

private infix fun List<JellyfinItem>.shouldHaveSameIdsAs(expected: List<JellyfinItem>) =
    map { it.id } shouldContainExactly expected.map { it.id }

/** Collects [flow] into [into] for the duration of a test; the caller cancels the job. */
private fun <T> CoroutineScope.launchCollecting(
    flow: Flow<T>,
    into: MutableList<T>,
): Job = launch { flow.collect { into += it } }
