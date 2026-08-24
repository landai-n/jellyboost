package dev.jellyboost.player.syncplay

import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [decideReconcile] — the four outcomes and the adoption-only-before-first-load
 * rule. The behaviour around each outcome (handshakes, launch requests, skip guards) stays
 * pinned through the controller in [SyncPlayControllerTest].
 */
internal class SyncPlayReconcilePlanTest {
    private val itemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val otherItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    private val playlistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val otherPlaylistItemId = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val entry = SyncPlayQueueEntry(itemId, playlistItemId)

    @Test
    fun `a queue with nothing playing clears the loaded slot`() {
        val action =
            decideReconcile(
                queue = queueOf(entries = emptyList(), playingIndex = 0),
                loadedPlaylistItemId = playlistItemId,
                hostAttached = true,
                snapshot = null,
            )

        action shouldBe ReconcileAction.None(playingEntry = null)
    }

    @Test
    fun `the slot already open is nothing to do — every reorder lands here`() {
        val action =
            decideReconcile(
                queue = queueOf(entries = listOf(entry), playingIndex = 0),
                loadedPlaylistItemId = playlistItemId,
                hostAttached = true,
                snapshot = null,
            )

        action shouldBe ReconcileAction.None(entry)
    }

    @Test
    fun `no host attached asks the app to open a player`() {
        val action =
            decideReconcile(
                queue = queueOf(entries = listOf(entry), playingIndex = 0),
                loadedPlaylistItemId = null,
                hostAttached = false,
                snapshot = null,
            )

        action shouldBe ReconcileAction.RequestLaunch(entry)
    }

    @Test
    fun `an item the host already holds is adopted, before anything was loaded`() {
        val snapshot = SyncPlayHostSnapshot(itemId, positionTicks = 300L, isPlaying = true)

        val action =
            decideReconcile(
                queue = queueOf(entries = listOf(entry), playingIndex = 0),
                loadedPlaylistItemId = null,
                hostAttached = true,
                snapshot = snapshot,
            )

        action shouldBe ReconcileAction.Adopt(entry, snapshot)
    }

    @Test
    fun `adoption is only offered before the first load — the same item on a new slot reloads`() {
        // The same episode queued twice: the group jumps to the second slot while the first is
        // open. The host's snapshot matches by *item*, but the slot is the identity, so it must
        // start again rather than carry on where the first copy had got to.
        val secondSlot = SyncPlayQueueEntry(itemId, otherPlaylistItemId)
        val snapshot = SyncPlayHostSnapshot(itemId, positionTicks = 300L, isPlaying = true)

        val action =
            decideReconcile(
                queue = queueOf(entries = listOf(entry, secondSlot), playingIndex = 1),
                loadedPlaylistItemId = playlistItemId,
                hostAttached = true,
                snapshot = snapshot,
            )

        action shouldBe ReconcileAction.Load(secondSlot)
    }

    @Test
    fun `a host holding a different item loads the group's one`() {
        val snapshot = SyncPlayHostSnapshot(otherItemId, positionTicks = 0L, isPlaying = false)

        val action =
            decideReconcile(
                queue = queueOf(entries = listOf(entry), playingIndex = 0),
                loadedPlaylistItemId = null,
                hostAttached = true,
                snapshot = snapshot,
            )

        action shouldBe ReconcileAction.Load(entry)
    }

    @Test
    fun `a host with nothing loaded yet loads rather than adopts`() {
        val snapshot = SyncPlayHostSnapshot(itemId = null, positionTicks = 0L, isPlaying = false)

        val action =
            decideReconcile(
                queue = queueOf(entries = listOf(entry), playingIndex = 0),
                loadedPlaylistItemId = null,
                hostAttached = true,
                snapshot = snapshot,
            )

        action shouldBe ReconcileAction.Load(entry)
    }

    private fun queueOf(
        entries: List<SyncPlayQueueEntry>,
        playingIndex: Int,
    ) = SyncPlayGroupQueue(
        entries = entries,
        playingItemIndex = playingIndex,
        startPositionTicks = 0L,
        isPlaying = false,
        shuffleMode = SyncPlayShuffleMode.Sorted,
        repeatMode = SyncPlayRepeatMode.None,
        reason = SyncPlayQueueUpdateReason.NewPlaylist,
        lastUpdate = Instant.parse("2026-07-30T18:41:00Z"),
    )
}
