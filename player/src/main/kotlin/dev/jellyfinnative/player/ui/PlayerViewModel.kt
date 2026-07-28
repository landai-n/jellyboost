package dev.jellyfinnative.player.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.player.fallback.DecoderFallbackHandler
import dev.jellyfinnative.player.fallback.FallbackDecision
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import dev.jellyfinnative.player.model.ticksToMillis
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.ExoMediaSourceFactory
import dev.jellyfinnative.player.resolve.PlaybackInfoResolver
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.session.PlayerEvent
import dev.jellyfinnative.player.session.PlayerHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sequences everything a playback session does.
 *
 * The player screen looks simple, but almost every control here is a *server* operation rather
 * than a local one: switching audio while transcoding, capping the bitrate, or recovering from a
 * decoder failure all mean going back to `PlaybackInfo` and reopening the stream at the current
 * position. Keeping that sequencing in one state holder — behind the [PlayerHandle] seam, so it
 * can be tested without a device — is what stops it leaking into the composables.
 *
 * Ordering rules that are not obvious and are load-bearing:
 *
 * - a re-resolve always stops the *previous* transcode before starting the next one, otherwise
 *   every quality change strands an ffmpeg process on the server;
 * - the stop report goes out on a detached scope, because `viewModelScope` is already cancelled by
 *   the time [onCleared] runs.
 */
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val resolver: PlaybackInfoResolver,
        private val mediaSourceFactory: ExoMediaSourceFactory,
        private val playerHandle: PlayerHandle,
        private val reporter: PlaybackReporter,
        private val fallback: DecoderFallbackHandler,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val itemId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
                "Player route is missing its '$ARG_ITEM_ID' argument"
            }
        private val mediaSourceId: String? = savedStateHandle[ARG_MEDIA_SOURCE_ID]
        private val startPositionTicks: Long = savedStateHandle[ARG_START_TICKS] ?: 0L

        private val _uiState = MutableStateFlow(PlayerUiState())

        /** The single source of truth for [PlayerScreen]. */
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        private val _videoPlayer = MutableStateFlow<Player?>(null)

        /**
         * The player to attach the video surface to.
         *
         * Separate from [uiState] because it is not state the UI reasons about — it is a handle
         * the surface binds to once, and putting a `Player` inside a data class would give that
         * class no meaningful equality.
         */
        val videoPlayer: StateFlow<Player?> = _videoPlayer.asStateFlow()

        /** The currently playing source; `null` until the first resolve succeeds. */
        private var source: RemotePlaybackMediaSource? = null

        private var reportingJob: Job? = null
        private var uiTickerJob: Job? = null

        /** Guards against reporting the stop twice when the item ends and the screen then closes. */
        private var stopReported = false

        init {
            observePlayerEvents()
            loadTitle()
            open(
                PlaybackResolveRequest(
                    itemId = UUID.fromString(itemId),
                    mediaSourceId = mediaSourceId,
                    startPositionTicks = startPositionTicks,
                ),
                playWhenReady = true,
            )
        }

        /**
         * Fetches the item's name for the top bar.
         *
         * Fire and forget, and deliberately not on the path to playback: a title is cosmetic and
         * must never delay the first frame.
         */
        private fun loadTitle() {
            viewModelScope.launch {
                val item = repository.getItem(itemId).getOrNull() ?: return@launch
                val label = listOfNotNull(item.displayTitle, item.displaySubtitle).joinToString(" · ")
                _uiState.update { it.copy(title = label) }
            }
        }

        // ---- user actions -------------------------------------------------------------------------

        fun togglePlayPause() {
            if (playerHandle.snapshot().isPlaying) playerHandle.pause() else playerHandle.play()
        }

        fun seekTo(positionMs: Long) {
            playerHandle.seekTo(positionMs)
            _uiState.update { it.copy(positionMs = positionMs) }
        }

        /** Jumps by [deltaMs], clamped to the item — backs the skip-back / skip-forward buttons. */
        fun seekBy(deltaMs: Long) {
            val snapshot = playerHandle.snapshot()
            seekTo((snapshot.positionMs + deltaMs).coerceIn(0L, snapshot.durationMs.coerceAtLeast(0L)))
        }

        /**
         * Switches audio track.
         *
         * Tried locally first; while transcoding the server only ever sent the one audio track it
         * was asked for, so the switch falls through to a re-resolve and a visible reload.
         */
        fun selectAudioTrack(jellyfinIndex: Int) {
            val current = source ?: return
            if (playerHandle.selectAudioTrack(current, jellyfinIndex)) {
                source = current.copy(selectedAudioIndex = jellyfinIndex)
                _uiState.update { it.copy(selectedAudioIndex = jellyfinIndex) }
                return
            }
            reopen(
                current.asRequest().copy(audioStreamIndex = jellyfinIndex),
                PlayerMessage.RestartedForTrackChange,
            )
        }

        /** Switches subtitle track; [jellyfinIndex] `null` turns subtitles off. */
        fun selectSubtitleTrack(jellyfinIndex: Int?) {
            val current = source ?: return
            if (playerHandle.selectSubtitleTrack(current, jellyfinIndex)) {
                source = current.copy(selectedSubtitleIndex = jellyfinIndex)
                _uiState.update { it.copy(selectedSubtitleIndex = jellyfinIndex) }
                return
            }
            // -1 is the server's "no subtitles"; null would make it pick the item's default again.
            reopen(
                current.asRequest().copy(subtitleStreamIndex = jellyfinIndex ?: SUBTITLES_OFF),
                PlayerMessage.RestartedForTrackChange,
            )
        }

        /**
         * Applies a bitrate cap and reopens the stream.
         *
         * Choosing a cap below the file's bitrate is what makes the server transcode, and is how
         * the milestone's forced-transcode verification is driven from the UI.
         */
        fun selectQuality(quality: PlaybackQuality) {
            val current = source ?: return
            if (quality.maxStreamingBitrate == current.maxStreamingBitrate) return
            _uiState.update { it.copy(quality = quality) }
            reopen(current.asRequest().copy(maxStreamingBitrate = quality.maxStreamingBitrate))
        }

        /**
         * Starts or stops the position poll that drives the seek bar.
         *
         * Driven by the screen's presence rather than by playback because it is purely cosmetic —
         * polling behind a backgrounded screen would burn battery updating a slider nobody can
         * see. Progress *reporting* is a separate ticker and keeps running regardless.
         */
        fun setScreenVisible(visible: Boolean) {
            uiTickerJob?.cancel()
            uiTickerJob =
                when {
                    !visible -> null
                    else ->
                        viewModelScope.launch {
                            while (true) {
                                delay(UI_TICK)
                                val snapshot = playerHandle.snapshot()
                                _uiState.update { it.withSnapshot(snapshot) }
                            }
                        }
                }
        }

        /** Clears the one-shot message once the snackbar has shown it. */
        fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        // ---- session ------------------------------------------------------------------------------

        /**
         * Resolves [request] and hands the result to the player.
         *
         * @param playWhenReady whether to start playing once buffered — `false` preserves a paused
         *   state across a re-resolve.
         */
        private fun open(
            request: PlaybackResolveRequest,
            playWhenReady: Boolean,
            message: PlayerMessage? = null,
        ) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                when (val result = resolver.resolve(request)) {
                    is AppResult.Failure -> fail(result.error.toMessage())

                    is AppResult.Success -> {
                        val resolved = result.value
                        val spec = mediaSourceFactory.create(resolved)
                        if (spec == null) {
                            fail(UNSUPPORTED_SOURCE)
                            return@launch
                        }

                        source = resolved
                        stopReported = false
                        playerHandle.prepare(
                            spec = spec,
                            startPositionMs = resolved.startPositionTicks.ticksToMillis(),
                            playWhenReady = playWhenReady,
                        )
                        _videoPlayer.value = playerHandle.player
                        _uiState.update { it.withSource(resolved, message) }

                        reporter.reportStart(resolved, playerHandle.snapshot())
                        setReportingActive(true)
                    }
                }
            }
        }

        /**
         * Reopens the current item under new terms, stopping the outgoing transcode first.
         *
         * Every re-negotiation goes through here — quality change, track change the server has to
         * perform, and both fallback ladders — so the "stop the old encoder, then start the new
         * stream, then resume where we were" order only exists once.
         */
        private fun reopen(
            request: PlaybackResolveRequest,
            message: PlayerMessage? = null,
        ) {
            val previous = source ?: return
            val snapshot = playerHandle.snapshot()
            setReportingActive(false)
            viewModelScope.launch { reporter.stopTranscoding(previous) }
            open(
                request.copy(
                    startPositionTicks =
                        request.startPositionTicks.takeIf { it > 0L } ?: snapshot.positionTicks,
                ),
                playWhenReady = snapshot.isPlaying,
                message = message,
            )
        }

        private fun observePlayerEvents() {
            viewModelScope.launch {
                playerHandle.events.collect(::onPlayerEvent)
            }
        }

        private fun onPlayerEvent(event: PlayerEvent) {
            when (event) {
                is PlayerEvent.Ready -> {
                    fallback.onPlaybackStarted()
                    _uiState.update { it.copy(isLoading = false, isBuffering = false) }
                }

                is PlayerEvent.IsPlayingChanged ->
                    _uiState.update { it.copy(isPlaying = event.isPlaying, isBuffering = false) }

                is PlayerEvent.TracksChanged -> Unit

                is PlayerEvent.Ended -> onEnded()

                is PlayerEvent.Error -> onError(event)
            }
        }

        private fun onEnded() {
            val current = source ?: return
            _uiState.update { it.copy(hasEnded = true, isPlaying = false) }
            setReportingActive(false)
            if (stopReported) return
            stopReported = true
            viewModelScope.launch { reporter.reportStop(current, playerHandle.snapshot().copy(hasEnded = true)) }
        }

        private fun onError(event: PlayerEvent.Error) {
            val current = source ?: return
            val positionTicks = playerHandle.snapshot().positionTicks

            when (val decision = fallback.onPlayerError(event.errorCode, current, positionTicks)) {
                is FallbackDecision.ForceTranscode ->
                    reopen(
                        current.asRequest().copy(
                            startPositionTicks = decision.positionTicks,
                            enableDirectPlay = false,
                            enableDirectStream = false,
                        ),
                        PlayerMessage.SwitchedToTranscode,
                    )

                is FallbackDecision.LowerBitrate ->
                    reopen(
                        current.asRequest().copy(
                            startPositionTicks = decision.positionTicks,
                            maxStreamingBitrate = decision.maxStreamingBitrate,
                        ),
                        PlayerMessage.RetryingAtLowerQuality,
                    )

                FallbackDecision.GiveUp -> {
                    Timber.w("Giving up on %s after error %d", current.itemId, event.errorCode)
                    fail(event.message ?: PLAYBACK_FAILED, PlayerMessage.PlaybackFailed)
                }
            }
        }

        /**
         * Starts or stops the 5-second progress ticker.
         *
         * Independent of the UI position poll: reporting has to keep running while the screen is
         * backgrounded, because that is exactly when a user leaves an episode playing.
         */
        private fun setReportingActive(active: Boolean) {
            reportingJob?.cancel()
            reportingJob =
                when {
                    !active -> null
                    else ->
                        reporter.startReporting(
                            scope = viewModelScope,
                            currentSource = { source },
                            snapshot = { playerHandle.snapshot() },
                        )
                }
        }

        private fun fail(
            message: String,
            userMessage: PlayerMessage? = null,
        ) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isBuffering = false,
                    errorMessage = message,
                    userMessage = userMessage ?: it.userMessage,
                )
            }
        }

        /**
         * Ends the session.
         *
         * The stop report is handed to the reporter's detached scope: by the time this runs
         * `viewModelScope` is cancelled, and a report launched there would never leave the device —
         * taking the resume position and the transcode cleanup with it.
         */
        override fun onCleared() {
            releaseSession()
            super.onCleared()
        }

        /**
         * The teardown [onCleared] performs.
         *
         * `internal` rather than private so the ordering it encodes — which is the entire reason
         * the stop report exists on a detached scope — can be unit tested without reflecting into
         * the lifecycle library's internals.
         */
        internal fun releaseSession() {
            setReportingActive(false)
            setScreenVisible(false)
            val current = source
            if (current != null && !stopReported) {
                stopReported = true
                reporter.reportStopDetached(current, playerHandle.snapshot())
            }
            _videoPlayer.value = null
            playerHandle.stop()
        }

        companion object {
            /** Keys the navigation library stores `Routes.Player`'s arguments under. */
            const val ARG_ITEM_ID = "itemId"
            const val ARG_MEDIA_SOURCE_ID = "mediaSourceId"
            const val ARG_START_TICKS = "startPositionTicks"

            /** The server's "no subtitles" sentinel; `null` would re-select the item's default. */
            const val SUBTITLES_OFF = -1

            private val UI_TICK = 500.milliseconds
            private const val UNSUPPORTED_SOURCE = "This item cannot be played on this device."
            private const val PLAYBACK_FAILED = "Playback failed."
        }
    }

