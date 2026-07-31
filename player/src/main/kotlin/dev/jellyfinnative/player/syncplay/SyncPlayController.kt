package dev.jellyfinnative.player.syncplay

import dev.jellyfinnative.core.network.SessionStateHolder
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.player.di.MainDispatcher
import dev.jellyfinnative.player.model.ticksToMillis
import dev.jellyfinnative.player.session.PlayerEvent
import dev.jellyfinnative.player.session.PlayerHandle
import dev.jellyfinnative.player.syncplay.api.SyncPlayApi
import dev.jellyfinnative.player.syncplay.di.SyncPlayScope
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommand
import dev.jellyfinnative.player.syncplay.model.SyncPlayCommandType
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupQueue
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupState
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyfinnative.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyfinnative.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyfinnative.player.syncplay.socket.SyncPlaySocket
import dev.jellyfinnative.player.syncplay.socket.SyncPlaySocketState
import dev.jellyfinnative.player.syncplay.time.SyncPlayPinger
import dev.jellyfinnative.player.syncplay.time.SyncPlayTimeSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The SyncPlay coordinator: everything the group protocol does to this device, and everything this
 * device asks of the group.
 *
 * A `@Singleton` with its own scope rather than a ViewModel, because group membership is not a
 * screen (docs/notes/syncplay-m11-plan.md, key decisions 4 and 5). The user can back out of the
 * player, the app can be backgrounded, and the group carries on — commands still have to land on
 * the shared [PlayerHandle], and a `PlayQueueUpdate` for an item nobody has open still has to bring
 * the player back ([launchRequests]).
 *
 * ### The one rule that shapes the whole class
 * **In a group, nothing this client does moves this client's player.** Pause, seek, next, queue
 * edits — all of them are requests to the server, and the player moves only when the server
 * rebroadcasts the matching command to everyone (key decision 11). It is what makes "in lockstep"
 * true rather than approximately true, and it is why every intent below is a one-line API call.
 *
 * ### Join handshake
 * Collect the websocket → join → the server sends the group and its queue → open the item paused
 * (or ask the app to open a player) → report `buffering` → report `ready` when the player is → the
 * server flips the group out of WAITING and schedules an unpause everyone applies at the same
 * instant.
 *
 * The websocket is collected *before* the join call, not after: collecting is what opens it (the SDK
 * has no `connect()`), and a group joined before the socket is up would never hear its own
 * `GroupJoined`/`PlayQueueUpdate`.
 *
 * **A `ready` is reported only when one is owed.** Readiness is not news on its own: the server
 * answers a `ready` from a group that is not waiting by re-sending that group's current state
 * command to this session alone ("Client got lost, sending current state" — `PausedGroupState`
 * and `PlayingGroupState`, `HandleRequest(ReadyGroupRequest)`). Since applying a pause or a seek
 * repositions the player and makes it emit another readiness, reporting every one of them is a
 * closed loop, and on device it ran at some thirteen requests a second (STATUS.md, DoD session #1,
 * B1). So [readyOwedFor] records the slot the group is actually waiting on — set when this member
 * loads an item, re-negotiates, or is handed a seek — and a readiness with nothing owed is silence.
 *
 * ### Losing the connection
 * A confirmed loss pauses the player and — once the rejoin below has failed — leaves the group and
 * says so (key decision 10 as amended). Nothing here resumes: playing on would mean drifting from
 * the group invisibly, so the state change is made honest and the user resumes solo with one tap.
 * Three things confirm one, and they are deliberately one mechanism ([confirmLoss]):
 *
 * | signal | why it is confirmation | delay |
 * |---|---|---|
 * | the socket collection ending | the SDK reconnects on its own; a *finished* stream is it giving up | immediate |
 * | [PING_FAILURE_STREAK] failed ping cycles | the REST API has stopped answering, whatever the OS says | ≥ 15 s |
 * | offline for [CONNECTIVITY_GRACE_MS] | the radio is genuinely gone, not switching | 5 s |
 *
 * The grace window is what a two-second Wi-Fi blip costs instead of the group (B9): playback is
 * frozen for it — paused, not run on into a drift nobody asked for — and connectivity returning
 * inside the window re-enters the buffering/ready handshake so the server re-syncs this member
 * rather than tearing anything down. A momentary socket flap that the SDK reconnects through is
 * *not* a loss and deliberately does nothing.
 *
 * ### Getting the group back
 * Surviving the blip is not the same thing as keeping the membership, because the *server* does not
 * survive it: a dropped websocket ends the session, `SyncPlayManager.OnSessionEnded` calls
 * `LeaveGroup`, and the next request this client makes arrives on a brand-new session that belongs
 * to no group — answered with a `SyncPlayNotInGroupUpdate` over the socket (the ping loop discovers
 * it within five seconds even when nothing else is happening). Nobody here asked to leave, so the
 * controller takes the membership back rather than reporting a loss (DECISIONS.md 2026-07-31,
 * amending key decision 10 a second time). **A confirmed loss goes the same way**: on the device the
 * grace window usually expires *before* anything discovers the removal, so [confirmLoss] hands over
 * to the rejoin rather than ending the group, and reaches the old ending only if the attempts do.
 * In detail:
 *
 * - the group is remembered in [rejoinTarget] for as long as membership is *not* given up
 *   deliberately — [leaveGroup], sign-out, `LibraryAccessDenied` and `GroupGone` all forget it, and
 *   so does a removal that arrives over a socket which was never in trouble ([recentlyTroubled]);
 * - losing it after trouble stands the session down into [SyncPlayState.Rejoining] and runs up to
 *   [REJOIN_MAX_ATTEMPTS] attempts, [REJOIN_RETRY_DELAY_MS] apart, of "list the groups, and if ours
 *   is still there, join it" — the ordinary join flow, handshake and all;
 * - the player is paused for the whole of it and is never started by the rejoin itself; the group's
 *   answer to this member's `ready` is what puts it back in step;
 * - a group that is no longer listed has dissolved (we were its last member), and exhausted attempts
 *   are the old ending: [teardown] to [SyncPlayState.Idle] with a message. There is no background
 *   retry loop after that — once out, we stay out until the user acts.
 */
