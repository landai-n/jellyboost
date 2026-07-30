package dev.jellyfinnative.player.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.core.common.model.MediaSegmentKind
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.player.fallback.DecoderFallbackHandler
import dev.jellyfinnative.player.fallback.FallbackDecision
import dev.jellyfinnative.player.model.LocalPlaybackMediaSource
import dev.jellyfinnative.player.model.PlaybackMediaSource
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.PlaybackSpeed
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import dev.jellyfinnative.player.model.ticksToMillis
import dev.jellyfinnative.player.pip.PipController
import dev.jellyfinnative.player.pip.PipState
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.ExoMediaSourceFactory
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.resolve.PlaybackSourceResolver
import dev.jellyfinnative.player.segments.MediaSegment
import dev.jellyfinnative.player.segments.MediaSegmentLoader
import dev.jellyfinnative.player.segments.SegmentSkipController
import dev.jellyfinnative.player.segments.SegmentSkipDecision
import dev.jellyfinnative.player.session.PlayerEvent
import dev.jellyfinnative.player.session.PlayerHandle
import dev.jellyfinnative.player.trickplay.TrickplayResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 *
 * Since M8 the source may equally be a local file: [PlaybackSourceResolver] picks between the
 * download on disk and the server, and nothing below this line knows which it got. That is the
 * whole reason `PlaybackMediaSource` is a sealed type.
 *
 * Both suppressed thresholds are exceeded for the same reason, and it is the class's whole purpose:
 * this is the one place a playback session is sequenced, so it has one function per user action, one
 * per player event, and one collaborator per thing a session needs (resolve, report, fall back,
 * trickplay, segments, preferences, picture-in-picture). Splitting it would move the sequencing —
 * the part that is actually hard and actually tested — behind another indirection.
 */
