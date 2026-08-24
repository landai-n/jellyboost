package dev.jellyboost.player.session

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber

/**
 * Shared by both [PlayerHandle] implementations so a new event cannot be added to one and forgotten
 * in the other; differences between them belong here as arguments.
 *
 * @param forwardVideoSize `false` on Cast, permanently: `CastPlayer` reports `VideoSize.UNKNOWN`
 *   throughout, so forwarding it would overwrite a good aspect ratio with nothing.
 */
internal fun playerEventListener(
    emit: (PlayerEvent) -> Unit,
    forwardVideoSize: Boolean = true,
    errorLogPrefix: String = "Playback error",
): Player.Listener =
    object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> emit(PlayerEvent.Ready)
                Player.STATE_ENDED -> emit(PlayerEvent.Ended)
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emit(PlayerEvent.IsPlayingChanged(isPlaying))
        }

        override fun onTracksChanged(tracks: Tracks) {
            emit(PlayerEvent.TracksChanged)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (!forwardVideoSize) return
            emit(PlayerEvent.VideoSizeChanged(videoSize.width, videoSize.height))
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.w(error, "%s %d", errorLogPrefix, error.errorCode)
            emit(PlayerEvent.Error(error.errorCode, error.message))
        }
    }

/**
 * `DROP_OLDEST` because a listener callback cannot suspend: `tryEmit` must succeed synchronously on
 * the player's thread, and a stale buffered event is worth less than the one arriving now.
 */
internal fun playerEventFlow(): MutableSharedFlow<PlayerEvent> =
    MutableSharedFlow(
        extraBufferCapacity = PLAYER_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

/** Room for a burst of callbacks while the ViewModel is between collections. */
private const val PLAYER_EVENT_BUFFER = 16
