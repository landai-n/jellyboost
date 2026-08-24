package dev.jellyboost.player.syncplay

import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.player.model.ticksToMillis
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.PlayerHandle
import dev.jellyboost.player.syncplay.api.SyncPlayApi
import dev.jellyboost.player.syncplay.di.SyncPlayScope
import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyboost.player.syncplay.socket.SyncPlaySocket
import dev.jellyboost.player.syncplay.socket.SyncPlaySocketState
import dev.jellyboost.player.syncplay.time.SyncPlayPinger
import dev.jellyboost.player.syncplay.time.SyncPlayTimeSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import timber.log.Timber
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SyncPlay coordinator. A `@Singleton` with its own scope, not a ViewModel: membership outlives
 * the player screen, and a `PlayQueueUpdate` for an item nobody has open has to bring one back
 * ([launchRequests]).
 *
 * **In a group, nothing this client does moves this client's player.** Every transport and queue
 * intent is a request to the server; the player moves only when the server rebroadcasts the command
 * to everyone.
 *
 * ### Join handshake
 * Collect the websocket → measure the clock → join → group + queue arrive → open the item paused (or
 * raise a launch request) → report `buffering` → report `ready` when parked → the server leaves
 * WAITING and schedules an unpause everyone applies at the same instant.
 *
 * - the websocket is collected *before* the join call: collecting is what opens it (the SDK has no
 *   `connect()`), and a group joined first would never hear its own `GroupJoined`/`PlayQueueUpdate`;
 * - a member reporting `ready` must be **stopped**: the server's `WaitingGroupState` answers a
 *   `ready` that claims to be playing by resuming everyone *else*;
 * - a `ready` is reported only when one is owed ([readyOwedFor]). The server answers an unowed one
 *   by re-sending the group's current state command to this session, which repositions the player
 *   and produces another readiness — a closed loop measured at some thirteen requests a second.
 *
 * ### Losing the connection, and getting it back
 * Three signals confirm a loss through one mechanism (`confirmLoss`): the socket collection *ending*
 * (a finished stream is the transport giving up), [PING_FAILURE_STREAK] failed ping cycles, and
 * being offline for [CONNECTIVITY_GRACE_MS]. Nothing here resumes playback — playing on would drift
 * from the group invisibly. A socket flap the SDK reconnects through is not a loss.
 *
 * The server does not survive a dropped websocket either: it ends the session,
 * `SyncPlayManager.OnSessionEnded` calls `LeaveGroup`, and the next request lands on a new session
 * belonging to no group (answered with `SyncPlayNotInGroupUpdate`). Nobody here asked to leave, so
 * a removal after trouble stands the session down into [SyncPlayState.Rejoining] and re-runs the
 * ordinary join flow rather than ending the group; a deliberate exit forgets the target first.
 * Exhausted attempts are terminal — there is no background retry loop.
 *
 * [onAppForegrounded] is the one moment a membership lost to a backgrounded process can be taken
 * back, and its rejoin is **silent**: opening the app must not put a message on screen.
 *
 * ### Threading: the confinement contract
 * Every field here — the [session] box, the collaborators' state, the scheduler's memory — is
 * confined to the single-threaded `@SyncPlayScope` (`limitedParallelism(1)`). That confinement **is**
 * the synchronization; [sessionMutex] serialises *membership transitions*, not field access.
 *
 * 1. **Public entry points hop first**: anything callable from another thread does nothing but
 *    `scope.launch`/`withContext(scope)` before touching state.
 * 2. **Session work runs on the session's child scope** ([launchInSession]), so [closeSession]
 *    cancels it wholesale; the child inherits the same confined dispatcher.
 * 3. **Only [PlayerHandle]/host reads leave the scope**, via `withContext(mainDispatcher)`, carrying
 *    no controller state beyond their parameters.
 */
