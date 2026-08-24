package dev.jellyboost.player.session

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayState
import timber.log.Timber

/**
 * The media session must be built on this, never on the shared `ExoPlayer`: in a group **nothing this client
 * does moves this client's player**, and a notification, headset or car control dispatches straight onto the
 * player it was built with, drifting this member away from the group.
 *
 * `stop` becomes a pause request (halting the whole group's queue is more than one button press should do);
 * a speed change and a seek to another window are dropped — the group's queue is the server's, and an index
 * from the session means nothing to this timeline.
 *
 * @param controller consulted per call, never collected: membership can change between two notification taps.
 */
@UnstableApi
internal class SyncPlayAwareForwardingPlayer(
    player: Player,
    private val controller: SyncPlayController,
) : ForwardingPlayer(player) {
    private val inGroup: Boolean get() = controller.state.value is SyncPlayState.InGroup

    override fun play() {
        if (inGroup) controller.requestUnpause() else super.play()
    }

    override fun pause() {
        if (inGroup) controller.requestPause() else super.pause()
    }

    override fun stop() {
        if (inGroup) controller.requestPause() else super.stop()
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (inGroup) {
            Timber.d("Ignoring a media-session playback-speed change while in a SyncPlay group")
            return
        }
        super.setPlaybackSpeed(speed)
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!inGroup) {
            super.setPlayWhenReady(playWhenReady)
            return
        }
        if (playWhenReady) controller.requestUnpause() else controller.requestPause()
    }

    override fun seekTo(positionMs: Long) {
        if (inGroup) requestSeek(positionMs) else super.seekTo(positionMs)
    }

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {
        if (!inGroup) {
            super.seekTo(mediaItemIndex, positionMs)
            return
        }
        if (mediaItemIndex == currentMediaItemIndex) requestSeek(positionMs) else dropWindowSeek(mediaItemIndex)
    }

    override fun seekToDefaultPosition() {
        if (inGroup) requestSeek(0L) else super.seekToDefaultPosition()
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        if (!inGroup) {
            super.seekToDefaultPosition(mediaItemIndex)
            return
        }
        if (mediaItemIndex == currentMediaItemIndex) requestSeek(0L) else dropWindowSeek(mediaItemIndex)
    }

    /** The increments come from the delegate, not re-derived: they label the notification's own button. */
    override fun seekBack() {
        if (inGroup) requestSeek(currentPosition - seekBackIncrement) else super.seekBack()
    }

    override fun seekForward() {
        if (inGroup) requestSeek(currentPosition + seekForwardIncrement) else super.seekForward()
    }

    override fun seekToNext() {
        if (inGroup) controller.requestNext() else super.seekToNext()
    }

    override fun seekToNextMediaItem() {
        if (inGroup) controller.requestNext() else super.seekToNextMediaItem()
    }

    override fun seekToPrevious() {
        if (inGroup) controller.requestPrevious() else super.seekToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        if (inGroup) controller.requestPrevious() else super.seekToPreviousMediaItem()
    }

    /** The clamp keeps a rewind past the start off the wire as a negative. */
    private fun requestSeek(positionMs: Long) {
        controller.requestSeek(positionMs.coerceAtLeast(0L).millisToTicks())
    }

    private fun dropWindowSeek(mediaItemIndex: Int) {
        Timber.d("Ignoring a media-session seek to window %d while in a SyncPlay group", mediaItemIndex)
    }
}
