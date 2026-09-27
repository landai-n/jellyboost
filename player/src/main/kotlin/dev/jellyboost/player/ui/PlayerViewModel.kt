package dev.jellyboost.player.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.ui.error.AppErrorCopy
import dev.jellyboost.core.ui.error.toUiText
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.player.R
import dev.jellyboost.player.cast.CastMetadata
import dev.jellyboost.player.cast.CastMetadataHolder
import dev.jellyboost.player.cast.CastPlaybackCoordinator
import dev.jellyboost.player.cast.CastReceiverHold
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.cast.NoCastPlaybackCoordinator
import dev.jellyboost.player.fallback.DecoderFallbackHandler
import dev.jellyboost.player.fallback.FallbackDecision
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.PlaybackSpeed
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import dev.jellyboost.player.model.audioTracksFor
import dev.jellyboost.player.model.contradicts
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.model.subtitleTracksFor
import dev.jellyboost.player.model.ticksToMillis
import dev.jellyboost.player.pip.PipController
import dev.jellyboost.player.pip.PipState
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.resolve.playbackResolveRequest
import dev.jellyboost.player.segments.MediaSegment
import dev.jellyboost.player.segments.MediaSegmentLoader
import dev.jellyboost.player.segments.SegmentSkipDecision
import dev.jellyboost.player.session.AssSubtitleSupport
import dev.jellyboost.player.session.PlaybackSessionController
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.PlayerHandle
import dev.jellyboost.player.session.SessionOpenResult
import dev.jellyboost.player.session.togglePlayWhenReady
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayHostSnapshot
import dev.jellyboost.player.syncplay.SyncPlayLocalSession
import dev.jellyboost.player.syncplay.SyncPlayPlaybackHost
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.trickplay.TrickplayResolver
import dev.jellyboost.player.upnext.UpNextController
import dev.jellyboost.player.upnext.UpNextEpisode
import dev.jellyboost.player.upnext.UpNextResolver
import io.github.peerless2012.ass.media.AssHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sequences a playback session. Most controls here are *server* operations, not local ones:
 * switching audio while transcoding, capping the bitrate and decoder fallback all mean going back
 * to `PlaybackInfo` and reopening the stream at the current position.
 *
 * Load-bearing order: a re-resolve stops the *previous* transcode before starting the next one, or
 * every quality change strands an ffmpeg process on the server ([PlaybackSessionController] owns
 * that order, in one coroutine). The stop report goes out on a detached scope, because
 * `viewModelScope` is already cancelled by the time [onCleared] runs.
 */
