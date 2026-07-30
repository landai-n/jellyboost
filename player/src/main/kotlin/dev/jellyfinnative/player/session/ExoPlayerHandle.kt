package dev.jellyfinnative.player.session

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.model.PlaybackMediaSource
import dev.jellyfinnative.player.model.PlaybackSnapshot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [PlayerHandle]: one process-wide [ExoPlayer], shared with [PlaybackService].
 *
 * Two configuration choices carry the milestone:
 *
 * - `EXTENSION_RENDERER_MODE_PREFER` puts the bundled `org.jellyfin.media3:media3-ffmpeg-decoder`
 *   ahead of the platform decoders, which is what lets AC3/DTS/TrueHD audio direct-play instead of
 *   dragging the whole file through a transcode. It pairs with the forced audio codecs in
 *   `DeviceProfileBuilder`: advertising codecs we then fail to decode would be worse than not
 *   advertising them.
 * - `setEnableDecoderFallback(true)` lets ExoPlayer try the next decoder when one fails to
 *   *initialise*. It does nothing for a decoder that fails mid-stream, which is exactly the gap
 *   `DecoderFallbackHandler` fills.
 *
 * The player instance is deliberately singleton and shared rather than reached through a
 * `MediaController`: see DECISIONS.md, 2026-07-28, "player UI drives the shared ExoPlayer".
 */
@Singleton
@UnstableApi
internal class ExoPlayerHandle
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataSourceFactory: DataSource.Factory,
    ) : PlayerHandle {
        private val _events =
            MutableSharedFlow<PlayerEvent>(
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        override val events: Flow<PlayerEvent> = _events.asSharedFlow()

        private var exoPlayer: ExoPlayer? = null

        /** Non-null once playback has been prepared; the video surface binds to it. */
        override val player: Player? get() = exoPlayer

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

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    _events.tryEmit(PlayerEvent.VideoSizeChanged(videoSize.width, videoSize.height))
                }

                override fun onPlayerError(error: PlaybackException) {
                    Timber.w(error, "Playback error %d", error.errorCode)
                    _events.tryEmit(PlayerEvent.Error(error.errorCode, error.message))
                }
            }

        /**
         * The shared player, created on first use.
         *
         * Created lazily rather than in the constructor because `ExoPlayer.Builder` binds itself
         * to the calling thread's looper, and Hilt may well construct this object off the main
         * thread. Every caller — the ViewModel and [PlaybackService] — is on the main thread.
         */
        fun requirePlayer(): ExoPlayer = exoPlayer ?: buildPlayer().also { exoPlayer = it }

        private fun buildPlayer(): ExoPlayer {
            val renderersFactory =
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)

            return ExoPlayer
                .Builder(context, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setUsePlatformDiagnostics(false)
                .build()
                .apply {
                    addListener(listener)
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        // handleAudioFocus =
                        true,
                    )
                    // Headphones pulled out, or a Bluetooth speaker walked away from: pause rather
                    // than blare the film out of the device speaker (M9).
                    setHandleAudioBecomingNoisy(true)
                    // Playback continues once the screen is off or the app is backgrounded, and
                    // both a partial wake lock and a Wi-Fi lock are needed for a *streamed* item to
                    // survive that — without them the device sleeps mid-buffer.
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
        }

        override fun prepare(
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            startPlaybackService()
            with(requirePlayer()) {
                setMediaItem(spec.toMediaItem(), startPositionMs.coerceAtLeast(0L))
                this.playWhenReady = playWhenReady
                prepare()
            }
        }

        override fun play() {
            requirePlayer().play()
        }

        override fun pause() {
            requirePlayer().pause()
        }

        override fun seekTo(positionMs: Long) {
            requirePlayer().seekTo(positionMs.coerceAtLeast(0L))
        }

        override fun snapshot(): PlaybackSnapshot {
            val current = exoPlayer ?: return PlaybackSnapshot()
            return PlaybackSnapshot(
                positionMs = current.currentPosition.coerceAtLeast(0L),
                durationMs = current.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                bufferedMs = current.bufferedPosition.coerceAtLeast(0L),
                isPlaying = current.isPlaying,
                hasEnded = current.playbackState == Player.STATE_ENDED,
            )
        }

        override fun selectAudioTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int,
        ): Boolean = TrackSelectionController(requirePlayer()).selectAudio(source, jellyfinIndex)

        override fun selectSubtitleTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int?,
        ): Boolean = TrackSelectionController(requirePlayer()).selectSubtitle(source, jellyfinIndex)

        override fun setPlaybackSpeed(speed: Float) {
            requirePlayer().setPlaybackSpeed(speed)
        }

        override fun stop() {
            exoPlayer?.run {
                stop()
                clearMediaItems()
            }
            stopPlaybackService()
        }

        /**
         * Brings up the media session service so playback survives the screen being left and gets
         * a notification with transport controls.
         *
         * `startService` rather than a bind: Media3 only promotes a *started* service to the
         * foreground, and that promotion is what keeps playback alive once the app is backgrounded
         * ([PlaybackService], M9).
         *
         * It is **not** guaranteed to be called from a foreground activity. [prepare] runs when the
         * resolve completes, and a resolve can take seconds on a slow server, so a user who presses
         * Home while the spinner is up lands here with the app already in the background. Past the
         * grace window API 26+ answers a background `startService` with an `IllegalStateException`,
         * and an uncaught throw on `Main.immediate` is process death — a crash traded for a feature
         * the user is not even using at that moment. `PlaybackService`'s
         * `onForegroundServiceStartNotAllowedException` does not cover this: that hook is Media3
         * declining to *promote* an already-started service, which never happens if the service
         * could not be started at all.
         *
         * So the start is best effort. Losing it costs the background-continue bonus and the
         * notification; playback itself runs off the shared [ExoPlayer] and is unaffected. The next
         * [prepare] — a quality change, a track switch, a fallback retry, or simply the next item —
         * tries again from wherever the app is by then.
         */
        private fun startPlaybackService() {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
                .onFailure { Timber.w(it, "Could not start the playback service; continuing without it") }
        }

        private fun stopPlaybackService() {
            context.stopService(Intent(context, PlaybackService::class.java))
        }

        private companion object {
            const val EVENT_BUFFER = 16
        }
    }
