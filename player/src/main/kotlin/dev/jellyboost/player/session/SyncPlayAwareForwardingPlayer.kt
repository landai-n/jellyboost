package dev.jellyboost.player.session

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayState
import timber.log.Timber

/**
 * The player the media session is given, so that the notification obeys the group.
 *
 * In a SyncPlay group **nothing this client does moves this client's player**: transport is a
 * request to the server, and the player moves when the server rebroadcasts the command to everyone.
 * `PlayerViewModel` enforces that for the in-app controls — but the media notification, a headset
 * button and a steering-wheel control do not go through the ViewModel. They go through
 * [androidx.media3.session.MediaSession], which dispatches straight onto the player it was built
 * with. Built on the shared `ExoPlayer` itself, a pause from the notification would pause *this*
 * member and nobody else: the same silent drift the rule exists to prevent, reached by the one
 * surface that stays reachable when the app is not on screen.
 *
 * So the session is built on this wrapper instead. Outside a group every call is the delegate's own,
 * unchanged; inside one, the calls that would move the player become the matching request and the
 * delegate is not touched at all. Everything else — position, duration, tracks, volume, listeners —
 * forwards either way, because the session still has to *describe* playback accurately.
 *
 * ### What is routed
 * | media session call | in a group |
 * |---|---|
 * | `play`, `setPlayWhenReady(true)` | [SyncPlayController.requestUnpause] |
 * | `pause`, `setPlayWhenReady(false)` | [SyncPlayController.requestPause] |
 * | `seekTo`, `seekBack`, `seekForward`, `seekToDefaultPosition` | [SyncPlayController.requestSeek] |
 * | `seekToNext`, `seekToNextMediaItem` | [SyncPlayController.requestNext] |
 * | `seekToPrevious`, `seekToPreviousMediaItem` | [SyncPlayController.requestPrevious] |
 * | `stop` | [SyncPlayController.requestPause] |
 * | `setPlaybackSpeed` | dropped |
 *
 * A `stop` (a headset's MEDIA_STOP, a car control) means "halt playback"; in a group that is a
 * pause request — halting only this member is precisely the silent drift this wrapper exists to
 * prevent, and stopping the whole group's queue is more than one button press should do. A
 * playback-speed change is dropped for the same reason `PlayerViewModel` refuses it in-app: the
 * group's timeline runs at 1× and a member at any other rate drifts by construction.
 *
 * A seek to a *different* media item is the one case with nowhere to go: the group's queue is the
 * server's, not this player's timeline, so an index from the session means nothing to it. It is
 * dropped rather than translated — moving the local player to some other window is precisely what
 * must not happen, and the queue is navigated through the requests above.
 *
 * @param controller consulted per call rather than collected: membership can change between two
 *   notification taps, and the state flow's current value is the answer at the moment of the tap.
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

    /**
     * The increments are read from the delegate, not re-derived: they are what the notification's
     * own rewind button is labelled with, and the group must be asked for the position the user was
     * shown.
     */
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

    /** Asks the group to move; the clamp keeps a rewind past the start off the wire as a negative. */
    private fun requestSeek(positionMs: Long) {
        controller.requestSeek(positionMs.coerceAtLeast(0L).millisToTicks())
    }

    private fun dropWindowSeek(mediaItemIndex: Int) {
        Timber.d("Ignoring a media-session seek to window %d while in a SyncPlay group", mediaItemIndex)
    }
}