/**
 * The request that would reproduce what is playing right now.
 *
 * Callers `copy()` the one thing they are changing, which keeps every re-negotiation — quality,
 * track, fallback — from silently dropping a setting the previous one had established.
 */
private fun RemotePlaybackMediaSource.asRequest(): PlaybackResolveRequest =
    PlaybackResolveRequest(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        maxStreamingBitrate = maxStreamingBitrate,
        audioStreamIndex = selectedAudioIndex,
        subtitleStreamIndex = selectedSubtitleIndex,
    )

private fun PlayerUiState.withSource(
    source: RemotePlaybackMediaSource,
    message: PlayerMessage?,
): PlayerUiState =
    copy(
        isLoading = false,
        isBuffering = true,
        errorMessage = null,
        hasEnded = false,
        playMethod = source.playMethod,
        durationMs = source.runTimeTicks.ticksToMillis(),
        positionMs = source.startPositionTicks.ticksToMillis(),
        audioTracks = source.audioTracks,
        subtitleTracks = source.subtitleTracks,
        selectedAudioIndex = source.selectedAudioIndex,
        selectedSubtitleIndex = source.selectedSubtitleIndex,
        quality = PlaybackQuality.forBitrate(source.maxStreamingBitrate),
        userMessage = message ?: userMessage,
    )

private fun PlayerUiState.withSnapshot(snapshot: PlaybackSnapshot): PlayerUiState =
    copy(
        positionMs = snapshot.positionMs,
        bufferedMs = snapshot.bufferedMs,
        // The server's runtime and the container's can disagree; once the player knows, it wins.
        durationMs = snapshot.durationMs.takeIf { it > 0L } ?: durationMs,
        isPlaying = snapshot.isPlaying,
    )

/** Turns the domain failure taxonomy into copy a user can act on. */
private fun AppError.toMessage(): String =
    when (this) {
        is AppError.Network -> "Can't reach your server. Check your connection and try again."
        is AppError.ServerResolution -> "Can't reach your server. Check your connection and try again."
        is AppError.Unauthorized -> "Your session expired. Sign in again to continue."
        is AppError.NotFound -> "That item is no longer on the server."
        is AppError.Server -> "The server could not start playback${statusCode?.let { " ($it)" }.orEmpty()}."
        is AppError.Storage -> "Couldn't read local data."
        is AppError.Unknown -> "Something went wrong starting playback."
    }