@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class PlayerViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val resolver: PlaybackSourceResolver,
        private val mediaSourceFactory: ExoMediaSourceFactory,
        private val playerHandle: PlayerHandle,
        private val reporter: PlaybackReporter,
        private val fallback: DecoderFallbackHandler,
        private val trickplayResolver: TrickplayResolver,
        private val segmentLoader: MediaSegmentLoader,
        private val preferences: AppPreferences,
        private val pipController: PipController,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val itemId: String =
            requireNotNull(savedStateHandle.get<String>(ARG_ITEM_ID)) {
                "Player route is missing its '$ARG_ITEM_ID' argument"
            }
        private val mediaSourceId: String? = savedStateHandle[ARG_MEDIA_SOURCE_ID]

        /**
         * Where the last session had actually got to, or `null` if this is a fresh navigation.
         *
         * Non-null only after a process death: the handle is restored with whatever the last
         * progress tick wrote into it ([rememberLivePosition]). It is the difference between coming
         * back to the film where the user left it and coming back to where they *tapped Play* —
         * and the latter is not merely a cosmetic annoyance, because the progress reporter would
         * then stamp that stale position with a fresh timestamp and most-recent-wins sync would
         * push it out to the server and every other device.
         */
        private val restoredPositionTicks: Long? = savedStateHandle[KEY_LIVE_POSITION_TICKS]

        private val startPositionTicks: Long =
            restoredPositionTicks ?: savedStateHandle[ARG_START_TICKS] ?: 0L

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

        /**
         * Picture-in-picture state, straight off the shared [PipController].
         *
         * Re-exposed here rather than injected into the composable so the screen keeps its single
         * dependency on this ViewModel; the activity reads the same flow from the other end.
         */
        val pipState: StateFlow<PipState> = pipController.state

        /** The currently playing source; `null` until the first resolve succeeds. */
        private var source: PlaybackMediaSource? = null

        private var reportingJob: Job? = null
        private var uiTickerJob: Job? = null

        /** Guards against reporting the stop twice when the item ends and the screen then closes. */
        private var stopReported = false

        /** The item's intro/outro ranges; empty offline and on a server without the segments API. */
        private var segments: List<MediaSegment> = emptyList()

        /** Remembers which segments this session already jumped over — one per playback session. */
        private val segmentSkip = SegmentSkipController()

        /** The user's per-type segment preference, kept current for the position ticker to read. */
        private var skipModes: Map<MediaSegmentKind, SegmentSkipMode> = emptyMap()

        /** `true` while entering picture-in-picture on user-leave is allowed by the preference. */
        private var pipEnabled = false

        /** `true` while the player screen is on top — one of the three conditions PiP needs. */
        private var screenPresent = false

        init {
            observePlayerEvents()
            observePreferences()
            loadTitle()
            open(
                PlaybackResolveRequest(
                    itemId = UUID.fromString(itemId),
                    mediaSourceId = mediaSourceId,
                    startPositionTicks = startPositionTicks,
                ),
                // A fresh tap on Play means play. A restore after process death resumes only what
                // was actually running: an item the user had paused before leaving the app must not
                // start talking to an empty room minutes later.
                playWhenReady =
                    restoredPositionTicks == null ||
                        savedStateHandle.get<Boolean>(KEY_WAS_PLAYING) == true,
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

        /**
         * Keeps the M9 preferences current for as long as playback lasts.
         *
         * Read continuously rather than once because the settings screen can change them from under
         * a playing item — turning auto-skip on mid-episode should take effect at the next segment,
         * not at the next film.
         */
        private fun observePreferences() {
            viewModelScope.launch {
                combine(
                    preferences.introSkipMode,
                    preferences.outroSkipMode,
                    preferences.pipOnLeave,
                ) { intro, outro, pip ->
                    Triple(intro, outro, pip)
                }.collect { (intro, outro, pip) ->
                    skipModes = mapOf(MediaSegmentKind.INTRO to intro, MediaSegmentKind.OUTRO to outro)
                    pipEnabled = pip
                    publishPipState()
                }
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
                source = current.withSelectedAudio(jellyfinIndex)
                _uiState.update { it.copy(selectedAudioIndex = jellyfinIndex) }
                return
            }
            if (current is LocalPlaybackMediaSource) return refuseLocalTrackChange(current)
            reopen(
                current.asRequest().copy(audioStreamIndex = jellyfinIndex),
                PlayerMessage.RestartedForTrackChange,
            )
        }

        /** Switches subtitle track; [jellyfinIndex] `null` turns subtitles off. */
        fun selectSubtitleTrack(jellyfinIndex: Int?) {
            val current = source ?: return
            if (playerHandle.selectSubtitleTrack(current, jellyfinIndex)) {
                source = current.withSelectedSubtitle(jellyfinIndex)
                _uiState.update { it.copy(selectedSubtitleIndex = jellyfinIndex) }
                return
            }
            if (current is LocalPlaybackMediaSource) return refuseLocalTrackChange(current)
            // -1 is the server's "no subtitles"; null would make it pick the item's default again.
            reopen(
                current.asRequest().copy(subtitleStreamIndex = jellyfinIndex ?: SUBTITLES_OFF),
                PlayerMessage.RestartedForTrackChange,
            )
        }

        /**
         * Declines a track the downloaded file cannot produce, instead of restarting it for nothing.
         *
         * A re-resolve is how the *online* player obtains a track the current stream lacks: it asks
         * the server for a different one. There is no server behind a `file://` URI, so re-resolving
         * a [LocalPlaybackMediaSource] runs `LocalPlaybackResolver` over the very same file and hands
         * back the very same tracks — the switch still cannot be applied, and playback has visibly
         * restarted for it. That loop is what a transcoded download did on every attempt to change
         * language or subtitles.
         *
         * With the resolver no longer offering tracks a transcoded file does not hold, this should
         * be unreachable from the pickers. It stays as the honest answer for whatever slips past —
         * a selection restored from a previous session, or a container whose streams disagree with
         * the cached blob.
         */
        private fun refuseLocalTrackChange(current: LocalPlaybackMediaSource) {
            Timber.i("Downloaded file %s cannot supply that track; leaving playback alone", current.itemId)
            _uiState.update { it.copy(userMessage = PlayerMessage.TrackUnavailableOffline) }
        }

        /**
         * Applies a bitrate cap and reopens the stream.
         *
         * Choosing a cap below the file's bitrate is what makes the server transcode, and is how
         * the milestone's forced-transcode verification is driven from the UI.
         *
         * A locally-played download has no bitrate to cap — there is no server in the loop — so the
         * control is hidden for it (`PlayerUiState.isLocalPlayback`) and the call is ignored if it
         * arrives anyway.
         */
        fun selectQuality(quality: PlaybackQuality) {
            val current = source as? RemotePlaybackMediaSource ?: return
            if (quality.maxStreamingBitrate == current.maxStreamingBitrate) return
            _uiState.update { it.copy(quality = quality) }
            reopen(current.asRequest().copy(maxStreamingBitrate = quality.maxStreamingBitrate))
        }

        /**
         * Applies a playback rate.
         *
         * Session-scoped: it is held in [PlayerUiState] and re-applied after every re-resolve
         * ([open]), and nothing writes it to disk (docs/PLAN.md, "M9 Polish" → speed).
         */
        fun selectSpeed(speed: PlaybackSpeed) {
            if (speed == _uiState.value.speed) return
            playerHandle.setPlaybackSpeed(speed.rate)
            _uiState.update { it.copy(speed = speed) }
        }

        /**
         * Jumps to the end of the intro or outro currently on screen.
         *
         * Backs the "Skip intro"/"Skip outro" button. A no-op when nothing is offered, so a stale
         * tap that lands just after the segment ended cannot seek somewhere arbitrary.
         */
        fun skipCurrentSegment() {
            val segment = _uiState.value.skippableSegment ?: return
            Timber.d("Skipping %s at %d ms", segment.kind, segment.startMs)
            seekTo(segment.endMs)
            _uiState.update { it.copy(skippableSegment = null) }
        }

        /**
         * Starts or stops the position poll that drives the seek bar.
         *
         * Driven by the screen's presence rather than by playback because it is purely cosmetic —
         * polling behind a backgrounded screen would burn battery updating a slider nobody can
         * see. Progress *reporting* is a separate ticker and keeps running regardless, which since
         * M9 is also what keeps a backgrounded film reporting while the notification controls it.
         *
         * The first reading is taken immediately rather than after a tick, so returning to the
         * screen after a spell in the background — or in picture-in-picture — shows the live
         * position instead of the one playback had when it was left.
         */
        fun setScreenVisible(visible: Boolean) {
            setScreenPresent(visible)
            uiTickerJob?.cancel()
            uiTickerJob =
                when {
                    !visible -> null
                    else ->
                        viewModelScope.launch {
                            while (true) {
                                onTick(playerHandle.snapshot())
                                delay(UI_TICK)
                            }
                        }
                }
        }

        /**
         * Records whether the player screen is on top, without the poll behind it.
         *
         * `internal` for the same reason [releaseSession] is: this half decides whether
         * picture-in-picture may arm, and it is worth testing; the 500 ms timer wrapped around it is
         * not, and a coroutine that never finishes is something `runTest` cannot drain.
         */
        internal fun setScreenPresent(present: Boolean) {
            screenPresent = present
            publishPipState()
        }

        /**
         * One reading of the player: the seek bar, and whatever the segment rules make of it.
         *
         * The segment check rides the existing poll rather than adding a second one — it needs
         * exactly the same information, twice a second is far more often than a segment boundary
         * moves, and a skip button that appears half a second late is a skip button nobody notices
         * is late.
         *
         * `internal` so a test can hand it a position directly; see [setScreenPresent].
         */
        internal fun onTick(snapshot: PlaybackSnapshot) {
            _uiState.update { it.withSnapshot(snapshot) }
            publishPipState()

            when (val decision = segmentSkip.decide(snapshot.positionMs, segments, skipModes)) {
                SegmentSkipDecision.None ->
                    _uiState.update { if (it.skippableSegment == null) it else it.copy(skippableSegment = null) }

                is SegmentSkipDecision.Offer ->
                    _uiState.update { it.copy(skippableSegment = decision.segment) }

                is SegmentSkipDecision.AutoSkip -> {
                    Timber.i("Auto-skipping %s to %d ms", decision.segment.kind, decision.segment.endMs)
                    seekTo(decision.segment.endMs)
                    _uiState.update { it.copy(skippableSegment = null) }
                }
            }
        }

        /**
         * Tells [PipController] whether leaving the app right now should float the video.
         *
         * All three conditions are decided here — the player screen is up, something is playing, and
         * the preference is on — so `MainActivity`, which hosts every other screen too, only has to
         * read one boolean.
         */
        private fun publishPipState() {
            val state = _uiState.value
            pipController.setPlayerState(
                active = pipEnabled && screenPresent && state.isPlaying && state.isReady,
                videoWidth = state.videoWidth,
                videoHeight = state.videoHeight,
            )
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
                        // A re-resolve builds a fresh media item, which starts at 1×; the speed the
                        // user chose belongs to the session, not to the media item.
                        _uiState.value.speed
                            .takeIf { !it.isNormal }
                            ?.let { playerHandle.setPlaybackSpeed(it.rate) }
                        _videoPlayer.value = playerHandle.player
                        _uiState.update { it.withSource(resolved, message) }

                        reporter.reportStart(resolved, playerHandle.snapshot())
                        setReportingActive(true)
                        loadPlaybackExtras(resolved)
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

        /**
         * Fetches the two things that decorate playback but must never delay it: the scrubbing
         * thumbnails and the intro/outro ranges.
         *
         * Deliberately launched *after* `prepare` and never awaited. Both are optional — most items
         * have neither — and both can involve a server round trip, so putting either on the path to
         * the first frame would trade something the user is waiting for against something they have
         * not asked for yet. Neither can fail visibly: absence is the resolvers' normal answer.
         */
        private fun loadPlaybackExtras(resolved: PlaybackMediaSource) {
            segments = emptyList()
            segmentSkip.reset()
            _uiState.update { it.copy(trickplay = null, skippableSegment = null) }

            viewModelScope.launch {
                _uiState.update { it.copy(trickplay = trickplayResolver.resolve(resolved)) }
            }
            viewModelScope.launch {
                segments = segmentLoader.load(resolved)
            }
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

                is PlayerEvent.IsPlayingChanged -> {
                    _uiState.update { it.copy(isPlaying = event.isPlaying, isBuffering = false) }
                    publishPipState()
                }

                is PlayerEvent.TracksChanged -> Unit

                is PlayerEvent.VideoSizeChanged -> {
                    _uiState.update { it.copy(videoWidth = event.width, videoHeight = event.height) }
                    publishPipState()
                }

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
         *
         * The same tick is what keeps [SavedStateHandle] current — see [rememberLivePosition]. It
         * rides this ticker rather than the UI one for exactly the reason above: the UI poll stops
         * when the screen goes away, which is precisely the state a process death happens in.
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
                            snapshot = { playerHandle.snapshot().also(::rememberLivePosition) },
                        )
                }
        }

        /**
         * Writes the live position back into the handle the system restores after a process death.
         *
         * Without this the handle only ever holds the navigation arguments, so a restored back
         * stack re-opens the player at the position the item had when Play was *tapped* — and the
         * next progress tick then writes that stale position to the local user-data row with a
         * fresh timestamp, which most-recent-wins sync happily propagates to the server and to
         * every other device. Losing the resume point of a film someone is halfway through is
         * silent, permanent and entirely invisible until they come back to it.
         *
         * Position 0 is not written: it is indistinguishable from "no session yet", and falling
         * back to the navigation argument is the better answer for it anyway.
         */
        private fun rememberLivePosition(snapshot: PlaybackSnapshot) {
            if (snapshot.positionTicks <= 0L) return
            savedStateHandle[KEY_LIVE_POSITION_TICKS] = snapshot.positionTicks
            savedStateHandle[KEY_WAS_PLAYING] = snapshot.isPlaying
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
            // Nothing is playing any more, so nothing should float when the user leaves next.
            pipController.clear()
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

            /**
             * Keys this ViewModel writes back into the handle, as opposed to the `ARG_` ones it
             * only reads. Distinct from [ARG_START_TICKS] on purpose: the navigation argument is
             * what the user tapped and must stay intact, while these two are what the session had
             * reached the last time anyone looked.
             */
            const val KEY_LIVE_POSITION_TICKS = "livePositionTicks"
            const val KEY_WAS_PLAYING = "wasPlaying"

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
 * track, fallback — from silently dropping a setting the previous one had established. A local
 * source has no bitrate cap to carry over, so it contributes `null` and the server picks freely if
 * the re-negotiation ends up there.
 */
private fun PlaybackMediaSource.asRequest(): PlaybackResolveRequest =
    PlaybackResolveRequest(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        maxStreamingBitrate = (this as? RemotePlaybackMediaSource)?.maxStreamingBitrate,
        audioStreamIndex = selectedAudioIndex,
        subtitleStreamIndex = selectedSubtitleIndex,
    )

private fun PlayerUiState.withSource(
    source: PlaybackMediaSource,
    message: PlayerMessage?,
): PlayerUiState =
    copy(
        isLoading = false,
        isBuffering = true,
        errorMessage = null,
        hasEnded = false,
        playMethod = source.playMethod,
        isLocalPlayback = source is LocalPlaybackMediaSource,
        durationMs = source.runTimeTicks.ticksToMillis(),
        positionMs = source.startPositionTicks.ticksToMillis(),
        audioTracks = source.audioTracks,
        subtitleTracks = source.subtitleTracks,
        selectedAudioIndex = source.selectedAudioIndex,
        selectedSubtitleIndex = source.selectedSubtitleIndex,
        quality = PlaybackQuality.forBitrate((source as? RemotePlaybackMediaSource)?.maxStreamingBitrate),
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
