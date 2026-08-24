package dev.jellyboost.player.session

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import timber.log.Timber

/**
 * The bridge from Media3's `Player.Listener` to this app's [PlayerEvent] vocabulary.
 *
 * Both [PlayerHandle] implementations — the local [ExoPlayerHandle] and the Cast
 * [CastPlayerHandle][dev.jellyboost.player.cast.CastPlayerHandle] — translate the same five
 * callbacks the same way. One function means a sixth event cannot be added to one and forgotten
 * in the other, and the one place they differ has to be spelled out as an argument rather than as
 * an absence.
 *
 * @param emit where the translated events go. A lambda rather than the flow itself so this function
 *   knows nothing about buffering policy, and so a test can pass a list.
 * @param forwardVideoSize `false` on a Cast receiver, and permanently so: `VideoSizeChanged` exists
 *   for picture-in-picture, which needs the *decoded* size, and the decoder is in the television.
 *   `CastPlayer` reports `VideoSize.UNKNOWN` throughout, so forwarding it would only overwrite a
 *   good aspect ratio with nothing.
 * @param errorLogPrefix distinguishes the two players in a bug report's logcat — "Playback error"
 *   against "Cast playback error".
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
 * The buffered flow both handles publish [PlayerEvent]s through.
 *
 * `DROP_OLDEST` because a listener callback cannot suspend: `tryEmit` has to succeed synchronously
 * on the player's thread, and a stale buffered event is worth less than the one arriving now.
 */
internal fun playerEventFlow(): MutableSharedFlow<PlayerEvent> =
    MutableSharedFlow(
        extraBufferCapacity = PLAYER_EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

/** Room for a burst of callbacks while the ViewModel is between collections. */
private const val PLAYER_EVENT_BUFFER = 16
