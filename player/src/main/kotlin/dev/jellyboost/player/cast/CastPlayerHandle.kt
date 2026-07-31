package dev.jellyboost.player.cast

import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.PlayerHandle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [PlayerHandle] that drives a Cast receiver, over media3-cast's `CastPlayer`.
 *
 * `CastPlayer` is an `androidx.media3.common.Player`, so the seam the ViewModel already talks to
 * fits it exactly — including the contract that carries the milestone: **a track selection that
 * returns `false` makes the caller re-negotiate with the server**. That is not a workaround here,
 * it is the correct behaviour twice over. A receiver has whatever single audio track the server
 * encoded for it, and a subtitle it cannot render has to be burned in — both of which only the
 * server can do, and both of which `PlayerViewModel` already knows how to ask for.
 *
 * ### What this handle deliberately does not do
 * - **No video surface.** [player] is always `null`. There is nothing to render on the phone while
 *   a television is rendering it, and handing `PlayerView` a `CastPlayer` would draw a black
 *   rectangle over the poster the screen shows instead (docs/notes/chromecast-m12-plan.md,
 *   decision 10). It is the one place where returning `null` from that property is not a
 *   "before the first prepare" state but the permanent, correct answer.
 * - **No playback service.** [ExoPlayerHandle][dev.jellyboost.player.session.ExoPlayerHandle]
 *   starts `PlaybackService` so a backgrounded session keeps its notification. While casting the
 *   local player is stopped, so that notification would be for a player that is not playing; the
 *   Cast framework publishes its own media session and notification (`CastMediaOptions`'
 *   `setMediaSessionEnabled` defaults to `true`, and `JellyboostCastOptionsProvider` configures the
 *   notification), and one of the two is exactly the right number.
 *
 * The `CastPlayer` is built lazily and only when a `CastContext` exists. Null-safety costs nothing
 * here: there is no way to reach this handle without a live cast session, because only
 * [CastSessionCoordinator] routes to it and only a started session makes it do so.
 */
