package dev.jellyboost.player.music

import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.player.di.MainDispatcher
import dev.jellyboost.player.model.millisToTicks
import dev.jellyboost.player.music.di.MusicSessionScope
import dev.jellyboost.player.report.MusicReportTarget
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.PlaybackHandover
import dev.jellyboost.player.session.PlaybackKind
import dev.jellyboost.player.syncplay.SyncPlayStatusHolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.RepeatMode
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * The music queue: what is in it, where it is, and everything the server is told about it.
 *
 * A `@Singleton` with its own confined scope rather than a ViewModel, for the reason the whole
 * milestone rests on: **the queue is not a screen** (docs/notes/music-m13-plan.md, key decision
 * 1). The user backs out of the album, opens Search, backgrounds the app, turns the screen off,
 * and the album plays on with its reports still going out. Every screen that draws music —
 * `NowPlaying`, the queue sheet, the mini-player in `:app`'s chrome — is a view onto [state].
 *
 * ### What it does not do
 * It does not touch Media3. Everything below the [MusicPlayerPort] seam is the adapter's, which is
 * what makes the ordering below — the part that is actually hard — testable against a fake port in
 * a plain JVM test.
 *
 * ### Ordering, and why there are no locks
 * Four things mutate this class's fields: the caller's [play], the player's event flow, the
 * position ticker and the reporting ticker. All four run on [MusicSessionScope], which is
 * `limitedParallelism(1)`; that is the synchronization, the same arrangement `SyncPlayController`
 * documents. The only calls that leave it are the port's, which hop to the main thread because
 * Media3 throws otherwise.
 *
 * ### One session per track
 * A queue is not one playback session, it is one per track: each entry carries its own
 * `playSessionId` from [MusicStreamResolver], and a media-item transition therefore **stops the
 * outgoing track's session and starts the incoming one's** in that order. Getting it the other way
 * round is what shows a device twice on the dashboard. The invariant is the same one
 * [PlaybackHandover] enforces across video and music, applied within the queue.
 */
