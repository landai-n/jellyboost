package dev.jellyfinnative.player.syncplay

import dev.jellyfinnative.core.network.SessionStateHolder
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.core.network.model.SessionState
import dev.jellyfinnative.player.di.MainDispatcher
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
import timber.log.Timber
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
 * ### Losing the connection
 * A confirmed loss — the socket collection ending, or [ConnectionStateProvider] going offline —
 * pauses the player, leaves the group and says so (key decision 10 as amended). Nothing here
 * resumes: playing on would mean drifting from the group invisibly, so the state change is made
 * honest and the user resumes solo with one tap. A momentary socket flap that the SDK reconnects
 * through is *not* a loss and deliberately does nothing.
 */
@Singleton
@Suppress("TooManyFunctions") // A protocol coordinator; the intents alone are the plan's 15.
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
            joined
                .onSuccess { enterGroup(it, session) }
                .onFailure {
                    Timber.w(it, "Could not join a SyncPlay group")
                    _state.value = SyncPlayState.Idle
                    closeSession()
                    _messages.tryEmit(SyncPlayMessage.JoinFailed)
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
            _state.value = SyncPlayState.InGroup(pendingGroup ?: group, null, SyncPlayPhase.Waiting)
            pendingGroup = null
            statusHolder.setInGroup(true)

            session.launch { pinger.run() }
            session.launch { playerHandle.events.collect(::onPlayerEvent) }
            session.launch { scheduler.applied.collect(::onCommandApplied) }
            session.launch { observeAnchor() }
            session.launch { watchConnectivity() }
            session.launch { watchSignOut() }

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
        ) = sessionMutex.withLock {
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
            if (pausePlayer) withContext(mainDispatcher) { playerHandle.pause() }
            message?.let { _messages.tryEmit(it) }
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
            onConnectionLost()
        }

        private fun onConnectionLost() {
            tearDownAsync(SyncPlayMessage.ConnectionLost, pausePlayer = true)
        }

        private suspend fun watchConnectivity() {
            connectionState.state.first { !it.isOnline }
            Timber.w("Connectivity lost while in a SyncPlay group")
            onConnectionLost()
        }

        private suspend fun watchSignOut() {
            sessionStateHolder.state.first { it is SessionState.LoggedOut }
            scope.launch {
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
                SyncPlayGroupEvent.NotInGroup -> tearDownAsync(SyncPlayMessage.RemovedFromGroup)
                SyncPlayGroupEvent.GroupGone -> tearDownAsync(SyncPlayMessage.GroupEnded)
                SyncPlayGroupEvent.LibraryAccessDenied -> tearDownAsync(SyncPlayMessage.LibraryAccessDenied)
            }
        }

        private fun onJoined(group: SyncPlayGroupSummary) {
            if (_state.value is SyncPlayState.InGroup) updateGroup { group } else pendingGroup = group
        }

        private fun onLeft(groupId: UUID) {
            val current = _state.value as? SyncPlayState.InGroup ?: return
            if (current.group.id != groupId) return
            tearDownAsync(SyncPlayMessage.RemovedFromGroup)
        }

        /**
         * Mirrors the group's own state onto this member's phase — except `Playing`.
         *
         * `Playing` is owned by the applied unpause, because that is the only thing that knows the
         * anchor; taking it from a state update would give the drift monitor nothing to measure.
         */
        private fun onGroupStateChanged(groupState: SyncPlayGroupState) {
            val phase =
                when (groupState) {
                    SyncPlayGroupState.Waiting -> SyncPlayPhase.Waiting
                    SyncPlayGroupState.Paused, SyncPlayGroupState.Idle -> SyncPlayPhase.Paused
                    SyncPlayGroupState.Playing -> return
                }
            setPhase(phase)
        }

        private suspend fun onQueueChanged(queue: SyncPlayGroupQueue) {
            val current = _state.value as? SyncPlayState.InGroup
            if (current == null) {
                pendingQueue = queue
                return
            }
            _state.value = current.copy(queue = queue)
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
            if (entry.playlistItemId == loadedPlaylistItemId) return

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
            runCatching {
                api.reportBuffering(timeSync.serverNow(), queue.startPositionTicks, false, entry.playlistItemId)
            }.onFailure { Timber.w(it, "Could not report SyncPlay buffering") }

            val loaded = runCatching { attached.loadItem(entry.itemId, queue.startPositionTicks) }.getOrElse { false }
            if (loaded) onEntryOpened(entry) else onEntryUnplayable(entry)
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
         * Every readiness, not only the first: a re-negotiation rebuilds the player, and the group
         * is waiting on this client until it says it is prepared again.
         */
        private suspend fun onPlayerReady() {
            val entry = currentEntry() ?: return
            reportReady(entry, hostSnapshot()?.positionTicks ?: 0L)
            if ((_state.value as? SyncPlayState.InGroup)?.phase == SyncPlayPhase.Buffering) {
                setPhase(SyncPlayPhase.Waiting)
            }
        }

        /**
         * Advancing the queue is the group's decision, not this client's: ask, and move when the
         * server tells everyone to.
         */
        private suspend fun onPlaybackEnded() {
            val entry = currentEntry() ?: return
            runCatching { api.requestNextItem(entry.playlistItemId) }
                .onFailure { Timber.w(it, "Could not request the next SyncPlay item") }
        }

        private suspend fun reportReady(
            entry: SyncPlayQueueEntry,
            positionTicks: Long,
        ) {
            runCatching { api.reportReady(timeSync.serverNow(), positionTicks, false, entry.playlistItemId) }
                .onFailure { Timber.w(it, "Could not report SyncPlay ready") }
        }

        private fun onCommand(command: SyncPlayCommand) {
            if (_state.value !is SyncPlayState.InGroup) return
            scheduler.schedule(command)
        }

        private fun onCommandApplied(applied: SyncPlayAppliedCommand) {
            val phase =
                when (applied.command.type) {
                    SyncPlayCommandType.Unpause -> applied.anchor?.let(SyncPlayPhase::Playing) ?: SyncPlayPhase.Paused
                    // A seek invalidates whatever anchor was current, so it drops out of Playing
                    // too — the unpause that follows the group's re-handshake sets the next one.
                    else -> SyncPlayPhase.Paused
                }
            setPhase(phase)
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

        private fun launchInSession(block: suspend CoroutineScope.() -> Unit) {
            (sessionScope ?: scope).launch(block = block)
        }

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

            private const val EVENT_BUFFER = 8
        }
    }