@Singleton
@Suppress(
    "TooManyFunctions", // A protocol coordinator; the intents alone are the plan's 15.
    // One membership lifecycle: joining, rejoining, losing and leaving all read and write the same
    // session scope, the same handshake bookkeeping and the same lock. Splitting it would mean
    // publishing that state to a collaborator, which is a larger surface than the class it saves.
    "LargeClass",
)
class SyncPlayController
    @Inject
    constructor(
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
        @SyncPlayScope private val scope: CoroutineScope,
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    ) {
        private val _state = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)

        /** Where the group is, and where this member is inside it. */
        val state: StateFlow<SyncPlayState> = _state.asStateFlow()

        private val _messages =
            MutableSharedFlow<SyncPlayMessage>(
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** Things the user has to be told; the UI owns the copy (M11 Phase 3). */
        val messages: SharedFlow<SyncPlayMessage> = _messages.asSharedFlow()

        private val _launchRequests =
            MutableSharedFlow<SyncPlayLaunchRequest>(
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** "The group moved on and no player is open" — collected by the NavHost (M11 Phase 5). */
        val launchRequests: SharedFlow<SyncPlayLaunchRequest> = _launchRequests.asSharedFlow()

        /** Serialises joining, leaving and tearing down; nothing else may change membership. */
        private val sessionMutex = Mutex()

        private var sessionJob: Job? = null
        private var sessionScope: CoroutineScope? = null

        @Volatile
        private var host: SyncPlayPlaybackHost? = null

        /** The slot the host currently has open, so a repeated `PlayQueueUpdate` reloads nothing. */
        private var loadedPlaylistItemId: UUID? = null

        /**
         * Slots this device could not open and has already asked the group to move past.
         *
         * The loop guard for [onEntryUnplayable]: skipping is itself a request that produces another
         * `PlayQueueUpdate`, so without a memory a queue of unplayable items would cycle for ever.
         * Cleared as soon as anything opens successfully.
         */
        private val skippedSlots = mutableSetOf<UUID>()

        /** `true` once the group has been told to stop waiting on us, so re-attaching can undo it. */
        private var ignoreWaitSent = false

        /** Group updates that arrived before the join call returned; replayed by [enterGroup]. */
        private var pendingGroup: SyncPlayGroupSummary? = null
        private var pendingQueue: SyncPlayGroupQueue? = null

        /**
         * The slot the group is waiting on a `ready` for, or `null` when it is waiting on nothing.
         *
         * The whole of the anti-storm rule (see the class docs): a `PlayerEvent.Ready` is only worth
         * reporting when the server has actually reset this member to buffering.
         */
        private var readyOwedFor: UUID? = null

        /** Reports the owed `ready` when the player will not re-buffer to announce itself. */
        private var readyFallbackJob: Job? = null

        /** The B3 safety net: fires when a completed handshake produced no command. */
        private var selfSyncJob: Job? = null

        /** The B9 grace window: fires when connectivity did not come back in time. */
        private var connectivityGraceJob: Job? = null

        /** Ping cycles that have failed in a row; [PING_FAILURE_STREAK] of them is a confirmed loss. */
        private var pingFailures = 0

        /**
         * Where the *group* is on its own timeline, as of the last time it said it was playing.
         *
         * Not the same thing as the anchor in [SyncPlayPhase.Playing]: that one is established by an
         * applied unpause and is exact, this one is inferred from the group's state updates and is
         * only ever used when no unpause arrived at all ([selfSyncToGroup]). `null` whenever the
         * group is not known to be playing.
         */
        private var groupPlayingAnchor: SyncPlayAnchor? = null

        /**
         * The group to take back if the server drops this session, or `null` if leaving would be
         * nobody's mistake.
         *
         * Set on entering a group and cleared by every *deliberate* exit, which is the whole of the
         * "never auto-rejoin something the user or the server meant to end" rule.
         */
        private var rejoinTarget: SyncPlayGroupSummary? = null

        /** The rejoin attempt loop, so leaving or signing out can abort it. */
        private var rejoinJob: Job? = null

        /**
         * When the connection last misbehaved, on the server clock.
         *
         * What tells "the server dropped us because the connection went" apart from "the server
         * dropped us on purpose": only the first is worth rejoining, and only the first is preceded
         * by connectivity going away, a ping failing, or the socket leaving `Connected`. Kept for
         * [REJOIN_TROUBLE_WINDOW_MS], because the removal is discovered by the *next* request rather
         * than at the moment of the trouble.
         */
        private var troubledAt: Instant? = null

        init {
            // On the singleton scope rather than a group session, because a rejoin attempt stands the
            // session down: a sign-out in the middle of one has to be able to abort it, and a watcher
            // that was cancelled along with the session could not.
            scope.launch { watchSignOut() }
        }

        // Membership intents ------------------------------------------------------------------------

        /** Creates a group and joins it. */
        fun createGroup(name: String) {
            scope.launch { startSession(existing = null, newGroupName = name) }
        }

        /**
         * Joins [group].
         *
         * Takes the summary rather than an id because every caller has one (the groups screen lists
         * them) and the server's own `GroupJoined` refreshes it a moment later anyway — so the
         * alternative would be a `getGroups` round trip to fill in a name we already knew.
         */
        fun joinGroup(group: SyncPlayGroupSummary) {
            scope.launch { startSession(existing = group, newGroupName = null) }
        }

        /** Leaves the group. Playback is left exactly as it is, now solo. */
        fun leaveGroup() {
            // Before the launch, not inside it: this is the one signal that the exit is deliberate,
            // and a rejoin attempt racing the coroutine must not read a target that is still set.
            rejoinTarget = null
            scope.launch {
                leaveOnServer()
                teardown(message = null, pausePlayer = false)
            }
        }

        // Transport intents — requests to the server, never local playback changes ------------------

        fun requestPause() = request { api.requestPause() }

        fun requestUnpause() = request { api.requestUnpause() }

        fun requestSeek(positionTicks: Long) = request { api.requestSeek(positionTicks) }

        fun requestNext() = request { currentEntry()?.let { api.requestNextItem(it.playlistItemId) } }

        fun requestPrevious() = request { currentEntry()?.let { api.requestPreviousItem(it.playlistItemId) } }

        fun requestSetPlaylistItem(playlistItemId: UUID) = request { api.setPlaylistItem(playlistItemId) }

        // Queue intents ----------------------------------------------------------------------------

        fun setNewQueue(
            itemIds: List<UUID>,
            playingItemPosition: Int = 0,
            startPositionTicks: Long = 0L,
        ) = request { api.setNewQueue(itemIds, playingItemPosition, startPositionTicks) }

        fun addToQueue(
            itemIds: List<UUID>,
            mode: SyncPlayQueueMode,
        ) = request { api.addToQueue(itemIds, mode) }

        fun moveQueueItem(
            playlistItemId: UUID,
            newIndex: Int,
        ) = request { api.movePlaylistItem(playlistItemId, newIndex) }

        fun removeFromQueue(playlistItemIds: List<UUID>) = request { api.removeFromPlaylist(playlistItemIds) }

        fun setShuffle(mode: SyncPlayShuffleMode) = request { api.setShuffleMode(mode) }

        fun setRepeat(mode: SyncPlayRepeatMode) = request { api.setRepeatMode(mode) }

        // Host attachment ---------------------------------------------------------------------------

        /**
         * Hands the controller a player to drive.
         *
         * If the group is already on an item, this is what gets it opened — including the ordinary
         * case where the user opened that very item themselves, which is adopted rather than
         * reloaded (see [reconcile]).
         */
        fun attachHost(host: SyncPlayPlaybackHost) {
            this.host = host
            val current = _state.value as? SyncPlayState.InGroup ?: return
            launchInSession {
                if (ignoreWaitSent) {
                    ignoreWaitSent = false
                    runCatching { api.setIgnoreWait(false) }
                        .onFailure { Timber.w(it, "Could not clear SyncPlay ignore-wait") }
                }
                current.queue?.let { reconcile(it) }
            }
        }

        /**
         * Gives the player back, keeping the group.
         *
         * `setIgnoreWait(true)` is the mechanism jellyfin-web uses for exactly this (key decision 5):
         * a member with no player must never be the reason everyone else is stuck in WAITING. The
         * group survives, and a later `PlayQueueUpdate` re-launches a player via [launchRequests].
         *
         * @param host ignored unless it is the attached one, so a stale ViewModel's teardown cannot
         *   detach the player that replaced it.
         */
        fun detachHost(host: SyncPlayPlaybackHost) {
            if (this.host !== host) return
            this.host = null
            loadedPlaylistItemId = null
            skippedSlots.clear()
            scheduler.cancel()
            if (_state.value !is SyncPlayState.InGroup) return
            setPhase(SyncPlayPhase.Paused)
            scope.launch {
                runCatching { api.setIgnoreWait(true) }
                    .onSuccess { ignoreWaitSent = true }
                    .onFailure { Timber.w(it, "Could not set SyncPlay ignore-wait") }
            }
        }

        /**
         * Re-enters the handshake because the host started re-negotiating (quality, track, decoder
         * fallback), which throws away the prepared player and rebuilds it.
         *
         * Called by the host rather than inferred, because `PlayerEvent` has no "buffering" —
         * ExoPlayer's re-prepare is invisible from here, and a group that was not told would keep
         * playing while this member reloads.
         */
        fun onHostBuffering() {
            val entry = currentEntry() ?: return
            setPhase(SyncPlayPhase.Buffering)
            // The player really is being rebuilt, so its own readiness is what ends this handshake:
            // no fallback, however long the re-negotiation takes.
            oweReady(entry, fallbackMillis = null)
            launchInSession {
                val positionTicks = hostSnapshot()?.positionTicks ?: 0L
                runCatching { api.reportBuffering(timeSync.serverNow(), positionTicks, false, entry.playlistItemId) }
                    .onFailure { Timber.w(it, "Could not report SyncPlay buffering") }
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
         * The join itself: collect the socket, ask the server, enter the group.
         *
         * Shared by the first join and by every rejoin attempt, deliberately — a rejoin that took a
         * shortcut would be a second protocol implementation, and the handshake is the part that has
         * to be identical for the server to put this member back in step.
         *
         * @return `true` when the group was entered. On `false` the session scope is still open and
         *   the caller decides what to do with it.
         */
        private suspend fun performJoin(
            existing: SyncPlayGroupSummary?,
            newGroupName: String?,
        ): Boolean {
            pendingGroup = null
            pendingQueue = null
            val session = openSession()
            session.launch { collectStream("group updates") { socket.groupUpdates.collect(::onGroupUpdate) } }
            session.launch { collectStream("commands") { socket.commands.collect(::onCommand) } }
            awaitSocketReady()

            val joined =
                runCatching {
                    if (existing != null) {
                        api.joinGroup(existing.id)
                        existing
                    } else {
                        api.createGroup(requireNotNull(newGroupName))
                    }
                }
            return joined
                .onSuccess { enterGroup(it, session) }
                .onFailure { Timber.w(it, "Could not join a SyncPlay group") }
                .isSuccess
        }

        /**
         * Waits for the socket to actually be up before the join call goes out.
         *
         * Bounded, because a socket that never connects must not block the join for ever: joining
         * anyway costs at most the initial `PlayQueueUpdate`, and the group is still joined.
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
            session: CoroutineScope,
        ) {
            _state.value = SyncPlayState.InGroup(pendingGroup ?: group, null, SyncPlayPhase.Waiting)
            pendingGroup = null
            rejoinTarget = group
            troubledAt = null
            statusHolder.setInGroup(true)

            session.launch { pinger.run(::onPingOutcome) }
            session.launch { playerHandle.events.collect(::onPlayerEvent) }
            session.launch { scheduler.applied.collect(::onCommandApplied) }
            session.launch { observeAnchor() }
            session.launch { watchConnectivity() }
            session.launch { watchSocket() }

            // A rejoin lands on a new server session, which knows nothing of the ignore-wait this
            // client sent when it gave the player back — so a member with no player would silently
            // start gating the group again.
            if (host == null && ignoreWaitSent) {
                session.launch {
                    runCatching { api.setIgnoreWait(true) }
                        .onFailure { Timber.w(it, "Could not restore SyncPlay ignore-wait after rejoining") }
                }
            }

            // Off the join path deliberately: opening an item can take a while, and nothing else
            // should be waiting behind it on the membership lock.
            pendingQueue?.let { queued ->
                pendingQueue = null
                session.launch { onQueueChanged(queued) }
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
         * Ends the group session.
         *
         * Idempotent by way of the state check inside the lock: a connection loss can be reported by
         * both socket collections at once, and the player must be paused once, not twice.
         */
        private suspend fun teardown(
            message: SyncPlayMessage?,
            pausePlayer: Boolean,
        ) {
            // Outside the lock, and first: a rejoin attempt holds the membership lock across its
            // REST calls, so cancelling it is what lets this teardown get in at all.
            abandonRejoin()
            sessionMutex.withLock {
                if (_state.value is SyncPlayState.Idle) return@withLock
                _state.value = SyncPlayState.Idle
                statusHolder.setInGroup(false)
                statusHolder.setMintedPlaySessionId(null)
                scheduler.cancel()
                closeSession()
                timeSync.reset()
                loadedPlaylistItemId = null
                skippedSlots.clear()
                ignoreWaitSent = false
                pendingGroup = null
                pendingQueue = null
                forgetHandshake()
                connectivityGraceJob = null
                pingFailures = 0
                troubledAt = null
                groupPlayingAnchor = null
                if (pausePlayer) withContext(mainDispatcher) { playerHandle.pause() }
                message?.let { _messages.tryEmit(it) }
            }
        }

        /** Forgets the group and stops any attempt to take it back. */
        private fun abandonRejoin() {
            rejoinTarget = null
            rejoinJob?.cancel()
            rejoinJob = null
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
            runCatching { api.leaveGroup() }
                .onFailure { Timber.w(it, "Could not leave the SyncPlay group cleanly") }
        }

        // Websocket ----------------------------------------------------------------------------------

        /**
         * Collects one socket stream, treating its end as the end of the group.
         *
         * The SDK reconnects on its own and the flow rides through it, so a stream that *finishes* —
         * normally or with an error — is the SDK having given up, which is the confirmed loss key
         * decision 10 talks about. Momentary `Disconnected` states on `SyncPlaySocket.connectionState`
         * are deliberately not watched: they flap.
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
            confirmLoss()
        }

        // Losing the connection ------------------------------------------------------------------------

        /**
         * The connection is gone as far as this client can tell — ask for the group back first.
         *
         * It used to be the end of the group outright. On the device it is also the *usual* way the
         * membership is lost: a three-second Wi-Fi drop costs more than [CONNECTIVITY_GRACE_MS] of
         * reported-offline once association, DHCP and the reachability probe are counted, so the
         * grace expires before anything has had the chance to discover a `NotInGroup`. Handing it to
         * the rejoin loop changes nothing when the connection really has gone — every attempt is
         * gated on being online, and the ending is the same [SyncPlayMessage.ConnectionLost] a few
         * seconds later, with the player paused from this moment either way.
         */
        private fun confirmLoss() {
            val target = rejoinTarget
            if (target == null) {
                tearDownAsync(SyncPlayMessage.ConnectionLost, pausePlayer = true)
                return
            }
            if (rejoinJob?.isActive == true) return
            rejoinJob = scope.launch { rejoin(target) }
        }

        /**
         * Watches connectivity for a loss worth confirming.
         *
         * Both edges, not just the first offline one: a Wi-Fi handover or a two-second blip reports
         * offline and then online again within a moment, and dropping the group on the transition is
         * exactly what the plan says must not happen ("never on a transient blip the socket recovers
         * from"). So going offline opens a [CONNECTIVITY_GRACE_MS] window instead, and coming back
         * inside it closes the window and re-negotiates.
         */
        private suspend fun watchConnectivity() {
            connectionState.state
                .map { it.isOnline }
                .distinctUntilChanged()
                .collect { online -> if (online) onConnectivityBack() else onConnectivityGone() }
        }

        /**
         * Connectivity went away; freeze and start counting.
         *
         * Freezing rather than playing on is a deliberate choice (DECISIONS.md 2026-07-31): five
         * seconds of playback with no way to hear a pause is five seconds of drift the group cannot
         * see, and a member that stops for a moment and resumes in step is easier to understand than
         * one that quietly ends up ahead. It also makes the hard-kill case — where the window will
         * expire — pause immediately rather than at the end of the grace.
         */
        private fun onConnectivityGone() {
            markTrouble()
            if (connectivityGraceJob != null) return
            Timber.w("Connectivity lost while in a SyncPlay group; freezing for %d ms", CONNECTIVITY_GRACE_MS)
            connectivityGraceJob =
                launchInSession {
                    withContext(mainDispatcher) { playerHandle.pause() }
                    setPhase(SyncPlayPhase.Waiting)
                    delay(CONNECTIVITY_GRACE_MS)
                    connectivityGraceJob = null
                    Timber.w("Connectivity did not return within the SyncPlay grace window")
                    confirmLoss()
                }
        }

        /** Connectivity came back inside the window: keep the group, and ask it to re-sync us. */
        private fun onConnectivityBack() {
            val grace = connectivityGraceJob ?: return
            connectivityGraceJob = null
            grace.cancel()
            Timber.i("Connectivity returned inside the SyncPlay grace window; re-negotiating")
            launchInSession { renegotiate() }
        }

        /**
         * Counts ping cycles, and calls a long enough silence what it is.
         *
         * The device case this exists for: the OS still reports a usable network while the platform
         * has quietly cut the app's, so every REST call times out, the socket never reconnects, and
         * the server disposes the group — with nothing here noticing for minutes (B8). The ping loop
         * is the only fixed-cadence conversation with the server, so its failures are the signal.
         */
        private fun onPingOutcome(succeeded: Boolean) {
            if (succeeded) {
                pingFailures = 0
                return
            }
            markTrouble()
            pingFailures++
            if (pingFailures < PING_FAILURE_STREAK) return
            Timber.w("SyncPlay: %d ping cycles failed in a row; treating it as a lost connection", pingFailures)
            confirmLoss()
        }

        /**
         * Watches the socket's own state, for the record only.
         *
         * Nothing here ends a group — a flapping socket is the SDK doing its job, and reacting to it
         * is exactly the bug the loss rules avoid. It is collected because a socket that went away
         * and came back is the *reason* the server no longer has this session in the group, and
         * [recentlyTroubled] is what stops an unexplained removal being rejoined.
         */
        private suspend fun watchSocket() {
            socket.connectionState
                .distinctUntilChanged()
                .collect { if (it !is SyncPlaySocketState.Connected) markTrouble() }
        }

        /** Records that the connection misbehaved just now; see [troubledAt]. */
        private fun markTrouble() {
            troubledAt = timeSync.serverNow()
        }

        /** Whether the connection misbehaved recently enough to explain a removal. */
        private fun recentlyTroubled(): Boolean {
            val at = troubledAt ?: return false
            return Duration.between(at, timeSync.serverNow()).toMillis() <= REJOIN_TROUBLE_WINDOW_MS
        }

        /** Signing out ends any membership, in any state, and forgets it for good. */
        private suspend fun watchSignOut() {
            sessionStateHolder.state.collect { session ->
                if (session !is SessionState.LoggedOut || _state.value is SyncPlayState.Idle) return@collect
                rejoinTarget = null
                leaveOnServer()
                teardown(message = null, pausePlayer = false)
            }
        }

        private suspend fun onGroupUpdate(event: SyncPlayGroupEvent) {
            Timber.d("SyncPlay group update: %s", event)
            when (event) {
                is SyncPlayGroupEvent.Joined -> onJoined(event.group)
                is SyncPlayGroupEvent.Left -> onLeft(event.groupId)
                is SyncPlayGroupEvent.StateChanged -> onGroupStateChanged(event.state)
                is SyncPlayGroupEvent.QueueChanged -> onQueueChanged(event.queue)
                is SyncPlayGroupEvent.UserJoined -> updateGroup { it.copy(participants = it.participants + event.name) }
                is SyncPlayGroupEvent.UserLeft -> updateGroup { it.copy(participants = it.participants - event.name) }
                SyncPlayGroupEvent.NotInGroup -> onMembershipGone()
                // Definitive, and nothing a rejoin could undo: the id this client would ask for is
                // the one the server has just said does not exist.
                SyncPlayGroupEvent.GroupGone -> forgetAndTearDown(SyncPlayMessage.GroupEnded)
                SyncPlayGroupEvent.LibraryAccessDenied -> forgetAndTearDown(SyncPlayMessage.LibraryAccessDenied)
            }
        }

        private fun onJoined(group: SyncPlayGroupSummary) {
            if (_state.value is SyncPlayState.InGroup) updateGroup { group } else pendingGroup = group
        }

        private fun onLeft(groupId: UUID) {
            val current = _state.value as? SyncPlayState.InGroup ?: return
            if (current.group.id != groupId) return
            onMembershipGone()
        }

        /** Gives up the group deliberately: no rejoin will follow. */
        private fun forgetAndTearDown(message: SyncPlayMessage) {
            rejoinTarget = null
            tearDownAsync(message)
        }

        // Taking the group back --------------------------------------------------------------------

        /**
         * The server no longer has this session in the group — decide whether that was meant.
         *
         * A removal over a connection that has been well all along is the server or another client
         * saying so, and is obeyed exactly as it always was. A removal after [recentlyTroubled] is
         * the websocket having dropped, `OnSessionEnded` having called `LeaveGroup` on our behalf,
         * and nobody having wanted any of it — so the membership is taken back.
         */
        private fun onMembershipGone() {
            val target = rejoinTarget
            if (target == null || !recentlyTroubled()) {
                Timber.i("Removed from the SyncPlay group with the connection healthy; not rejoining")
                forgetAndTearDown(SyncPlayMessage.RemovedFromGroup)
                return
            }
            if (rejoinJob?.isActive == true) return
            // The singleton scope, always: the first thing a rejoin does is cancel the session scope.
            rejoinJob = scope.launch { rejoin(target) }
        }

        /**
         * Stands the lost session down and tries [REJOIN_MAX_ATTEMPTS] times to get [target] back.
         *
         * The attempts are spaced because the first one can legitimately be too early: the server
         * removes a session from its group when the *old* websocket is finally reaped, which can
         * land after this client has already noticed and asked to come back.
         */
        private suspend fun rejoin(target: SyncPlayGroupSummary) {
            val standing =
                sessionMutex.withLock {
                    if (_state.value !is SyncPlayState.InGroup) return@withLock false
                    standDown(target)
                    true
                }
            if (!standing) return

            for (attempt in 1..REJOIN_MAX_ATTEMPTS) {
                if (attempt > 1) delay(REJOIN_RETRY_DELAY_MS)
                if (!awaitOnline()) {
                    Timber.w("Still offline; SyncPlay rejoin attempt %d spent waiting", attempt)
                    continue
                }
                when (attemptRejoin(target, attempt)) {
                    RejoinOutcome.Rejoined -> {
                        rejoinJob = null
                        Timber.i("Rejoined the SyncPlay group %s on attempt %d", target.name, attempt)
                        _messages.tryEmit(SyncPlayMessage.Rejoined)
                        return
                    }

                    // Listed groups came back without ours: we were its last member and it is gone.
                    RejoinOutcome.Dissolved -> {
                        Timber.w("The SyncPlay group %s no longer exists; staying out", target.id)
                        endRejoin(SyncPlayMessage.GroupEnded)
                        return
                    }

                    // Left or signed out from under us; whoever did it owns the teardown.
                    RejoinOutcome.Aborted -> return

                    RejoinOutcome.Failed -> Unit
                }
            }
            Timber.w("Could not rejoin the SyncPlay group after %d attempts", REJOIN_MAX_ATTEMPTS)
            endRejoin(SyncPlayMessage.ConnectionLost)
        }

        /**
         * Waits, briefly, for a network worth spending an attempt on.
         *
         * The radio comes back several seconds after it went — Wi-Fi association, DHCP and the
         * reachability probe all happen after `svc wifi enable` returns — so an attempt fired the
         * instant the loss is confirmed is an attempt thrown away. Bounded by the same
         * [REJOIN_RETRY_DELAY_MS] as the spacing, so a connection that is genuinely gone still ends
         * at [SyncPlayState.Idle] within a handful of seconds.
         *
         * @return `false` when the wait ran out and the device is still offline.
         */
        private suspend fun awaitOnline(): Boolean {
            if (connectionState.state.value.isOnline) return true
            return withTimeoutOrNull(REJOIN_RETRY_DELAY_MS) {
                connectionState.state.first { it.isOnline }
            } != null
        }

        /** One attempt: is the group still there, and will it have us back? */
        private suspend fun attemptRejoin(
            target: SyncPlayGroupSummary,
            attempt: Int,
        ): RejoinOutcome =
            sessionMutex.withLock {
                if (_state.value !is SyncPlayState.Rejoining) return@withLock RejoinOutcome.Aborted
                _state.value = SyncPlayState.Rejoining(target, attempt)
                val groups =
                    runCatching { api.getGroups() }
                        .onFailure { Timber.w(it, "Could not list the SyncPlay groups while rejoining") }
                        .getOrElse { return@withLock RejoinOutcome.Failed }
                if (groups.none { it.id == target.id }) return@withLock RejoinOutcome.Dissolved
                if (performJoin(existing = target, newGroupName = null)) {
                    RejoinOutcome.Rejoined
                } else {
                    closeSession()
                    RejoinOutcome.Failed
                }
            }

        /**
         * Ends the group session that the server has already ended, keeping [rejoinTarget].
         *
         * Everything [teardown] resets except two things: the clock offset, because it is the same
         * server and the rejoin handshake needs it immediately, and [ignoreWaitSent], because a
         * member with no player must go on being one after the rejoin ([enterGroup] re-sends it).
         */
        private suspend fun standDown(target: SyncPlayGroupSummary) {
            Timber.w("Lost the SyncPlay membership of %s server-side; taking it back", target.name)
            _state.value = SyncPlayState.Rejoining(target, attempt = 1)
            statusHolder.setInGroup(false)
            statusHolder.setMintedPlaySessionId(null)
            scheduler.cancel()
            closeSession()
            loadedPlaylistItemId = null
            skippedSlots.clear()
            pendingGroup = null
            pendingQueue = null
            forgetHandshake()
            connectivityGraceJob = null
            pingFailures = 0
            groupPlayingAnchor = null
            // Frozen, never resumed: the rejoin does not start playback, the group does.
            withContext(mainDispatcher) { playerHandle.pause() }
        }

        /** The rejoin gave up; hand over to the ordinary teardown without cancelling this coroutine. */
        private suspend fun endRejoin(message: SyncPlayMessage) {
            rejoinJob = null
            teardown(message, pausePlayer = false)
        }

        /** What one [attemptRejoin] came to. */
        private enum class RejoinOutcome {
            Rejoined,
            Dissolved,
            Failed,
            Aborted,
        }

        /**
         * Mirrors the group's own state onto this member's phase — except `Playing`.
         *
         * `Playing` is owned by the applied unpause, because that is the only thing that knows the
         * anchor; taking it from a state update would give the drift monitor nothing to measure. It
         * is still recorded, in [groupPlayingAnchor], as the coarse reading the B3 safety net falls
         * back on when no unpause turns up at all.
         *
         * **WAITING pauses.** The group entering `Waiting` means it is stalled on somebody, and a
         * member that keeps playing behind the overlay is not waiting, it is drifting ahead —
         * measured at seven seconds over eight on device (B5). jellyfin-web pauses here and so do
         * we. It is a command-like application, not a handshake: nothing is reported, and the
         * position is held for the resume the server will schedule.
         */
        private suspend fun onGroupStateChanged(groupState: SyncPlayGroupState) {
            if (groupState == SyncPlayGroupState.Playing) {
                groupPlayingAnchor = inferredGroupAnchor()
                // Either order is possible — the state update can beat this member's own `ready` or
                // trail it — so the net is armed from both ends. It disarms on the first command.
                armSelfSync()
                return
            }
            groupPlayingAnchor = null
            val wasPlaying = (_state.value as? SyncPlayState.InGroup)?.phase is SyncPlayPhase.Playing
            if (groupState == SyncPlayGroupState.Waiting && wasPlaying) {
                Timber.d("SyncPlay group is waiting; holding this member where it is")
                withContext(mainDispatcher) { playerHandle.pause() }
            }
            val phase =
                when (groupState) {
                    SyncPlayGroupState.Waiting -> SyncPlayPhase.Waiting
                    else -> SyncPlayPhase.Paused
                }
            setPhase(phase)
        }

        /** Where the group is now, from its queue update alone — the only reading a wedge leaves. */
        private fun inferredGroupAnchor(): SyncPlayAnchor? {
            val queue = (_state.value as? SyncPlayState.InGroup)?.queue ?: return null
            return SyncPlayAnchor(queue.startPositionTicks.ticksToMillis(), timeSync.serverNow())
        }

        private suspend fun onQueueChanged(queue: SyncPlayGroupQueue) {
            val current = _state.value as? SyncPlayState.InGroup
            if (current == null) {
                pendingQueue = queue
                return
            }
            _state.value = current.copy(queue = queue)
            if (queue.isPlaying) groupPlayingAnchor = inferredGroupAnchor()
            reconcile(queue)
        }

        /**
         * Makes the player show what the group is on.
         *
         * Four outcomes: nothing to do (the slot is already open — which is every reorder, removal,
         * shuffle and repeat change that leaves the playing item alone), ask the app to open a
         * player (nothing attached), adopt what the host has (the user opened this very item, so
         * reloading it would restart playback for nothing), or run the buffering/ready handshake
         * around [SyncPlayPlaybackHost.loadItem].
         *
         * The slot, not the item, is the identity: the same episode queued twice is two slots, and a
         * group jumping from one to the other has to start it again rather than carry on where the
         * first copy had got to. Adoption is therefore only offered before this controller has
         * loaded anything of its own — [loadedPlaylistItemId] is `null` on a fresh join and after a
         * detach, which are exactly the two moments the host might already hold the right item.
         */
        private suspend fun reconcile(queue: SyncPlayGroupQueue) {
            val entry = queue.playingEntry
            if (entry == null) {
                loadedPlaylistItemId = null
                return
            }
            if (entry.playlistItemId == loadedPlaylistItemId) {
                onSameSlotUpdate(entry, queue)
                return
            }

            val attached = host
            if (attached == null) {
                loadedPlaylistItemId = null
                _launchRequests.tryEmit(SyncPlayLaunchRequest(entry.itemId, queue.startPositionTicks))
                return
            }

            val snapshot = hostSnapshot()
            if (loadedPlaylistItemId == null && snapshot?.itemId == entry.itemId) {
                onEntryOpened(entry)
                reportReady(entry, snapshot.positionTicks)
                return
            }

            loadedPlaylistItemId = entry.playlistItemId
            setPhase(SyncPlayPhase.Buffering)
            oweReady(entry, fallbackMillis = null)
            runCatching {
                api.reportBuffering(timeSync.serverNow(), queue.startPositionTicks, false, entry.playlistItemId)
            }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }

            val loaded = runCatching { attached.loadItem(entry.itemId, queue.startPositionTicks) }.getOrElse { false }
            if (loaded) onEntryOpened(entry) else onEntryUnplayable(entry)
        }

        /**
         * The group re-sent a queue this member is already on.
         *
         * Usually nothing to do — every reorder, removal, shuffle and repeat change lands here. But
         * some of those updates come with `SetAllBuffering(true)` on the server side (a new playlist,
         * a new current item, next, previous), which means the group has gone back to waiting on
         * *everyone*, this member included, and will sit there until it hears a `ready`. The player
         * is already prepared, so the answer is one report rather than a reload.
         */
        private suspend fun onSameSlotUpdate(
            entry: SyncPlayQueueEntry,
            queue: SyncPlayGroupQueue,
        ) {
            if (queue.reason !in READY_OWING_REASONS || host == null) return
            reportReady(entry, hostSnapshot()?.positionTicks ?: queue.startPositionTicks)
        }

        /** A slot is open on the host; whatever was skipped to get here is forgiven. */
        private fun onEntryOpened(entry: SyncPlayQueueEntry) {
            loadedPlaylistItemId = entry.playlistItemId
            skippedSlots.clear()
        }

        /**
         * The group is on something this device cannot open.
         *
         * A group's queue is not filtered for this client: it can hold an audio track, an item whose
         * file has gone, or something this account may not see — and the resolver refuses all three,
         * which is what `loadItem` returning `false` means here. The controller cannot tell them
         * apart (it holds item ids, not metadata) and does not need to: the answer is the same, say
         * so and ask the group to move on rather than sit there gating everyone.
         *
         * Asking to move on is also how this turns into a loop, so each slot is skipped **once**
         * (M11 Phase 4, DECISIONS.md 2026-07-30). A queue of unplayable items therefore costs one
         * pass and then stops, instead of cycling for as long as the group exists.
         */
        private suspend fun onEntryUnplayable(entry: SyncPlayQueueEntry) {
            Timber.w("SyncPlay could not open item %s", entry.itemId)
            loadedPlaylistItemId = null
            _messages.tryEmit(SyncPlayMessage.ItemUnavailable)
            if (!skippedSlots.add(entry.playlistItemId)) {
                Timber.w("Not skipping past SyncPlay slot %s twice", entry.playlistItemId)
                return
            }
            runCatching { api.requestNextItem(entry.playlistItemId) }
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
         * A readiness the group is waiting for — and only that one.
         *
         * The player becomes ready again after every seek and every pause the scheduler applies, and
         * a report the group did not ask for is answered with the very command that caused it. See
         * the class docs; [readyOwedFor] is the whole of the rule.
         */
        private suspend fun onPlayerReady() {
            val entry = currentEntry() ?: return
            if (readyOwedFor != entry.playlistItemId) {
                Timber.v("Player ready with no SyncPlay handshake outstanding; saying nothing")
                return
            }
            reportReady(entry, hostSnapshot()?.positionTicks ?: 0L)
        }

        /**
         * Records that the group has been told to wait on this member.
         *
         * @param fallbackMillis when set, how long to wait for the player to announce itself before
         *   reporting `ready` anyway. Needed where the player has no reason to re-buffer and so will
         *   never emit a readiness — a seek to where it already is, or a re-negotiation after a
         *   connectivity blip that never actually stopped it. `null` where the player really is
         *   being rebuilt and its own readiness is the honest answer.
         */
        private fun oweReady(
            entry: SyncPlayQueueEntry,
            fallbackMillis: Long?,
        ) {
            readyOwedFor = entry.playlistItemId
            readyFallbackJob?.cancel()
            readyFallbackJob =
                fallbackMillis?.let { millis ->
                    launchInSession {
                        delay(millis)
                        readyFallbackJob = null
                        val current = currentEntry() ?: return@launchInSession
                        if (readyOwedFor != current.playlistItemId) return@launchInSession
                        Timber.d("Player never re-buffered; reporting SyncPlay ready from where it stands")
                        reportReady(current, hostSnapshot()?.positionTicks ?: 0L)
                    }
                }
        }

        private fun forgetHandshake() {
            readyOwedFor = null
            readyFallbackJob?.cancel()
            readyFallbackJob = null
            selfSyncJob?.cancel()
            selfSyncJob = null
        }

        /**
         * Starts the clock on "the group said go and nothing came".
         *
         * Armed after every `ready`, disarmed by the first command applied. What it catches is the
         * queue-advance wedge (B3): the handshake completes, the group's own state update says
         * `Playing`, and the unpause that should have followed it never arrives — leaving this
         * member parked at 0:00 under the WAITING overlay while everyone else watches. A group
         * unpause cannot recover it either, because the group *is* playing and the request no-ops.
         *
         * Nothing here reports anything: another `ready` would only re-enter the storm B1 is about.
         */
        private fun armSelfSync() {
            selfSyncJob?.cancel()
            selfSyncJob =
                launchInSession {
                    delay(SELF_SYNC_TIMEOUT_MS)
                    selfSyncJob = null
                    selfSyncToGroup()
                }
        }

        private suspend fun selfSyncToGroup() {
            val current = _state.value as? SyncPlayState.InGroup ?: return
            if (current.phase is SyncPlayPhase.Playing) return
            // Never on a detached player: a member with no screen open has told the group to stop
            // waiting on it (key decision 5), and starting the shared ExoPlayer behind nothing at
            // all would be sound from nowhere.
            if (host == null) return
            val anchor = groupPlayingAnchor ?: return
            val expected =
                (anchor.positionMs + Duration.between(anchor.at, timeSync.serverNow()).toMillis())
                    .coerceAtLeast(0L)
            Timber.w("SyncPlay group is playing but sent no command; self-syncing to %d ms", expected)
            withContext(mainDispatcher) {
                playerHandle.seekTo(expected)
                playerHandle.play()
            }
            setPhase(SyncPlayPhase.Playing(anchor))
        }

        /**
         * Re-enters the handshake from a player that is already prepared — see [onConnectivityBack].
         *
         * The first request after a blip is also the first chance to find out that the membership did
         * not survive it. Usually the server answers that on the websocket (`NotInGroup`), but a
         * refusal on the REST call says the same thing sooner, so it is treated the same way.
         */
        private suspend fun renegotiate() {
            val entry = currentEntry() ?: return
            setPhase(SyncPlayPhase.Buffering)
            val positionTicks = hostSnapshot()?.positionTicks ?: 0L
            oweReady(entry, fallbackMillis = SETTLED_READY_FALLBACK_MS)
            runCatching { api.reportBuffering(timeSync.serverNow(), positionTicks, false, entry.playlistItemId) }
                .onFailure { error ->
                    Timber.w(error, "Could not report SyncPlay buffering")
                    if (error.isMembershipRefused()) onMembershipGone()
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
            runCatching { api.requestNextItem(entry.playlistItemId) }
                .onFailure { Timber.w(it, "Could not request the next SyncPlay item") }
        }

        /**
         * Answers the group's wait, once.
         *
         * Clearing [readyOwedFor] *before* the call is what makes it once: the report is a round
         * trip, and a second readiness arriving inside it would otherwise send a second one.
         */
        private suspend fun reportReady(
            entry: SyncPlayQueueEntry,
            positionTicks: Long,
        ) {
            readyOwedFor = null
            readyFallbackJob?.cancel()
            readyFallbackJob = null
            runCatching { api.reportReady(timeSync.serverNow(), positionTicks, false, entry.playlistItemId) }
                .onFailure { Timber.w(it, "Could not report SyncPlay ready") }
            if ((_state.value as? SyncPlayState.InGroup)?.phase == SyncPlayPhase.Buffering) {
                setPhase(SyncPlayPhase.Waiting)
            }
            armSelfSync()
        }

        private fun onCommand(command: SyncPlayCommand) {
            // Logged on arrival as well as on application: "the command never came" and "the command
            // came and did nothing" are the two halves of every desync, and only the pair tells them
            // apart in a device log.
            Timber.d("SyncPlay command %s for %s (emitted %s)", command.type, command.whenInstant, command.emittedAt)
            if (_state.value !is SyncPlayState.InGroup) return
            scheduler.schedule(command)
        }

        private suspend fun onCommandApplied(applied: SyncPlayAppliedCommand) {
            // The group has spoken, so the safety net is not needed for this handshake.
            selfSyncJob?.cancel()
            selfSyncJob = null
            val phase =
                when (applied.command.type) {
                    SyncPlayCommandType.Unpause -> applied.anchor?.let(SyncPlayPhase::Playing) ?: SyncPlayPhase.Paused
                    // A seek invalidates whatever anchor was current, so it drops out of Playing
                    // too — the unpause that follows the group's re-handshake sets the next one.
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

        /** Runs the drift monitor for exactly as long as there is an anchor to run it against. */
        private suspend fun observeAnchor() {
            state
                .map { ((it as? SyncPlayState.InGroup)?.phase as? SyncPlayPhase.Playing)?.anchor }
                .distinctUntilChanged()
                .collectLatest { anchor -> if (anchor != null) driftMonitor.monitor(anchor) }
        }

        // Plumbing -----------------------------------------------------------------------------------

        /**
         * Issues one server request, in a group only.
         *
         * Every user transport action funnels through here, which is where key decision 11 is
         * enforced structurally: there is no path from an intent to [playerHandle].
         */
        private fun request(block: suspend () -> Unit) {
            if (_state.value !is SyncPlayState.InGroup) {
                Timber.d("Ignoring a SyncPlay request while not in a group")
                return
            }
            scope.launch {
                runCatching { block() }.onFailure { Timber.w(it, "A SyncPlay request failed") }
            }
        }

        private fun launchInSession(block: suspend CoroutineScope.() -> Unit): Job =
            (sessionScope ?: scope).launch(block = block)

        private fun setPhase(phase: SyncPlayPhase) {
            _state.update { current ->
                if (current is SyncPlayState.InGroup) current.copy(phase = phase) else current
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

        companion object {
            /** How long the join waits for the websocket before going ahead without it. */
            const val SOCKET_READY_TIMEOUT_MS = 5_000L

            /**
             * How long connectivity may be gone before the group is given up on, in milliseconds.
             *
             * Long enough to ride out a Wi-Fi handover or the two-second blip that ejected the group
             * on device (B9), short enough that a genuinely dead connection is admitted while the
             * user is still looking at the screen. Playback is frozen for the duration either way,
             * so the cost of being wrong is a pause, not a drift.
             */
            const val CONNECTIVITY_GRACE_MS = 5_000L

            /**
             * Consecutive failed ping cycles that count as a lost connection.
             *
             * Three at the pinger's five-second cadence is about fifteen seconds of silence — long
             * enough not to fire on one timed-out request, short enough to beat the server's own
             * group disposal (B8).
             */
            const val PING_FAILURE_STREAK = 3

            /**
             * How many times a lost membership is asked for back before the group is given up on.
             *
             * Three rather than one because the server can still be reaping the old session when the
             * first attempt lands, and bounded rather than open-ended because a client that retries
             * for ever is a client the user cannot get out of a group they are no longer in.
             */
            const val REJOIN_MAX_ATTEMPTS = 3

            /** Spacing between rejoin attempts, in milliseconds. */
            const val REJOIN_RETRY_DELAY_MS = 2_000L

            /**
             * How long after connection trouble a removal is still blamed on it, in milliseconds.
             *
             * The removal is discovered by the *next* request rather than at the moment of the
             * trouble — the ping loop's five-second cadence is the usual finder — so the window has
             * to outlast a couple of those. Outside it, a removal is somebody's decision and is
             * obeyed.
             */
            const val REJOIN_TROUBLE_WINDOW_MS = 30_000L

            /**
             * How long a completed handshake waits for the group's command before syncing itself.
             *
             * Comfortably more than the delay the server builds into an unpause (twice the highest
             * member ping, floored at its 500 ms default), so the net only catches a command that is
             * not coming at all.
             */
            const val SELF_SYNC_TIMEOUT_MS = 3_000L

            /**
             * How long an owed `ready` waits for a player that may never re-buffer, in milliseconds.
             *
             * Only used where the player is already prepared and the handshake is a formality — a
             * seek to where it stands, a re-negotiation after a blip.
             */
            const val SETTLED_READY_FALLBACK_MS = 1_500L

            /**
             * Queue updates that put every member back to buffering on the server.
             *
             * `WaitingGroupState` calls `SetAllBuffering(true)` for each of them, and then waits for
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
