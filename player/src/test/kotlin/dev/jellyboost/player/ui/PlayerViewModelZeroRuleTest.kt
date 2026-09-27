package dev.jellyboost.player.ui

import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import io.kotest.matchers.shouldBe
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The zero rule (`PlaybackSnapshot.contradicts`) exists for readings nobody can vouch for: a torn-down
 * receiver, or the idle local player right after routing fell back to it. A local player playing its
 * own session is always telling the truth, and it can be moved to 0 by things that never pass through
 * `PlayerViewModel.seekTo`: a SyncPlay command (the scheduler seeks the player directly) or a
 * media-session / notification / Bluetooth seek. Those zeros are real and must be reported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerViewModelZeroRuleTest : PlayerViewModelFixture() {
    @Test
    fun `a SyncPlay seek to the start is where the film is, and the stop says so`() =
        runTest(dispatcher) {
            syncPlayState.value = inGroup()
            playerHandle.snapshot = TEN_MINUTES_IN
            val model = viewModel()
            advanceUntilIdle()
            model.onTick(TEN_MINUTES_IN)

            // What `SyncPlayCommandScheduler` does with a group Seek to 0: the player, directly.
            playerHandle.seekTo(0L)
            model.onTick(playerHandle.snapshot)
            model.releaseSession()
            advanceUntilIdle()

            model.position.value.positionMs shouldBe 0L
            verify(exactly = 1) { reporter.reportStopDetached(any(), match { it.isValid && it.positionMs == 0L }) }
        }

    @Test
    fun `a media-session seek to the start while paused is where the film is, and the stop says so`() =
        runTest(dispatcher) {
            playerHandle.snapshot = TEN_MINUTES_IN.copy(isPlaying = false)
            val model = viewModel()
            advanceUntilIdle()
            model.onTick(playerHandle.snapshot)

            // A notification or Bluetooth seek reaches the player through the media session, not the screen.
            playerHandle.seekTo(0L)
            model.onTick(playerHandle.snapshot)
            model.releaseSession()
            advanceUntilIdle()

            model.position.value.positionMs shouldBe 0L
            verify(exactly = 1) { reporter.reportStopDetached(any(), match { it.isValid && it.positionMs == 0L }) }
        }

    private fun inGroup() =
        SyncPlayState.InGroup(
            group(),
            SyncPlayGroupQueue(
                entries = listOf(SyncPlayQueueEntry(PlayerFixtures.ITEM_ID, PLAYLIST_ITEM)),
                playingItemIndex = 0,
                startPositionTicks = 0L,
                isPlaying = false,
                shuffleMode = SyncPlayShuffleMode.Sorted,
                repeatMode = SyncPlayRepeatMode.None,
                reason = SyncPlayQueueUpdateReason.NewPlaylist,
                lastUpdate = Instant.parse("2026-07-30T18:00:00Z"),
            ),
            SyncPlayGroupState.Paused,
            SyncPlayPhase.Paused,
        )

    private companion object {
        val TEN_MINUTES_IN = PlaybackSnapshot(positionMs = 600_000L, isPlaying = true)
        val PLAYLIST_ITEM: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    }
}