@Singleton
@Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongParameterList",
)
class SyncPlayController
    @Inject
    internal constructor(
        private val api: SyncPlayApi,
        private val socket: SyncPlaySocket,
        private val timeSync: SyncPlayTimeSync,
        private val scheduler: SyncPlayCommandScheduler,
        private val driftMonitor: SyncPlayDriftMonitor,
        private val pinger: SyncPlayPinger,
        private val statusHolder: SyncPlayStatusHolder,
        private val playerHandle: PlayerHandle,
        private val connectionState: ConnectionStateProvider,
        private val sessionStateHolder: SessionStateHolder,
        // The *device* clock, deliberately, and only for `lostMembership`: `timeSync.serverNow()`
        // is reset by the very teardown that memory has to outlive.
        private val clock: Clock,
        @SyncPlayScope private val scope: CoroutineScope,
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    ) {
        // The read-only half is `internal`, not public; ktlint's rule only recognises the public idiom.
        @Suppress("ktlint:standard:backing-property-naming")
        private val _state = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)

        internal val state: StateFlow<SyncPlayState> = _state.asStateFlow()

        @Suppress("ktlint:standard:backing-property-naming")
        private val _messages =
            MutableSharedFlow<SyncPlayMessage>(
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        internal val messages: SharedFlow<SyncPlayMessage> = _messages.asSharedFlow()

        private val _launchRequests =
            MutableSharedFlow<SyncPlayLaunchRequest>(
                // Replayed: the NavHost effect only exists while an Activity is composed, and the
                // presence service keeps the group alive precisely when none is. The collector calls
                // [consumeLaunchRequest] once it has acted, and teardown clears it.
                replay = 1,
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** "The group moved on and no player is open" — collected by the NavHost. */
        val launchRequests: SharedFlow<SyncPlayLaunchRequest> = _launchRequests.asSharedFlow()

        fun consumeLaunchRequest() {
            _launchRequests.resetReplayCache()
        }

        /** Serialises joining, leaving and tearing down; nothing else may change membership. */
        private val sessionMutex = Mutex()

        private var sessionJob: Job? = null
        private var sessionScope: CoroutineScope? = null

        @Volatile
        private var host: SyncPlayPlaybackHost? = null

        /**
         * Every field one group session owns, boxed so that each ending ([teardown], [standDown]) is
         * one assignment rather than a dozen resets kept in step by hand — a field missing from such
         * a list carries stale state into a rejoined group invisibly.
         *
         * Confined to the single-threaded `@SyncPlayScope`, so plain `var`s are safe.
         */
        private class GroupSessionState(
            /**
             * `true` once the group has been told to stop waiting on us. Survives a stand-down: a
             * member with no player must go on being one after the rejoin ([enterGroup] re-sends it).
             */
            var ignoreWaitSent: Boolean = false,
        ) {
            /** The slot the host has open, so a repeated `PlayQueueUpdate` reloads nothing. */
            var loadedPlaylistItemId: UUID? = null

            /**
             * The loop guard for [onEntryUnplayable]: skipping is itself a request that produces
             * another `PlayQueueUpdate`, so a queue of unplayable items would cycle for ever.
             */
            val skippedSlots = mutableSetOf<UUID>()

            /** Group updates that arrived before the join call returned; replayed by [enterGroup]. */
            var pendingGroup: SyncPlayGroupSummary? = null
            var pendingQueue: SyncPlayGroupQueue? = null

            /**
             * The slot the group is waiting on a `ready` for; the anti-storm rule (see the class
             * docs) reports a `PlayerEvent.Ready` only when the server reset this member to buffering.
             */
            var readyOwedFor: UUID? = null

            /** Reports the owed `ready` when the player will not re-buffer to announce itself. */
            var readyFallbackJob: Job? = null

            /** Fires when the radio did not come back inside the grace window. */
            var connectivityGraceJob: Job? = null

            /** [PING_FAILURE_STREAK] of them in a row is a confirmed loss. */
            var pingFailures = 0

            /**
             * Explicit rather than left to [closeSession]'s scope cancellation: [launchInSession]
             * falls back to the singleton scope when no session scope is open, and nilling the
             * handles alone would leave such a job running with nothing able to reach it.
             */
            fun cancelJobs() {
                readyFallbackJob?.cancel()
                connectivityGraceJob?.cancel()
            }

            companion object {
                /**
                 * The [standDown] ending: the server ended the *session*, nobody ended the
                 * *membership*, so the ignore-wait promise carries over — as do the clock offset and
                 * the rejoin policy's trouble memory, unlike [teardown].
                 */
                fun carriedAcrossStandDown(previous: GroupSessionState) =
                    GroupSessionState(
                        ignoreWaitSent = previous.ignoreWaitSent,
                    )
            }
        }

        /** Replaced wholesale when the session ends. */
        private var session = GroupSessionState()

        /** Drives its membership transitions through [RejoinDriver], each one under [sessionMutex]. */
        private val rejoinPolicy =
            SyncPlayRejoinPolicy(connectionState, timeSync, clock, scope, RejoinDriver())

        private val recoveryNets =
            SyncPlayRecoveryNets(playerHandle, timeSync, mainDispatcher, NetsDriver())

        init {
            // On the singleton scope, not a group session: a rejoin attempt stands the session down,
            // and a sign-out during one has to be able to abort it.
            scope.launch { watchSignOut() }
        }

        // Membership intents ------------------------------------------------------------------------

        internal fun createGroup(name: String) {
            scope.launch { startSession(existing = null, newGroupName = name) }
        }

        internal fun joinGroup(group: SyncPlayGroupSummary) {
            scope.launch { startSession(existing = group, newGroupName = null) }
        }

        /**
         * Called by [SyncPlaySignOutHook] **before** `SessionRepository` revokes the token: waiting
         * for the [SessionState.LoggedOut] transition would send the leave with a dead credential.
         */
        internal suspend fun leaveBeforeSignOut() {
            // Awaited, not just hopped: `signOut` must not revoke the token until the leave is out.
            withContext(scope.coroutineContext) {
                rejoinPolicy.forgetLoss()
                // Awaited so a join in flight cannot land after the leave and strand the session in
                // the group server-side.
                rejoinPolicy.abandonRejoinAndAwait()
                if (_state.value is SyncPlayState.Idle) return@withContext
                leaveOnServer()
                teardown(message = null, pausePlayer = false)
            }
        }

        /** Leaves the group. Playback is left exactly as it is, now solo. */
        internal fun leaveGroup() {
            // Clearing the target first, before anything suspends: it is the one signal that the exit
            // is deliberate, and a loss confirmed mid-leave must find nothing to rejoin.
            scope.launch {
                rejoinPolicy.forgetLoss()
                rejoinPolicy.abandonRejoinAndAwait()
                leaveOnServer()
                teardown(message = null, pausePlayer = false)
            }
        }

        /**
         * `ProcessLifecycleOwner`, `ON_START`. In a group, sample the ping immediately: the platform
         * may have cut this process's network while it was away, which the ordinary cadence would
         * not even suspect for five seconds. Idle, take a recently-lost membership back, once.
         */
        internal fun onAppForegrounded() {
            scope.launch {
                when (_state.value) {
                    is SyncPlayState.InGroup -> pinger.sampleNow()
                    SyncPlayState.Idle -> rejoinPolicy.resumeLostMembership()
                    else -> Unit
                }
            }
        }

        // Transport intents — requests to the server, never local playback changes ------------------

        internal fun requestPause() = request { api.requestPause() }

        internal fun requestUnpause() = request { api.requestUnpause() }

        internal fun requestSeek(positionTicks: Long) = request { api.requestSeek(positionTicks) }

        internal fun requestNext() = request { currentEntry()?.let { api.requestNextItem(it.playlistItemId) } }

        internal fun requestPrevious() = request { currentEntry()?.let { api.requestPreviousItem(it.playlistItemId) } }

        internal fun requestSetPlaylistItem(playlistItemId: UUID) = request { api.setPlaylistItem(playlistItemId) }

        // Queue intents ----------------------------------------------------------------------------

        internal fun setNewQueue(
            itemIds: List<UUID>,
            playingItemPosition: Int = 0,
            startPositionTicks: Long = 0L,
        ) = request { api.setNewQueue(itemIds, playingItemPosition, startPositionTicks) }

        internal fun addToQueue(
            itemIds: List<UUID>,
            mode: SyncPlayQueueMode,
        ) = request { api.addToQueue(itemIds, mode) }

        internal fun moveQueueItem(
            playlistItemId: UUID,
            newIndex: Int,
        ) = request { api.movePlaylistItem(playlistItemId, newIndex) }

        internal fun removeFromQueue(playlistItemIds: List<UUID>) = request { api.removeFromPlaylist(playlistItemIds) }

        internal fun setShuffle(mode: SyncPlayShuffleMode) = request { api.setShuffleMode(mode) }

        internal fun setRepeat(mode: SyncPlayRepeatMode) = request { api.setRepeatMode(mode) }

        // Host attachment ---------------------------------------------------------------------------

        /**
         * If the group is already on an item, this is what gets it opened — an item the user opened
         * themselves is adopted rather than reloaded (see [reconcile]).
         */
        internal fun attachHost(host: SyncPlayPlaybackHost) {
            // Hopped onto the confined scope: a main-thread write would race the handshake bookkeeping.
            scope.launch {
                this@SyncPlayController.host = host
                val current = _state.value as? SyncPlayState.InGroup ?: return@launch
                launchInSession {
                    if (session.ignoreWaitSent) {
                        session.ignoreWaitSent = false
                        runCatchingUnlessCancelled { api.setIgnoreWait(false) }
                            .onFailure { Timber.w(it, "Could not clear SyncPlay ignore-wait") }
                    }
                    current.queue?.let { reconcile(it) }
                }
            }
        }

        /**
         * Gives the player back, keeping the group. `setIgnoreWait(true)` is the mechanism
         * jellyfin-web uses: a member with no player must never keep everyone else in WAITING.
         *
         * **Do not cancel the scheduler or force the phase to `Paused` here.** `PlaybackService`
         * keeps the shared ExoPlayer alive and playing, so either would leave a member playing on
         * with the group's commands landing nowhere and the drift monitor (which runs in `Playing`
         * only) shut off. Only [teardown] and [standDown] end the timeline the scheduler tracks.
         *
         * @param host ignored unless it is the attached one, so a stale ViewModel's teardown cannot
         *   detach the player that replaced it.
         */
        internal fun detachHost(host: SyncPlayPlaybackHost) {
            // Hopped like [attachHost]: `session.skippedSlots.clear()` from the main thread would
            // race the collectors' own `add`/`clear` on a plain `HashSet`, and the resulting
            // `ConcurrentModificationException` inside the collector reads as a lost connection.
            scope.launch {
                if (this@SyncPlayController.host !== host) return@launch
                this@SyncPlayController.host = null
                session.loadedPlaylistItemId = null
                session.skippedSlots.clear()
                if (_state.value !is SyncPlayState.InGroup) return@launch
                runCatchingUnlessCancelled { api.setIgnoreWait(true) }
                    .onSuccess { session.ignoreWaitSent = true }
                    .onFailure { Timber.w(it, "Could not set SyncPlay ignore-wait") }
            }
        }

        /**
         * Called by the host rather than inferred: `PlayerEvent` has no "buffering", so ExoPlayer's
         * re-prepare is invisible from here and the group would keep playing while this member
         * rebuilds its player.
         */
        internal fun onHostBuffering() {
            scope.launch {
                val entry = currentEntry() ?: return@launch
                setPhase(SyncPlayPhase.Buffering)
                // The server settles this member by re-sending the standing command verbatim after
                // the ready, so remembering it as applied would dedupe exactly that answer. Measured
                // on device: the resumed Unpause was dropped as a repeat and the blind fallback then
                // jumped from 6:35 to 27:27.
                scheduler.forgetApplied()
                // The player really is being rebuilt, so its own readiness ends this handshake: no
                // fallback, however long the re-negotiation takes.
                oweReady(entry, fallbackMillis = null)
                launchInSession {
                    val snapshot = hostSnapshot()
                    runCatchingUnlessCancelled {
                        api.reportBuffering(
                            timeSync.serverNow(),
                            snapshot?.positionTicks ?: 0L,
                            reportedIsPlaying(snapshot),
                            entry.playlistItemId,
                        )
                    }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }
                }
            }
        }

        // Session lifecycle --------------------------------------------------------------------------

        private suspend fun startSession(
            existing: SyncPlayGroupSummary?,
            newGroupName: String?,
        ) = sessionMutex.withLock {
            if (_state.value !is SyncPlayState.Idle) {
                Timber.w("Ignoring a SyncPlay join while already in a group")
                return@withLock
            }
            _state.value = SyncPlayState.Joining
            if (performJoin(existing, newGroupName)) return@withLock
            _state.value = SyncPlayState.Idle
            closeSession()
            _messages.tryEmit(SyncPlayMessage.JoinFailed)
        }

        /**
         * Shared by the first join and by every rejoin attempt: the handshake has to be identical
         * for the server to put this member back in step.
         *
         * @return `true` when the group was entered. On `false` the session scope is still open and
         *   the caller decides what to do with it.
         */
        private suspend fun performJoin(
            existing: SyncPlayGroupSummary?,
            newGroupName: String?,
        ): Boolean {
            session.pendingGroup = null
            session.pendingQueue = null
            val openedSession = openSession()
            openedSession.launch {
                collectStream("group updates") {
                    socket.groupUpdates.collect { handleStreamEvent("group update", it, ::onGroupUpdate) }
                }
            }
            openedSession.launch {
                collectStream("commands") {
                    socket.commands.collect { handleStreamEvent("command", it) { command -> onCommand(command) } }
                }
            }
            awaitSocketReady()
            warmClock()

            val joined =
                runCatchingUnlessCancelled {
                    if (existing != null) {
                        api.joinGroup(existing.id)
                        existing
                    } else {
                        api.createGroup(requireNotNull(newGroupName))
                    }
                }
            return joined
                .onSuccess { enterGroup(it, openedSession) }
                .onFailure { Timber.w(it, "Could not join a SyncPlay group") }
                .isSuccess
        }

        /**
         * Measures the server clock **before** the handshake can produce a command to schedule:
         * [SyncPlayTimeSync.offset] is `Duration.ZERO` until a sample lands and the pinger only
         * starts in [enterGroup], so a device clock off by a second would build that second of
         * desync into the very first unpause. Inline, not on the session scope, for that ordering.
         */
        private suspend fun warmClock() {
            if (pinger.sampleClock() == null) {
                Timber.w("Joining a SyncPlay group without a measured clock offset")
            }
        }

        /**
         * Bounded: a socket that never connects must not block the join for ever, and joining anyway
         * costs at most the initial `PlayQueueUpdate`.
         */
        private suspend fun awaitSocketReady() {
            val connected =
                withTimeoutOrNull(SOCKET_READY_TIMEOUT_MS) {
                    socket.connectionState.first { it is SyncPlaySocketState.Connected }
                }
            if (connected == null) Timber.w("SyncPlay websocket not connected before joining; joining anyway")
        }

        private suspend fun enterGroup(
            group: SyncPlayGroupSummary,
            openedSession: CoroutineScope,
        ) {
            val entered = session.pendingGroup ?: group
            _state.value = SyncPlayState.InGroup(entered, null, entered.state, SyncPlayPhase.Waiting)
            session.pendingGroup = null
            rejoinPolicy.onEnteredGroup(group)
            statusHolder.setInGroup(true)

            openedSession.launch { pinger.run(::onPingOutcome) }
            openedSession.launch { playerHandle.events.collect(::onPlayerEvent) }
            openedSession.launch { scheduler.applied.collect(::onCommandApplied) }
            openedSession.launch { observeAnchor() }
            openedSession.launch { watchConnectivity() }
            openedSession.launch { watchSocket() }

            // Nothing here can tell "paused before we joined" from "paused a moment ago and the
            // command was lost"; the net only ever pauses a player that is running.
            if (entered.state == SyncPlayGroupState.Paused) recoveryNets.armPauseNet()

            // A rejoin lands on a new server session, which knows nothing of the ignore-wait sent
            // when the player was given back — a member with no player would gate the group again.
            if (host == null && session.ignoreWaitSent) {
                openedSession.launch {
                    runCatchingUnlessCancelled { api.setIgnoreWait(true) }
                        .onFailure { Timber.w(it, "Could not restore SyncPlay ignore-wait after rejoining") }
                }
            }

            // Off the join path: opening an item can take a while, and nothing else should wait
            // behind it on the membership lock.
            session.pendingQueue?.let { queued ->
                session.pendingQueue = null
                openedSession.launch { onQueueChanged(queued) }
            }
        }

        private fun openSession(): CoroutineScope {
            closeSession()
            val job = SupervisorJob(scope.coroutineContext[Job])
            val session =
                CoroutineScope(
                    scope.coroutineContext + job +
                        CoroutineExceptionHandler { _, error ->
                            Timber.e(error, "Uncaught exception in a SyncPlay session coroutine")
                        },
                )
            sessionJob = job
            sessionScope = session
            return session
        }

        private fun closeSession() {
            sessionJob?.cancel()
            sessionJob = null
            sessionScope = null
        }

        /**
         * Idempotent by way of the state check inside the lock: a connection loss can be reported by
         * both socket collections at once, and the player must be paused once, not twice.
         */
        private suspend fun teardown(
            message: SyncPlayMessage?,
            pausePlayer: Boolean,
        ) {
            // Outside the lock, and first: a rejoin attempt holds the membership lock across its
            // REST calls, so cancelling it is what lets this teardown get in at all.
            rejoinPolicy.onTeardown()
            sessionMutex.withLock {
                if (_state.value is SyncPlayState.Idle) return@withLock
                _state.value = SyncPlayState.Idle
                releaseSession()
                timeSync.reset()
                session = GroupSessionState()
                if (pausePlayer) withContext(mainDispatcher) { playerHandle.pause() }
                message?.let { _messages.tryEmit(it) }
            }
        }

        /** The caller must replace [session] right after; this resets no field of it. */
        private fun releaseSession() {
            statusHolder.setInGroup(false)
            statusHolder.setMintedPlaySessionId(null)
            scheduler.cancel()
            session.cancelJobs()
            recoveryNets.reset()
            closeSession()
            // A launch request held for replay dies with the group it was raised for.
            _launchRequests.resetReplayCache()
        }

        private fun tearDownAsync(
            message: SyncPlayMessage?,
            pausePlayer: Boolean = false,
        ) {
            // Always on the singleton scope: teardown cancels the session scope, and a coroutine
            // cannot be relied on to finish the work that cancels it.
            scope.launch { teardown(message, pausePlayer) }
        }

        private suspend fun leaveOnServer() {
            if (_state.value is SyncPlayState.Idle) return
            runCatchingUnlessCancelled { api.leaveGroup() }
                .onFailure { Timber.w(it, "Could not leave the SyncPlay group cleanly") }
        }

        // Websocket ----------------------------------------------------------------------------------

        /**
         * A [SyncPlaySocket] reconnects on its own and the flow rides through it, so a stream that
         * *finishes* is the transport having given up — a confirmed loss. Momentary `Disconnected`
         * states on `SyncPlaySocket.connectionState` are deliberately not watched: they flap.
         */
        private suspend fun collectStream(
            name: String,
            block: suspend () -> Unit,
        ) {
            try {
                block()
                Timber.w("SyncPlay %s stream ended", name)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.w(error, "SyncPlay %s stream failed", name)
            }
            rejoinPolicy.confirmLoss()
        }

        /**
         * A handler bug must not end the stream: with the in-house socket that would be the only way
         * [collectStream]'s loss path could fire, and the rejoin would hit the same bug again.
         */
        private suspend fun <T : Any> handleStreamEvent(
            name: String,
            event: T,
            handler: suspend (T) -> Unit,
        ) {
            try {
                handler(event)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.e(error, "SyncPlay %s handler failed on %s", name, event::class.simpleName)
            }
        }

        // Losing the connection ------------------------------------------------------------------------

        /**
         * Both edges, not just the first offline one: a Wi-Fi handover reports offline then online
         * within a moment, and the group must never be dropped on a transient blip. Going offline
         * opens a [CONNECTIVITY_GRACE_MS] window; coming back inside it re-negotiates.
         */
        private suspend fun watchConnectivity() {
            connectionState.state
                .map { it.isOnline }
                .distinctUntilChanged()
                .collect { online -> if (online) onConnectivityBack() else onConnectivityGone() }
        }

        /**
         * Freezes rather than playing on: playback with no way to hear a pause is drift the group
         * cannot see, and the hard-kill case pauses immediately instead of at the end of the grace.
         */
        private fun onConnectivityGone() {
            rejoinPolicy.markTrouble()
            if (session.connectivityGraceJob != null) return
            Timber.w("Connectivity lost while in a SyncPlay group; freezing for %d ms", CONNECTIVITY_GRACE_MS)
            session.connectivityGraceJob =
                launchInSession {
                    withContext(mainDispatcher) { playerHandle.pause() }
                    setPhase(SyncPlayPhase.Waiting)
                    delay(CONNECTIVITY_GRACE_MS)
                    // Identity-guarded, like the other timers.
                    if (session.connectivityGraceJob === coroutineContext[Job]) session.connectivityGraceJob = null
                    Timber.w("Connectivity did not return within the SyncPlay grace window")
                    rejoinPolicy.confirmLoss()
                }
        }

        /** Connectivity came back inside the window: keep the group, and ask it to re-sync us. */
        private fun onConnectivityBack() {
            val grace = session.connectivityGraceJob ?: return
            session.connectivityGraceJob = null
            grace.cancel()
            Timber.i("Connectivity returned inside the SyncPlay grace window; re-negotiating")
            launchInSession { renegotiate() }
        }

        /**
         * The case this exists for: the OS reports a usable network while the platform has quietly
         * cut the app's, so every REST call times out and nothing else here would notice for
         * minutes. The ping loop is the only fixed-cadence conversation with the server.
         */
        private fun onPingOutcome(succeeded: Boolean) {
            if (succeeded) {
                session.pingFailures = 0
                return
            }
            rejoinPolicy.markTrouble()
            session.pingFailures++
            if (session.pingFailures < PING_FAILURE_STREAK) return
            Timber.w("SyncPlay: %d ping cycles failed in a row; treating it as a lost connection", session.pingFailures)
            rejoinPolicy.confirmLoss()
        }

        /**
         * For the record only — nothing here ends a group; a flapping socket is the SDK doing its
         * job. A socket that went away is the *reason* the server dropped this session, and
         * `recentlyTroubled` is what stops an unexplained removal being rejoined.
         */
        private suspend fun watchSocket() {
            socket.connectionState
                .distinctUntilChanged()
                .collect { if (it !is SyncPlaySocketState.Connected) rejoinPolicy.markTrouble() }
        }

        /**
         * Local teardown only: the server-side leave happens in [leaveBeforeSignOut] before the token
         * is revoked, so a server call from here could only 401.
         */
        private suspend fun watchSignOut() {
            sessionStateHolder.state.collect { session ->
                if (session !is SessionState.LoggedOut) return@collect
                // Cleared even from Idle: a signed-out account must not have a group taken back for it.
                rejoinPolicy.forgetLoss()
                if (_state.value is SyncPlayState.Idle) return@collect
                // Awaited, not just cancelled, so a join in flight cannot land after the local
                // teardown and take the group back for a signed-out account.
                rejoinPolicy.abandonRejoinAndAwait()
                teardown(message = null, pausePlayer = false)
            }
        }

        private suspend fun onGroupUpdate(event: SyncPlayGroupEvent) {
            // The class name only, never the payload: `UserJoined`/`UserLeft` carry display names.
            Timber.d("SyncPlay group update: %s", event::class.simpleName)
            when (event) {
                is SyncPlayGroupEvent.Joined -> onJoined(event.group)
                is SyncPlayGroupEvent.Left -> onLeft(event.groupId)
                is SyncPlayGroupEvent.StateChanged -> onGroupStateChanged(event.state)
                is SyncPlayGroupEvent.QueueChanged -> onQueueChanged(event.queue)
                is SyncPlayGroupEvent.UserJoined -> updateGroup { it.copy(participants = it.participants + event.name) }
                is SyncPlayGroupEvent.UserLeft -> updateGroup { it.copy(participants = it.participants - event.name) }
                SyncPlayGroupEvent.NotInGroup -> rejoinPolicy.onMembershipGone()
                // Nothing a rejoin could undo: the id it would ask for is the one just declared gone.
                SyncPlayGroupEvent.GroupGone -> forgetAndTearDown(SyncPlayMessage.GroupEnded)
                SyncPlayGroupEvent.LibraryAccessDenied -> forgetAndTearDown(SyncPlayMessage.LibraryAccessDenied)
            }
        }

        private fun onJoined(group: SyncPlayGroupSummary) {
            if (_state.value is SyncPlayState.InGroup) updateGroup { group } else session.pendingGroup = group
        }

        private fun onLeft(groupId: UUID) {
            val current = _state.value as? SyncPlayState.InGroup ?: return
            if (current.group.id != groupId) return
            rejoinPolicy.onMembershipGone()
        }

        /** Gives up the group deliberately: no rejoin will follow, and none on the next foreground. */
        private fun forgetAndTearDown(message: SyncPlayMessage) {
            rejoinPolicy.forgetDeliberately()
            tearDownAsync(message)
        }

        // Taking the group back --------------------------------------------------------------------

        /** Each operation under [sessionMutex], through the same entry points every join uses. */
        private inner class RejoinDriver : SyncPlayRejoinPolicy.SessionDriver {
            override suspend fun standDownForRejoin(target: SyncPlayGroupSummary): Boolean =
                sessionMutex.withLock {
                    if (_state.value !is SyncPlayState.InGroup) return@withLock false
                    standDown(target)
                    true
                }

            /**
             * The foreground re-check: the teardown that got here already released everything, so
             * only the state is set. The ignore-wait is restored by hand — with no player attached
             * this member must not be why the whole group sits in WAITING.
             */
            override suspend fun standUpFromIdle(target: SyncPlayGroupSummary): Boolean =
                sessionMutex.withLock {
                    if (_state.value !is SyncPlayState.Idle) return@withLock false
                    Timber.i("Back in the foreground; asking for the SyncPlay group %s back", target.id)
                    _state.value = SyncPlayState.Rejoining(target, attempt = 1)
                    session.ignoreWaitSent = host == null
                    true
                }

            /** One attempt: is the group still there, and will it have us back? */
            override suspend fun attemptJoin(
                target: SyncPlayGroupSummary,
                attempt: Int,
            ): SyncPlayRejoinOutcome =
                sessionMutex.withLock {
                    if (_state.value !is SyncPlayState.Rejoining) return@withLock SyncPlayRejoinOutcome.Aborted
                    _state.value = SyncPlayState.Rejoining(target, attempt)
                    val groups =
                        runCatchingUnlessCancelled { api.getGroups() }
                            .onFailure { Timber.w(it, "Could not list the SyncPlay groups while rejoining") }
                            .getOrElse { return@withLock SyncPlayRejoinOutcome.Failed }
                    if (groups.none { it.id == target.id }) return@withLock SyncPlayRejoinOutcome.Dissolved
                    if (performJoin(existing = target, newGroupName = null)) {
                        SyncPlayRejoinOutcome.Rejoined
                    } else {
                        closeSession()
                        SyncPlayRejoinOutcome.Failed
                    }
                }

            override suspend fun tearDown(
                message: SyncPlayMessage?,
                pausePlayer: Boolean,
            ) = teardown(message, pausePlayer)

            override fun announce(message: SyncPlayMessage) {
                _messages.tryEmit(message)
            }
        }

        /**
         * Ends the session the server has already ended, keeping `rejoinTarget` — and the clock
         * offset, because it is the same server and the rejoin handshake needs it immediately.
         */
        private suspend fun standDown(target: SyncPlayGroupSummary) {
            Timber.w("Lost the SyncPlay membership of %s server-side; taking it back", target.id)
            _state.value = SyncPlayState.Rejoining(target, attempt = 1)
            releaseSession()
            session = GroupSessionState.carriedAcrossStandDown(session)
            // Frozen, never resumed: the rejoin does not start playback, the group does.
            withContext(mainDispatcher) { playerHandle.pause() }
        }

        /**
         * The phase mirrors the group's state **except `Playing`**, which is owned by the applied
         * unpause: only that knows the anchor, and taking it from a state update would leave the
         * drift monitor nothing to measure.
         *
         * **WAITING pauses** — a member playing on behind the overlay drifts ahead (measured at
         * seven seconds over eight on device). The *player* is asked whether it is running, not the
         * phase: the phase is the thing that lies after a lost command.
         *
         * **PAUSED arms a net**, because a pause command that never arrives would leave this member
         * playing for ever with the drift monitor (which runs in `Playing` only) shut off with it.
         */
        private suspend fun onGroupStateChanged(groupState: SyncPlayGroupState) {
            val resumedFromPause =
                (_state.value as? SyncPlayState.InGroup)?.groupState == SyncPlayGroupState.Paused
            setGroupState(groupState)
            if (groupState == SyncPlayGroupState.Playing) {
                recoveryNets.groupPlayingAnchor =
                    (if (resumedFromPause) parkedPlayerAnchor() else null) ?: inferredGroupAnchor()
                recoveryNets.cancelPauseNet()
                // The state update can beat this member's own `ready` or trail it, so the net is
                // armed from both ends. It disarms on the first command.
                recoveryNets.armSelfSync()
                return
            }
            recoveryNets.groupPlayingAnchor = null
            // A self-sync armed while the group was playing must never start playback in a group
            // that has since stopped.
            recoveryNets.cancelSelfSync()
            if (groupState == SyncPlayGroupState.Waiting && isPlayerRunning()) {
                Timber.d("SyncPlay group is waiting; holding this member where it is")
                withContext(mainDispatcher) { playerHandle.pause() }
            }
            if (groupState == SyncPlayGroupState.Paused) recoveryNets.armPauseNet()
            val phase =
                when (groupState) {
                    SyncPlayGroupState.Waiting -> SyncPlayPhase.Waiting
                    else -> SyncPlayPhase.Paused
                }
            setPhase(phase)
        }

        /**
         * **The instant is the queue's `lastUpdate`, not `serverNow()`.** `startPositionTicks` is
         * where the group was when it last published the queue; pairing it with now claims the group
         * has not moved since, and everything downstream then defends a timeline seconds behind.
         */
        private fun inferredGroupAnchor(): SyncPlayAnchor? {
            val queue = (_state.value as? SyncPlayState.InGroup)?.queue ?: return null
            return SyncPlayAnchor(queue.startPositionTicks.ticksToMillis(), queue.lastUpdate)
        }

        /**
         * Trusted before the queue's anchor on a pause-to-playing transition: every pause path parks
         * this member at the position the group froze at, while [inferredGroupAnchor] goes stale the
         * moment a pause/resume happens without a queue update — measured on device turning a 6:03
         * seek into a 23:17 landing off a seventeen-minute-old queue. A player still *running* is no
         * anchor (it may be the member that missed the pause), nor is one with nothing loaded.
         */
        private suspend fun parkedPlayerAnchor(): SyncPlayAnchor? {
            if (session.loadedPlaylistItemId == null) return null
            val snapshot = withContext(mainDispatcher) { playerHandle.snapshot() }
            if (snapshot.isPlaying) return null
            return SyncPlayAnchor(snapshot.positionMs, timeSync.serverNow())
        }

        private suspend fun onQueueChanged(queue: SyncPlayGroupQueue) {
            val current = _state.value as? SyncPlayState.InGroup
            if (current == null) {
                session.pendingQueue = queue
                return
            }
            // Queues can arrive out of order (the pre-join stash replayed by [enterGroup] racing a
            // live update), and an older queue applied over a newer one loads the slot the group
            // already left. `lastUpdate` is the server's own publication instant.
            val known = current.queue
            if (known != null && queue.lastUpdate.isBefore(known.lastUpdate)) {
                Timber.d("Ignoring a stale SyncPlay queue from %s; already on %s", queue.lastUpdate, known.lastUpdate)
                return
            }
            // `update`, not read-copy-write: a phase the scheduler's collector applies between this
            // function's read and its write must never be reverted by the queue.
            _state.update { if (it is SyncPlayState.InGroup) it.copy(queue = queue) else it }
            if (queue.isPlaying) recoveryNets.groupPlayingAnchor = inferredGroupAnchor()
            reconcile(queue)
        }

        /**
         * The decision itself is pure ([decideReconcile], which owns the slot-not-item identity);
         * this only gathers inputs and acts, so `loadedPlaylistItemId` has one write site per outcome.
         */
        private suspend fun reconcile(queue: SyncPlayGroupQueue) {
            val entry = queue.playingEntry
            val snapshot =
                if (host != null && entry != null && entry.playlistItemId != session.loadedPlaylistItemId) {
                    // A same-slot update must not pay the main-thread hop for an unread snapshot.
                    hostSnapshot()
                } else {
                    null
                }
            when (val action = decideReconcile(queue, session.loadedPlaylistItemId, host != null, snapshot)) {
                is ReconcileAction.None ->
                    if (action.playingEntry == null) {
                        session.loadedPlaylistItemId = null
                    } else {
                        onSameSlotUpdate(action.playingEntry, queue)
                    }

                is ReconcileAction.RequestLaunch -> {
                    session.loadedPlaylistItemId = null
                    _launchRequests.tryEmit(SyncPlayLaunchRequest(action.entry.itemId, queue.startPositionTicks))
                }

                is ReconcileAction.Adopt -> {
                    onEntryOpened(action.entry)
                    adoptOpenItem(action.entry, action.snapshot)
                }

                is ReconcileAction.Load -> loadEntry(action.entry, queue, snapshot)
            }
        }

        /** [ReconcileAction.Load]: the buffering/ready handshake around [SyncPlayPlaybackHost.loadItem]. */
        private suspend fun loadEntry(
            entry: SyncPlayQueueEntry,
            queue: SyncPlayGroupQueue,
            snapshot: SyncPlayHostSnapshot?,
        ) {
            val attached = host ?: return
            session.loadedPlaylistItemId = entry.playlistItemId
            setPhase(SyncPlayPhase.Buffering)
            // A new slot is a new player: what the old one applied means nothing here, and the
            // server's post-ready re-send must not be mistaken for a repeat (see onHostBuffering).
            scheduler.forgetApplied()
            oweReady(entry, fallbackMillis = null)
            runCatchingUnlessCancelled {
                api.reportBuffering(
                    timeSync.serverNow(),
                    queue.startPositionTicks,
                    reportedIsPlaying(snapshot),
                    entry.playlistItemId,
                )
            }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }

            val loaded =
                try {
                    attached.loadItem(entry.itemId, queue.startPositionTicks)
                } catch (cancellation: CancellationException) {
                    // Not necessarily *our* cancellation: the host loads on its own scope, so a
                    // screen dismissed mid-load cancels it from under us, and reporting the item
                    // unplayable would skip the queue forward for the whole group on a Back press.
                    currentCoroutineContext().ensureActive()
                    Timber.d(cancellation, "A SyncPlay item load was cancelled; leaving the queue alone")
                    session.loadedPlaylistItemId = null
                    return
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Throwable,
                ) {
                    Timber.w(error, "A SyncPlay item load failed")
                    false
                }
            if (loaded) onEntryOpened(entry) else onEntryUnplayable(entry)
        }

        /**
         * The host already holds the item: the handshake without the load. `buffering` is reported
         * first rather than `ready` — that is what puts the group back to waiting on this member,
         * which is what earns the unpause that clears the overlay.
         *
         * The fallback is [SETTLED_READY_FALLBACK_MS], not `null` as in the load branch: an
         * already-prepared player may have passed its readiness before the host was attached and
         * would then never announce itself, wedging the group on a `ready` that cannot come.
         */
        private suspend fun adoptOpenItem(
            entry: SyncPlayQueueEntry,
            snapshot: SyncPlayHostSnapshot,
        ) {
            setPhase(SyncPlayPhase.Buffering)
            oweReady(entry, fallbackMillis = SETTLED_READY_FALLBACK_MS)
            runCatchingUnlessCancelled {
                api.reportBuffering(
                    timeSync.serverNow(),
                    snapshot.positionTicks,
                    snapshot.isPlaying,
                    entry.playlistItemId,
                )
            }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }
        }

        /**
         * Some queue updates come with `SetAllBuffering(true)` server-side (new playlist, new current
         * item, next, previous): the group is waiting on everyone again and sits there until it
         * hears a `ready`. The player is already prepared, so the answer is one report.
         */
        private suspend fun onSameSlotUpdate(
            entry: SyncPlayQueueEntry,
            queue: SyncPlayGroupQueue,
        ) {
            if (queue.reason !in READY_OWING_REASONS || host == null) return
            reportReady(entry)
        }

        /** A slot is open on the host; whatever was skipped to get here is forgiven. */
        private fun onEntryOpened(entry: SyncPlayQueueEntry) {
            session.loadedPlaylistItemId = entry.playlistItemId
            session.skippedSlots.clear()
        }

        /**
         * A group's queue is not filtered for this client, so `loadItem` returning `false` is normal.
         * Asking the group to move on produces another `PlayQueueUpdate`, so each slot is skipped
         * **once** — a queue of unplayable items costs one pass rather than cycling for ever.
         */
        private suspend fun onEntryUnplayable(entry: SyncPlayQueueEntry) {
            Timber.w("SyncPlay could not open item %s", entry.itemId)
            session.loadedPlaylistItemId = null
            _messages.tryEmit(SyncPlayMessage.ItemUnavailable)
            if (!session.skippedSlots.add(entry.playlistItemId)) {
                Timber.w("Not skipping past SyncPlay slot %s twice", entry.playlistItemId)
                return
            }
            runCatchingUnlessCancelled { api.requestNextItem(entry.playlistItemId) }
                .onFailure { Timber.w(it, "Could not skip past an unplayable SyncPlay item") }
        }

        // Player -------------------------------------------------------------------------------------

        private suspend fun onPlayerEvent(event: PlayerEvent) {
            when (event) {
                PlayerEvent.Ready -> onPlayerReady()
                PlayerEvent.Ended -> onPlaybackEnded()
                else -> Unit
            }
        }

        /**
         * A readiness the group is waiting for, and only that one: the player becomes ready again
         * after every applied seek and pause, and an unasked-for report is answered with the very
         * command that caused it.
         */
        private suspend fun onPlayerReady() {
            val entry = currentEntry() ?: return
            if (session.readyOwedFor != entry.playlistItemId) {
                Timber.v("Player ready with no SyncPlay handshake outstanding; saying nothing")
                return
            }
            reportReady(entry)
        }

        /**
         * @param fallbackMillis how long to wait for the player to announce itself before reporting
         *   `ready` anyway — needed wherever the player has no reason to re-buffer (a seek to where
         *   it already is). `null` where it really is being rebuilt.
         */
        private fun oweReady(
            entry: SyncPlayQueueEntry,
            fallbackMillis: Long?,
        ) {
            session.readyOwedFor = entry.playlistItemId
            session.readyFallbackJob?.cancel()
            session.readyFallbackJob =
                fallbackMillis?.let { millis ->
                    launchInSession {
                        delay(millis)
                        // Identity-guarded, and cleared *before* reportReady: an unconditional nil
                        // could orphan a replacement's handle, and reportReady's own disarm must not
                        // cancel the job that is reporting.
                        if (session.readyFallbackJob === coroutineContext[Job]) session.readyFallbackJob = null
                        val current = currentEntry() ?: return@launchInSession
                        if (session.readyOwedFor != current.playlistItemId) return@launchInSession
                        Timber.d("Player never re-buffered; reporting SyncPlay ready from where it stands")
                        reportReady(current)
                    }
                }
        }

        private inner class NetsDriver : SyncPlayRecoveryNets.Driver {
            override fun state(): SyncPlayState = _state.value

            override fun hasHost(): Boolean = host != null

            override fun launchNet(block: suspend CoroutineScope.() -> Unit): Job = launchInSession(block)

            override fun requestUnpause() = this@SyncPlayController.requestUnpause()

            override fun requestPause() = this@SyncPlayController.requestPause()

            override fun onSelfSynced(anchor: SyncPlayAnchor) = setPhase(SyncPlayPhase.Playing(anchor))
        }

        /** Whether the player is actually running — the one reading no lost command can falsify. */
        private suspend fun isPlayerRunning(): Boolean =
            withContext(mainDispatcher) {
                playerHandle.snapshot().isPlaying
            }

        /**
         * Re-enters the handshake from an already-prepared player ([onConnectivityBack]). The first
         * request after a blip is also the first chance to learn the membership did not survive it,
         * so a REST refusal is treated like the websocket's own `NotInGroup`.
         */
        private suspend fun renegotiate() {
            val entry = currentEntry() ?: return
            setPhase(SyncPlayPhase.Buffering)
            // The server answers the coming ready with a verbatim re-send, which the applied-memory
            // would dedupe away (see onHostBuffering).
            scheduler.forgetApplied()
            val snapshot = hostSnapshot()
            // The shared player's reading when no screen is attached: a detached member plays on in
            // the background, and a buffering report at 0 would misstate it by the whole duration.
            val positionTicks =
                snapshot?.positionTicks
                    ?: withContext(mainDispatcher) { playerHandle.snapshot() }.positionTicks
            val isPlaying = reportedIsPlaying(snapshot)
            oweReady(entry, fallbackMillis = SETTLED_READY_FALLBACK_MS)
            runCatchingUnlessCancelled {
                api.reportBuffering(
                    timeSync.serverNow(),
                    positionTicks,
                    isPlaying,
                    entry.playlistItemId,
                )
            }.onFailure { error ->
                Timber.w(error, "Could not report SyncPlay buffering")
                if (error.isMembershipRefused()) rejoinPolicy.onMembershipGone()
            }
        }

        /** A `403` from a SyncPlay call: this session may not act on that group any more. */
        private fun Throwable.isMembershipRefused(): Boolean =
            this is InvalidStatusException && status == HTTP_FORBIDDEN

        /**
         * Advancing the queue is the group's decision, not this client's: ask, and move when the
         * server tells everyone to.
         */
        private suspend fun onPlaybackEnded() {
            val entry = currentEntry() ?: return
            runCatchingUnlessCancelled { api.requestNextItem(entry.playlistItemId) }
                .onFailure { Timber.w(it, "Could not request the next SyncPlay item") }
        }

        /**
         * Clearing [readyOwedFor] *before* the call is what makes this happen once: the report is a
         * round trip, and a second readiness arriving inside it would send a second one.
         *
         * **A member reporting `ready` must be stopped, and say so.** `WaitingGroupState` answers a
         * `ReadyGroupRequest` whose `IsPlaying` is true by resuming everyone *else*, assuming a
         * client already playing will catch up on its own — which strands this member under the
         * WAITING overlay. So [parkForReady] stops the player first and the report carries
         * `isPlaying = false`.
         */
        private suspend fun reportReady(entry: SyncPlayQueueEntry) {
            session.readyOwedFor = null
            session.readyFallbackJob?.cancel()
            session.readyFallbackJob = null
            val parked = parkForReady()
            Timber.d(
                "Reporting SyncPlay ready at %d ticks (parked the player: %b)",
                parked.positionTicks,
                parked.parked,
            )
            runCatchingUnlessCancelled {
                api.reportReady(timeSync.serverNow(), parked.positionTicks, false, entry.playlistItemId)
            }.onFailure { Timber.w(it, "Could not report SyncPlay ready") }
            if ((_state.value as? SyncPlayState.InGroup)?.phase == SyncPlayPhase.Buffering) {
                setPhase(SyncPlayPhase.Waiting)
            }
            recoveryNets.armSelfSync()
        }

        /**
         * One pass on the main dispatcher: the position reported has to be the one the player is
         * parked at, and a second snapshot after the pause would only add dispatch jitter. With no
         * host attached the *shared* player answers both questions — a detached member forty minutes
         * in would otherwise report `ready` at 0, and the server schedules the group off that.
         */
        private suspend fun parkForReady(): ParkedForReady =
            withContext(mainDispatcher) {
                val hostSnapshot = host?.snapshot()
                val playback = playerHandle.snapshot()
                val running = hostSnapshot?.isPlaying ?: playback.isPlaying
                if (running) {
                    Timber.i("Parking the player to report ready")
                    playerHandle.pause()
                }
                ParkedForReady(hostSnapshot?.positionTicks ?: playback.positionTicks, running)
            }

        private data class ParkedForReady(
            val positionTicks: Long,
            val parked: Boolean,
        )

        private fun onCommand(command: SyncPlayCommand) {
            // Logged on arrival as well as on application: only the pair distinguishes "the command
            // never came" from "the command came and did nothing" in a device log.
            Timber.d("SyncPlay command %s for %s (emitted %s)", command.type, command.whenInstant, command.emittedAt)
            if (_state.value !is SyncPlayState.InGroup) return
            scheduler.schedule(command)
        }

        private suspend fun onCommandApplied(applied: SyncPlayAppliedCommand) {
            recoveryNets.cancelSelfSync()
            recoveryNets.cancelPauseNet()
            val phase =
                when (applied.command.type) {
                    SyncPlayCommandType.Unpause -> applied.anchor?.let(SyncPlayPhase::Playing) ?: SyncPlayPhase.Paused
                    // A seek invalidates the current anchor; the unpause after the group's
                    // re-handshake sets the next one.
                    else -> SyncPlayPhase.Paused
                }
            setPhase(phase)
            // A group seek puts every member back to buffering server-side and waits for a `ready`
            // from each; the fallback covers a seek the player is already sitting on, which would
            // otherwise never re-buffer and never announce itself.
            if (applied.command.type == SyncPlayCommandType.Seek) {
                currentEntry()?.let { oweReady(it, fallbackMillis = SETTLED_READY_FALLBACK_MS) }
            }
        }

        private suspend fun observeAnchor() {
            state
                .map { ((it as? SyncPlayState.InGroup)?.phase as? SyncPlayPhase.Playing)?.anchor }
                .distinctUntilChanged()
                .collectLatest { anchor -> if (anchor != null) driftMonitor.monitor(anchor) }
        }

        // Plumbing -----------------------------------------------------------------------------------

        /**
         * Every user transport action funnels through here, which is where the one rule is enforced
         * structurally: there is no path from an intent to [playerHandle].
         */
        private fun request(block: suspend () -> Unit) {
            if (_state.value !is SyncPlayState.InGroup) {
                Timber.d("Ignoring a SyncPlay request while not in a group")
                return
            }
            scope.launch {
                runCatchingUnlessCancelled { block() }.onFailure { Timber.w(it, "A SyncPlay request failed") }
            }
        }

        private fun launchInSession(block: suspend CoroutineScope.() -> Unit): Job =
            (sessionScope ?: scope).launch(block = block)

        private fun setPhase(phase: SyncPlayPhase) {
            _state.update { current ->
                if (current is SyncPlayState.InGroup) current.copy(phase = phase) else current
            }
        }

        private fun setGroupState(groupState: SyncPlayGroupState) {
            _state.update { current ->
                if (current is SyncPlayState.InGroup) current.copy(groupState = groupState) else current
            }
        }

        private fun updateGroup(transform: (SyncPlayGroupSummary) -> SyncPlayGroupSummary) {
            _state.update { current ->
                if (current is SyncPlayState.InGroup) current.copy(group = transform(current.group)) else current
            }
        }

        private fun currentEntry(): SyncPlayQueueEntry? = (_state.value as? SyncPlayState.InGroup)?.queue?.playingEntry

        private suspend fun hostSnapshot(): SyncPlayHostSnapshot? {
            val attached = host ?: return null
            return withContext(mainDispatcher) { attached.snapshot() }
        }

        /**
         * For a `buffering` report only: a blanket `false` would be a lie the *server* acts on. A
         * `ready` is reported from a player [parkForReady] has just stopped, so its answer is
         * `false` by construction.
         */
        private suspend fun reportedIsPlaying(snapshot: SyncPlayHostSnapshot?): Boolean =
            snapshot?.isPlaying ?: isPlayerRunning()

        companion object {
            /** How long the join waits for the websocket before going ahead without it. */
            const val SOCKET_READY_TIMEOUT_MS = 5_000L

            /**
             * Long enough to ride out the two-second Wi-Fi blip that ejected the group on device;
             * playback is frozen for the duration, so the cost of being wrong is a pause, not drift.
             */
            const val CONNECTIVITY_GRACE_MS = 5_000L

            /** Three at the pinger's five-second cadence: ~15 s, which beats the server's disposal. */
            const val PING_FAILURE_STREAK = 3

            /** How long an owed `ready` waits for a player that may never re-buffer, in millis. */
            const val SETTLED_READY_FALLBACK_MS = 1_500L

            /**
             * `WaitingGroupState` calls `SetAllBuffering(true)` for each of these and then waits for
             * a `ready` from everyone — including a member whose playing slot did not change.
             */
            private val READY_OWING_REASONS =
                setOf(
                    SyncPlayQueueUpdateReason.NewPlaylist,
                    SyncPlayQueueUpdateReason.SetCurrentItem,
                    SyncPlayQueueUpdateReason.NextItem,
                    SyncPlayQueueUpdateReason.PreviousItem,
                )

            private const val EVENT_BUFFER = 8
        }
    }