@Singleton
@UnstableApi
internal class CastPlayerHandle
    @Inject
    constructor(
        private val availability: CastAvailability,
        private val specMapper: CastSpecMapper,
        private val converter: CastMediaItemConverter,
    ) : PlayerHandle {
        private val _events =
            MutableSharedFlow<PlayerEvent>(
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        override val events: Flow<PlayerEvent> = _events.asSharedFlow()

        private var castPlayer: CastPlayer? = null

        /** The load currently on the receiver; its tracks are what a subtitle selection matches. */
        private var loaded: CastMediaSpec? = null

        /** Always `null`, and permanently so — see the class docs. */
        override val player: Player? = null

        /**
         * The same events the local handle publishes, minus one.
         *
         * `VideoSizeChanged` has no meaning while casting: it exists for picture-in-picture, which
         * needs the *decoded* size, and the decoder is in the television. `CastPlayer` reports
         * `VideoSize.UNKNOWN` throughout, so forwarding it would only overwrite a good aspect ratio
         * with nothing.
         */
        private val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> _events.tryEmit(PlayerEvent.Ready)
                        Player.STATE_ENDED -> _events.tryEmit(PlayerEvent.Ended)
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _events.tryEmit(PlayerEvent.IsPlayingChanged(isPlaying))
                }

                override fun onTracksChanged(tracks: Tracks) {
                    _events.tryEmit(PlayerEvent.TracksChanged)
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Cast playback error %d", error.errorCode)
                    _events.tryEmit(PlayerEvent.Error(error.errorCode, error.message))
                }
            }

        /**
         * The cast player, created on first use, or `null` when there is no Cast stack at all.
         *
         * Lazy for the same reason the local player is — Hilt may construct this off the main
         * thread, and `CastPlayer` binds to the calling thread's looper — and additionally because
         * *constructing* it is the first thing in this app that touches a `com.google.android.gms`
         * class. On a device without Play services nothing ever gets this far.
         *
         * The two-argument constructor is media3-cast 1.9.0's, and it fixes the seek increments at
         * 5 s back / 15 s forward. That only matters for `seekBack`/`seekForward`, which nothing in
         * this app calls — the controls seek to absolute positions.
         */
        private fun requirePlayer(): CastPlayer? {
            castPlayer?.let { return it }
            val context = availability.castContext
            if (context == null) {
                Timber.w("No CastContext; the cast player cannot be built")
                return null
            }
            return CastPlayer(context, converter).also {
                it.addListener(listener)
                castPlayer = it
            }
        }

        /**
         * Loading without the negotiated source is not something this handle can do.
         *
         * The URL alone does not say what the receiver has to be told, and inventing the rest would
         * put a stream on the television that nothing could then switch tracks on. It surfaces as an
         * ordinary player error, which the ViewModel already knows how to end a session with.
         */
        override fun prepare(
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            Timber.w("Cast prepare without a resolved source for %s", spec.mediaId)
            _events.tryEmit(PlayerEvent.Error(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, NO_SOURCE))
        }

        override fun prepare(
            source: PlaybackMediaSource,
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            // Casting always streams: `PlaybackResolveRequest.castTarget` skips the copy on disk,
            // because a `file://` URI means nothing on the other side of the network.
            val remote = source as? RemotePlaybackMediaSource
            if (remote == null) {
                Timber.w("Refusing to cast a local source for %s", source.itemId)
                _events.tryEmit(PlayerEvent.Error(PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK, LOCAL_SOURCE))
                return
            }
            val player = requirePlayer()
            if (player == null) {
                _events.tryEmit(PlayerEvent.Error(PlaybackException.ERROR_CODE_REMOTE_ERROR, NO_RECEIVER))
                return
            }

            val castSpec = specMapper.map(spec, remote)
            loaded = castSpec
            Timber.d("Casting %s as %s", castSpec.mediaId, castSpec.contentType)
            with(player) {
                setMediaItem(castSpec.toMediaItem(), startPositionMs.coerceAtLeast(0L))
                this.playWhenReady = playWhenReady
                prepare()
            }
        }

        override fun play() {
            castPlayer?.play()
        }

        override fun pause() {
            castPlayer?.pause()
        }

        override fun seekTo(positionMs: Long) {
            castPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        }

        override fun snapshot(): PlaybackSnapshot {
            val current = castPlayer ?: return PlaybackSnapshot()
            return PlaybackSnapshot(
                positionMs = current.currentPosition.coerceAtLeast(0L),
                durationMs = current.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                bufferedMs = current.bufferedPosition.coerceAtLeast(0L),
                isPlaying = current.isPlaying,
                hasEnded = current.playbackState == Player.STATE_ENDED,
            )
        }

        /**
         * Never satisfied locally, by design.
         *
         * The stream on the receiver holds the one audio track the server was asked for — a
         * transcode encodes nothing else, and a direct play of an `mp4` the cast profile accepts has
         * at most the one the picker is already on. `false` sends the caller to the server with an
         * `audioStreamIndex`, which is the only thing that can actually change the language.
         */
        override fun selectAudioTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int,
        ): Boolean = false

        /**
         * Turns a side-loaded subtitle on, off, or refuses.
         *
         * Goes through `RemoteMediaClient.setActiveMediaTracks` rather than
         * `TrackSelectionParameters`: media3-cast 1.9.0's `RemoteCastPlayer.setTrackSelectionParameters`
         * is an empty method, so the Player-level API would silently do nothing.
         *
         * The track ids the receiver knows are the Jellyfin stream indices `CastSpecMapper` gave it,
         * which is why the index arrives here needing no translation. An index that is not among
         * them is one the server did not deliver as a file — an image subtitle, or a stream the
         * profile refused — and `false` is what sends the caller back to re-negotiate for a burned-in
         * one.
         */
        override fun selectSubtitleTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int?,
        ): Boolean {
            val client = remoteMediaClient() ?: return false
            if (jellyfinIndex == null) {
                client.setActiveMediaTracks(NO_TRACKS)
                return true
            }
            val sideLoaded = loaded?.tracks.orEmpty().any { it.id == jellyfinIndex }
            if (!sideLoaded) return false
            client.setActiveMediaTracks(longArrayOf(jellyfinIndex.toLong()))
            return true
        }

        /**
         * Applies a rate, if the receiver has one.
         *
         * Guarded rather than attempted: the available commands depend on what the receiver reports,
         * and calling an unavailable one on a `BasePlayer` is at best ignored and at worst logged as
         * an error on every session that never wanted a rate in the first place.
         */
        override fun setPlaybackSpeed(speed: Float) {
            val player = castPlayer ?: return
            if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                Timber.d("The receiver does not support playback speed; leaving it at 1×")
                return
            }
            player.setPlaybackSpeed(speed)
        }

        /**
         * The same question [setPlaybackSpeed] asks, asked before the user is offered the control.
         *
         * `false` with no player yet, which is the honest answer at that moment: a rate cannot be
         * applied to a receiver that has not been reached, and the screen re-reads this at every
         * open — by which time the session exists and the receiver has published its commands.
         */
        override val supportsPlaybackSpeed: Boolean
            get() = castPlayer?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true

        override fun stop() {
            castPlayer?.run {
                stop()
                clearMediaItems()
            }
            loaded = null
        }

        /**
         * Gives the cast player back, idempotently, and leaves the handle reusable.
         *
         * Same shape as the local handle's, for the same reasons: the field is cleared first so the
         * second caller finds nothing to do, the listener is removed explicitly because it is a
         * strong reference from a `@Singleton` to a flow that outlives every session, and
         * [requirePlayer] builds a fresh player for whatever comes next.
         *
         * Releasing does **not** end the cast session. The receiver keeps whatever it is playing and
         * the framework keeps the session; ending it is the user's business, through the route
         * button or the television.
         */
        override fun release() {
            val player = castPlayer ?: return
            castPlayer = null
            loaded = null
            player.removeListener(listener)
            player.release()
            Timber.d("Released the cast player")
        }

        private fun remoteMediaClient(): RemoteMediaClient? =
            runCatching {
                availability.castContext
                    ?.sessionManager
                    ?.currentCastSession
                    ?.remoteMediaClient
            }.getOrNull()

        private companion object {
            const val EVENT_BUFFER = 16
            val NO_TRACKS = longArrayOf()
            const val NO_SOURCE = "Casting needs the resolved source."
            const val LOCAL_SOURCE = "A downloaded file cannot be reached by a Cast receiver."
            const val NO_RECEIVER = "No Cast receiver is connected."
        }
    }