@HiltViewModel
@Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongParameterList",
)
internal class PlayerViewModel
    @Inject
    internal constructor(
        private val repository: JellyfinRepository,
        private val sessionController: PlaybackSessionController,
        private val playerHandle: PlayerHandle,
        private val reporter: PlaybackReporter,
        private val fallback: DecoderFallbackHandler,
        private val trickplayResolver: TrickplayResolver,
        private val segmentLoader: MediaSegmentLoader,
        private val upNextResolver: UpNextResolver,
        private val preferences: AppPreferences,
        assSubtitles: AssSubtitleSupport,
        private val pipController: PipController,
        private val connectionState: ConnectionStateProvider,
        syncPlayController: SyncPlayController,
        syncPlayLocalSession: SyncPlayLocalSession,
        savedStateHandle: SavedStateHandle,
        /** The holder, not `CastSessionCoordinator`: keeps every `com.google.android.gms` type out. */
        castStatus: CastStatusHolder = CastStatusHolder(),
        /**
         * Written in [loadTitleAndArtwork], read by `CastPlayerHandle.prepare`: a `PlaybackInfo`
         * response names nothing, so without it a cast session shows an unlabelled stream.
         */
        private val castMetadata: CastMetadataHolder = CastMetadataHolder(),
        /** The interface, not `CastSessionCoordinator`, so a test can build this without a Cast stack. */
        castCoordinator: CastPlaybackCoordinator = NoCastPlaybackCoordinator,
    ) : ViewModel(),
        SyncPlayPlaybackHost {
        private val sessionStore = PlayerSessionStore(savedStateHandle)

        private val syncPlay = PlayerSyncPlayBridge(syncPlayController, syncPlayLocalSession, host = this)

        private val cast =
            PlayerCastBridge(
                status = castStatus,
                coordinator = castCoordinator,
                // A session whose stop is already reported (it ended, or the receiver dropped it) has
                // nothing left for the coordinator to report when this screen goes.
                currentSource = { session?.takeUnless { it.stopReported }?.source },
                currentReading = { session?.takeUnless { it.stopReported }?.lastValidReading },
                onStarted = ::onCastStarted,
                onEnded = ::onCastEnded,
                onItemLost = ::onCastItemLost,
            )

        private val positionTracker = PlaybackPositionTracker()

        /** Reset by [publish], so a dismissal belongs to the episode it was made on and no other. */
        private val upNext = UpNextController()

        // The read-only half is `internal`, not public; ktlint's rule only recognises the public idiom.
        @Suppress("ktlint:standard:backing-property-naming")
        private val _uiState = MutableStateFlow(PlayerUiState())

        /** The slow-changing state [PlayerScreen] draws; the position is deliberately not in it. */
        internal val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

        /** Separate from [uiState] so a twice-a-second number cannot invalidate the rest of the screen. */
        internal val position: StateFlow<PlaybackPosition> = positionTracker.position

        @Suppress("ktlint:standard:backing-property-naming")
        private val _videoPlayer = MutableStateFlow<Player?>(null)

        /** Not in [uiState]: a `Player` inside a data class would give it no meaningful equality. */
        internal val videoPlayer: StateFlow<Player?> = _videoPlayer.asStateFlow()

        /**
         * libass's overlay handle while it is driving, `null` otherwise. Not in [uiState] for the same
         * reason [videoPlayer] is not, and separate from it because the two are read by different views:
         * the player goes on the surface, this goes inside the surface's `SubtitleView`.
         */
        internal val assSubtitleHandler: StateFlow<AssHandler?> = assSubtitles.handler

        internal val pipState: StateFlow<PipState> = pipController.state

        /**
         * Boxed so a new session is *one* assignment ([publish]) and a forgotten field is a compile
         * error rather than a stale value from the film before.
         */
        private var session: ActiveSession? = null

        /** Read-only on purpose: everything that *changes* it goes through [session]. */
        private val source: PlaybackMediaSource? get() = session?.source

        /**
         * Awaited only by a *cast* open ([openSession]): a receiver loaded before the label existed
         * shows an unnamed stream until the film is opened again. Cannot live in [ActiveSession] —
         * it is started before [publish] mints the session.
         */
        private var metadataLoad: Job? = null

        /**
         * Decides what the pickers *offer*: the source's full track list while a server can stream a
         * missing track, only what the downloaded file holds when it cannot. Not in [ActiveSession]:
         * the collector writing it runs before, between and after sessions.
         */
        private var isOnline = true

        private var reportingJob: Job? = null
        private var uiTickerJob: Job? = null

        /**
         * A session operation waits for its predecessor instead of racing it: two concurrent
         * resolve → prepare → publish sequences can leave [source] describing a stream the player is
         * not decoding.
         */
        private var openJob: Job? = null

        /**
         * `true` between an up-next tap and the episode it asks for being open: [onEnded] publishes
         * `hasEnded` and `PlayerScreen` turns that into `onBack()` on the next frame, so a late tap
         * would pop the route out from under the episode it just asked for.
         */
        private var advancing = false

        private var skipModes: Map<MediaSegmentKind, SegmentSkipMode> = emptyMap()

        private var pipEnabled = false

        private var screenPresent = false

        /**
         * Read fresh at every open rather than captured: a session can start or end at any point,
         * and the next re-negotiation has to be for whichever player will actually decode it.
         */
        private val isCasting: Boolean get() = cast.isCasting

        /**
         * Always re-reads [session]: a caller that has already suspended cannot write a whole stale
         * session back over a newer one on its way to flipping one boolean.
         */
        private fun updateSession(block: (ActiveSession) -> ActiveSession) {
            session = session?.let(block)
        }

        /**
         * Every reading this screen takes of its own player passes through here, so the session always
         * knows the last position it can vouch for ([ActiveSession.lastValidReading]). A transfer home
         * resolves its position from it synchronously ([onCastEnded]), before the next tick can read the
         * idle local player.
         */
        private fun rememberReading(reading: PlaybackSnapshot) {
            if (vetted(reading).isValid) updateSession { it.copy(lastValidReading = reading) }
        }

        /**
         * [reading], or the same reading marked **invalid** when it is a zero this session cannot vouch
         * for ([contradicts] its last valid reading): a torn-down receiver, or the idle local player
         * routing has already fallen back to. Every reading headed for a report, the scrubber or
         * [ActiveSession.lastValidReading] passes through here; a seek to 0 ([seekTo]) is what makes a
         * zero believable.
         */
        private fun vetted(reading: PlaybackSnapshot): PlaybackSnapshot =
            if (reading.contradicts(session?.lastValidReading)) reading.copy(isValid = false) else reading

        /**
         * The player's reading if it is valid (and [vetted]), else the last valid one this session took,
         * else the invalid reading itself — which a report then carries no position for.
         */
        private fun vouchedReading(): PlaybackSnapshot {
            val reading = vetted(playerHandle.snapshot())
            return reading.takeIf { it.isValid } ?: session?.lastValidReading ?: reading
        }

        init {
            observePlayerEvents()
            observePreferences()
            observeConnectivity()
            observeSyncPlay()
            observeCast()
            loadTitleAndArtwork()
            val held =
                runCatchingUnlessCancelled { UUID.fromString(sessionStore.itemId) }
                    .getOrNull()
                    ?.let(cast::heldSourceFor)
            if (held != null) {
                adoptCastSession(held)
            } else {
                openSession(
                    playbackResolveRequest(
                        itemId = sessionStore.itemId,
                        mediaSourceId = sessionStore.mediaSourceId,
                        startPositionTicks = sessionStore.startPositionTicks,
                    ).copy(castTarget = isCasting),
                    // In a group, **paused**: the group decides when playback starts, and a member that
                    // started on its own would be out of sync from the first frame.
                    playWhenReady = sessionStore.playWhenReady && !syncPlay.isInGroup,
                )
            }
        }

        /**
         * Fire and forget — cosmetic, and must never delay the first frame. A *cast* open awaits it
         * ([openSession]) instead: a receiver is loaded once, so metadata arriving after the load
         * could only be applied by loading the film again.
         *
         * @param itemId whatever the group just moved to on [loadItem], not the navigation argument.
         */
        private fun loadTitleAndArtwork(itemId: String = sessionStore.itemId) {
            metadataLoad =
                viewModelScope.launch {
                    val item = repository.getItem(itemId).getOrNull() ?: return@launch
                    val label =
                        listOfNotNull(item.displayTitle, item.displaySubtitle)
                            .joinToString(PLAYER_LABEL_SEPARATOR)
                    val artwork = item.backdropImageUrl ?: item.thumbImageUrl ?: item.primaryImageUrl
                    castMetadata.publish(
                        mediaId = itemId,
                        metadata =
                            CastMetadata(
                                title = item.displayTitle,
                                subtitle = item.displaySubtitle,
                                posterUrl = artwork,
                            ),
                    )
                    _uiState.update { it.copy(title = label, artworkUrl = artwork) }
                }
        }

        /** Collected, not read once: the settings screen can change these under a playing item. */
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
            // Its own collection rather than a fourth arm of the combine above: these two reach the
            // UI state and nothing else, while those three are fields the player logic reads. Folding
            // them together would rebuild the state object every time a skip mode changed.
            viewModelScope.launch {
                combine(
                    preferences.subtitleTextSize,
                    preferences.subtitleBackground,
                    ::Pair,
                ).collect { (size, background) ->
                    _uiState.update { it.copy(subtitleTextSize = size, subtitleBackground = background) }
                }
            }
        }

        /**
         * A downloaded item's pickers show the source's full set online and the file's own set
         * offline, and the network can drop while the sheet is on screen.
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

        /** Two collections: the state is conflated and drawn, the messages are one-shot. */
        private fun observeSyncPlay() {
            viewModelScope.launch {
                syncPlay.states.collect { group -> _uiState.update { it.copy(syncPlay = group) } }
            }
            viewModelScope.launch {
                syncPlay.messages.collect { message -> _uiState.update { it.copy(userMessage = message) } }
            }
            viewModelScope.launch {
                syncPlay.membership.collect { syncPlay.syncServerSession(source, playerHandle.snapshot()) }
            }
        }

        /**
         * The *edges* deliberately do not arrive here: a session starting or ending carries a
         * position that can only be read at the instant playback is routed, and a collector by
         * definition runs after it — [PlayerCastBridge]'s host callbacks push those in.
         */
        private fun observeCast() {
            viewModelScope.launch {
                cast.states.collect { receiver ->
                    _uiState.update { it.copy(cast = receiver) }
                    publishPipState()
                    publishSpeedSupport()
                }
            }
        }

        // ---- cast transfers -----------------------------------------------------------------------

        /**
         * Reattaches to the item the receiver is already playing instead of opening it again: **no
         * `PlaybackInfo`, no `prepare`, no start report**. Opening it again stopped the receiver,
         * rebuffered it and started a second server transcode, for a film that was playing fine.
         *
         * The held source is the receiver's own, play session id and all, so this screen's ticker
         * carries on reporting the session the coordinator's ticker was reporting; attaching it as
         * [PlayerCastBridge.castSource] is what tells the coordinator to stop its ticker without a stop
         * report. The track pickers offer what that source was negotiated with, and the position comes
         * from the receiver's live reading — the navigation argument's start position means nothing to
         * a film already playing.
         *
         * Deliberately not claimed through the video/music handover: nothing is prepared on this
         * device's player, so there is nothing for a music queue to be stopped for.
         */
        private fun adoptCastSession(held: CastReceiverHold) {
            val resolved = held.source
            Timber.i("Reattaching to %s, already playing on the receiver", resolved.itemId)
            session =
                ActiveSession(
                    source = resolved,
                    // Applied when this source was opened; the receiver is playing them now.
                    pendingAudioIndex = null,
                    pendingSubtitleApply = false,
                    // Unknowable from the source, and moot while casting (`castTarget` always streams); a
                    // later cast→local transfer may therefore pick the copy on disk again.
                    localSource = null,
                    forcedRemote = false,
                    recoverySource = null,
                    stopReported = false,
                    segments = emptyList(),
                    upNext = null,
                    castItemLostAt = null,
                    // The coordinator's, not the source's start: that is where the film was first sent,
                    // and a session ending before this screen reads the receiver would come home there.
                    lastValidReading = held.lastValidReading,
                )
            _videoPlayer.value = playerHandle.player
            val reading = held.reading.takeIf { it.isValid }
            val known = held.lastValidReading
            _uiState.update { previous ->
                val opened = previous.withSource(resolved, isOnline, message = null, buffering = held.isBuffering)
                opened.copy(
                    isPlaying = reading?.isPlaying == true,
                    // The server's runtime and the receiver's can disagree; the player's wins, as in `onTick`.
                    durationMs = known?.durationMs?.takeIf { it > 0L } ?: opened.durationMs,
                )
            }
            // The source's own start only when nobody has ever read a valid position for it.
            positionTracker.onSessionOpened(known?.positionMs ?: resolved.startPositionTicks.ticksToMillis())
            upNext.reset()
            setReportingActive(true)
            loadPlaybackExtras(resolved)
            syncPlay.attach()
            cast.attach()
            publishSpeedSupport()
            publishPipState()
        }

        /**
         * Order is load-bearing: the outgoing session is stopped and reported at [from] — killing its
         * transcode — before the item is re-negotiated, in one coroutine so the new `PlaybackInfo`
         * cannot overtake the stop. [from] is the coordinator's snapshot: [playerHandle] is by now
         * the receiver's, and it has never played anything.
         */
        private fun onCastStarted(
            deviceName: String?,
            from: PlaybackSnapshot,
        ) {
            val active = session ?: return
            val current = active.source
            val leftGroup = syncPlay.isInGroup
            if (leftGroup) {
                Timber.i("A receiver connected while in a SyncPlay group; leaving the group")
                syncPlay.leaveGroup()
            }
            Timber.i("Moving %s to %s at %d ms", current.itemId, deviceName ?: "a receiver", from.positionMs)
            openSession(
                current.asRequest(active.forcedRemote, castTarget = true).copy(startPositionTicks = from.positionTicks),
                playWhenReady = from.isPlaying,
                message = if (leftGroup) PlayerMessage.CastLeftSyncPlayGroup else PlayerMessage.CastTransferred,
                endingAt = from,
            )
        }

        /**
         * The film comes home **paused**: a disconnect is not a request to keep watching out loud on
         * the phone. Only ever reached with this screen attached — a session that ends after it has
         * gone is the coordinator's to close (one stop report per source).
         *
         * [at] is usually **invalid**: a session ended from the Cast notification ends with the receiver
         * already gone. The film then comes home — and its stop is reported — at the session's last valid
         * reading, resolved here, synchronously: routing is already local, and the next tick would record
         * the idle local player's zero. The source's start position is the fallback only for a session
         * that never produced a valid reading at all; its stop then carries no position.
         */
        private fun onCastEnded(at: PlaybackSnapshot) {
            val active = session ?: return
            val current = active.source
            // Vetted too: a torn-down receiver still claims our item — at zero — and counts as valid.
            val known = vetted(at).takeIf { it.isValid } ?: active.lastValidReading
            val resumeTicks = known?.positionTicks ?: current.startPositionTicks
            Timber.i("Bringing %s back to this device at %d ticks", current.itemId, resumeTicks)
            openSession(
                current.asRequest(active.forcedRemote, castTarget = false).copy(startPositionTicks = resumeTicks),
                playWhenReady = false,
                endingAt = known ?: at,
            )
        }

        /**
         * The receiver dropped the item while the session stayed up. Deliberately **not** reloaded:
         * whoever pressed Stop on the television meant it. The session is closed at [lastHeld] (its
         * stop report kills the encoder the receiver no longer reads) and the screen is left paused
         * there, so Play sends the film back ([resendLostCastItem]).
         *
         * Ignored while an open is in flight — that open replaces what the receiver holds anyway — and
         * for a session already closed, which is the natural end's case.
         */
        private fun onCastItemLost(reportedLastHeld: PlaybackSnapshot) {
            val active = session ?: return
            if (active.stopReported || active.castItemLostAt != null) return
            if (openJob?.isActive == true) return
            // The handle's "last held" is any valid reading, a torn-down zero included.
            val lastHeld = vetted(reportedLastHeld).takeIf { it.isValid } ?: active.lastValidReading ?: reportedLastHeld
            Timber.i(
                "The receiver stopped %s at %d ms; waiting for the user",
                active.source.itemId,
                lastHeld.positionMs,
            )
            updateSession { it.copy(castItemLostAt = lastHeld) }
            positionTracker.onSeekTo(lastHeld.positionMs)
            _uiState.update {
                it.copy(isPlaying = false, isBuffering = false, userMessage = PlayerMessage.CastPlaybackStopped)
            }
            publishPipState()
            viewModelScope.launch { endCurrentSource(lastHeld) }
        }

        /**
         * From the position the screen shows — where the receiver stopped, or wherever the user has
         * since dragged the scrubber — and playing, since this is the answer to a tap on Play.
         */
        private fun resendLostCastItem(active: ActiveSession) {
            val resumeMs = positionTracker.position.value.positionMs
            Timber.i("Sending %s back to the receiver at %d ms", active.source.itemId, resumeMs)
            openSession(
                active.source
                    .asRequest(active.forcedRemote, castTarget = isCasting)
                    .copy(startPositionTicks = resumeMs.millisToTicks()),
                playWhenReady = true,
            )
        }

        // ---- SyncPlay host ------------------------------------------------------------------------

        /**
         * Opens what the group is watching, **paused**: the group decides when playback starts.
         *
         * Runs on `viewModelScope`'s context rather than the caller's — the controller drives this
         * from a background scope and `PlayerHandle` is main-thread-only.
         */
        override suspend fun loadItem(
            itemId: UUID,
            startPositionTicks: Long,
        ): Boolean =
            withContext(viewModelScope.coroutineContext) {
                replaceItem(
                    itemId = itemId,
                    startPositionTicks = startPositionTicks,
                    playWhenReady = false,
                    // Nobody has picked a quality for the item the group just moved to.
                    autoBitrate = true,
                )
            }

        /**
         * The outgoing item is stopped and reported first, so a queue that moves on cannot strand its
         * transcode on the server.
         *
         * Known gap: [PlayerSessionStore.itemId] is the navigation argument and this cannot rewrite
         * it, so a process death after a swap restores the *original* episode.
         */
        private suspend fun replaceItem(
            itemId: UUID,
            startPositionTicks: Long,
            playWhenReady: Boolean,
            autoBitrate: Boolean,
            maxStreamingBitrate: Int? = null,
        ): Boolean {
            endCurrentSource()
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            loadTitleAndArtwork(itemId.toString())
            val result =
                sessionController.open(
                    PlaybackResolveRequest(
                        itemId = itemId,
                        startPositionTicks = startPositionTicks,
                        maxStreamingBitrate = maxStreamingBitrate,
                        castTarget = isCasting,
                        autoBitrate = autoBitrate,
                    ),
                    playWhenReady = playWhenReady,
                )
            publish(result, message = null)
            return result is SessionOpenResult.Opened
        }

        override fun snapshot(): SyncPlayHostSnapshot {
            val playback = playerHandle.snapshot()
            return SyncPlayHostSnapshot(
                itemId = source?.itemId,
                positionTicks = playback.positionTicks,
                isPlaying = playback.isPlaying,
            )
        }

        /**
         * Closes the outgoing session before something else takes its place; the stop report is what
         * kills the encoder. Idempotent per source ([ActiveSession.stopReported]).
         *
         * @param at where to report the stop from. The default asks the player — falling back to the
         *   session's last valid reading when the player's is not ([vouchedReading]) — which is wrong for
         *   a transfer: by then [playerHandle] is the *other* player.
         */
        private suspend fun endCurrentSource(at: PlaybackSnapshot = vouchedReading()) {
            val active = session ?: return
            setReportingActive(false)
            if (active.stopReported) return
            updateSession { it.copy(stopReported = true) }
            reporter.reportStop(active.source, at)
        }

        // ---- user actions -------------------------------------------------------------------------

        /**
         * Reverses the player's **intent** ([PlayerHandle.playWhenReady]), never a snapshot: a player
         * buffering toward play is not playing, yet a tap on it means "pause", and a receiver's
         * snapshot is invalid (all zeroes) for seconds after every load — reading `isPlaying` there
         * turned every pause into a play. The one exception, a receiver settled paused under a stale
         * intent, is [togglePlayWhenReady]'s — the casting bar applies the same rule.
         */
        internal fun togglePlayPause() {
            if (syncPlay.isInGroup) return syncPlay.requestPlayPause()
            session?.takeIf { it.castItemLostAt != null }?.let { return resendLostCastItem(it) }
            playerHandle.togglePlayWhenReady()
        }

        /**
         * In a group nothing local happens, including the optimistic position publish: the seek bar
         * follows the server's command, and springing it forward would show a position this player
         * is not at.
         */
        internal fun seekTo(positionMs: Long) {
            if (syncPlay.isInGroup) return syncPlay.requestSeek(positionMs)
            // Nothing is loaded on the receiver: the position is remembered for the resend instead.
            if (session?.castItemLostAt == null) playerHandle.seekTo(positionMs)
            positionTracker.onSeekTo(positionMs)
            // The user's own seek is the one way a zero becomes believable ([vetted]).
            updateSession { it.copy(lastValidReading = it.lastValidReading?.copy(positionMs = positionMs)) }
        }

        /**
         * Measured from the last *valid* reading. A receiver's snapshot is invalid — position and
         * duration both zero — for seconds after every load, and clamping to that duration sent every
         * skip to 0:00. An unknown duration leaves the upper end unclamped rather than clamped to zero.
         */
        internal fun seekBy(deltaMs: Long) {
            val snapshot = vetted(playerHandle.snapshot()).takeIf { it.isValid }
            val fromMs = snapshot?.positionMs ?: positionTracker.position.value.positionMs
            val durationMs = snapshot?.durationMs?.takeIf { it > 0L } ?: _uiState.value.durationMs
            val target = (fromMs + deltaMs).coerceAtLeast(0L)
            seekTo(if (durationMs > 0L) target.coerceAtMost(durationMs) else target)
        }

        /**
         * Tried locally first: while transcoding the server only ever sent the one audio track it
         * was asked for, so the switch falls through to a re-resolve and a visible reload. The
         * exception is [goesHome] — an in-stream switch that happened to succeed would strand a
         * forced-remote session on the network when its file can serve the selection.
         */
        internal fun selectAudioTrack(jellyfinIndex: Int) {
            // A tap that beats the first `TracksChanged` outranks the open's own choice.
            updateSession { it.copy(pendingAudioIndex = null) }
            val active = session ?: return
            val current = active.source
            val home = active.goesHome(audioIndex = jellyfinIndex, subtitleIndex = current.selectedSubtitleIndex)
            if (!home && playerHandle.selectAudioTrack(current, jellyfinIndex)) {
                updateSession { it.copy(source = current.withSelectedAudio(jellyfinIndex)) }
                _uiState.update { it.copy(selectedAudioIndex = jellyfinIndex) }
                return
            }
            if (current is LocalPlaybackMediaSource && !isOnline) return refuseLocalTrackChange(current)
            val remote = active.needsServer(home)
            reopenSession(
                current.asRequest(remote, isCasting).copy(audioStreamIndex = jellyfinIndex),
                trackChangeMessage(current, remote),
            )
        }

        /** Switches subtitle track; [jellyfinIndex] `null` turns subtitles off. */
        internal fun selectSubtitleTrack(jellyfinIndex: Int?) {
            // As in [selectAudioTrack]: the open's pending choice is spent either way.
            updateSession { it.copy(pendingSubtitleApply = false) }
            val active = session ?: return
            val current = active.source
            val home = active.goesHome(audioIndex = current.selectedAudioIndex, subtitleIndex = jellyfinIndex)
            if (!home && playerHandle.selectSubtitleTrack(current, jellyfinIndex)) {
                updateSession { it.copy(source = current.withSelectedSubtitle(jellyfinIndex)) }
                _uiState.update { it.copy(selectedSubtitleIndex = jellyfinIndex) }
                return
            }
            if (current is LocalPlaybackMediaSource && !isOnline) return refuseLocalTrackChange(current)
            val remote = active.needsServer(home)
            // -1 is the server's "no subtitles"; null would make it pick the item's default again.
            reopenSession(
                current.asRequest(remote, isCasting).copy(subtitleStreamIndex = jellyfinIndex ?: SUBTITLES_OFF),
                trackChangeMessage(current, remote),
            )
        }

        /**
         * Asked *before* the player is offered the switch: a forced-remote session is not necessarily
         * a transcode, and when the server direct-plays the original file the in-stream switch
         * succeeds and strands the item on the network for good.
         *
         * Both selections are weighed, not just the one being changed: going home has to take the
         * whole session with it.
         */
        private fun ActiveSession.goesHome(
            audioIndex: Int?,
            subtitleIndex: Int?,
        ): Boolean =
            forcedRemote &&
                source !is LocalPlaybackMediaSource &&
                localSource?.plays(audioIndex, subtitleIndex) == true

        /**
         * Whether the reopen that satisfies a track change has to bypass the download on disk.
         * Playing the local file: the player has just refused the track, so only the server can
         * supply it. Streaming an item that is also downloaded: dropping the flag lets
         * `PlaybackSourceResolver` pick the local copy again once the selection goes home.
         */
        private fun ActiveSession.needsServer(goesHome: Boolean): Boolean =
            when {
                source is LocalPlaybackMediaSource -> true
                else -> localSource != null && !goesHome
            }

        private fun trackChangeMessage(
            current: PlaybackMediaSource,
            remote: Boolean,
        ): PlayerMessage =
            when {
                remote && current is LocalPlaybackMediaSource -> PlayerMessage.StreamingForTrackChange
                else -> PlayerMessage.RestartedForTrackChange
            }

        /**
         * Re-resolving a [LocalPlaybackMediaSource] runs `LocalPlaybackResolver` over the very same
         * file and hands back the very same tracks, so the restart buys nothing. Offline answer
         * only: online the same tap is satisfied by streaming the item ([needsServer]).
         */
        private fun refuseLocalTrackChange(current: LocalPlaybackMediaSource) {
            Timber.i("Downloaded file %s cannot supply that track; leaving playback alone", current.itemId)
            _uiState.update { it.copy(userMessage = PlayerMessage.TrackUnavailableOffline) }
        }

        /**
         * The no-op guard compares *picker entries*, not bitrates: Auto resolves to a measured cap,
         * so comparing numbers would make a stream measured at 8 Mbps indistinguishable from a
         * hand-picked Medium. [PlaybackQuality.AUTO] is sent with a `null` cap and
         * `autoBitrate = true` so the tap never waits on a measurement round trip.
         */
        internal fun selectQuality(quality: PlaybackQuality) {
            val active = session ?: return
            val current = active.source as? RemotePlaybackMediaSource ?: return
            if (quality == qualityOf(current)) return
            _uiState.update { it.copy(quality = quality) }
            reopenSession(
                current
                    .asRequest(active.forcedRemote, isCasting)
                    .copy(
                        maxStreamingBitrate = quality.maxStreamingBitrate,
                        autoBitrate = quality == PlaybackQuality.AUTO,
                    ),
            )
        }

        /**
         * Session-scoped: held in [PlayerUiState] and re-applied after every re-resolve
         * ([PlaybackSessionController.open]); nothing writes it to disk.
         */
        internal fun selectSpeed(speed: PlaybackSpeed) {
            if (syncPlay.isInGroup) {
                // SyncPlay has no per-member rate; the control is hidden, so this is a stale-tap backstop.
                Timber.d("Ignoring a playback rate change while in a SyncPlay group")
                return
            }
            if (speed == _uiState.value.speed) return
            playerHandle.setPlaybackSpeed(speed.rate)
            _uiState.update { it.copy(speed = speed) }
        }

        // ---- group actions ------------------------------------------------------------------------

        internal fun leaveGroup() = syncPlay.leaveGroup()

        internal fun setGroupShuffle(shuffled: Boolean) = syncPlay.setShuffle(shuffled)

        internal fun setGroupRepeat(mode: SyncPlayRepeatMode) = syncPlay.setRepeat(mode)

        /** A no-op when nothing is offered, so a stale tap cannot seek somewhere arbitrary. */
        internal fun skipCurrentSegment() {
            val segment = _uiState.value.skippableSegment ?: return
            Timber.d("Skipping %s at %d ms", segment.kind, segment.startMs)
            seekTo(segment.endMs)
            _uiState.update { it.copy(skippableSegment = null) }
        }

        /**
         * Deliberately not a navigation event: popping and re-pushing the player would tear down the
         * ExoPlayer, the media session and the surface between two episodes. The quality terms carry
         * over; the track choices do not, since another episode's stream list may not support them.
         */
        internal fun playNextEpisode() {
            if (syncPlay.isInGroup) return
            val next = session?.upNext ?: return
            val itemId =
                runCatchingUnlessCancelled { UUID.fromString(next.itemId) }.getOrNull() ?: run {
                    Timber.w("Ignoring an up-next tap for a malformed item id")
                    return
                }
            val quality = _uiState.value.quality
            Timber.i("Playing the next episode %s in the current session", itemId)
            // Armed before anything suspends: `Ended` can arrive during the very first suspension.
            advancing = true
            _uiState.update { it.copy(upNext = null) }
            viewModelScope.launch {
                try {
                    replaceItem(
                        itemId = itemId,
                        startPositionTicks = 0L,
                        playWhenReady = true,
                        autoBitrate = quality == PlaybackQuality.AUTO,
                        maxStreamingBitrate = quality.maxStreamingBitrate,
                    )
                } finally {
                    // Cleared on failure too: a standing flag would suppress the *next* natural end.
                    advancing = false
                }
            }
        }

        /**
         * A dismissal also stops the natural end from advancing past this episode — [onEnded] reads
         * the same flag.
         */
        internal fun dismissUpNext() {
            upNext.dismiss()
            _uiState.update { if (it.upNext == null) it else it.copy(upNext = null) }
        }

        /**
         * The cosmetic poll only; progress *reporting* is a separate ticker that keeps running while
         * the screen is away. The first reading is taken immediately rather than after a tick, so
         * returning from the background shows the live position rather than the one it was left at.
         */
        internal fun setScreenVisible(visible: Boolean) {
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
         * `internal` so a test can drive PiP arming without the never-finishing poll around it,
         * which `runTest` cannot drain.
         */
        internal fun setScreenPresent(present: Boolean) {
            screenPresent = present
            publishPipState()
        }

        /**
         * The position itself goes to [PlaybackPositionTracker], not into [uiState]; only what the
         * *slow* state has to learn lands here. `internal` so a test can hand it a position.
         *
         * An invalid reading is dropped whole: it is a receiver not (yet) holding this item, and its
         * zeroes would flip the play icon, spring the scrubber to 0:00 and feed the segment and
         * up-next decisions a position that belongs to nothing.
         */
        internal fun onTick(reading: PlaybackSnapshot) {
            val snapshot = vetted(reading)
            if (!snapshot.isValid) return
            rememberReading(snapshot)
            val decision = positionTracker.onTick(snapshot, session?.segments.orEmpty(), skipModes)
            _uiState.update {
                it.copy(
                    // The server's runtime and the container's can disagree; the player's wins.
                    durationMs = snapshot.durationMs.takeIf { ms -> ms > 0L } ?: it.durationMs,
                    isPlaying = snapshot.isPlaying,
                )
            }
            publishPipState()
            applySegmentDecision(decision)
            applyUpNextDecision(snapshot)
        }

        /**
         * Runs *after* [applySegmentDecision] on purpose: that method reads the card's state to
         * decide whether an outro skip button would be redundant, and wants the reading the user can
         * currently see. The `_uiState` write is diffed because this runs twice a second.
         */
        private fun applyUpNextDecision(snapshot: PlaybackSnapshot) {
            val active = session ?: return
            val episode = active.upNext
            if (syncPlay.isInGroup || episode == null) return

            val show =
                upNext.shouldShow(
                    positionMs = snapshot.positionMs,
                    durationMs = _uiState.value.durationMs,
                    outro = active.segments.firstOrNull { it.kind == MediaSegmentKind.OUTRO },
                    hasNext = true,
                )
            _uiState.update {
                when {
                    show && it.upNext == null -> it.copy(upNext = UpNextState(episode))
                    !show && it.upNext != null -> it.copy(upNext = null)
                    else -> it
                }
            }
        }

        /**
         * In a group an auto-skip becomes an *offer* (nothing may move this player on its own), and
         * an outro *offer* is suppressed while the up-next card supersedes it — but an outro
         * auto-skip still seeks.
         */
        private fun applySegmentDecision(decision: SegmentSkipDecision) {
            when (decision) {
                SegmentSkipDecision.None ->
                    _uiState.update { if (it.skippableSegment == null) it else it.copy(skippableSegment = null) }

                is SegmentSkipDecision.Offer ->
                    if (supersededByUpNext(decision.segment)) {
                        _uiState.update { if (it.skippableSegment == null) it else it.copy(skippableSegment = null) }
                    } else {
                        _uiState.update { it.copy(skippableSegment = decision.segment) }
                    }

                is SegmentSkipDecision.AutoSkip ->
                    if (syncPlay.isInGroup) {
                        _uiState.update { it.copy(skippableSegment = decision.segment) }
                    } else {
                        Timber.i("Auto-skipping %s to %d ms", decision.segment.kind, decision.segment.endMs)
                        seekTo(decision.segment.endMs)
                        _uiState.update { it.copy(skippableSegment = null) }
                    }
            }
        }

        private fun supersededByUpNext(segment: MediaSegment): Boolean =
            segment.kind == MediaSegmentKind.OUTRO && _uiState.value.upNext != null

        /**
         * The cast condition is the non-obvious one: while casting there is no video surface to
         * shrink (`CastPlayerHandle.player` is permanently `null`), so leaving the app must simply
         * leave the screen rather than ask for a floating window over nothing.
         */
        private fun publishPipState() {
            val state = _uiState.value
            pipController.setPlayerState(
                active = pipEnabled && screenPresent && state.isPlaying && state.isReady && !state.cast.isCasting,
                videoWidth = state.videoWidth,
                videoHeight = state.videoHeight,
            )
        }

        /**
         * Also asked when a session becomes ready: a `CastPlayer` only learns which commands its
         * receiver supports once one has actually loaded something.
         */
        private fun publishSpeedSupport() {
            val supported = playerHandle.supportsPlaybackSpeed
            _uiState.update { it.copy(canSetSpeed = supported) }
        }

        internal fun consumeMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        // ---- session ------------------------------------------------------------------------------

        /**
         * @param endingAt closes the session playing now, at that position, **before** the next is
         *   negotiated (the transfer case). Both halves share one coroutine, or the new
         *   `PlaybackInfo` can overtake the stop that kills the outgoing encoder.
         */
        private fun openSession(
            request: PlaybackResolveRequest,
            playWhenReady: Boolean,
            message: PlayerMessage? = null,
            endingAt: PlaybackSnapshot? = null,
        ) {
            // Whatever an earlier re-negotiation stashed belongs to a stream this open replaces.
            updateSession { it.copy(recoverySource = null) }
            launchSessionOp {
                endingAt?.let { endCurrentSource(it) }
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                // A receiver is loaded exactly once, with whatever it is told at that instant, so a
                // cast open waits for the fetch that names the film. Bounded by the repository's own
                // ceiling (`JellyfinRepository.ONLINE_CALL_TIMEOUT_MS`).
                if (request.castTarget) metadataLoad?.join()
                publish(sessionController.open(request, playWhenReady), message)
            }
        }

        /**
         * One coroutine, not two: [PlaybackSessionController.reopen] stops the outgoing transcode and
         * asks for the next stream in sequence, or the new `PlaybackInfo` reaches the server before
         * the old encoder is killed and strands an ffmpeg process.
         */
        private fun reopenSession(
            request: PlaybackResolveRequest,
            message: PlayerMessage? = null,
        ) {
            val previous = session?.source ?: return
            val snapshot = playerHandle.snapshot()
            // A receiver's invalid reading is at zero; a re-negotiation must not restart the film there.
            val resumeTicks = vouchedReading().positionTicks
            updateSession { it.copy(forcedRemote = request.forceRemote) }
            setReportingActive(false)
            // `PlayerEvent` has no "buffering", so nothing else can tell the group this member is
            // rebuilding its player.
            syncPlay.onBuffering()
            val resumed =
                request.copy(
                    startPositionTicks =
                        request.startPositionTicks.takeIf { it > 0L } ?: resumeTicks,
                )

            // What to fall back to if the resolve fails: the player is still prepared on
            // [previous] at that point, so its terms are worth one retry ([onResolveFailed]).
            updateSession { it.copy(recoverySource = previous) }
            launchSessionOp {
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
         * Every open and reopen goes through here, so at most one resolve → prepare → publish
         * sequence is in flight ([openJob]); a superseded one is cancelled where it is suspended
         * rather than allowed to finish out of order.
         */
        private fun launchSessionOp(block: suspend () -> Unit) {
            val predecessor = openJob
            openJob =
                viewModelScope.launch {
                    predecessor?.cancelAndJoin()
                    block()
                }
        }

        /**
         * [session] is assigned before anything suspends, so a player event arriving during the first
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
                    val previous = session
                    session =
                        ActiveSession(
                            source = resolved,
                            // The player has only just been prepared and has no tracks yet, so both
                            // wait for the first `TracksChanged`.
                            pendingAudioIndex = resolved.selectedAudioIndex,
                            pendingSubtitleApply = true,
                            // Kept, not derived: a forced-remote track change replaces the source with
                            // the server's copy, and the download has to outlive it.
                            localSource = resolved as? LocalPlaybackMediaSource ?: previous?.localSource,
                            // Carried across the open: resetting it would drop a session back onto the
                            // file it deliberately left.
                            forcedRemote = previous?.forcedRemote == true,
                            // Untouched by an open: `openSession` clears it and `onResolveFailed`
                            // spends it.
                            recoverySource = previous?.recoverySource,
                            stopReported = false,
                            // Fetched by `loadPlaybackExtras` below; until then none, not the last
                            // film's.
                            segments = emptyList(),
                            // The previous episode's successor is this one; offering it again loops.
                            upNext = null,
                            // A fresh load: whatever the receiver dropped was the stream this replaces.
                            castItemLostAt = null,
                            // Nothing has been read from the new stream yet.
                            lastValidReading = null,
                        )
                    // A re-resolve builds a fresh media item, which starts at 1×; the speed belongs
                    // to the session, not to the media item.
                    _uiState.value.speed
                        .takeIf { !it.isNormal }
                        ?.let { playerHandle.setPlaybackSpeed(it.rate) }
                    _videoPlayer.value = playerHandle.player
                    // Buffering only if the open means to play: `isBuffering` draws a Pause glyph, and
                    // a film opened paused (home from a television) must offer Play.
                    val opening = playerHandle.playWhenReady
                    _uiState.update { it.withSource(resolved, isOnline, message, buffering = opening) }
                    positionTracker.onSessionOpened(resolved.startPositionTicks.ticksToMillis())
                    // A dismissal belongs to the episode it was made on.
                    upNext.reset()

                    // Before the start report, not after: the id that report is keyed on is minted here.
                    syncPlay.syncServerSession(resolved, playerHandle.snapshot())
                    reporter.reportStart(resolved, playerHandle.snapshot())
                    setReportingActive(true)
                    loadPlaybackExtras(resolved)
                    // Idempotent, which matters: also reached from the group's own `loadItem`.
                    syncPlay.attach()
                    // From here the cast coordinator stays quiet: the ticker above is the one telling
                    // the server where the film is, wherever the bytes are decoded.
                    cast.attach()
                }
            }
        }

        /**
         * Two recoveries, both one-shot — the retry does not re-arm them, so a server that is really
         * gone still ends in the error state after one extra attempt:
         *
         * - a forced-remote session whose server turned out not to be there goes back to the file on
         *   disk, unforced so it can only resolve locally;
         * - a failed re-negotiation re-asks [recoverySource]'s terms from the position reached, since
         *   the session behind it was working a second ago.
         */
        private fun onResolveFailed(error: AppError) {
            val active = session
            val downloaded = active?.localSource
            if (active?.forcedRemote == true && downloaded != null) {
                Timber.i("Streaming %s for a track change failed; returning to the file on disk", downloaded.itemId)
                reopenSession(
                    downloaded.asRequest(forceRemote = false, castTarget = isCasting),
                    PlayerMessage.TrackUnavailableOffline,
                )
                return
            }

            val previous = active?.recoverySource
            if (previous != null) {
                updateSession { it.copy(recoverySource = null) }
                Timber.i("Re-negotiating %s failed; retrying the terms that were playing", previous.itemId)
                val snapshot = playerHandle.snapshot()
                val resumeTicks = vouchedReading().positionTicks
                launchSessionOp {
                    publish(
                        sessionController.open(
                            previous
                                // Read here, not captured above: this runs once the predecessor
                                // operation has been cancelled, and that flag is the one in force.
                                .asRequest(session?.forcedRemote == true, isCasting)
                                .copy(startPositionTicks = resumeTicks),
                            playWhenReady = snapshot.isPlaying,
                        ),
                        PlayerMessage.ChangeReverted,
                    )
                }
                return
            }

            fail(error.toMessage())
        }

        /**
         * Launched *after* `prepare` and never awaited: all three decorations involve a server round
         * trip and must never delay the first frame. Absence is the resolvers' normal answer, so none
         * can fail visibly.
         */
        private fun loadPlaybackExtras(resolved: PlaybackMediaSource) {
            updateSession { it.copy(segments = emptyList(), upNext = null) }
            _uiState.update { it.copy(trickplay = null, skippableSegment = null, upNext = null) }

            viewModelScope.launch {
                _uiState.update { it.copy(trickplay = trickplayResolver.resolve(resolved)) }
            }
            viewModelScope.launch {
                val loaded = segmentLoader.load(resolved)
                updateSession { it.copy(segments = loaded) }
            }
            // Not in a group: the server owns what everyone watches next.
            if (!syncPlay.isInGroup) {
                viewModelScope.launch {
                    val next = upNextResolver.resolve(resolved.itemId.toString())
                    // Identity-guarded: this resolve is two server calls long and the session can be
                    // replaced under it, which would offer the episode already playing.
                    updateSession { if (it.source.itemId == resolved.itemId) it.copy(upNext = next) else it }
                }
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
                    // A receiver publishes its commands only once it has something loaded.
                    publishSpeedSupport()
                }

                // Only `true` settles buffering: a rebuffer's `false` arrives alongside `Buffering(true)`,
                // and must not be what clears it.
                is PlayerEvent.IsPlayingChanged -> {
                    _uiState.update {
                        it.copy(isPlaying = event.isPlaying, isBuffering = it.isBuffering && !event.isPlaying)
                    }
                    publishPipState()
                }

                // Both players: a receiver can buffer for minutes, a local stream rebuffers, and each
                // should show a spinner rather than a Play triangle that invites the wrong tap.
                is PlayerEvent.Buffering ->
                    if (session?.castItemLostAt == null) {
                        _uiState.update { it.copy(isBuffering = event.isBuffering) }
                    }

                is PlayerEvent.TracksChanged -> applyPendingTrackSelections()

                is PlayerEvent.VideoSizeChanged -> {
                    _uiState.update { it.copy(videoWidth = event.width, videoHeight = event.height) }
                    publishPipState()
                }

                is PlayerEvent.Ended -> onEnded()

                is PlayerEvent.Error -> onError(event)

                // `CastSessionCoordinator`'s to judge; it calls back through [onCastItemLost].
                is PlayerEvent.RemoteItemMissing, PlayerEvent.RemoteItemMissingCleared -> Unit
            }
        }

        /**
         * Retried on every `TracksChanged` because tracks arrive in stages — a side-loaded subtitle's
         * group appears after the container's. Each half clears itself the moment it lands, so a
         * later event cannot undo a choice the user has since made.
         *
         * A failure here never re-resolves: this stream *is* the one the server built for this
         * selection, so a missing track means the server burned it in, and re-asking would loop.
         */
        private fun applyPendingTrackSelections() {
            val active = session ?: return
            val current = active.source
            active.pendingAudioIndex?.let { index ->
                if (playerHandle.selectAudioTrack(current, index)) updateSession { it.copy(pendingAudioIndex = null) }
            }
            if (active.pendingSubtitleApply &&
                playerHandle.selectSubtitleTrack(current, current.selectedSubtitleIndex)
            ) {
                updateSession { it.copy(pendingSubtitleApply = false) }
            }
        }

        /**
         * `hasEnded` is what `PlayerScreen` turns into `onBack()`, so it is withheld whenever
         * something is about to fill this same session: a group queue moving on, an up-next tap
         * already in flight ([advancing]), or the natural advance below. A dismissed card opts out
         * of that advance.
         *
         * The stop report is unconditional, and runs *before* the advance is triggered.
         */
        private fun onEnded() {
            val active = session ?: return
            val current = active.source
            val groupContinues = syncPlay.isInGroup && syncPlay.hasNextInQueue
            val autoAdvance =
                !syncPlay.isInGroup && !advancing && active.upNext != null && !upNext.dismissed
            _uiState.update {
                it.copy(hasEnded = !groupContinues && !advancing && !autoAdvance, isPlaying = false)
            }
            setReportingActive(false)
            if (!active.stopReported) {
                updateSession { it.copy(stopReported = true) }
                // The detached scope, not `viewModelScope`: publishing `hasEnded` pops the route on
                // the next frame, cancelling this scope, and `stopReported` is already armed so the
                // `releaseSession` fallback would never resend.
                reporter.reportStopDetached(current, playerHandle.snapshot().copy(hasEnded = true))
            }
            // After the report block, never before: `viewModelScope` is `Main.immediate`, so an
            // advance triggered earlier would race `endCurrentSource` against a `stopReported` not
            // yet armed and report the outgoing episode twice.
            if (autoAdvance) playNextEpisode()
        }

        /**
         * The fallback ladder is skipped entirely while casting: every rung diagnoses *this device's*
         * decoders, which says nothing about a failure on the far end of a network.
         */
        private fun onError(event: PlayerEvent.Error) {
            val active = session ?: return
            val current = active.source
            val positionTicks = playerHandle.snapshot().positionTicks

            if (isCasting) {
                Timber.w("Giving up on %s after a cast error %d", current.itemId, event.errorCode)
                return fail(event.message?.let(UiText::Raw) ?: PLAYBACK_FAILED, PlayerMessage.CastPlaybackFailed)
            }

            when (val decision = fallback.onPlayerError(event.errorCode, current, positionTicks)) {
                is FallbackDecision.ForceTranscode ->
                    reopenSession(
                        current.asRequest(active.forcedRemote, isCasting).copy(
                            startPositionTicks = decision.positionTicks,
                            enableDirectPlay = false,
                            enableDirectStream = false,
                        ),
                        PlayerMessage.SwitchedToTranscode,
                    )

                is FallbackDecision.LowerBitrate ->
                    reopenSession(
                        current.asRequest(active.forcedRemote, isCasting).copy(
                            startPositionTicks = decision.positionTicks,
                            maxStreamingBitrate = decision.maxStreamingBitrate,
                            // Leaving the flag on would have the resolver overwrite the ladder's rung
                            // with a fresh measurement — the one that just failed to play.
                            autoBitrate = false,
                        ),
                        PlayerMessage.RetryingAtLowerQuality,
                    )

                FallbackDecision.GiveUp -> {
                    Timber.w("Giving up on %s after error %d", current.itemId, event.errorCode)
                    fail(event.message?.let(UiText::Raw) ?: PLAYBACK_FAILED, PlayerMessage.PlaybackFailed)
                }
            }
        }

        /**
         * Independent of the UI position poll: reporting keeps running while the screen is
         * backgrounded. The same tick keeps [SavedStateHandle] current
         * ([PlayerSessionStore.rememberLivePosition]) — the UI poll stops exactly where a process
         * death happens.
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
                            // Vetted, so a zero this session cannot vouch for is skipped like any invalid tick.
                            snapshot = {
                                vetted(playerHandle.snapshot()).also {
                                    sessionStore.rememberLivePosition(it)
                                    rememberReading(it)
                                }
                            },
                        )
                }
        }

        private fun fail(
            message: UiText,
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
         * The stop report goes to the reporter's detached scope: by the time this runs
         * `viewModelScope` is cancelled and a report launched there would never leave the device.
         */
        override fun onCleared() {
            releaseSession()
            super.onCleared()
        }

        /**
         * `internal` rather than private so the ordering it encodes can be unit tested without
         * reflecting into the lifecycle library's internals.
         *
         * [PlayerHandle.release] and not just `stop()`: stopping idles the player but leaves its
         * playback thread, loaders, allocator buffers and ffmpeg renderer alive for the rest of the
         * process. It is idempotent, and the media-session service's teardown reaches it too.
         *
         * While casting the last three steps are skipped: the receiver plays on, its stop report is
         * the coordinator's to send, and [playerHandle] is pointing at the television.
         */
        internal fun releaseSession() {
            setReportingActive(false)
            setScreenVisible(false)
            // The group survives the screen: the controller sends `ignoreWait` from here.
            syncPlay.detach()
            // After the ticker above is stopped, never before: the coordinator starts its own here,
            // and two would double every reported position.
            cast.detach()
            pipController.clear()
            val active = session
            if (active != null && !active.stopReported && !isCasting) {
                updateSession { it.copy(stopReported = true) }
                reporter.reportStopDetached(active.source, vouchedReading())
            }
            // After the stop report is handed over, never before: this closes the group's view of a
            // downloaded item and reads the minted id on its way out.
            syncPlay.onSessionClosed()
            // …and after it too: disowning video's claim before the report was handed over would
            // leave nothing to close this session at all.
            sessionController.endVideoSession()
            _videoPlayer.value = null
            if (isCasting) return
            playerHandle.stop()
            playerHandle.release()
        }

        companion object {
            /** Keys the navigation library stores `Routes.Player`'s arguments under. */
            const val ARG_ITEM_ID = "itemId"
            const val ARG_MEDIA_SOURCE_ID = "mediaSourceId"
            const val ARG_START_TICKS = "startPositionTicks"

            /**
             * Written back into the handle, unlike the `ARG_` keys: [ARG_START_TICKS] is what the
             * user tapped and must stay intact.
             */
            const val KEY_LIVE_POSITION_TICKS = "livePositionTicks"
            const val KEY_WAS_PLAYING = "wasPlaying"

            /** The server's "no subtitles" sentinel; `null` would re-select the item's default. */
            const val SUBTITLES_OFF = -1

            private val UI_TICK = 500.milliseconds

            /**
             * The floor under an ExoPlayer or Cast error that carried no message of its own; one
             * that *did* is passed through verbatim as `UiText.Raw`.
             */
            private val UNSUPPORTED_SOURCE = UiText.res(R.string.player_error_unsupported_source)
            private val PLAYBACK_FAILED = UiText.res(R.string.player_message_failed)
        }
    }

/**
 * Everything one playback session remembers, in one value, so that "a new session" is a single
 * assignment in `PlayerViewModel.publish` and a forgotten field is a compile error.
 *
 * @property pendingAudioIndex the audio stream this open resolved, until the player has been told
 *   about it. It cannot be applied where it is resolved — `Player.currentTracks` is still empty
 *   after `prepare` — so it waits for the first `TracksChanged` (`applyPendingTrackSelections`).
 * @property pendingSubtitleApply a flag rather than a nullable index because `null` *is* a choice
 *   here — subtitles off — and `prepare` re-enables the text renderer, so ExoPlayer's selector will
 *   otherwise pick up a default-flagged text track on its own.
 * @property localSource the downloaded copy, **carried across a re-open rather than derived from
 *   [source]**: a forced-remote track change replaces [source] with the server's copy, and this is
 *   what says which tracks the file could still play and what to fall back to.
 * @property recoverySource what was playing when the current re-negotiation started; `null` once
 *   spent. One-shot — the retry does not re-arm it.
 * @property forcedRemote `true` while this session is deliberately streaming an item that is also on
 *   disk. Carried into every later re-negotiation (`asRequest`), **including across an open**.
 * @property stopReported guards the three exits that can each report the stop — the item ending, a
 *   session being replaced, and the screen going away — against reporting it twice.
 * @property upNext `null` for a non-episode, the last episode of a series, in a group, and while the
 *   prefetch is in flight.
 * @property castItemLostAt non-`null` once the receiver has dropped this session's item: the reading it
 *   was last held at. From then on the stop is reported, the transport acts on nothing, and Play
 *   loads the item again (`PlayerViewModel.onCastItemLost`).
 * @property lastValidReading the last [valid][PlaybackSnapshot.isValid] reading taken for [source] —
 *   the only position this session can vouch for once its player stops answering (a receiver that has
 *   gone). Here and not on the ViewModel or the tracker: its lifetime is exactly this source's, so a
 *   new session can never inherit the last one's position. Seeded from the coordinator on adoption.
 */
@Suppress("LongParameterList")
private data class ActiveSession(
    val source: PlaybackMediaSource,
    val pendingAudioIndex: Int?,
    val pendingSubtitleApply: Boolean,
    val localSource: LocalPlaybackMediaSource?,
    val recoverySource: PlaybackMediaSource?,
    val forcedRemote: Boolean,
    val stopReported: Boolean,
    val segments: List<MediaSegment>,
    // No default, deliberately: one would let a construction site inherit the *previous* episode's
    // successor by saying nothing.
    val upNext: UpNextEpisode?,
    val castItemLostAt: PlaybackSnapshot?,
    val lastValidReading: PlaybackSnapshot?,
)

/**
 * The request that would reproduce what is playing right now; callers `copy()` the one thing they
 * are changing so a re-negotiation cannot silently drop a setting the previous one established.
 *
 * @param forceRemote whether this re-negotiation must keep bypassing the download on disk — a
 *   parameter because the source cannot know: a streamed `RemotePlaybackMediaSource` looks the same
 *   whether nobody downloaded the item or the user is deliberately streaming past the file.
 * @param castTarget likewise unknowable from the source; only the live cast session says which
 *   profile the next negotiation belongs to.
 */
private fun PlaybackMediaSource.asRequest(
    forceRemote: Boolean,
    castTarget: Boolean,
): PlaybackResolveRequest =
    PlaybackResolveRequest(
        itemId = itemId,
        mediaSourceId = mediaSourceId,
        maxStreamingBitrate = (this as? RemotePlaybackMediaSource)?.maxStreamingBitrate,
        audioStreamIndex = selectedAudioIndex,
        subtitleStreamIndex = selectedSubtitleIndex,
        forceRemote = forceRemote,
        castTarget = castTarget,
        // A track change on an Auto stream is still an Auto stream; the resolver re-fills the cap.
        autoBitrate = (this as? RemotePlaybackMediaSource)?.autoBitrate ?: false,
    )

/** A `null` index is "nothing being asked for" on either side, and needs no stream to satisfy. */
private fun LocalPlaybackMediaSource.plays(
    audioIndex: Int?,
    subtitleIndex: Int?,
): Boolean = (audioIndex == null || playsAudioLocally(audioIndex)) && playsSubtitleLocally(subtitleIndex)

private fun PlayerUiState.withSource(
    source: PlaybackMediaSource,
    online: Boolean,
    message: PlayerMessage?,
    buffering: Boolean,
): PlayerUiState =
    copy(
        isLoading = false,
        isBuffering = buffering,
        errorMessage = null,
        hasEnded = false,
        playMethod = source.playMethod,
        isLocalPlayback = source is LocalPlaybackMediaSource,
        durationMs = source.runTimeTicks.ticksToMillis(),
        selectedAudioIndex = source.selectedAudioIndex,
        selectedSubtitleIndex = source.selectedSubtitleIndex,
        quality = qualityOf(source),
        userMessage = message ?: userMessage,
    ).withTracks(source, online)

/**
 * Flag-driven rather than a reverse lookup of the cap: Auto resolves to a *measured* number, and a
 * measurement landing on 8 Mbps would otherwise light up a "Medium" chip the user never chose.
 */
private fun qualityOf(source: PlaybackMediaSource): PlaybackQuality {
    val remote = source as? RemotePlaybackMediaSource ?: return PlaybackQuality.forBitrate(null)
    return if (remote.autoBitrate) PlaybackQuality.AUTO else PlaybackQuality.forBitrate(remote.maxStreamingBitrate)
}

/**
 * Separate from [withSource] because connectivity changes on its own timetable: a network dropping
 * while the audio sheet is open re-derives exactly these two fields and touches nothing else.
 */
private fun PlayerUiState.withTracks(
    source: PlaybackMediaSource,
    online: Boolean,
): PlayerUiState =
    copy(
        audioTracks = source.audioTracksFor(online),
        subtitleTracks = source.subtitleTracksFor(online),
    )

/**
 * The server branches are overridden: a refusal to *open a stream* has a different remedy from a
 * failed browse (try another quality, not pull-to-refresh).
 */
private val PlayerErrorCopy =
    AppErrorCopy(
        unknown = R.string.player_error_unknown,
        server = R.string.player_error_server,
        serverWithCode = R.string.player_error_server_with_code,
    )

private fun AppError.toMessage(): UiText = toUiText(PlayerErrorCopy)
