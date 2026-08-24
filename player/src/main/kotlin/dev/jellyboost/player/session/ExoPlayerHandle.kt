package dev.jellyboost.player.session

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [PlayerHandle]: one process-wide [ExoPlayer], shared with [PlaybackService].
 *
 * - `EXTENSION_RENDERER_MODE_PREFER` must stay paired with the forced audio codecs in
 *   `DeviceProfileBuilder`: it is what makes the advertised AC3/DTS/TrueHD actually decodable.
 * - `setEnableDecoderFallback(true)` only covers a decoder that fails to *initialise*; the
 *   mid-stream case is `DecoderFallbackHandler`'s.
 *
 * The player UI drives this instance directly rather than through a `MediaController`.
 */
@Singleton
@UnstableApi
internal class ExoPlayerHandle
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dataSourceFactory: DataSource.Factory,
        private val serviceState: PlaybackServiceState,
    ) : PlayerHandle {
        private val _events = playerEventFlow()

        override val events: Flow<PlayerEvent> = _events.asSharedFlow()

        private var exoPlayer: ExoPlayer? = null

        /**
         * Hand-built sources must go through the *same* factory the player uses, or they open their
         * URIs through Media3's default data source: no auth headers, no cache, no timeouts.
         */
        private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        override val player: Player? get() = exoPlayer

        private val listener = playerEventListener(emit = { _events.tryEmit(it) })

        /**
         * Built lazily and on the main thread: `ExoPlayer.Builder` binds itself to the calling
         * thread's looper, and Hilt may construct this object off it.
         */
        fun requirePlayer(): ExoPlayer = exoPlayer ?: buildPlayer().also { exoPlayer = it }

        private fun buildPlayer(): ExoPlayer {
            val renderersFactory =
                DefaultRenderersFactory(context)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                    .setEnableDecoderFallback(true)

            return ExoPlayer
                .Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
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
                    setHandleAudioBecomingNoisy(true)
                    // NETWORK, not LOCAL: a streamed item needs the Wi-Fi lock too, or the device
                    // sleeps mid-buffer once the screen goes off.
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
        }

        /**
         * **The child order is a contract.** Child 0 is always the main source, subtitles included;
         * child `i + 1` is `spec.audioSidecars[i]`. `MergingMediaPeriod` re-ids every child's track
         * groups as `"<childIndex>:<originalId>"`, and that prefix is the only thing tying an
         * ExoPlayer audio group back to the Jellyfin stream behind it — see
         * `TrackSelectionController.selectAudio`, which reads it, and `LocalPlaybackResolver`,
         * which fixes the order.
         *
         * `clipDurations` is required: a sidecar a few milliseconds longer than the film is an
         * `IllegalMergeException` otherwise. `adjustPeriodTimeOffsets` is off — every child starts
         * at zero.
         */
        override fun prepare(
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            startPlaybackService()
            with(requirePlayer()) {
                // This player outlives the item: without the reset, the previous item's overrides
                // — including its "subtitles off" — are this one's starting point.
                TrackSelectionController(this).reset()
                val position = startPositionMs.coerceAtLeast(0L)
                if (spec.audioSidecars.isEmpty()) {
                    setMediaItem(spec.toMediaItem(), position)
                } else {
                    setMediaSource(spec.toMergedSource(), position)
                }
                this.playWhenReady = playWhenReady
                prepare()
            }
        }

        /** The main source and its audio sidecars, in the child order [prepare] documents. */
        @Suppress("SpreadOperator")
        private fun PlaybackMediaItemSpec.toMergedSource(): MergingMediaSource {
            val children =
                buildList {
                    add(mediaSourceFactory.createMediaSource(toMediaItem()))
                    audioSidecars.mapTo(this) { mediaSourceFactory.createMediaSource(it.toMediaItem()) }
                }
            Timber.d("Merging %d audio sidecars into %s", audioSidecars.size, mediaId)
            return MergingMediaSource(
                // adjustPeriodTimeOffsets =
                false,
                // clipDurations =
                true,
                *children.toTypedArray(),
            )
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
         * Idempotent: the field is cleared first, so the second of `PlayerViewModel.releaseSession`
         * and [PlaybackService.onDestroy] finds nothing to do. The listener must be removed
         * explicitly — it is a strong reference from a `@Singleton` to a `MutableSharedFlow` that
         * outlives every session. Deliberately does *not* stop the playback service; [stop] owns
         * that.
         *
         * Deferred while [PlaybackService] is alive: its `MediaSession` is built around this
         * player and Media3 requires the session to be released first, so the service's `onDestroy`
         * (which clears the running flag before calling back in) does the release instead.
         */
        override fun release() {
            if (serviceState.running.value) {
                Timber.d("Deferring the player release to the playback service's own teardown")
                return
            }
            val player = exoPlayer ?: return
            exoPlayer = null
            player.removeListener(listener)
            player.release()
            Timber.d("Released the shared ExoPlayer")
        }

        /**
         * `startService`, not a bind: Media3 only promotes a *started* service to the foreground.
         *
         * Best effort, and the catch must stay: this can run with the app already backgrounded (a
         * slow resolve finishing after Home), where API 26+ answers with an `IllegalStateException`
         * that would be process death on `Main.immediate`. `PlaybackService`'s
         * `onForegroundServiceStartNotAllowedException` does not cover it — that hook is about
         * promoting an already-started service. Losing the start costs only the notification and
         * background continuation; the next [prepare] tries again.
         */
        internal fun startPlaybackService() {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
                .onFailure { Timber.w(it, "Could not start the playback service; continuing without it") }
        }

        internal fun stopPlaybackService() {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
