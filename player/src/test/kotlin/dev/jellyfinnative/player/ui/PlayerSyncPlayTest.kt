package dev.jellyfinnative.player.ui

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.PlaybackSpeed
import dev.jellyfinnative.player.model.millisToTicks
import dev.jellyfinnative.player.model.ticksToMillis
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.syncplay.SyncPlayAnchor
import dev.jellyfinnative.player.syncplay.SyncPlayMessage
import dev.jellyfinnative.player.syncplay.SyncPlayPhase
import dev.jellyfinnative.player.syncplay.SyncPlayState
import dev.jellyfinnative.player.syncplay.group
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyfinnative.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayShuffleMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * What the player does while it is part of a SyncPlay group (M11 Phase 3).
 *
 * One rule carries the whole class and it is a claim about calls that are *never made*: in a group,
 * no user action moves this player (docs/notes/syncplay-m11-plan.md, key decision 11). Every intent
 * test therefore asserts both halves — the request that went to the coordinator, and the fact that
 * `PlayerHandle` was left completely alone. Asserting only the first would pass just as happily for
 * a player that seeks locally *and* tells the group, which is the failure mode: two clients drifting
 * apart while both believe they are in sync.
 *
 * The solo behaviour these branches must not disturb is pinned by [PlayerViewModelTest] and
 * [PlayerTrackPickerTest], which run against the same fixture with no group in it — they are the
 * other half of this phase's acceptance, and they are unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerSyncPlayTest : PlayerViewModelFixture() {
    private val playlistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val otherItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c9")

    // ---- transport routes to the group ------------------------------------------------------------

    @Test
    fun `a play tap in a group asks the server to unpause, and never starts this player`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.togglePlayPause()

            verify(exactly = 1) { syncPlayController.requestUnpause() }
            playerHandle.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `a pause tap in a group asks the server to pause, and never pauses this player`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.togglePlayPause()

            verify(exactly = 1) { syncPlayController.requestPause() }
            playerHandle.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `a scrub in a group is a group seek in ticks, and the seek bar does not jump ahead of it`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.seekTo(90_000L)

            verify(exactly = 1) { syncPlayController.requestSeek(90_000L.millisToTicks()) }
            playerHandle.hadNoTransportCalls shouldBe true
            // Publishing optimistically would show a position this player is not at until the
            // server's command arrives — and never correct it if the group refuses the seek.
            model.position.value.positionMs shouldBe RESUME_TICKS.ticksToMillis()
        }

    @Test
    fun `a skip-forward jump in a group is a group seek from the current position`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 60_000L, durationMs = 600_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.seekBy(30_000L)

            verify(exactly = 1) { syncPlayController.requestSeek(90_000L.millisToTicks()) }
            playerHandle.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `solo, the very same actions still drive the player directly`() =
        runTest(dispatcher) {
            // The control: nothing above may be reachable without a group.
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.togglePlayPause()
            model.seekTo(90_000L)

            playerHandle.playCount shouldBe 1
            playerHandle.seekedToMs shouldContainExactly listOf(90_000L)
            model.position.value.positionMs shouldBe 90_000L
            verify(exactly = 0) { syncPlayController.requestUnpause() }
            verify(exactly = 0) { syncPlayController.requestSeek(any()) }
        }

    // ---- speed and segments -----------------------------------------------------------------------

    @Test
    fun `a playback rate change is refused in a group`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.selectSpeed(PlaybackSpeed.ONE_AND_HALF)

            // There is no per-member rate in SyncPlay: playing faster than the group is drifting.
            playerHandle.playbackSpeeds.shouldBeEmpty()
            model.uiState.value.speed shouldBe PlaybackSpeed.NORMAL
        }

    @Test
    fun `an auto-skip in a group becomes an offer instead of a seek`() =
        runTest(dispatcher) {
            every { preferences.introSkipMode } returns flowOf(SegmentSkipMode.AUTO_SKIP)
            coEvery { segmentLoader.load(any()) } returns listOf(intro)
            syncPlayState.value = inGroup()
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.onTick(PlaybackSnapshot(positionMs = 35_000L))

            playerHandle.hadNoTransportCalls shouldBe true
            verify(exactly = 0) { syncPlayController.requestSeek(any()) }
            // Suppressed, not discarded: the button is what an auto-skip preference degrades to.
            model.uiState.value.skippableSegment shouldBe intro
        }

    @Test
    fun `the skip button in a group asks the whole group to skip the intro`() =
        runTest(dispatcher) {
            coEvery { segmentLoader.load(any()) } returns listOf(intro)
            syncPlayState.value = inGroup()
            val model = viewModel()
            advanceUntilIdle()
            model.onTick(PlaybackSnapshot(positionMs = 60_000L))
            playerHandle.resetCalls()

            model.skipCurrentSegment()

            verify(exactly = 1) { syncPlayController.requestSeek(intro.endMs.millisToTicks()) }
            playerHandle.hadNoTransportCalls shouldBe true
        }

    // ---- the host the coordinator drives ----------------------------------------------------------

    @Test
    fun `loading the group's item resolves it and leaves the player paused`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            val request = slot<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(request)) } returns AppResult.Success(source)
            playerHandle.resetCalls()

            val opened = model.loadItem(otherItemId, 60_000L.millisToTicks())
            advanceUntilIdle()

            opened shouldBe true
            request.captured.itemId shouldBe otherItemId
            request.captured.startPositionTicks shouldBe 60_000L.millisToTicks()
            // The group decides when playback starts; a host that started on its own would be out
            // of sync from the first frame.
            playerHandle.prepared.single().playWhenReady shouldBe false
            playerHandle.playCount shouldBe 0
        }

    @Test
    fun `an item that cannot be resolved is reported back rather than left half-open`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.NotFound(otherItemId.toString()))
            val model = viewModel()
            advanceUntilIdle()

            val opened = model.loadItem(otherItemId, 0L)
            advanceUntilIdle()

            // `false` is what stops the group waiting for ever on a member that will never be ready.
            opened shouldBe false
        }

    @Test
    fun `the coordinator gets this player once a session is open, and gets it back when the screen closes`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            verify(exactly = 1) { syncPlayController.attachHost(model) }

            model.releaseSession()

            // The controller sends `ignoreWait` from its side, so nobody is left waiting on a
            // player that no longer exists (key decision 5).
            verify(exactly = 1) { syncPlayController.detachHost(model) }
        }

    @Test
    fun `a snapshot tells the coordinator what is open and where it is`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 45_000L, isPlaying = true)

            val snapshot = model.snapshot()

            snapshot.itemId shouldBe PlayerFixtures.ITEM_ID
            snapshot.positionTicks shouldBe 45_000L.millisToTicks()
            snapshot.isPlaying shouldBe true
        }

    @Test
    fun `a re-negotiation tells the group this member is buffering`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // `PlayerEvent` has no "buffering", so nothing but the host can tell the group that the
            // player it is waiting on has been thrown away and rebuilt.
            verify(exactly = 1) { syncPlayController.onHostBuffering() }
        }

    // ---- what the screen is told -------------------------------------------------------------------

    @Test
    fun `the group, its participants and its queue reach the ui state`() =
        runTest(dispatcher) {
            syncPlayState.value =
                inGroup(
                    phase = SyncPlayPhase.Playing(SyncPlayAnchor(positionMs = 0L, at = Instant.EPOCH)),
                    queue = queue(shuffle = SyncPlayShuffleMode.Shuffle, repeat = SyncPlayRepeatMode.All),
                )
            val model = viewModel()
            advanceUntilIdle()

            val syncPlay = model.uiState.value.syncPlay
            syncPlay.inGroup shouldBe true
            syncPlay.groupName shouldBe "Film night"
            syncPlay.participants shouldContainExactly listOf("casey")
            syncPlay.phase shouldBe PlayerSyncPlayPhase.PLAYING
            syncPlay.queueSize shouldBe 1
            syncPlay.hasQueue shouldBe true
            syncPlay.isShuffled shouldBe true
            syncPlay.repeatMode shouldBe SyncPlayRepeatMode.All
            syncPlay.isWaitingForGroup shouldBe false
        }

    @Test
    fun `a group that is waiting on someone raises the overlay`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup(phase = SyncPlayPhase.Waiting)
            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.syncPlay.isWaitingForGroup shouldBe true
            model.uiState.value.syncPlay.phase shouldBe PlayerSyncPlayPhase.WAITING
        }

    @Test
    fun `solo, the ui state carries no group at all`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.syncPlay shouldBe PlayerSyncPlayState()
        }

    @Test
    fun `a lost connection is said out loud rather than left as a mysterious pause`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            val model = viewModel()
            advanceUntilIdle()

            syncPlayMessages.emit(SyncPlayMessage.ConnectionLost)
            advanceUntilIdle()

            // The controller has already paused playback and left the group; the copy is this
            // layer's ("Left SyncPlay — connection lost").
            model.uiState.value.userMessage shouldBe PlayerMessage.SyncPlayConnectionLost
        }

    // ---- fixture ------------------------------------------------------------------------------------

    private fun inGroup(
        phase: SyncPlayPhase = SyncPlayPhase.Paused,
        queue: SyncPlayGroupQueue? = queue(),
    ) = SyncPlayState.InGroup(group(), queue, phase)

    private fun queue(
        shuffle: SyncPlayShuffleMode = SyncPlayShuffleMode.Sorted,
        repeat: SyncPlayRepeatMode = SyncPlayRepeatMode.None,
    ) = SyncPlayGroupQueue(
        entries = listOf(SyncPlayQueueEntry(PlayerFixtures.ITEM_ID, playlistItemId)),
        playingItemIndex = 0,
        startPositionTicks = 0L,
        isPlaying = false,
        shuffleMode = shuffle,
        repeatMode = repeat,
        reason = SyncPlayQueueUpdateReason.NewPlaylist,
        lastUpdate = Instant.parse("2026-07-30T18:00:00Z"),
    )
}
