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
        private val serviceState: PlaybackServiceState,
    ) : PlayerHandle {
        private val _events = playerEventFlow()

        override val events: Flow<PlayerEvent> = _events.asSharedFlow()

        private var exoPlayer: ExoPlayer? = null

        /**
         * The factory the player is built with, kept so [prepare] can build sources by hand.
         *
         * Assembling a `MediaSource` outside the player has to go through the *same* factory the
         * player uses, or the hand-built one would open its URIs through Media3's default data
         * source instead of the app's — no auth headers, no cache, none of the timeouts
         * `PlayerModule` configures.
         */
        private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        /** Non-null once playback has been prepared; the video surface binds to it. */
        override val player: Player? get() = exoPlayer

        /** The shared Media3→[PlayerEvent] bridge; the local player forwards every event it has. */
        private val listener = playerEventListener(emit = { _events.tryEmit(it) })

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
                    // Headphones pulled out, or a Bluetooth speaker walked away from: pause rather
                    // than blare the film out of the device speaker (M9).
                    setHandleAudioBecomingNoisy(true)
                    // Playback continues once the screen is off or the app is backgrounded, and
                    // both a partial wake lock and a Wi-Fi lock are needed for a *streamed* item to
                    // survive that — without them the device sleeps mid-buffer.
                    setWakeMode(C.WAKE_MODE_NETWORK)
                }
        }

        /**
         * Opens [spec] and starts buffering it.
         *
         * A spec with no audio sidecars — everything streamed, and every original download — is a
         * plain `setMediaItem`, the item carrying its own side-loaded subtitles as
         * `SubtitleConfiguration`s. `MediaItem` has no audio analogue of that, which is why a
         * downloaded item whose extra languages live in their own files can only be assembled where
         * a `MediaSourceFactory` is in reach: here, rather than in the pure
         * `ExoMediaSourceFactory` (DECISIONS.md 2026-07-31, "Offline multi-track Phase 2"). Nothing
         * is *decided* here — the spec already fixes which files and in which order.
         *
         * **The child order is a contract.** Child 0 is always the main source, subtitles included;
         * child `i + 1` is `spec.audioSidecars[i]`. `MergingMediaPeriod` re-ids every child's track
         * groups as `"<childIndex>:<originalId>"`, and that prefix is the only thing tying an
         * ExoPlayer audio group back to the Jellyfin stream behind it — see
         * `TrackSelectionController.selectAudio`, which reads it, and
         * `LocalPlaybackResolver`, which fixes the order.
         *
         * `clipDurations` absorbs the drift between a re-encoded video and its separately
         * transcoded audio; without it a sidecar a few milliseconds longer than the film is an
         * `IllegalMergeException` instead of playback. `adjustPeriodTimeOffsets` is off because
         * every child starts at zero — they are the same title, cut the same way.
         */
        override fun prepare(
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) {
            startPlaybackService()
            with(requirePlayer()) {
                // This player outlives the item: the previous one's overrides — including its
                // "subtitles off" — would otherwise be this one's starting point.
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
        @Suppress("SpreadOperator") // MergingMediaSource only takes varargs; the copy is a handful of refs.
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
         * Releases the shared player.
         *
         * Idempotent by construction: the field is cleared first, so the second caller — whichever
         * of `PlayerViewModel.releaseSession` and [PlaybackService.onDestroy] arrives last — finds
         * nothing to do. [requirePlayer] then builds a fresh instance for the next session, which is
         * why releasing here costs nothing but the rebuild.
         *
         * The listener is removed explicitly rather than left to `release()`: it is a strong
         * reference from a `@Singleton` to a `MutableSharedFlow` that outlives every session, and
         * leaving it attached is what turned "one leaked player" into "one leaked player per
         * process, still emitting".
         *
         * Deliberately does *not* stop the playback service. [stop] owns that, and the service's own
         * teardown is one of the two callers here — asking it to stop itself from inside `onDestroy`
         * would be a no-op at best.
         *
         * ### Deferred while [PlaybackService] is alive (audit PC-04)
         * The service's `MediaSession` is built *around* this player, and Media3 requires the
         * session to be released before it. The ViewModel's teardown reaches here synchronously
         * while the `stopService` it just issued is still a pending main-looper message — so for
         * that window the live session (its notification, a headset or Assistant controller) would
         * be poking a released player, and a `startService` racing the pending stop (backing out
         * and re-entering the player) would leave the session wrapping a dead instance for good.
         * While the service reports itself running, the release is therefore left to its
         * `onDestroy`, which clears the flag before calling back in; a session whose service never
         * managed to start still releases here directly.
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
    }
