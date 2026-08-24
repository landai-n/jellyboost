package dev.jellyboost.player.music

import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicController
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
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
 * There are no locks: every mutator (callers, the player event flow, both tickers) runs on
 * [MusicSessionScope], which is `limitedParallelism(1)`. Only port calls leave it, hopping to
 * the main thread because Media3 throws otherwise.
 *
 * One playback session per *track*, not per queue: a transition must stop the outgoing track's
 * session before starting the incoming one's, or the device shows twice on the dashboard.
 */
@Singleton
@Suppress("TooManyFunctions")
internal class MusicPlaybackController
    @Inject
    @Suppress("LongParameterList")
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

        /** Parallel to [entries]: same order, same size. */
        private var items: List<JellyfinItem> = emptyList()
        private var entries: List<MusicQueueEntry> = emptyList()

        private var currentIndex = 0
        private var shuffleEnabled = false
        private var repeatMode = MusicRepeatMode.OFF

        private var openSession: MusicReportTarget? = null

        private var openIndex: Int? = null

        private var relinquished = false

        private var atQueueEnd = false

        /** After an error ExoPlayer parks in `IDLE`, where `play()` is a silent no-op. */
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
                    // A bare play() on an ended player does nothing; restart instead.
                    atQueueEnd -> restartQueue()
                    else -> resumePlayback()
                }
            }
        }

        /**
         * Two cases where a bare `play()` silently no-ops: an errored player, and a player
         * released and rebuilt underneath the queue (empty playlist while this state is `Active`).
         * Guarded against SyncPlay like [startQueue] — resume is the other way into a group.
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
                    // The error handler closed the track's session; a successful retry re-opens it.
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

        private suspend fun reprepareFromState() {
            val resumeAt = _state.value.positionMsOrZero()
            Timber.i("The player lost the queue (rebuild); re-preparing %d entries", entries.size)
            onPlayer {
                setShuffleEnabled(shuffleEnabled)
                setRepeatMode(repeatMode)
                setQueue(entries, currentIndex, startPositionMs = resumeAt, playWhenReady = true)
            }
            publish(isPlaying = true, positionMs = resumeAt)
            // Same track, same playSessionId: re-reporting a start would double the dashboard row.
            if (openSession == null) {
                openSessionFor(currentIndex, positionTicks = resumeAt.millisToTicks(), isPaused = false)
            }
            startTicker()
        }

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
                // Close before the player removal: the transition Media3 then fires must find no
                // open session (and no stale openIndex), or the new track's start is swallowed
                // as the echo of the removed one.
                if (index == openIndex) {
                    closeOpenSession(hasEnded = false, positionMs = _state.value.positionMsOrZero())
                }
                onPlayer { removeItem(index) }
                if (entries.isEmpty()) {
                    endSession()
                    return@launchOnSession
                }
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
                // Not looked up by playSessionId: a downloaded track's is null, and null matches
                // the *first* downloaded entry rather than the one holding the session.
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
            val resolved = resolveAll(queue)
            val playable = resolved.filter { it.second != null }
            if (playable.isEmpty()) {
                if (queue.isNotEmpty()) {
                    Timber.w("Nothing in a %d-track queue could be resolved", queue.size)
                }
                _messages.tryEmit(MusicMessage.QueueUnavailable)
                return false
            }
            // One message however many dropped: a snackbar each would bury the queue that started.
            resolved.firstOrNull { it.second == null }?.let {
                _messages.tryEmit(MusicMessage.TrackUnavailable(it.first.name))
            }

            val wanted = queue.getOrNull(startIndex)?.id
            val start = playable.indexOfFirst { it.first.id == wanted }.takeIf { it >= 0 } ?: 0

            // Must complete before anything below touches the player: video closes its session here.
            handover.claim(PlaybackKind.MUSIC, ::relinquishToOther)

            // The previous queue's session ends here, not on the transition `setQueue` will fire.
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
         * Bounded because each resolve is a Room lookup plus a filesystem stat: a three-hundred-track
         * shuffle firing them all at once stalls the IO dispatcher. `awaitAll` preserves queue order.
         */
        private suspend fun resolveAll(queue: List<JellyfinItem>): List<Pair<JellyfinItem, MusicStream?>> =
            coroutineScope {
                val permits = Semaphore(RESOLVE_PARALLELISM)
                queue
                    .map { item -> async { item to permits.withPermit { resolver.resolve(item) } } }
                    .awaitAll()
            }

        private suspend fun restartQueue() {
            atQueueEnd = false
            needsPrepare = false
            currentIndex = 0
            onPlayer { setQueue(entries, 0, startPositionMs = 0L, playWhenReady = true) }
            publish(index = 0, isPlaying = true, positionMs = 0L)
            openSessionFor(0, positionTicks = 0L, isPaused = false)
            startTicker()
        }

        /** Refused in a SyncPlay group: a parked queue reaches the player here, bypassing [startQueue]'s guard. */
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
         * Must complete before the claimant prepares: the stop report below is the only thing that
         * closes this device's music session on the server.
         *
         * [PlaybackHandover] invokes this inline in the *claimant's* context, so the body hops onto
         * the session dispatcher to serialise with commands already queued on [MusicSessionScope];
         * setting [relinquished] is the first act there for the same reason.
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
                // release(), not stopAndRelease(): the claimant prepares on this same player and
                // `ExoPlayerHandle.prepare` restarts the service itself.
                onPlayer { release() }
            }
        }

        private fun sessionDispatcher(): CoroutineContext =
            scope.coroutineContext[ContinuationInterceptor] ?: EmptyCoroutineContext

        /**
         * The `Active` guard is load-bearing: `stopAndRelease` stops the *shared* media session
         * service, so a `stop()` arriving with no queue loaded would tear down a film's
         * notification and its foreground promotion.
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
         * The `openIndex` check drops the echo of our own `setQueue`: Media3 fires a transition for
         * the playlist change too, and that entry already has the session [startQueue] opened.
         *
         * The [relinquished] check drops a buffered echo emitted before the adapter detached its
         * listener; processing it would re-open a session the handover just closed.
         */
        private suspend fun onTransition(event: MusicPlayerEvent.ItemTransition) {
            if (relinquished) return
            if (entries.isEmpty()) return
            if (event.index == openIndex) return
            atQueueEnd = false
            needsPrepare = false
            // The outgoing track's position is gone once the player moved, so a skip reports the
            // last position the ticker saw; a finished track's stop report carries its full runtime.
            closeOpenSession(hasEnded = event.automatic, positionMs = _state.value.positionMsOrZero())
            currentIndex = event.index.coerceIn(entries.indices)
            publish(index = currentIndex, positionMs = 0L, durationMs = 0L)
            openSessionFor(currentIndex, positionTicks = 0L, isPaused = false)
        }

        private suspend fun onIsPlayingChanged(isPlaying: Boolean) {
            if (_state.value !is MusicPlaybackState.Active) return
            publish(isPlaying = isPlaying)
            if (isPlaying) startTicker() else stopTicker()
            // A pause should reach the dashboard without waiting for the next ten-second tick.
            reportProgressNow()
        }

        /** Stays `Active`, paused on the last track — only [stop] returns to `Idle`. */
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
            // Cleared before the suspending report: a stop is issued once per session.
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

        /** One loop for both cadences: separate timers would drift and double the player snapshots. */
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

        /** Unnamed fields carry forward from the published state; a caller passes only what it knows. */
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

        /** Every port call must run on the main thread; Media3 throws otherwise. */
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
                // player that will never play it.
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

            /** Ten [STATE_TICK]s = the ten-second reporting cadence. */
            const val PROGRESS_EVERY_N_TICKS = 10

            const val RESOLVE_PARALLELISM = 4

            const val MESSAGE_BUFFER = 8
        }
    }

private fun MusicPlaybackState.positionMsOrZero(): Long = (this as? MusicPlaybackState.Active)?.positionMs ?: 0L

private fun MusicRepeatMode.toSdk(): RepeatMode =
    when (this) {
        MusicRepeatMode.OFF -> RepeatMode.REPEAT_NONE
        MusicRepeatMode.ALL -> RepeatMode.REPEAT_ALL
        MusicRepeatMode.ONE -> RepeatMode.REPEAT_ONE
    }
