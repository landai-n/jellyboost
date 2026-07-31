package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.player.di.MainDispatcher
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
import java.time.Clock
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
 * Collect the websocket → measure the clock → join → the server sends the group and its queue →
 * open the item paused (or ask the app to open a player) → report `buffering` → park the player and
 * report `ready` when it is → the server flips the group out of WAITING and schedules an unpause
 * everyone applies at the same instant. **A member reporting `ready` is stopped** — the server's own
 * `WaitingGroupState` answers a `ready` that claims to be playing by resuming everyone *else*
 * ([reportReady]).
 *
 * The websocket is collected *before* the join call, not after: collecting is what opens it (the SDK
 * has no `connect()`), and a group joined before the socket is up would never hear its own
 * `GroupJoined`/`PlayQueueUpdate`. The clock is measured before it too — see [warmClock].
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
 *
 * ### Coming back to the app
 * Returning to the foreground **is** the user acting, and it is the one moment a membership lost to a
 * backgrounded process having its network cut can be taken back (DECISIONS.md 2026-07-31). So
 * [onAppForegrounded]:
 *
 * - in a group, fires an immediate ping cycle, because a connection that died off screen is
 *   otherwise not even suspected until the next five-second cadence;
 * - at [SyncPlayState.Idle] with a [lostMembership] still inside [FOREGROUND_REJOIN_WINDOW_MS], runs
 *   the same rejoin attempts, once and **silently** — a re-check that fails or finds the group gone
 *   must not put a message on screen every time the app is opened.
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
        // The *device* clock, deliberately, and only for [lostMembership]: `timeSync.serverNow()`
        // is reset by the very teardown that memory has to outlive.
        private val clock: Clock,
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

        /** The other half of it: fires when a paused group produced no pause command. */
        private var pauseNetJob: Job? = null

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
         * The group this client was thrown out of against its will, and when — the one thing that
         * survives [teardown].
         *
         * Everything else about a group session is forgotten when the session ends, deliberately.
         * This is not, because the ending it records is the one nobody chose: the platform cut the
         * app's network while it was backgrounded, the rejoin attempts all failed for the same
         * reason, and the group is very probably still there with the user watching it somewhere
         * else. Without a memory the controller sits at [SyncPlayState.Idle] for ever knowing
         * nothing; with one, [onAppForegrounded] can ask for the group back at the one moment the
         * ask can succeed.
         *
         * Bounded by [FOREGROUND_REJOIN_WINDOW_MS] and cleared by every deliberate exit — see
         * [rememberLoss] and [forgetLoss].
         */
        private var lostMembership: LostMembership? = null

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
            forgetLoss()
            scope.launch {
                leaveOnServer()
                teardown(message = null, pausePlayer = false)
            }
        }

        /**
         * The app is back in front of the user (`ProcessLifecycleOwner`, `ON_START`).
         *
         * Two jobs, and which one runs depends on whether the group survived the background:
         *
         * - **still in it** — take a ping sample immediately. Between the last one and now the
         *   platform may have cut this process's network without telling anyone, and the ordinary
         *   cadence would leave that undiscovered for up to five seconds before the failure streak
         *   even starts.
         * - **out of it** — if the membership was lost involuntarily and recently
         *   ([FOREGROUND_REJOIN_WINDOW_MS]), ask for it back, once. This is the net under the
         *   presence service: an OEM that kills the service anyway costs the group until the user
         *   comes back, not for the rest of the evening.
         *
         * Anything else — [SyncPlayState.Joining], [SyncPlayState.Rejoining] — is already in the
         * middle of the conversation this would start.
         */
        fun onAppForegrounded() {
            when (_state.value) {
                is SyncPlayState.InGroup -> pinger.sampleNow()
                SyncPlayState.Idle -> resumeLostMembership()
                else -> Unit
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
         * **The group keeps its reach.** Giving back the *screen* is not giving back the *player*:
         * `PlaybackService` keeps the shared ExoPlayer alive and playing, so this used to leave a
         * member that went on playing with the scheduler cancelled (the group's commands landing
         * nowhere) and a phase forced to `Paused` (the drift monitor, which runs in `Playing` only,
         * shut off with it) — the free-running background member of `syncplay-bugreport.md`. So
         * neither happens here any more: commands go on being scheduled and applied, and the phase
         * goes on saying what the group is doing. Only a full [teardown] or [standDown] cancels the
         * scheduler, because only those end the timeline it is tracking.
         *
         * What still resets is what belongs to the *screen*: [loadedPlaylistItemId] (so a re-attach
         * may adopt an item the host already holds, see [reconcile]) and [skippedSlots].
         *
         * @param host ignored unless it is the attached one, so a stale ViewModel's teardown cannot
         *   detach the player that replaced it.
         */
        fun detachHost(host: SyncPlayPlaybackHost) {
            if (this.host !== host) return
            this.host = null
            loadedPlaylistItemId = null
            skippedSlots.clear()
            if (_state.value !is SyncPlayState.InGroup) return
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
            // The rebuild breaks the player's continuity, so what was applied to the old player has
            // not been applied to the one that comes back. The server settles this member by
            // re-sending the standing command verbatim after the ready — same `when`, same position
            // — and remembering it as applied would dedupe exactly that answer. Measured on device
            // (2026-07-31): a track change left the resumed Unpause dropped as a repeat, and the
            // blind fallback then jumped from 6:35 to 27:27.
            scheduler.forgetApplied()
            // The player really is being rebuilt, so its own readiness is what ends this handshake:
            // no fallback, however long the re-negotiation takes.
            oweReady(entry, fallbackMillis = null)
            launchInSession {
                val snapshot = hostSnapshot()
                runCatching {
                    api.reportBuffering(
                        timeSync.serverNow(),
                        snapshot?.positionTicks ?: 0L,
                        reportedIsPlaying(snapshot),
                        entry.playlistItemId,
                    )
                }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }
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
            warmClock()

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
         * Measures the server clock once, before the handshake can produce a command to schedule.
         *
         * [SyncPlayTimeSync.offset] is `Duration.ZERO` until something records a sample, and the
         * pinger only starts in [enterGroup] — so between the join call returning and its first
         * sample landing, every `SendCommand` would be converted to local time with an *assumed*
         * offset. On a device whose clock is off by a second that is a second of desync built into
         * the very first unpause, invisible to everyone including this client.
         *
         * Inline rather than on the session scope, because "before the join returns" is the whole
         * point; a clock exchange is one round trip and the socket is already up. A failure is
         * logged by the pinger and changes nothing: joining with an unmeasured clock is exactly
         * where this started, and the pinger corrects it a moment later.
         */
        private suspend fun warmClock() {
            if (pinger.sampleClock() == null) {
                Timber.w("Joining a SyncPlay group without a measured clock offset")
            }
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
            val entered = pendingGroup ?: group
            _state.value = SyncPlayState.InGroup(entered, null, entered.state, SyncPlayPhase.Waiting)
            pendingGroup = null
            rejoinTarget = group
            troubledAt = null
            // Whatever was lost has been recovered, or replaced by a group the user chose.
            forgetLoss()
            statusHolder.setInGroup(true)

            session.launch { pinger.run(::onPingOutcome) }
            session.launch { playerHandle.events.collect(::onPlayerEvent) }
            session.launch { scheduler.applied.collect(::onCommandApplied) }
            session.launch { observeAnchor() }
            session.launch { watchConnectivity() }
            session.launch { watchSocket() }

            // A group already paused when we arrived gets the same net as one that pauses while we
            // are in it: nothing here can tell "paused before we joined" from "paused a moment ago
            // and the command was lost", and the net only ever pauses a player that is running.
            if (entered.state == SyncPlayGroupState.Paused) armPauseNet()

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
                if (session !is SessionState.LoggedOut) return@collect
                // The memory is cleared even from Idle: a signed-out account must not have a group
                // taken back for it when somebody signs in and opens the app.
                forgetLoss()
                if (_state.value is SyncPlayState.Idle) return@collect
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

        /** Gives up the group deliberately: no rejoin will follow, and none on the next foreground. */
        private fun forgetAndTearDown(message: SyncPlayMessage) {
            rejoinTarget = null
            forgetLoss()
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
            runRejoinAttempts(target, quiet = false)
        }

        /**
         * The attempt loop itself, shared by the loss path and by the foreground re-check.
         *
         * @param quiet suppresses the two *endings* — "connection lost" and "the group has ended" —
         *   without changing anything the protocol does. The re-check runs on every foreground, and
         *   a group that is genuinely gone would otherwise announce itself every time the user
         *   opened the app. A success is still announced: that one is news.
         */
        private suspend fun runRejoinAttempts(
            target: SyncPlayGroupSummary,
            quiet: Boolean,
        ) {
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
                        forgetLoss()
                        endRejoin(SyncPlayMessage.GroupEnded.takeUnless { quiet })
                        return
                    }

                    // Left or signed out from under us; whoever did it owns the teardown.
                    RejoinOutcome.Aborted -> return

                    RejoinOutcome.Failed -> Unit
                }
            }
            Timber.w("Could not rejoin the SyncPlay group after %d attempts", REJOIN_MAX_ATTEMPTS)
            // Remembered on the way out, so returning to the app can ask once more. A re-check that
            // fails keeps the memory it already had, original instant and all.
            rememberLoss(target)
            endRejoin(SyncPlayMessage.ConnectionLost.takeUnless { quiet })
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

        /**
         * The rejoin gave up; hand over to the ordinary teardown without cancelling this coroutine.
         *
         * @param message `null` for the foreground re-check, which ends silently — see
         *   [runRejoinAttempts].
         */
        private suspend fun endRejoin(message: SyncPlayMessage?) {
            rejoinJob = null
            teardown(message, pausePlayer = false)
        }

        // The foreground safety net -------------------------------------------------------------------

        /**
         * Asks for a recently, involuntarily lost group back — the [onAppForegrounded] half.
         *
         * Deliberately not a loop and not a retry schedule: it runs at most once per foreground, and
         * a failure leaves the memory alone so the *next* foreground may try again inside the
         * window. A memory that has expired is dropped here rather than left to rot, which is what
         * makes "the user came back much later, to something else entirely" cost nothing.
         */
        private fun resumeLostMembership() {
            val lost = lostMembership ?: return
            val age = Duration.between(lost.at, clock.instant()).toMillis()
            if (age !in 0..FOREGROUND_REJOIN_WINDOW_MS) {
                Timber.i("The lost SyncPlay group %s is too old to take back; forgetting it", lost.group.name)
                lostMembership = null
                return
            }
            if (rejoinJob?.isActive == true) return
            rejoinJob = scope.launch { rejoinFromIdle(lost.group) }
        }

        /**
         * A rejoin that starts from [SyncPlayState.Idle] rather than from a session being stood down.
         *
         * Everything a group session needs was already released by the [teardown] that got here, so
         * there is nothing to tear down and nothing to pause — only the state to set, so that
         * [attemptRejoin] and [enterGroup] behave exactly as they do on every other rejoin.
         *
         * The one thing restored by hand is the ignore-wait: with no player attached this member
         * must not be why the whole group sits in WAITING (key decision 5), and [enterGroup] sends it
         * only when it believes one was owed.
         */
        private suspend fun rejoinFromIdle(target: SyncPlayGroupSummary) {
            val standing =
                sessionMutex.withLock {
                    if (_state.value !is SyncPlayState.Idle) return@withLock false
                    Timber.i("Back in the foreground; asking for the SyncPlay group %s back", target.name)
                    _state.value = SyncPlayState.Rejoining(target, attempt = 1)
                    ignoreWaitSent = host == null
                    true
                }
            if (!standing) return
            runRejoinAttempts(target, quiet = true)
        }

        /**
         * Records that [group] was lost without anyone choosing it.
         *
         * The instant is **not** refreshed for a group already remembered: the window is counted
         * from the loss, and a failed re-check on every foreground would otherwise walk the deadline
         * forward for as long as the user kept opening the app.
         */
        private fun rememberLoss(group: SyncPlayGroupSummary) {
            if (lostMembership?.group?.id == group.id) return
            lostMembership = LostMembership(group, clock.instant())
            Timber.i("Remembering the lost SyncPlay group %s for %d ms", group.name, FOREGROUND_REJOIN_WINDOW_MS)
        }

        /** Drops the memory: this exit was chosen, or the group has been recovered. */
        private fun forgetLoss() {
            lostMembership = null
        }

        /** A group that was lost involuntarily, and the device instant it was lost at. */
        private data class LostMembership(
            val group: SyncPlayGroupSummary,
            val at: Instant,
        )

        /** What one [attemptRejoin] came to. */
        private enum class RejoinOutcome {
            Rejoined,
            Dissolved,
            Failed,
            Aborted,
        }

        /**
         * Mirrors the group's own state onto [SyncPlayState.InGroup.groupState], and onto this
         * member's phase — except `Playing`.
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
         * position is held for the resume the server will schedule. The player itself is asked
         * whether it is running, deliberately: the phase is the thing that lies after a lost
         * command, and a member whose phase says `Paused` over a player that is still playing is
         * exactly the case the hold exists for.
         *
         * **PAUSED arms a net.** The pause the group named reaches this member as a `SendCommand`
         * and nothing here acts locally — but a command that never arrives leaves this member
         * playing on for ever, with the drift monitor (which runs in `Playing` only) shut off along
         * with it. See [armPauseNet]: the mirror image of [armSelfSync].
         */
        private suspend fun onGroupStateChanged(groupState: SyncPlayGroupState) {
            val resumedFromPause =
                (_state.value as? SyncPlayState.InGroup)?.groupState == SyncPlayGroupState.Paused
            setGroupState(groupState)
            if (groupState == SyncPlayGroupState.Playing) {
                groupPlayingAnchor = (if (resumedFromPause) parkedPlayerAnchor() else null) ?: inferredGroupAnchor()
                cancelPauseNet()
                // Either order is possible — the state update can beat this member's own `ready` or
                // trail it — so the net is armed from both ends. It disarms on the first command.
                armSelfSync()
                return
            }
            groupPlayingAnchor = null
            // Structural rather than incidental: a self-sync armed while the group was playing must
            // never start playback in a group that has since stopped.
            cancelSelfSync()
            if (groupState == SyncPlayGroupState.Waiting && isPlayerRunning()) {
                Timber.d("SyncPlay group is waiting; holding this member where it is")
                withContext(mainDispatcher) { playerHandle.pause() }
            }
            if (groupState == SyncPlayGroupState.Paused) armPauseNet()
            val phase =
                when (groupState) {
                    SyncPlayGroupState.Waiting -> SyncPlayPhase.Waiting
                    else -> SyncPlayPhase.Paused
                }
            setPhase(phase)
        }

        /**
         * Where the group is now, from its queue update alone — the only reading a wedge leaves.
         *
         * **The instant is the queue's, not this moment's.** `startPositionTicks` is where the group
         * was when it last published the queue, so pairing it with `serverNow()` claims the group has
         * not moved since — and everything downstream then measures against a timeline that is behind
         * the real one by however long ago the update was. That is the browser-resume desync in the
         * bug report: [selfSyncToGroup] seeks to a position seconds short, and the drift monitor,
         * handed the same anchor, defends the wrong timeline instead of closing the gap
         * (`syncplay-bugreport.md`, "keeping a desynchronization of a few seconds initially, somehow
         * growing from there"). `lastUpdate` is the instant the position was actually true at.
         */
        private fun inferredGroupAnchor(): SyncPlayAnchor? {
            val queue = (_state.value as? SyncPlayState.InGroup)?.queue ?: return null
            return SyncPlayAnchor(queue.startPositionTicks.ticksToMillis(), queue.lastUpdate)
        }

        /**
         * The group's position read off this member's own parked player — the anchor a
         * pause-to-playing transition trusts before the queue's.
         *
         * A group leaving `Paused` resumes from the position it froze at, and every pause path ends
         * with this member parked exactly there: the pause command seeks before it pauses, the
         * elicited repeat does the same, and a parked player does not move. The queue's reading
         * ([inferredGroupAnchor]) dates from its `lastUpdate` and goes stale the moment any
         * pause/resume happens without a queue update — device run 3 measured the fallback turning a
         * 6:03 seek into a 23:17 landing off a seventeen-minute-old queue. A player still running is
         * no anchor (it may be the very member that missed the pause), and neither is one with
         * nothing loaded: both fall back to the queue.
         */
        private suspend fun parkedPlayerAnchor(): SyncPlayAnchor? {
            if (loadedPlaylistItemId == null) return null
            val snapshot = withContext(mainDispatcher) { playerHandle.snapshot() }
            if (snapshot.isPlaying) return null
            return SyncPlayAnchor(snapshot.positionMs, timeSync.serverNow())
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
         * player (nothing attached), adopt what the host has (this very item is already open, so
         * reloading it would restart playback for nothing — [adoptOpenItem] still runs the
         * handshake around it), or run that same buffering/ready handshake around
         * [SyncPlayPlaybackHost.loadItem].
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
                adoptOpenItem(entry, snapshot)
                return
            }

            loadedPlaylistItemId = entry.playlistItemId
            setPhase(SyncPlayPhase.Buffering)
            // A new slot is a new player: what the old one applied means nothing here, and the
            // server's post-ready re-send must not be mistaken for a repeat (see onHostBuffering).
            scheduler.forgetApplied()
            oweReady(entry, fallbackMillis = null)
            runCatching {
                api.reportBuffering(
                    timeSync.serverNow(),
                    queue.startPositionTicks,
                    reportedIsPlaying(snapshot),
                    entry.playlistItemId,
                )
            }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }

            val loaded = runCatching { attached.loadItem(entry.itemId, queue.startPositionTicks) }.getOrElse { false }
            if (loaded) onEntryOpened(entry) else onEntryUnplayable(entry)
        }

        /**
         * The host already holds the item the group is on — run the handshake around it rather than
         * reloading it.
         *
         * The same three steps [reconcile]'s load branch takes, minus the load: phase to
         * `Buffering`, owe the `ready`, tell the group this member is buffering. It used to report
         * `ready` on the spot, which is a claim about a player that has very often only just been
         * handed the item — the launch-request route (the group's `PlayQueueUpdate` opens a player
         * through `launchRequests`) adopts within a moment of `prepare`, before any readiness. A
         * `ready` for a player that is not is what left this member under the WAITING overlay while
         * the group moved on (`syncplay-bugreport.md`, DECISIONS.md 2026-07-31); reporting
         * `buffering` first is also what puts the group back to waiting on us, which is what earns
         * the unpause that clears the overlay.
         *
         * The fallback is [SETTLED_READY_FALLBACK_MS] rather than `null`, unlike the load branch,
         * precisely because the player is already prepared: it may have passed its readiness before
         * the host was attached and would then never announce itself again, wedging the whole group
         * on a `ready` that could not come.
         */
        private suspend fun adoptOpenItem(
            entry: SyncPlayQueueEntry,
            snapshot: SyncPlayHostSnapshot,
        ) {
            setPhase(SyncPlayPhase.Buffering)
            oweReady(entry, fallbackMillis = SETTLED_READY_FALLBACK_MS)
            runCatching {
                api.reportBuffering(
                    timeSync.serverNow(),
                    snapshot.positionTicks,
                    snapshot.isPlaying,
                    entry.playlistItemId,
                )
            }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }
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
            reportReady(entry, fallbackPositionTicks = queue.startPositionTicks)
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
            reportReady(entry)
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
                        reportReady(current)
                    }
                }
        }

        private fun forgetHandshake() {
            readyOwedFor = null
            readyFallbackJob?.cancel()
            readyFallbackJob = null
            cancelSelfSync()
            cancelPauseNet()
        }

        /**
         * Starts the clock on "the group said go and nothing came".
         *
         * Armed after every `ready`, and by a `StateChanged(Playing)`; disarmed by the first command
         * applied. What it catches is the queue-advance wedge (B3): the handshake completes, the
         * group's own state update says `Playing`, and the unpause that should have followed it
         * never arrives — leaving this member parked at 0:00 under the WAITING overlay while
         * everyone else watches. A group unpause cannot recover it either, because the group *is*
         * playing and the request no-ops.
         *
         * **Two windows, and the first one asks the server rather than guessing.** See [elicitRepeat]:
         * at [SELF_SYNC_TIMEOUT_MS] this sends a redundant `UnpauseGroupRequest`, which a group that
         * is already playing answers by re-sending its authoritative unpause to this session alone,
         * and re-arms itself once for [COMMAND_REPEAT_TIMEOUT_MS] ([NetStage.Fallback]). Only when
         * that window expires too does [selfSyncToGroup] act locally, off the inferred anchor. The
         * order matters because the inferred anchor is the weaker reading of the two: it comes from
         * the queue's `startPositionTicks`/`lastUpdate` and goes stale the moment the group pauses
         * and resumes without publishing a queue, which on device landed the self-sync seconds off
         * and compounded across cycles (STATUS.md, logcat run 3).
         *
         * Nothing here reports a `ready`: that would re-enter the storm B1 is about. A request is not
         * a report — the server answers it with a command, not with a wait.
         */
        private fun armSelfSync(stage: NetStage = NetStage.Elicit) {
            selfSyncJob?.cancel()
            selfSyncJob =
                launchInSession {
                    delay(if (stage == NetStage.Elicit) SELF_SYNC_TIMEOUT_MS else COMMAND_REPEAT_TIMEOUT_MS)
                    selfSyncJob = null
                    val asked = stage == NetStage.Elicit && elicitUnpauseRepeat()
                    if (asked) armSelfSync(NetStage.Fallback) else selfSyncToGroup()
                }
        }

        private fun cancelSelfSync() {
            selfSyncJob?.cancel()
            selfSyncJob = null
        }

        /**
         * The [armSelfSync] half of [elicitRepeat]: a playing group this member is not keeping up with.
         *
         * The phase check is the same one [selfSyncToGroup] opens with, so the ask goes out exactly
         * where the local fallback would have acted. A member already playing needs nothing repeated
         * — and a `StateChanged(Playing)` that merely trails its own applied command would otherwise
         * cost a request every single time the two arrive in that order.
         */
        private fun elicitUnpauseRepeat(): Boolean {
            val current = _state.value as? SyncPlayState.InGroup ?: return false
            if (current.phase is SyncPlayPhase.Playing) return false
            return elicitRepeat(current, SyncPlayGroupState.Playing) { requestUnpause() }
        }

        /**
         * The [armPauseNet] half of [elicitRepeat]: a paused group this member is still playing past.
         *
         * Gated on the player actually running, for the same reason [pauseToGroup] is: a member that
         * is already stopped is already where the group is, and has nothing to ask for.
         */
        private suspend fun elicitPauseRepeat(): Boolean {
            if (!isPlayerRunning()) return false
            // Read after the snapshot, not before: that hop to the main thread is long enough for
            // the group to have moved, and the state check is only worth anything when it is current.
            val current = _state.value as? SyncPlayState.InGroup ?: return false
            return elicitRepeat(current, SyncPlayGroupState.Paused) { requestPause() }
        }

        /**
         * Asks the server to say again what this member never heard — the first stage of both nets.
         *
         * The protocol has a recovery for exactly this and we were not using it. A group request
         * that asks for the state the group is *already* in is not a state change: the server reads
         * it as a member that has lost the thread and answers by re-sending the current command to
         * that one session, with the exact `When` and `PositionTicks` everyone else got —
         * `PausedGroupState.HandleRequest(PauseGroupRequest)` when `prevState == Paused`
         * (`PausedGroupState.cs`:88-93, "Client got lost, sending current state") and
         * `PlayingGroupState.HandleRequest(UnpauseGroupRequest)` when `prevState == Playing`
         * (`PlayingGroupState.cs`:80-86). The scheduler applies a re-sent command that never applied
         * locally (its "applied exactly once" guard remembers only what actually reached the player),
         * so the repeat lands and the ordinary command path — anchor, phase, drift monitor — does the
         * rest. That is the group's own timeline instead of this client's guess at it.
         *
         * **Only when the group is still where the net was armed for.** An `UnpauseGroupRequest`
         * sent to a group that has moved to WAITING is not a repeat, it is this member starting
         * everyone — so a group state that no longer matches falls straight through to the local
         * fallback, which is what the net did all along. The two callers add the other half of the
         * condition, "and this member really is out of step" ([elicitUnpauseRepeat],
         * [elicitPauseRepeat]), so the ask goes out exactly where the fallback would have acted.
         *
         * The ask itself goes through [request]: fire-and-forget, failure logged, and a failed one
         * simply leaves [NetStage.Fallback] to fire.
         *
         * @return whether the ask went out, which is the caller's cue to re-arm for a second window.
         */
        private fun elicitRepeat(
            current: SyncPlayState.InGroup,
            expected: SyncPlayGroupState,
            ask: () -> Unit,
        ): Boolean {
            if (current.groupState != expected) return false
            Timber.w(
                "SyncPlay group is %s and sent no command; asking the server to repeat itself",
                expected,
            )
            ask()
            return true
        }

        /**
         * Which of the two windows a safety net is in.
         *
         * The stage is carried rather than remembered, which is the whole of the loop guard: only an
         * [Elicit] net asks, and it can only ever re-arm as a [Fallback] one. A fresh episode — a
         * new `StateChanged`, a new `ready` — arms an [Elicit] net again and so gets one ask of its
         * own, but nothing inside an episode can produce a second.
         */
        private enum class NetStage {
            /** The group said something and no command came: ask it to say so again. */
            Elicit,

            /** The repeat did not come either: act locally, off the inferred anchor. */
            Fallback,
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
         * Starts the clock on "the group said stop and nothing came" — the mirror of [armSelfSync].
         *
         * The observed failure it exists for is the pause direction of B3, and it is the worse half:
         * a `Pause` this client never receives leaves the member playing on alone, while the phase
         * quietly goes to `Paused` and takes the drift monitor — which only runs in `Playing` — down
         * with it. Nothing then measures anything, and the member free-runs for the rest of the
         * evening (syncplay-bugreport.md, "Pause from browser: app continues playing").
         *
         * Two things separate it from the play net. It is **not** gated on a host: pausing a
         * detached background player that the group has paused is right, where *starting* one would
         * be sound from nowhere. And it takes no other action — no seek, no report, no `play` — so
         * firing at a player that is already stopped costs nothing, which is what lets it be armed
         * from the group's state alone rather than from a proof that this member is out of step.
         * (The elicit stage below does want that proof, because a request is not free — see
         * [elicitPauseRepeat].)
         *
         * It has the same two windows as [armSelfSync]: a redundant `PauseGroupRequest` first, which
         * a group that is already paused answers with its authoritative pause — exact instant, exact
         * position — to this session alone, and only then the local pause. The re-sent command is
         * strictly better than the local one, because it parks this member *where the group is*
         * rather than merely stopping it where it happens to be.
         */
        private fun armPauseNet(stage: NetStage = NetStage.Elicit) {
            pauseNetJob?.cancel()
            pauseNetJob =
                launchInSession {
                    delay(if (stage == NetStage.Elicit) PAUSE_NET_TIMEOUT_MS else COMMAND_REPEAT_TIMEOUT_MS)
                    pauseNetJob = null
                    val asked = stage == NetStage.Elicit && elicitPauseRepeat()
                    if (asked) armPauseNet(NetStage.Fallback) else pauseToGroup()
                }
        }

        private fun cancelPauseNet() {
            pauseNetJob?.cancel()
            pauseNetJob = null
        }

        private suspend fun pauseToGroup() {
            if (_state.value !is SyncPlayState.InGroup) return
            withContext(mainDispatcher) {
                // A player that is already stopped is already where the group is: no pause, and
                // nothing reported either way.
                if (!playerHandle.snapshot().isPlaying) return@withContext
                Timber.w("SyncPlay group is paused but sent no command; pausing this member")
                playerHandle.pause()
            }
        }

        /** Whether the player is actually running — the one reading no lost command can falsify. */
        private suspend fun isPlayerRunning(): Boolean =
            withContext(mainDispatcher) {
                playerHandle.snapshot().isPlaying
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
            // The blip may have cost this member commands it will never see; the server's answer to
            // the coming ready is a verbatim re-send, which the applied-memory would dedupe away
            // (see onHostBuffering). The player survived, but the timeline's continuity did not.
            scheduler.forgetApplied()
            val snapshot = hostSnapshot()
            val positionTicks = snapshot?.positionTicks ?: 0L
            val isPlaying = reportedIsPlaying(snapshot)
            oweReady(entry, fallbackMillis = SETTLED_READY_FALLBACK_MS)
            runCatching { api.reportBuffering(timeSync.serverNow(), positionTicks, isPlaying, entry.playlistItemId) }
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
         * Answers the group's wait, once — from a player that has been **parked** for it.
         *
         * Clearing [readyOwedFor] *before* the call is what makes it once: the report is a round
         * trip, and a second readiness arriving inside it would otherwise send a second one.
         *
         * **A member reporting `ready` is stopped, and says so.** The handshake the design is built
         * on is open-paused → buffering → `ready` → *the server's unpause starts playback*
         * (docs/notes/syncplay-m11-plan.md), and `WaitingGroupState` enforces it: a
         * `ReadyGroupRequest` from a resuming group whose reporter is more than `2 × highestPing`
         * behind is answered `AllExceptCurrentSession` **when `request.IsPlaying` is true** — the
         * group is told to resume and the reporter is deliberately sent nothing, on the assumption
         * that a client already playing will catch up by playing (`WaitingGroupState.cs`:484-498).
         * Several of our paths report while the player really is running — the post-seek settle, the
         * adopt path, the re-negotiation after a blip — and each of them stranded this member under
         * the WAITING overlay until somebody else moved the group (DECISIONS.md 2026-07-31,
         * amending the "honest reports" entry). So [parkForReady] stops the player first and the
         * report then carries `isPlaying = false`, which is both what jellyfin-web does and, now,
         * the truth. Parking is idempotent: a player already stopped is not touched.
         *
         * @param fallbackPositionTicks where to say the player is when no host is attached to ask.
         */
        private suspend fun reportReady(
            entry: SyncPlayQueueEntry,
            fallbackPositionTicks: Long = 0L,
        ) {
            readyOwedFor = null
            readyFallbackJob?.cancel()
            readyFallbackJob = null
            val parked = parkForReady(fallbackPositionTicks)
            Timber.d(
                "Reporting SyncPlay ready at %d ticks (parked the player: %b)",
                parked.positionTicks,
                parked.parked,
            )
            runCatching {
                api.reportReady(timeSync.serverNow(), parked.positionTicks, false, entry.playlistItemId)
            }.onFailure { Timber.w(it, "Could not report SyncPlay ready") }
            if ((_state.value as? SyncPlayState.InGroup)?.phase == SyncPlayPhase.Buffering) {
                setPhase(SyncPlayPhase.Waiting)
            }
            armSelfSync()
        }

        /**
         * Stops the player where it stands, so the `ready` about to go out is true — see [reportReady].
         *
         * On the main dispatcher and in one pass, deliberately: the position reported has to be the
         * position the player is parked at, and reading it from a second snapshot taken after the
         * pause would only add the jitter of a dispatch. The host's own reading where one is
         * attached, the shared player's otherwise — a detached member goes on playing in the
         * background (see [detachHost]), and it is exactly as unwelcome a `ready` from a running
         * player as an attached one.
         *
         * Nothing else changes: [armSelfSync] still runs after the report, so a member the group
         * never answers is still recovered three seconds later by the self-sync net.
         */
        private suspend fun parkForReady(fallbackPositionTicks: Long): ParkedForReady =
            withContext(mainDispatcher) {
                val snapshot = host?.snapshot()
                val running = snapshot?.isPlaying ?: playerHandle.snapshot().isPlaying
                if (running) {
                    Timber.i("Parking the player to report ready")
                    playerHandle.pause()
                }
                ParkedForReady(snapshot?.positionTicks ?: fallbackPositionTicks, running)
            }

        /** What [parkForReady] found: where the player stands, and whether it had to be stopped. */
        private data class ParkedForReady(
            val positionTicks: Long,
            val parked: Boolean,
        )

        private fun onCommand(command: SyncPlayCommand) {
            // Logged on arrival as well as on application: "the command never came" and "the command
            // came and did nothing" are the two halves of every desync, and only the pair tells them
            // apart in a device log.
            Timber.d("SyncPlay command %s for %s (emitted %s)", command.type, command.whenInstant, command.emittedAt)
            if (_state.value !is SyncPlayState.InGroup) return
            scheduler.schedule(command)
        }

        private suspend fun onCommandApplied(applied: SyncPlayAppliedCommand) {
            // The group has spoken, so neither safety net is needed for this handshake.
            cancelSelfSync()
            cancelPauseNet()
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
         * Whether this member is actually playing, for a `buffering` report.
         *
         * Those used to say `false` unconditionally, which is a lie the *server* acts on: it tracks
         * what each member reported and jellyfin-web sends its real state here. The host's own
         * reading where one is attached, the shared player otherwise — a detached member goes on
         * playing in the background (see [detachHost]), so `false` is only right when nothing is
         * running at all.
         *
         * **Buffering only.** A `ready` is reported from a player [parkForReady] has just stopped,
         * so its answer is `false` by construction rather than by measurement — see [reportReady].
         */
        private suspend fun reportedIsPlaying(snapshot: SyncPlayHostSnapshot?): Boolean =
            snapshot?.isPlaying ?: isPlayerRunning()

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
             * How long an involuntarily lost membership is still worth taking back, in milliseconds.
             *
             * Ten minutes is a judgement about what the user meant, not about the protocol: the case
             * this exists for is the app being backgrounded while the same tablet drives the group
             * from jellyfin-web, where coming back within a few minutes means "I never left". Much
             * longer and the app starts re-joining groups the user has genuinely finished with;
             * much shorter and an evening's browsing in another app costs the group anyway.
             */
            const val FOREGROUND_REJOIN_WINDOW_MS = 600_000L

            /**
             * How long a completed handshake waits for the group's command before syncing itself.
             *
             * Comfortably more than the delay the server builds into an unpause (twice the highest
             * member ping, floored at its 500 ms default), so the net only catches a command that is
             * not coming at all.
             */
            const val SELF_SYNC_TIMEOUT_MS = 3_000L

            /**
             * How long a paused group waits for its pause command before this member pauses itself.
             *
             * The same three seconds as [SELF_SYNC_TIMEOUT_MS], and for the same reason: it is
             * comfortably past the delay the server builds into a scheduled command, so only a
             * command that is not coming at all is caught. Deliberately not shorter — a member that
             * pauses ahead of the instant the group named is out of step just as surely.
             */
            const val PAUSE_NET_TIMEOUT_MS = 3_000L

            /**
             * The second window of both safety nets: how long the command elicited from the server
             * has to arrive before this member acts locally, in milliseconds.
             *
             * Shorter than the first windows because it measures something narrower. Those have to
             * outlast the delay the server *builds into* a scheduled command (twice the highest
             * member ping); this one covers one REST round trip and the application of a command
             * whose instant has, in the "client got lost" re-send, usually already passed — the
             * scheduler applies those immediately. Two seconds is generous for that on a tablet with
             * a busy main thread, and it is the whole of what the two-stage shape costs when the
             * server does not answer: the local fallback that used to fire at three seconds now
             * fires at five.
             */
            const val COMMAND_REPEAT_TIMEOUT_MS = 2_000L

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
