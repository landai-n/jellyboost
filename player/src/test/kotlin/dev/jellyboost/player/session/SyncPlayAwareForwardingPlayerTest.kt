package dev.jellyboost.player.session

import androidx.media3.common.Player
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayPhase
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.group
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test

/**
 * What the media session is allowed to do to the player while this member is in a SyncPlay group.
 *
 * The notification, a headset button and a Bluetooth remote all dispatch through the session, and
 * before [SyncPlayAwareForwardingPlayer] they dispatched onto the shared `ExoPlayer` itself — so a
 * pause from the notification paused this member and nobody else, which is exactly the silent drift
 * the M11 rule exists to prevent ("in-group transport never acts locally — API calls only").
 *
 * Every in-group test therefore asserts both halves, as `PlayerSyncPlayTest` does for the in-app
 * controls: the request that reached the coordinator, *and* the delegate having been left alone.
 * Asserting only the first would pass just as happily for a wrapper that seeks locally **and** tells
 * the group, which is the failure mode being fixed — two clients drifting apart while both believe
 * they are in step.
 */
internal class SyncPlayAwareForwardingPlayerTest {
    private val controllerState = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)

    private val controller =
        mockk<SyncPlayController>(relaxed = true) {
            every { state } returns controllerState
        }

    private val delegate =
        mockk<Player>(relaxed = true) {
            every { currentPosition } returns POSITION_MS
            every { currentMediaItemIndex } returns 0
            every { seekBackIncrement } returns SEEK_BACK_MS
            every { seekForwardIncrement } returns SEEK_FORWARD_MS
        }

    private val player = SyncPlayAwareForwardingPlayer(delegate, controller)

    private fun joinGroup() {
        controllerState.value =
            SyncPlayState.InGroup(
                group = group(),
                queue = null,
                groupState = SyncPlayGroupState.Paused,
                phase = SyncPlayPhase.Paused,
            )
    }

    /** The claim every in-group test makes: reading the delegate is fine, moving it is not. */
    private fun delegateNeverMoved() {
        verify(exactly = 0) {
            delegate.play()
            delegate.pause()
            delegate.playWhenReady = any()
            delegate.seekTo(any())
            delegate.seekTo(any(), any())
            delegate.seekToDefaultPosition()
            delegate.seekToDefaultPosition(any())
            delegate.seekBack()
            delegate.seekForward()
            delegate.seekToNext()
            delegate.seekToNextMediaItem()
            delegate.seekToPrevious()
            delegate.seekToPreviousMediaItem()
        }
    }

    // ---- in a group: transport is a request, never a local move ----------------------------------

    @Test
    fun `a notification play in a group asks the server to unpause`() {
        joinGroup()

        player.play()

        verify(exactly = 1) { controller.requestUnpause() }
        delegateNeverMoved()
    }

    @Test
    fun `a notification pause in a group asks the server to pause`() {
        joinGroup()

        player.pause()

        verify(exactly = 1) { controller.requestPause() }
        delegateNeverMoved()
    }

    @Test
    fun `setPlayWhenReady in a group becomes the matching request`() {
        joinGroup()

        player.playWhenReady = true
        player.playWhenReady = false

        verify(exactly = 1) { controller.requestUnpause() }
        verify(exactly = 1) { controller.requestPause() }
        delegateNeverMoved()
    }

    @Test
    fun `a seek in a group asks the server to seek, in ticks`() {
        joinGroup()

        player.seekTo(TARGET_MS)

        verify(exactly = 1) { controller.requestSeek(TARGET_MS.millisToTicks()) }
        delegateNeverMoved()
    }

    @Test
    fun `a seek inside the item already playing is an ordinary seek request`() {
        joinGroup()

        player.seekTo(0, TARGET_MS)

        verify(exactly = 1) { controller.requestSeek(TARGET_MS.millisToTicks()) }
        delegateNeverMoved()
    }

    @Test
    fun `a seek to another window in a group is dropped rather than applied locally`() {
        joinGroup()

        player.seekTo(1, TARGET_MS)

        verify(exactly = 0) { controller.requestSeek(any()) }
        delegateNeverMoved()
    }

    @Test
    fun `rewind in a group asks for the position the notification offered`() {
        joinGroup()

        player.seekBack()

        verify(exactly = 1) { controller.requestSeek((POSITION_MS - SEEK_BACK_MS).millisToTicks()) }
        delegateNeverMoved()
    }

    @Test
    fun `fast-forward in a group asks for the position the notification offered`() {
        joinGroup()

        player.seekForward()

        verify(exactly = 1) { controller.requestSeek((POSITION_MS + SEEK_FORWARD_MS).millisToTicks()) }
        delegateNeverMoved()
    }

    @Test
    fun `a rewind past the start of the item is clamped, not sent negative`() {
        every { delegate.currentPosition } returns SEEK_BACK_MS / 2
        joinGroup()

        player.seekBack()

        verify(exactly = 1) { controller.requestSeek(0L) }
        delegateNeverMoved()
    }

    @Test
    fun `skipping in a group asks the group to move, both spellings`() {
        joinGroup()

        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()

        verify(exactly = 2) { controller.requestNext() }
        verify(exactly = 2) { controller.requestPrevious() }
        delegateNeverMoved()
    }

    @Test
    fun `seeking to the default position in a group asks the group for the start of the item`() {
        joinGroup()

        player.seekToDefaultPosition()

        verify(exactly = 1) { controller.requestSeek(0L) }
        delegateNeverMoved()
    }

    // ---- solo: the wrapper is not there ----------------------------------------------------------

    @Test
    fun `solo transport reaches the player and asks the server nothing`() {
        player.play()
        player.pause()
        player.playWhenReady = true
        player.seekTo(TARGET_MS)
        player.seekTo(0, TARGET_MS)
        player.seekToDefaultPosition()
        player.seekBack()
        player.seekForward()
        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()

        verify(exactly = 1) { delegate.play() }
        verify(exactly = 1) { delegate.pause() }
        verify(exactly = 1) { delegate.playWhenReady = true }
        verify(exactly = 1) { delegate.seekTo(TARGET_MS) }
        verify(exactly = 1) { delegate.seekTo(0, TARGET_MS) }
        verify(exactly = 1) { delegate.seekToDefaultPosition() }
        verify(exactly = 1) { delegate.seekBack() }
        verify(exactly = 1) { delegate.seekForward() }
        verify(exactly = 1) { delegate.seekToNext() }
        verify(exactly = 1) { delegate.seekToNextMediaItem() }
        verify(exactly = 1) { delegate.seekToPrevious() }
        verify(exactly = 1) { delegate.seekToPreviousMediaItem() }
        verify(exactly = 0) {
            controller.requestPause()
            controller.requestUnpause()
            controller.requestSeek(any())
            controller.requestNext()
            controller.requestPrevious()
        }
    }

    /**
     * A membership that ends between two notification taps.
     *
     * The state flow is read per call rather than collected once, and this is why: a group can be
     * left while the app is nowhere near the foreground, and the next tap on the notification has to
     * move the player again.
     */
    @Test
    fun `leaving the group hands the notification its player back`() {
        joinGroup()
        player.pause()
        controllerState.value = SyncPlayState.Idle

        player.pause()

        verify(exactly = 1) { controller.requestPause() }
        verify(exactly = 1) { delegate.pause() }
    }

    private companion object {
        const val POSITION_MS = 120_000L
        const val TARGET_MS = 42_000L
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
    }
}