@Singleton
@Suppress("TooManyFunctions") // The MusicController surface is eleven verbs; this is that surface.
internal class MusicPlaybackController
    @Inject
    constructor(
        private val port: MusicPlayerPort,
        private val resolver: MusicStreamResolver,
        private val specFactory: MusicQueueSpecFactory,
        private val reporter: PlaybackReporter,
        private val handover: PlaybackHandover,
        private val syncPlay: SyncPlayStatusHolder,
        @MusicSessionScope private val scope: CoroutineScope,
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    ) : MusicController {
        private val _state = MutableStateFlow<MusicPlaybackState>(MusicPlaybackState.Idle)
        override val state: StateFlow<MusicPlaybackState> = _state.asStateFlow()

        private val _messages =
            MutableSharedFlow<MusicMessage>(
                extraBufferCapacity = MESSAGE_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        override val messages: Flow<MusicMessage> = _messages.asSharedFlow()

        /** The queue as the UI sees it, and the same queue as the player sees it. Kept in step. */
        private var items: List<JellyfinItem> = emptyList()
        private var entries: List<MusicQueueEntry> = emptyList()

        private var currentIndex = 0
        private var shuffleEnabled = false
        private var repeatMode = MusicRepeatMode.OFF

        /** The track that currently has an open server session, if any. */
        private var openSession: MusicReportTarget? = null

        /** Which queue position [openSession] belongs to; `null` when no session is open. */
        private var openIndex: Int? = null

        /** `true` once the player has been handed to video; the queue survives as paused state. */
        private var relinquished = false

        /** `true` after the queue ran out — the next play restarts it rather than doing nothing. */
        private var atQueueEnd = false

        /**
         * `true` after a player error: ExoPlayer parks in `IDLE`, where `play()` is a no-op, so
         * the next transport intent routes through [MusicPlayerPort.retryPrepare] first.
         */
        private var needsPrepare = false

        private var ticker: Job? = null

        init {
            scope.launch {
                port.events.collect(::onPlayerEvent)
            }
        }

        override suspend fun play(
            queue: List<JellyfinItem>,
            startIndex: Int,
            shuffled: Boolean,
            startPositionMs: Long,
        ): Boolean = scope.async { startQueue(queue, startIndex, shuffled, startPositionMs) }.await()

        override fun togglePlayPause() {
            launchOnSession {
                val active = _state.value as? MusicPlaybackState.Active ?: return@launchOnSession
                when {
                    relinquished -> reclaimAndResume()
                    active.isPlaying -> onPlayer { pause() }
                    // Play on an exhausted queue means "start it again", which is what Media3's own
                    // play-button handling does for an ended player: a bare play() would do nothing.
                    atQueueEnd -> restartQueue()
                    else -> resumePlayback()
                }
            }
        }

        /**
         * Resume on a paused queue, with the two cases a bare `play()` silently no-ops on: an
         * errored player is re-prepared first, and a player that was released and rebuilt
         * underneath the queue (empty playlist while this state says `Active`) is re-prepared
         * from this class's own state — the mirror of [reclaimAndResume]'s re-prepare.
         *
         * Guarded against SyncPlay like [startQueue]: resume is how a refused `play()` would
         * otherwise sneak music into a group through the mini-player.
         */
        private suspend fun resumePlayback() {
            if (refuseInSyncPlayGroup()) return
            when {
                needsPrepare -> {
                    needsPrepare = false
                    onPlayer {
                        retryPrepare()
                        play()
                    }
                    publish(isPlaying = true)
                    // The error handler closed the track's session; the retry re-opens it so the
                    // resumed playback reports again. A second failure closes it again.
                    if (openSession == null) {
                        openSessionFor(
                            currentIndex,
                            positionTicks = _state.value.positionMsOrZero().millisToTicks(),
                            isPaused = false,
                        )
                    }
                    startTicker()
                }

                onPlayer { snapshot() }.mediaItemCount == 0 -> reprepareFromState()
                else -> onPlayer { play() }
            }
        }

        /** Rebuilds the player's playlist from this class's state, keeping the open session. */
        private suspend fun reprepareFromState() {
            val resumeAt = _state.value.positionMsOrZero()
            Timber.i("The player lost the queue (rebuild); re-preparing %d entries", entries.size)
            onPlayer {
                setShuffleEnabled(shuffleEnabled)
                setRepeatMode(repeatMode)
                setQueue(entries, currentIndex, startPositionMs = resumeAt, playWhenReady = true)
            }
            publish(isPlaying = true, positionMs = resumeAt)
            // The session survives a rebuild — same track, same playSessionId — so it is only
            // opened when none is: re-reporting a start would double the dashboard row.
            if (openSession == null) {
                openSessionFor(currentIndex, positionTicks = resumeAt.millisToTicks(), isPaused = false)
            }
            startTicker()
        }

        /** Emits [MusicMessage.RefusedInSyncPlayGroup] and answers `true` while in a group. */
        private fun refuseInSyncPlayGroup(): Boolean {
            if (!syncPlay.inGroup.value) return false
            Timber.i("Refusing to resume music: this device is in a SyncPlay group")
            _messages.tryEmit(MusicMessage.RefusedInSyncPlayGroup)
            return true
        }

        override fun next() = onSession { next() }

        override fun previous() = onSession { previous() }

        override fun seekTo(positionMs: Long) {
            launchOnSession {
                onPlayer { seekTo(positionMs) }
                publish(positionMs = positionMs)
            }
        }

        override fun setShuffle(enabled: Boolean) {
            launchOnSession {
                shuffleEnabled = enabled
                onPlayer { setShuffleEnabled(enabled) }
                publish()
                // The dashboard shows the mode on the session, so a change is worth one tick.
                reportProgressNow()
            }
        }

        override fun cycleRepeat() {
            launchOnSession {
                repeatMode = repeatMode.next
                onPlayer { setRepeatMode(repeatMode) }
                publish()
                reportProgressNow()
            }
        }

        override fun jumpTo(index: Int) {
            launchOnSession {
                if (index !in entries.indices) return@launchOnSession
                if (relinquished) {
                    currentIndex = index
                    reclaimAndResume()
                    return@launchOnSession
                }
                atQueueEnd = false
                onPlayer { seekToItem(index) }
            }
        }

        override fun removeAt(index: Int) {
            launchOnSession {
                if (index !in entries.indices) return@launchOnSession
                items = items.toMutableList().apply { removeAt(index) }
                entries = entries.toMutableList().apply { removeAt(index) }
                // Removing the entry that holds the open session closes it *before* the player
                // removal: the transition Media3 fires for the track that slides in must find no
                // open session (and no stale openIndex to match), so it opens the new track's own
                // rather than being swallowed as the echo of the one just removed.
                if (index == openIndex) {
                    closeOpenSession(hasEnded = false, positionMs = _state.value.positionMsOrZero())
                }
                onPlayer { removeItem(index) }
                if (entries.isEmpty()) {
                    endSession()
                    return@launchOnSession
                }
                // The player has already moved the timeline; the open session's position follows it.
                openIndex = openIndex?.let { if (it > index) it - 1 else it }
                publish(index = onPlayer { snapshot() }.currentItemIndex)
            }
        }

        override fun moveItem(
            from: Int,
            to: Int,
        ) {
            launchOnSession {
                if (from !in entries.indices || to !in entries.indices || from == to) return@launchOnSession
                items = items.toMutableList().apply { add(to, removeAt(from)) }
                entries = entries.toMutableList().apply { add(to, removeAt(from)) }
                onPlayer { moveItem(from, to) }
                // Pure index arithmetic, the same displacement the player applies. Looking the
                // session up by playSessionId cannot work here: a downloaded track's is null, and
                // null matches the *first* downloaded entry, wherever the session really is.
                openIndex =
                    openIndex?.let { open ->
                        when {
                            open == from -> to
                            open in (from + 1)..to -> open - 1
                            open in to until from -> open + 1
                            else -> open
                        }
                    }
                publish(index = onPlayer { snapshot() }.currentItemIndex)
            }
        }

        override fun stop() {
            launchOnSession { endSession() }
        }

        // ---------------------------------------------------------------- queue lifecycle

        private suspend fun startQueue(
            queue: List<JellyfinItem>,
            startIndex: Int,
            shuffled: Boolean,
            startPositionMs: Long = 0L,
        ): Boolean {
            if (syncPlay.inGroup.value) {
                Timber.i("Refusing to start music: this device is in a SyncPlay group")
                _messages.tryEmit(MusicMessage.RefusedInSyncPlayGroup)
                return false
            }
            if (queue.isEmpty()) {
                _messages.tryEmit(MusicMessage.QueueUnavailable)
                return false
            }

            val resolved = resolveAll(queue)
            val playable = resolved.filter { it.second != null }
            if (playable.isEmpty()) {
                Timber.w("Nothing in a %d-track queue could be resolved", queue.size)
                _messages.tryEmit(MusicMessage.QueueUnavailable)
                return false
            }
            // One message however many were dropped: a snackbar per unavailable track would bury
            // the queue that did start, and the first name is enough to say what went wrong.
            resolved.firstOrNull { it.second == null }?.let {
                _messages.tryEmit(MusicMessage.TrackUnavailable(it.first.name))
            }

            // Whatever the caller asked to start at, mapped past the tracks that fell out.
            val wanted = queue.getOrNull(startIndex)?.id
            val start = playable.indexOfFirst { it.first.id == wanted }.takeIf { it >= 0 } ?: 0

            // Video, if it holds the player, closes its session here — before anything below
            // touches the player, and completed by the time this returns.
            handover.claim(PlaybackKind.MUSIC, ::relinquishToOther)

            // A session may already be open on the previous queue; it ends here, not on the
            // transition the new `setMediaItems` is about to fire.
            closeOpenSession(hasEnded = false, positionMs = _state.value.positionMsOrZero())

            items = playable.map { it.first }
            entries = playable.map { specFactory.create(it.first, requireNotNull(it.second)) }
            currentIndex = start
            shuffleEnabled = shuffled
            relinquished = false
            atQueueEnd = false
            needsPrepare = false

            onPlayer {
                setShuffleEnabled(shuffled)
                setRepeatMode(repeatMode)
                setQueue(entries, start, startPositionMs = startPositionMs, playWhenReady = true)
            }
            publish(index = start, isPlaying = true, positionMs = startPositionMs, durationMs = 0L)
            openSessionFor(start, positionTicks = startPositionMs.millisToTicks(), isPaused = false)
            startTicker()
            return true
        }

        /**
         * Resolves the whole queue at once, a few tracks at a time.
         *
         * Bounded because a resolve is a downloads lookup — Room plus a filesystem stat — and a
         * three-hundred-track artist shuffle firing all of them at once would stall the IO
         * dispatcher for everything else in the app. Order is preserved: `awaitAll` returns in the
         * order the deferreds were created, which is the queue's order.
         */
        private suspend fun resolveAll(queue: List<JellyfinItem>): List<Pair<JellyfinItem, MusicStream?>> =
            coroutineScope {
                val permits = Semaphore(RESOLVE_PARALLELISM)
                queue
                    .map { item -> async { item to permits.withPermit { resolver.resolve(item) } } }
                    .awaitAll()
            }

        /** Play on a queue that has run out: back to the top, from the beginning. */
        private suspend fun restartQueue() {
            atQueueEnd = false
            needsPrepare = false
            currentIndex = 0
            onPlayer { setQueue(entries, 0, startPositionMs = 0L, playWhenReady = true) }
            publish(index = 0, isPlaying = true, positionMs = 0L)
            openSessionFor(0, positionTicks = 0L, isPaused = false)
            startTicker()
        }

        /**
         * Takes the player back after video borrowed it, at the position music left off at.
         *
         * Refused in a SyncPlay group: [startQueue]'s guard covers a *new* queue, but resuming a
         * parked one reaches the player through here without ever passing it, and a parked queue
         * must not sneak music into a group either.
         */
        private suspend fun reclaimAndResume() {
            if (refuseInSyncPlayGroup()) return
            val resumeAt = _state.value.positionMsOrZero()
            handover.claim(PlaybackKind.MUSIC, ::relinquishToOther)
            relinquished = false
            atQueueEnd = false
            needsPrepare = false
            onPlayer {
                setShuffleEnabled(shuffleEnabled)
                setRepeatMode(repeatMode)
                setQueue(entries, currentIndex, startPositionMs = resumeAt, playWhenReady = true)
            }
            publish(isPlaying = true, positionMs = resumeAt)
            openSessionFor(currentIndex, positionTicks = resumeAt.millisToTicks(), isPaused = false)
            startTicker()
        }

        /**
         * Video is taking the player.
         *
         * Called by [PlaybackHandover] while it holds its lock, and it must complete before the
         * claimant prepares: the stop report below is the *only* thing that closes this device's
         * music session on the server, and a `PlaybackInfo` for the film arriving first is exactly
         * the double session the arbiter exists to prevent.
         *
         * The arbiter invokes it inline in the *claimant's* context (that is what "completed
         * before `claim` returns" means), so the body hops onto the session dispatcher first:
         * that serialises it with every command already queued on [MusicSessionScope] — a `next()`
         * tapped a frame before the film started runs *after* this and finds [relinquished] set,
         * instead of racing the park on another thread. Setting the flag is the first act on that
         * dispatcher for the same reason. Player calls inside still marshal through [onPlayer];
         * the arbiter never dictates a thread ([PlaybackHandover]'s contract).
         *
         * The queue is not thrown away — it becomes a paused [MusicPlaybackState.Active] snapshot,
         * so the mini-player still shows what was playing and one tap resumes it where it was
         * (key decision 3).
         */
        private suspend fun relinquishToOther() {
            withContext(sessionDispatcher()) {
                if (_state.value !is MusicPlaybackState.Active) return@withContext
                relinquished = true
                Timber.i("Music is handing the player over; parking the queue at index %d", currentIndex)
                stopTicker()
                val snapshot = onPlayer { snapshot() }
                closeOpenSession(hasEnded = false, positionMs = snapshot.positionMs)
                currentIndex = snapshot.currentItemIndex.coerceIn(entries.indices)
                publish(index = currentIndex, isPlaying = false, positionMs = snapshot.positionMs)
                // The playlist goes, the service does not: whoever is claiming is about to prepare
                // on this same player and `ExoPlayerHandle.prepare` starts the service itself.
                onPlayer { release() }
            }
        }

        /** The session scope's own dispatcher, for completing work *on* the queue's serial lane. */
        private fun sessionDispatcher(): CoroutineContext =
            scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

        /**
         * The session ends for good: final report, player and notification gone, back to Idle.
         *
         * A no-op when there is no queue, and that guard is load-bearing rather than defensive:
         * `stopAndRelease` stops the *shared* media session service, so a `stop()` arriving while
         * nothing musical is loaded — a stale mini-player action, a queue emptied twice — would
         * tear down a film's notification and its foreground promotion with it.
         */
        private suspend fun endSession() {
            if (_state.value !is MusicPlaybackState.Active) return
            stopTicker()
            val positionMs = if (relinquished) _state.value.positionMsOrZero() else onPlayer { snapshot() }.positionMs
            closeOpenSession(hasEnded = false, positionMs = positionMs)
            if (!relinquished) onPlayer { stopAndRelease() }
            handover.release(PlaybackKind.MUSIC)
            items = emptyList()
            entries = emptyList()
            currentIndex = 0
            relinquished = false
            atQueueEnd = false
            needsPrepare = false
            _state.value = MusicPlaybackState.Idle
        }

        // ---------------------------------------------------------------- player events

        private suspend fun onPlayerEvent(event: MusicPlayerEvent) {
            when (event) {
                is MusicPlayerEvent.ItemTransition -> onTransition(event)
                is MusicPlayerEvent.IsPlayingChanged -> onIsPlayingChanged(event.isPlaying)
                MusicPlayerEvent.Ended -> onEnded()
                is MusicPlayerEvent.Error -> onError(event)
            }
        }

        /**
         * The player moved to another entry — the one event the video path never needed.
         *
         * The echo of our own `setMediaItems` is ignored: Media3 fires a transition for the
         * playlist change too, and its entry already has the session [startQueue] opened for it.
         *
         * Ignored outright while [relinquished]: the adapter's teardown detaches its listener
         * before it clears the playlist, but this event flow is buffered and [relinquishToOther]
         * suspends — an echo emitted just before the detach can still be waiting here after the
         * park, and processing it would re-open a session the handover just closed (defence in
         * depth for the release-echo bug; the listener-before-clear ordering is the first line).
         */
        private suspend fun onTransition(event: MusicPlayerEvent.ItemTransition) {
            if (relinquished) return
            if (entries.isEmpty()) return
            if (event.index == openIndex) return
            atQueueEnd = false
            needsPrepare = false
            // The outgoing track's own position is gone the moment the player moved, so a *skip*
            // is reported at the last position the one-second ticker saw. A track that finished
            // does not need one: its stop report carries the item's full runtime.
            closeOpenSession(hasEnded = event.automatic, positionMs = _state.value.positionMsOrZero())
            currentIndex = event.index.coerceIn(entries.indices)
            publish(index = currentIndex, positionMs = 0L, durationMs = 0L)
            openSessionFor(currentIndex, positionTicks = 0L, isPaused = false)
        }

        private suspend fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_state.value !is MusicPlaybackState.Active) return
            publish(isPlaying = isPlaying)
            if (isPlaying) startTicker() else stopTicker()
            // A pause is news the dashboard should show without waiting for the next ten-second tick.
            reportProgressNow()
        }

        /**
         * The queue ran out.
         *
         * The state stays `Active` — paused on the last track, which is what every music player
         * does and what leaves the mini-player showing something to press play on. Only [stop]
         * goes back to `Idle`.
         */
        private suspend fun onEnded() {
            if (_state.value !is MusicPlaybackState.Active) return
            stopTicker()
            atQueueEnd = true
            closeOpenSession(hasEnded = true, positionMs = 0L)
            publish(isPlaying = false)
        }

        private suspend fun onError(event: MusicPlayerEvent.Error) {
            Timber.w("Music playback failed (%d): %s", event.code, event.message)
            stopTicker()
            // The player is now parked in IDLE; a bare play() there is a silent no-op, so the
            // next resume or skip routes through retryPrepare() first.
            needsPrepare = true
            closeOpenSession(hasEnded = false, positionMs = _state.value.positionMsOrZero())
            _messages.tryEmit(MusicMessage.PlaybackFailed(items.getOrNull(currentIndex)?.name.orEmpty()))
            publish(isPlaying = false)
        }

        // ---------------------------------------------------------------- reporting

        private suspend fun openSessionFor(
            index: Int,
            positionTicks: Long,
            isPaused: Boolean,
        ) {
            val entry = entries.getOrNull(index) ?: return
            val target = entry.toReportTarget()
            openSession = target
            openIndex = index
            reporter.reportMusicStart(
                target = target,
                positionTicks = positionTicks,
                isPaused = isPaused,
                repeatMode = repeatMode.toSdk(),
                playbackOrder = playbackOrder(),
            )
        }

        private suspend fun closeOpenSession(
            hasEnded: Boolean,
            positionMs: Long,
        ) {
            val target = openSession ?: return
            // Cleared first: a stop report is issued once per session, and everything below can
            // suspend.
            openSession = null
            openIndex = null
            reporter.reportMusicStop(
                target = target,
                positionTicks = positionMs.millisToTicks(),
                hasEnded = hasEnded,
            )
        }

        private suspend fun reportProgressNow() {
            val target = openSession ?: return
            val active = _state.value as? MusicPlaybackState.Active ?: return
            reporter.reportMusicProgress(
                target = target,
                positionTicks = active.positionMs.millisToTicks(),
                isPaused = !active.isPlaying,
                repeatMode = repeatMode.toSdk(),
                playbackOrder = playbackOrder(),
            )
        }

        /**
         * The one-second state tick, and every tenth of them a progress report.
         *
         * One loop rather than two because the reporting cadence is a multiple of the UI's: two
         * timers would drift apart and take two snapshots of the player per second for no reason.
         */
        private fun startTicker() {
            if (ticker?.isActive == true) return
            ticker =
                scope.launch {
                    var tick = 0
                    while (isActive) {
                        delay(STATE_TICK)
                        val snapshot = onPlayer { snapshot() }
                        publish(
                            index = snapshot.currentItemIndex,
                            isPlaying = snapshot.isPlaying,
                            positionMs = snapshot.positionMs,
                            durationMs = snapshot.durationMs,
                        )
                        tick++
                        if (tick % PROGRESS_EVERY_N_TICKS == 0) reportProgressNow()
                    }
                }
        }

        private fun stopTicker() {
            ticker?.cancel()
            ticker = null
        }

        // ---------------------------------------------------------------- plumbing

        /**
         * Publishes a state change, carrying forward everything the caller did not name.
         *
         * Every field defaults to what is already published, so a caller says only what it knows —
         * a transition names the index, the ticker names the position — and nothing else is
         * silently reset to a default that happens to be wrong.
         */
        private fun publish(
            index: Int? = null,
            isPlaying: Boolean? = null,
            positionMs: Long? = null,
            durationMs: Long? = null,
        ) {
            if (items.isEmpty()) return
            val previous = _state.value as? MusicPlaybackState.Active
            _state.value =
                MusicPlaybackState.Active(
                    queue = items,
                    currentIndex = (index ?: previous?.currentIndex ?: currentIndex).coerceIn(items.indices),
                    isPlaying = isPlaying ?: previous?.isPlaying ?: false,
                    positionMs = positionMs ?: previous?.positionMs ?: 0L,
                    durationMs = durationMs ?: previous?.durationMs ?: 0L,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    parked = relinquished,
                )
        }

        /** Runs [block] against the port on the thread Media3 insists on. */
        private suspend fun <T> onPlayer(block: MusicPlayerPort.() -> T): T =
            withContext(mainDispatcher) { port.block() }

        private fun launchOnSession(block: suspend () -> Unit) {
            scope.launch { block() }
        }

        private fun onSession(block: MusicPlayerPort.() -> Unit) {
            launchOnSession {
                if (_state.value !is MusicPlaybackState.Active) return@launchOnSession
                if (relinquished) {
                    reclaimAndResume()
                    return@launchOnSession
                }
                atQueueEnd = false
                // A skip after an error must revive the IDLE player first, or the seek lands in a
                // player that will never play it (finding: post-error transport no-ops).
                if (needsPrepare) {
                    needsPrepare = false
                    onPlayer { retryPrepare() }
                }
                onPlayer(block)
            }
        }

        private fun playbackOrder(): PlaybackOrder =
            if (shuffleEnabled) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT

        private fun MusicQueueEntry.toReportTarget(): MusicReportTarget =
            MusicReportTarget(
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                playMethod = playMethod,
                playSessionId = playSessionId,
                runTimeTicks = runTimeTicks,
            )

        private companion object {
            val STATE_TICK = 1.seconds

            /** Ten one-second ticks — the ten-second reporting cadence the plan settles on. */
            const val PROGRESS_EVERY_N_TICKS = 10

            /** How many tracks are resolved at once; see `resolveAll`. */
            const val RESOLVE_PARALLELISM = 4

            const val MESSAGE_BUFFER = 8
        }
    }

/** The published position, or zero when nothing is loaded. */
private fun MusicPlaybackState.positionMsOrZero(): Long = (this as? MusicPlaybackState.Active)?.positionMs ?: 0L

/** Our repeat vocabulary is the server's; this is the one place the two meet. */
private fun MusicRepeatMode.toSdk(): RepeatMode =
    when (this) {
        MusicRepeatMode.OFF -> RepeatMode.REPEAT_NONE
        MusicRepeatMode.ALL -> RepeatMode.REPEAT_ALL
        MusicRepeatMode.ONE -> RepeatMode.REPEAT_ONE
    }
