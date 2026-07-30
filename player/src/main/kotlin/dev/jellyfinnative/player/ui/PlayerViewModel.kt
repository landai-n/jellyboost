package dev.jellyfinnative.player.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.getOrNull
import dev.jellyfinnative.core.common.model.MediaSegmentKind
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.player.fallback.DecoderFallbackHandler
import dev.jellyfinnative.player.fallback.FallbackDecision
import dev.jellyfinnative.player.model.LocalPlaybackMediaSource
import dev.jellyfinnative.player.model.PlaybackMediaSource
import dev.jellyfinnative.player.model.PlaybackQuality
import dev.jellyfinnative.player.model.PlaybackSnapshot
import dev.jellyfinnative.player.model.PlaybackSpeed
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import dev.jellyfinnative.player.model.audioTracksFor
import dev.jellyfinnative.player.model.subtitleTracksFor
import dev.jellyfinnative.player.model.ticksToMillis
import dev.jellyfinnative.player.pip.PipController
import dev.jellyfinnative.player.pip.PipState
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.segments.MediaSegment
import dev.jellyfinnative.player.segments.MediaSegmentLoader
import dev.jellyfinnative.player.segments.SegmentSkipDecision
import dev.jellyfinnative.player.session.PlaybackSessionController
import dev.jellyfinnative.player.session.PlayerEvent
import dev.jellyfinnative.player.session.PlayerHandle
import dev.jellyfinnative.player.session.SessionOpenResult
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
 *   every quality change strands an ffmpeg process on the server. That order is now owned by
 *   [PlaybackSessionController], in one coroutine, because two coroutines could not guarantee it;
 * - the stop report goes out on a detached scope, because `viewModelScope` is already cancelled by
 *   the time [onCleared] runs.
 *
 * Since M8 the source may equally be a local file: `PlaybackSourceResolver` picks between the
 * download on disk and the server, and nothing below this line knows which it got. That is the
 * whole reason `PlaybackMediaSource` is a sealed type.
 *
 * ### What this class is, after the ARCH-10 decomposition
 * Three things that were sequencing-adjacent rather than sequencing now live next door and are
 * tested on their own: [PlaybackSessionController] (resolve → prepare → re-negotiate),
 * [PlayerSessionStore] (the route's arguments, and the live position written back over them), and
 * [PlaybackPositionTracker] (the 500 ms tick and the segment decision it feeds). What is left is
 * what was always the hard part — deciding *what to do* with a user action, a player event, or a
 * failure — plus the one state object the screen draws.
 *
 * `TooManyFunctions` still applies and is still suppressed, for a reason the decomposition does not
 * remove: this is the one place a playback session is sequenced, so it has one function per user
 * action and one per player event, and there are simply that many of both. Splitting *those* would
 * move the sequencing, which is the part that is actually hard and actually tested.
 */
@HiltViewModel
@Suppress("TooManyFunctions")
class PlayerViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val sessionController: PlaybackSessionController,
        private val playerHandle: PlayerHandle,
        private val reporter: PlaybackReporter,
        private val fallback: DecoderFallbackHandler,
        private val trickplayResolver: TrickplayResolver,
        private val segmentLoader: MediaSegmentLoader,
        private val preferences: AppPreferences,
        private val pipController: PipController,
        private val connectionState: ConnectionStateProvider,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val sessionStore = PlayerSessionStore(savedStateHandle)

        private val positionTracker = PlaybackPositionTracker()

        private val _uiState = MutableStateFlow(PlayerUiState())

        /** The slow-changing state [PlayerScreen] draws; the position is deliberately not in it. */
        val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        /**
         * The seek bar's position and buffer, ticking twice a second (audit PERF-04).
         *
         * Separate from [uiState] so that a number only the scrubber and the clock read cannot
         * invalidate the top bar, the transport row and the pickers along with them.
         */
        val position: StateFlow<PlaybackPosition> = positionTracker.position

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

        /**
         * The downloaded copy of this item, once one has been resolved.
         *
         * Kept for the whole session rather than read off [source], because a forced-remote track
         * change replaces [source] with the *server's* copy — and it is precisely then that the two
         * questions this answers arise: which tracks the file could still play (so choosing one of
         * them can go home instead of streaming on), and what to fall back to if the server turns
         * out not to be there after all.
         */
        private var localSource: LocalPlaybackMediaSource? = null

        /**
         * `true` while the app believes it can reach the server.
         *
         * Kept in step with [ConnectionStateProvider] for as long as playback lasts, because it
         * decides what the pickers *offer*: the source's full track list while there is a server to
         * stream a missing track from, and only what the downloaded file holds when there is not.
         * A sheet that is already open therefore reacts to the network dropping.
         */
        private var isOnline = true

        /**
         * `true` while this session is deliberately streaming an item that is also on disk.
         *
         * Carried into every later re-negotiation ([asRequest]) so that changing quality — or a
         * decoder fallback — does not silently drop back to the local file and lose the track the
         * user went to the server for.
         */
        private var forcedRemote = false

        private var reportingJob: Job? = null
        private var uiTickerJob: Job? = null

        /** Guards against reporting the stop twice when the item ends and the screen then closes. */
        private var stopReported = false

        /** The item's intro/outro ranges; empty offline and on a server without the segments API. */
        private var segments: List<MediaSegment> = emptyList()

        /** The user's per-type segment preference, kept current for the position ticker to read. */
        private var skipModes: Map<MediaSegmentKind, SegmentSkipMode> = emptyMap()

        /** `true` while entering picture-in-picture on user-leave is allowed by the preference. */
        private var pipEnabled = false

        /** `true` while the player screen is on top — one of the three conditions PiP needs. */
        private var screenPresent = false

        init {
            observePlayerEvents()
            observePreferences()
            observeConnectivity()
            loadTitle()
            openSession(
                PlaybackResolveRequest(
                    itemId = UUID.fromString(sessionStore.itemId),
                    mediaSourceId = sessionStore.mediaSourceId,
                    startPositionTicks = sessionStore.startPositionTicks,
                ),
                playWhenReady = sessionStore.playWhenReady,
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
                val item = repository.getItem(sessionStore.itemId).getOrNull() ?: return@launch
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

        /**
         * Keeps the pickers honest about what can still be reached.
         *
         * A downloaded item's audio and subtitle pickers show two different lists depending on the
         * answer — the source's full set online, the file's own set offline — and the network can
         * perfectly well drop while the sheet is on screen. Collected rather than read once for
         * exactly that: the state flow replays its current value, so the first emission is the
         * initial answer and every later one re-derives the lists under the source that is playing.
         */
        private fun observeConnectivity() {
            viewModelScope.launch {
                connectionState.state.collect { state ->
                    isOnline = state.isOnline
                    val current = source ?: return@collect
                    _uiState.update { it.withTracks(current, isOnline) }
                }
            }
        }

        // ---- user actions -------------------------------------------------------------------------

        fun togglePlayPause() {
            if (playerHandle.snapshot().isPlaying) playerHandle.pause() else playerHandle.play()
        }

        fun seekTo(positionMs: Long) {
            playerHandle.seekTo(positionMs)
            positionTracker.onSeekTo(positionMs)
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
         * was asked for, so the switch falls through to a re-resolve and a visible reload. For a
         * downloaded item that re-resolve has to be told to bypass the file — see [needsServer].
         */
        fun selectAudioTrack(jellyfinIndex: Int) {
            val current = source ?: return
            if (playerHandle.selectAudioTrack(current, jellyfinIndex)) {
                source = current.withSelectedAudio(jellyfinIndex)
                _uiState.update { it.copy(selectedAudioIndex = jellyfinIndex) }
                return
            }
            if (current is LocalPlaybackMediaSource && !isOnline) return refuseLocalTrackChange(current)
            val remote = needsServer(current, localSource?.playsAudioLocally(jellyfinIndex) == true)
            reopenSession(
                current.asRequest(remote).copy(audioStreamIndex = jellyfinIndex),
                trackChangeMessage(current, remote),
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
            if (current is LocalPlaybackMediaSource && !isOnline) return refuseLocalTrackChange(current)
            val remote = needsServer(current, localSource?.playsSubtitleLocally(jellyfinIndex) == true)
            // -1 is the server's "no subtitles"; null would make it pick the item's default again.
            reopenSession(
                current.asRequest(remote).copy(subtitleStreamIndex = jellyfinIndex ?: SUBTITLES_OFF),
                trackChangeMessage(current, remote),
            )
        }

        /**
         * Whether the reopen that satisfies a track change has to bypass the download on disk.
         *
         * Three cases, and only the middle one is new:
         *
         * - **playing the local file:** the player has just refused the track, so the file cannot
         *   supply it whatever its stream list claims — only the server can, and by the time this is
         *   asked we already know there is one;
         * - **streaming an item that is also downloaded** (a previous forced-remote switch): a track
         *   the file *does* hold goes home. Reopening without the flag lets `PlaybackSourceResolver`
         *   pick the local copy again, which costs no bandwidth and survives the network dropping —
         *   the whole reason the download exists. Anything else keeps streaming;
         * - **an item that was never downloaded:** nothing to bypass; this is M5's path untouched.
         */
        private fun needsServer(
            current: PlaybackMediaSource,
            fileHoldsTrack: Boolean,
        ): Boolean =
            when {
                current is LocalPlaybackMediaSource -> true
                else -> localSource != null && !fileHoldsTrack
            }

        /**
         * What to tell the user about the restart they are about to see.
         *
         * Leaving a downloaded file to stream is a different thing from the server re-encoding a
         * stream it is already sending, and it is worth saying so: the item plays from the network
         * from now on. Both are said *after* the fact, in the snackbar, exactly as a quality change
         * is — the pickers themselves stay a plain list of languages.
         */
        private fun trackChangeMessage(
            current: PlaybackMediaSource,
            remote: Boolean,
        ): PlayerMessage =
            when {
                remote && current is LocalPlaybackMediaSource -> PlayerMessage.StreamingForTrackChange
                else -> PlayerMessage.RestartedForTrackChange
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
         * Since the pickers became connectivity-aware this is the **offline** answer only: online
         * the same tap is satisfied by streaming the item ([needsServer]). Offline the picker offers
         * nothing the file cannot play, so this should be unreachable from it, and it stays as the
         * honest answer for whatever slips past — a selection restored from a previous session, a
         * container whose streams disagree with the cached blob, or the network dropping between the
         * picker being drawn and the row being tapped.
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
            reopenSession(current.asRequest(forcedRemote).copy(maxStreamingBitrate = quality.maxStreamingBitrate))
        }

        /**
         * Applies a playback rate.
         *
         * Session-scoped: it is held in [PlayerUiState] and re-applied after every re-resolve
         * ([PlaybackSessionController.open]), and nothing writes it to disk (docs/PLAN.md,
         * "M9 Polish" → speed).
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
         * The position itself goes to [PlaybackPositionTracker] rather than into [uiState]; what
         * lands here is only what the *slow* state has to learn — that the container disagreed with
         * the server about the runtime, and whether playback is running.
         *
         * `internal` so a test can hand it a position directly; see [setScreenPresent].
         */
        internal fun onTick(snapshot: PlaybackSnapshot) {
            val decision = positionTracker.onTick(snapshot, segments, skipModes)
            _uiState.update {
                it.copy(
                    // The server's runtime and the container's can disagree; once the player knows,
                    // it wins.
                    durationMs = snapshot.durationMs.takeIf { ms -> ms > 0L } ?: it.durationMs,
                    isPlaying = snapshot.isPlaying,
                )
            }
            publishPipState()
            applySegmentDecision(decision)
        }

        private fun applySegmentDecision(decision: SegmentSkipDecision) {
            when (decision) {
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

        /** Opens [request] and publishes whatever [PlaybackSessionController] made of it. */
        private fun openSession(
            request: PlaybackResolveRequest,
            playWhenReady: Boolean,
            message: PlayerMessage? = null,
        ) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                publish(sessionController.open(request, playWhenReady), message)
            }
        }

        /**
         * Reopens the current item under new terms.
         *
         * One coroutine, not two: [PlaybackSessionController.reopen] stops the outgoing transcode
         * and asks for the next stream in sequence. Launching them independently — which is what
         * this used to do — let the new `PlaybackInfo` reach the server before the old encoder was
         * killed, which is the stranded ffmpeg process the ordering exists to prevent.
         */
        private fun reopenSession(
            request: PlaybackResolveRequest,
            message: PlayerMessage? = null,
        ) {
            val previous = source ?: return
            val snapshot = playerHandle.snapshot()
            forcedRemote = request.forceRemote
            setReportingActive(false)
            val resumed =
                request.copy(
                    startPositionTicks =
                        request.startPositionTicks.takeIf { it > 0L } ?: snapshot.positionTicks,
                )

            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val result =
                    sessionController.reopen(
                        previous = previous,
                        request = resumed,
                        playWhenReady = snapshot.isPlaying,
                    )
                publish(result, message)
            }
        }

        /**
         * Adopts the outcome of an open.
         *
         * [source] is assigned before anything suspends, so a player event arriving during the first
         * buffer is attributed to the stream that produced it rather than to the one it replaced.
         */
        private suspend fun publish(
            result: SessionOpenResult,
            message: PlayerMessage?,
        ) {
            when (result) {
                is SessionOpenResult.ResolveFailed -> onResolveFailed(result.error)

                SessionOpenResult.UnsupportedSource -> fail(UNSUPPORTED_SOURCE)

                is SessionOpenResult.Opened -> {
                    val resolved = result.source
                    source = resolved
                    (resolved as? LocalPlaybackMediaSource)?.let { localSource = it }
                    stopReported = false
                    // A re-resolve builds a fresh media item, which starts at 1×; the speed the user
                    // chose belongs to the session, not to the media item.
                    _uiState.value.speed
                        .takeIf { !it.isNormal }
                        ?.let { playerHandle.setPlaybackSpeed(it.rate) }
                    _videoPlayer.value = playerHandle.player
                    _uiState.update { it.withSource(resolved, isOnline, message) }
                    positionTracker.onSessionOpened(resolved.startPositionTicks.ticksToMillis())

                    reporter.reportStart(resolved, playerHandle.snapshot())
                    setReportingActive(true)
                    loadPlaybackExtras(resolved)
                }
            }
        }

        /**
         * A resolve that produced nothing to play.
         *
         * Normally that is the end of the session and the error goes on screen. There is one case
         * worth recovering from: the user asked for a track only the server has, and the server
         * turned out not to be there — the network died between the picker being drawn and the
         * request going out, or it was the *server* rather than the network, which
         * `ConnectionStateProvider` cannot know until a probe says so. Playback was fine a second
         * ago and the file is still on the device, so the item goes back to playing off it with the
         * same message a refused offline switch gets. The retry is not forced, so it can only
         * resolve locally, and a second failure falls through to the error.
         */
        private fun onResolveFailed(error: AppError) {
            val downloaded = localSource
            if (!forcedRemote || downloaded == null) return fail(error.toMessage())

            Timber.i("Streaming %s for a track change failed; returning to the file on disk", downloaded.itemId)
            reopenSession(downloaded.asRequest(forceRemote = false), PlayerMessage.TrackUnavailableOffline)
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
                    reopenSession(
                        current.asRequest(forcedRemote).copy(
                            startPositionTicks = decision.positionTicks,
                            enableDirectPlay = false,
                            enableDirectStream = false,
                        ),
                        PlayerMessage.SwitchedToTranscode,
                    )

                is FallbackDecision.LowerBitrate ->
                    reopenSession(
                        current.asRequest(forcedRemote).copy(
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
         * The same tick is what keeps [SavedStateHandle] current — see
         * [PlayerSessionStore.rememberLivePosition]. It rides this ticker rather than the UI one for
         * exactly the reason above: the UI poll stops when the screen goes away, which is precisely
         * the state a process death happens in.
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
                            snapshot = { playerHandle.snapshot().also(sessionStore::rememberLivePosition) },
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
         *
         * [PlayerHandle.release] is the audit's STAB-05 fix: `stop()` alone idles the player but
         * leaves its playback thread, loaders, allocator buffers and ffmpeg renderer alive for the
         * rest of the process. It is idempotent, and the media-session service's teardown reaches it
         * too — this is the path that covers a session whose service never started.
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
            playerHandle.release()
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
 *
 * @param forceRemote whether this re-negotiation must keep bypassing the download on disk. It is a
 *   parameter rather than something read off the source because the source cannot know: a streamed
 *   `RemotePlaybackMediaSource` looks the same whether it is an item nobody downloaded or the
 *   server's copy of one the user is deliberately streaming for a track the file lacks.
 */
private fun PlaybackMediaSource.asRequest(forceRemote: Boolean): PlaybackResolveRequest =
    PlaybackResolveRequest(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        maxStreamingBitrate = (this as? RemotePlaybackMediaSource)?.maxStreamingBitrate,
        audioStreamIndex = selectedAudioIndex,
        subtitleStreamIndex = selectedSubtitleIndex,
        forceRemote = forceRemote,
    )

private fun PlayerUiState.withSource(
    source: PlaybackMediaSource,
    online: Boolean,
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
        selectedAudioIndex = source.selectedAudioIndex,
        selectedSubtitleIndex = source.selectedSubtitleIndex,
        quality = PlaybackQuality.forBitrate((source as? RemotePlaybackMediaSource)?.maxStreamingBitrate),
        userMessage = message ?: userMessage,
    ).withTracks(source, online)

/**
 * The two picker lists, under the source that is playing and the connection there is.
 *
 * Separate from [withSource] because connectivity changes on its own timetable: a network that drops
 * while the audio sheet is open has to re-derive exactly these two fields and touch nothing else.
 */
private fun PlayerUiState.withTracks(
    source: PlaybackMediaSource,
    online: Boolean,
): PlayerUiState =
    copy(
        audioTracks = source.audioTracksFor(online),
        subtitleTracks = source.subtitleTracksFor(online),
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
