package dev.jellyboost.player.ui

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.model.ticksToMillis
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.syncplay.SyncPlayAnchor
import dev.jellyboost.player.syncplay.SyncPlayMessage
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
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
 * In a group, no user action moves this player directly, so every intent test asserts both halves:
 * the request sent to the coordinator, and that `PlayerHandle` was left untouched. Asserting only
 * the first would still pass for a player that seeks locally *and* tells the group — two clients
 * drifting apart while both believe they're in sync.
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
            syncPlayState.value = inGroup(groupState = SyncPlayGroupState.Playing)
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.togglePlayPause()

            verify(exactly = 1) { syncPlayController.requestPause() }
            playerHandle.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `a missed echo leaves the local player still playing in a paused group, but a tap still asks to unpause`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup(groupState = SyncPlayGroupState.Paused)
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.togglePlayPause()

            // Deciding from local `isPlaying` here would re-send `requestPause` — already missed —
            // and leave the group waiting on a second tap.
            verify(exactly = 1) { syncPlayController.requestUnpause() }
            verify(exactly = 0) { syncPlayController.requestPause() }
            playerHandle.hadNoTransportCalls shouldBe true
        }

    @Test
    fun `a stalled local player in a playing group still gets a pause request from a tap`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup(groupState = SyncPlayGroupState.Playing)
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 30_000L, isPlaying = false)
            val model = viewModel()
            advanceUntilIdle()
            playerHandle.resetCalls()

            model.togglePlayPause()

            verify(exactly = 1) { syncPlayController.requestPause() }
            verify(exactly = 0) { syncPlayController.requestUnpause() }
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
            // Publishing optimistically would show a position never corrected if the group refuses the seek.
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

    // ---- opening into a group ----------------------------------------------------------------------

    @Test
    fun `a session opened while in a group starts paused, whoever opened it`() =
        runTest(dispatcher) {
            // The NavHost opens this screen with ordinary player arguments that say "play" — the
            // group decides when playback starts, so this must not.
            syncPlayState.value = inGroup()

            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.syncPlay.inGroup shouldBe true
            playerHandle.prepared.single().playWhenReady shouldBe false
            playerHandle.playCount shouldBe 0
        }

    @Test
    fun `solo, a session opened from a Play tap still starts playing`() =
        runTest(dispatcher) {
            // The control for the above: with no group, opening the player from Play means play.
            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.syncPlay.inGroup shouldBe false
            playerHandle.prepared.single().playWhenReady shouldBe true
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
    fun `loading the group's item in the same session refreshes title, subtitle and duration (B4)`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()
            model.uiState.value.title shouldBe "Arrival"
            model.uiState.value.durationMs shouldBe PlayerFixtures.RUN_TIME_TICKS.ticksToMillis()

            val nextEpisode =
                JellyfinItem(
                    id = otherItemId.toString(),
                    name = "Episode 8",
                    type = ItemType.EPISODE,
                    seriesName = "Pyjamasques",
                    parentIndexNumber = 1,
                    indexNumber = 8,
                )
            coEvery { repository.getItem(otherItemId.toString()) } returns AppResult.Success(nextEpisode)
            val nextRunTimeTicks = 60_000L.millisToTicks()
            coEvery { resolver.resolve(any()) } returns
                AppResult.Success(source.copy(itemId = otherItemId, runTimeTicks = nextRunTimeTicks))

            val opened = model.loadItem(otherItemId, 0L)
            advanceUntilIdle()

            opened shouldBe true
            // Both halves of the device finding (check B4): the old item's title survived the
            // reload, and so did its duration — the queue sheet's now-playing marker was the only
            // thing that had actually moved on.
            model.uiState.value.title shouldBe "Pyjamasques · S1:E8 · Episode 8"
            model.uiState.value.durationMs shouldBe nextRunTimeTicks.ticksToMillis()
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
            // player that no longer exists.
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
            // The fixture's group state is Paused (this member's own `phase` above is a different
            // thing — see SyncPlayState.InGroup.groupState's doc).
            syncPlay.groupPlaying shouldBe false
        }

    @Test
    fun `the play-pause icon in a group follows the group's state, not the local player`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup(groupState = SyncPlayGroupState.Playing)
            val model = viewModel()
            advanceUntilIdle()

            // The local player has not caught up to the group's Playing command, but the icon must
            // not show "paused" — that invites the second, wrong tap the bug report describes.
            playerHandle.emit(PlayerEvent.IsPlayingChanged(isPlaying = false))
            advanceUntilIdle()

            model.uiState.value.isPlaying shouldBe false
            model.uiState.value.syncPlay.groupPlaying shouldBe true
            model.uiState.value.showsPlaying shouldBe true
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

    // ---- the end of an item, in a group -------------------------------------------------------------

    @Test
    fun `an item ending with more group queue behind it does not close the screen`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup(queue = queue(entryCount = 2))
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            // `hasEnded` is what `PlayerScreen` turns into `onBack()`; the group's next item is
            // loaded into *this* session, so popping here would close the player it lands in.
            model.uiState.value.hasEnded shouldBe false
            model.uiState.value.isPlaying shouldBe false
            // The outgoing item is still reported, exactly as it is solo — on the detached scope,
            // which the solo path also uses since its auto-close cancels viewModelScope.
            coVerify { reporter.reportStopDetached(any(), match { it.hasEnded }) }
        }

    @Test
    fun `an item ending on the group's last item closes the screen as it does solo`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup(queue = queue())
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe true
        }

    @Test
    fun `a repeating group queue keeps the screen open on its only item`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup(queue = queue(repeat = SyncPlayRepeatMode.All))
            val model = viewModel()
            advanceUntilIdle()

            playerHandle.emit(PlayerEvent.Ended)
            advanceUntilIdle()

            model.uiState.value.hasEnded shouldBe false
        }

    // ---- fixture ------------------------------------------------------------------------------------

    private fun inGroup(
        phase: SyncPlayPhase = SyncPlayPhase.Paused,
        queue: SyncPlayGroupQueue? = queue(),
        groupState: SyncPlayGroupState = SyncPlayGroupState.Paused,
    ) = SyncPlayState.InGroup(group(), queue, groupState, phase)

    private fun queue(
        shuffle: SyncPlayShuffleMode = SyncPlayShuffleMode.Sorted,
        repeat: SyncPlayRepeatMode = SyncPlayRepeatMode.None,
        entryCount: Int = 1,
    ) = SyncPlayGroupQueue(
        entries =
            List(entryCount) { index ->
                if (index == 0) {
                    SyncPlayQueueEntry(PlayerFixtures.ITEM_ID, playlistItemId)
                } else {
                    SyncPlayQueueEntry(otherItemId, UUID.nameUUIDFromBytes(byteArrayOf(index.toByte())))
                }
            },
        playingItemIndex = 0,
        startPositionTicks = 0L,
        isPlaying = false,
        shuffleMode = shuffle,
        repeatMode = repeat,
        reason = SyncPlayQueueUpdateReason.NewPlaylist,
        lastUpdate = Instant.parse("2026-07-30T18:00:00Z"),
    )
}
