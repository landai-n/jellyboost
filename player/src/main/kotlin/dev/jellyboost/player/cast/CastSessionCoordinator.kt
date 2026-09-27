package dev.jellyboost.player.cast

import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.player.deviceprofile.CastReceiverClass
import dev.jellyboost.player.di.DetachedPlayerScope
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.model.contradicts
import dev.jellyboost.player.model.isSameLoadAs
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.PlaybackTarget
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.RoutingPlayerHandle
import dev.jellyboost.player.session.togglePlayWhenReady
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the cast session: what it is, which player it puts in charge, and who tells the server
 * about it. [start] is called once, from `JellyboostApplication.onCreate`.
 *
 * **Reporting invariant: this coordinator reports only while no host is attached.** With a screen
 * attached, that screen owns the progress ticker and the stop report; a second party sending them
 * would double every stop and race the encoder kill.
 *
 * Transfers themselves belong to the screen that holds the source. What this owes it is the one
 * thing only it can see: where the outgoing player was at the instant playback was routed away
 * ([CastPlaybackHost.onCastStarted], [CastPlaybackHost.onCastEnded]).
 *
 * It also decides when a receiver that let go of the item ([PlayerEvent.RemoteItemMissing]) has
 * really stopped it: after [ITEM_LOST_GRACE] without it coming back. With a screen attached that is
 * the screen's to handle ([CastPlaybackHost.onCastItemLost]); with none, this sends the stop report —
 * once, since the source is forgotten as it is sent and [onCastEnded] then finds nothing to report.
 * A film the receiver played to its end is not a loss ([PlayerEvent.Ended], from the receiver's own
 * finish): with a screen attached the screen ends it as played; with none, [onReceiverFinished] closes
 * it the same one-report way, at the ended reading.
 *
 * **One stop report per source, including a source nobody reopens.** A screen that attaches while a
 * detached source is held either *adopts* it ([heldSourceFor], then [attachHost] with that load as its
 * [CastPlaybackHost.castSource]: no report, the screen's ticker carries on under the same play session)
 * or replaces it on the receiver, in which case [attachHost] reports the orphan's stop before forgetting
 * it.
 */
@Singleton
class CastSessionCoordinator
    @Inject
    @Suppress("LongParameterList") // Six collaborators and the metadata the bar reads; each is a seam a test drives.
    internal constructor(
        private val monitor: CastSessionMonitor,
        private val routing: RoutingPlayerHandle,
        private val reporter: PlaybackReporter,
        private val status: CastStatusHolder,
        @DetachedPlayerScope detachedScope: CoroutineScope,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
        /** Read once per detach, so what the casting bar names cannot be replaced by a later item's fetch. */
        private val metadata: CastMetadataHolder = CastMetadataHolder(),
    ) : CastPlaybackCoordinator {
        internal val connection: StateFlow<CastConnection> = status.connection

        /** `true` while a receiver is playing, or about to — what a resolve asks before negotiating. */
        internal val isCasting: Boolean get() = status.isCasting

        private var host: CastPlaybackHost? = null

        // The read-only half is `internal`, not public; ktlint's rule only recognises the public idiom.
        @Suppress("ktlint:standard:backing-property-naming")
        private val _detached = MutableStateFlow<DetachedCast?>(null)

        /**
         * What this app left playing on the receiver when its screen went: non-`null` exactly while
         * [detachedSource] is. What the chrome's casting bar is drawn from ([CastNowPlaying]).
         */
        internal val detached: StateFlow<DetachedCast?> = _detached.asStateFlow()

        /**
         * Non-`null` only while nobody is attached: then it is the only record the reports have.
         * Backed by [detached], so the bar and the reports can never disagree about what is held; the
         * item's metadata is captured with it, and the last held reading is forgotten with it.
         */
        private var detachedSource: PlaybackMediaSource?
            get() = _detached.value?.source
            set(value) {
                lastHeldReading = null
                _detached.value = value?.let { DetachedCast(it, metadata.metadataFor(it.itemId.toString())) }
            }

        /**
         * The last *valid* reading taken for [detachedSource] ([readReceiver]). What an orphan's stop
         * report carries: by the time [attachHost] learns the source was replaced, the receiver has
         * already been loaded with the next item and its reading belongs to that one.
         */
        private var lastHeldReading: PlaybackSnapshot? = null

        @Suppress("ktlint:standard:backing-property-naming")
        private val _receiverBuffering = MutableStateFlow(false)

        /**
         * `true` while the receiver is waiting for data and means to play ([PlayerEvent.Buffering]).
         * Only ever `true` while casting: a local player's buffering is not the receiver's.
         */
        internal val receiverBuffering: StateFlow<Boolean> = _receiverBuffering.asStateFlow()

        private var tickerJob: Job? = null

        /** Running between a receiver dropping the item and the grace period deciding it is gone. */
        private var itemLostJob: Job? = null

        /**
         * Main-dispatched because every tick reads [RoutingPlayerHandle.snapshot] and `PlayerHandle`
         * snapshots are main-thread-only. Built over the detached scope's *context* rather than a
         * fresh `Job` so the ticker stays a child of it and is never orphaned.
         */
        private val tickerScope = CoroutineScope(detachedScope.coroutineContext + mainDispatcher)

        private val sessionListener =
            object : CastSessionListener {
                override fun onSessionStarted(
                    deviceName: String?,
                    modelName: String?,
                ) = onCastStarted(deviceName, modelName)

                override fun onSessionEnded() = onCastEnded()

                override fun onSessionSuspended() = onCastSuspended()
            }

        /**
         * The player events are collected here as well as by the screen, for the one kind only this
         * class acts on: a receiver dropping the item must be noticed whether or not a screen exists.
         */
        fun start() {
            monitor.start(sessionListener)
            tickerScope.launch { routing.events.collect(::onPlayerEvent) }
        }

        /**
         * Silences this class's own reporting: from here the host's ticker owns the reports.
         *
         * A detached source the host has not adopted is an **orphan**: the host's own open has
         * replaced it on the receiver, and nobody else will ever report it. Its stop goes out here,
         * once, at the last reading held for it — before it is forgotten, and after the ticker that
         * could have reported it again is stopped.
         */
        override fun attachHost(host: CastPlaybackHost) {
            this.host = host
            // The same load, not equality: an adopting screen may since have chosen a track in place (a copy
            // of the source), while anything else — even the same item renegotiated — is a new play session
            // that replaced this one ([isSameLoadAs]).
            val orphan = detachedSource?.takeUnless { it.isSameLoadAs(host.castSource) }
            val lastHeld = lastHeldReading
            stopTicker()
            detachedSource = null
            if (orphan != null) {
                Timber.i("%s was replaced on the receiver; closing its session", orphan.itemId)
                reporter.reportStopDetached(orphan, lastHeld ?: PlaybackSnapshot(isValid = false))
            }
        }

        /**
         * "The receiver holds [itemId]": the detached source is that item, the session is live and
         * not in the middle of losing it ([ITEM_LOST_GRACE]), and the receiver either gives a valid
         * reading for it or is buffering it. An invalid reading with no buffering is a receiver that
         * holds nothing of ours (stopped from the television, or taken over by another sender), and
         * adopting that would show a screen with nothing behind it.
         */
        override fun heldSourceFor(itemId: UUID): CastReceiverHold? {
            val held = detachedSource?.takeIf { it.itemId == itemId && isCasting && itemLostJob == null }
            val reading = held?.let { readReceiver() } ?: return null
            val buffering = _receiverBuffering.value
            return CastReceiverHold(
                source = held,
                reading = reading,
                isBuffering = buffering,
                lastValidReading = lastHeldReading,
            ).takeIf { reading.isValid || (buffering && receiverHoldsDetached()) }
        }

        /**
         * The receiver's reading, remembered when it is valid for a detached source. Every reading
         * this class or the casting bar takes goes through here, so an orphan's stop report carries the
         * freshest position anyone saw. Main thread only, as every snapshot is.
         */
        internal fun readReceiver(): PlaybackSnapshot {
            val reading = detachedReading()
            if (reading.isValid && detachedSource != null) lastHeldReading = reading
            return reading
        }

        /**
         * The receiver's reading, **invalid unless it is about the detached source**. The cast handle
         * judges validity against its newest load, and a new screen's open loads its own film there
         * before it attaches (its `publish` suspends in the start report first): taken at face value, the
         * bar's poll and the detached ticker would record, and report, that film's position as the held
         * one's — and the orphan's stop would carry it.
         */
        private fun detachedReading(): PlaybackSnapshot {
            val reading = routing.snapshot()
            if (detachedSource == null || receiverHoldsDetached()) return reading
            return reading.copy(isValid = false)
        }

        /**
         * The receiver's load is the detached source's **load** ([isSameLoadAs], as [attachHost] compares):
         * same item, media source and play session. Not identity: a track chosen in place (subtitles off, a
         * side-loaded subtitle) replaces the screen's source with a copy while the receiver's load is
         * unchanged, and identity then refused every reading once the screen had left. A handle that tracks
         * no source (`null`) is judged by validity alone.
         */
        private fun receiverHoldsDetached(): Boolean {
            val prepared = routing.preparedSource ?: return true
            return prepared.isSameLoadAs(detachedSource)
        }

        /** The last valid reading for the detached source; `null` when none has been taken. */
        internal val lastHeld: PlaybackSnapshot? get() = lastHeldReading

        /** The receiver's intent — what the casting bar's toggle reverses, readable when the snapshot is not. */
        internal val receiverPlayWhenReady: Boolean get() = routing.playWhenReady

        /** The other half of the toggle's rule ([togglePlayWhenReady]): the receiver's own paused state. */
        internal val receiverSettledPaused: Boolean get() = routing.isSettledPaused

        /**
         * The casting bar's play/pause. Acts only for a detached source: with a screen attached the
         * transport is the screen's, and with nothing held there is nothing of ours to pause. The
         * decision itself is [togglePlayWhenReady], the player screen's rule too.
         */
        internal fun toggleDetachedPlayback() {
            if (host != null || detachedSource == null || !isCasting) return
            routing.togglePlayWhenReady()
        }

        /**
         * The source is taken across on the way out because the host is about to stop existing —
         * and **only while a session is live**: every screen detaches through here, casting or not,
         * and a source remembered from a local session would later have [onCastEnded] report a
         * stop at position zero for a film that was never cast, wiping its resume position.
         *
         * @param host ignored unless it is the attached one, so a stale ViewModel's teardown cannot
         *   detach the screen that replaced it.
         */
        override fun detachHost(host: CastPlaybackHost) {
            if (this.host !== host) return
            this.host = null
            detachedSource = host.castSource.takeIf { isCasting }
            // Seeds the orphan's stop position with where the film was as the screen went: the screen's
            // own last valid reading first (after the setter above cleared the previous source's), then
            // the receiver's, which replaces it when it is valid.
            if (detachedSource != null) {
                lastHeldReading = host.lastValidReading?.takeIf { it.isValid }
                readReceiver()
            }
            startTicker()
        }

        /**
         * Order matters. The snapshot comes **first**, off the still-playing local player — a
         * moment later the only readable player is a cast one at zero. The routing flip comes
         * before [RoutingPlayerHandle.stopInactive], or the local player's `IsPlayingChanged(false)`
         * reaches the screen as if the session about to open had failed.
         *
         * A start for an already-connected session is dropped: the framework delivers one on
         * `onSessionResumed` after a Wi-Fi blip, and re-running the transfer would stop and
         * re-negotiate a stream the receiver is happily playing.
         */
        private fun onCastStarted(
            deviceName: String?,
            modelName: String?,
        ) {
            if (isCasting) {
                Timber.d("Cast session already connected; ignoring a repeated start from %s", deviceName)
                // …apart from what the repeat means: a resumed session is reachable again.
                (status.connection.value as? CastConnection.Connected)
                    ?.takeIf { it.suspended }
                    ?.let { status.setConnection(it.copy(suspended = false)) }
                return
            }
            val receiver = CastReceiverClass.fromModelName(modelName)
            // A 4K receiver logging as LEGACY_1080P here is an allowlist fix in CastReceiverClass.
            Timber.i(
                "Cast session started on %s (model %s, classified %s)",
                deviceName ?: "an unnamed receiver",
                modelName ?: "unknown",
                receiver,
            )
            val handover = routing.snapshot()
            // The intent, not `handover.isPlaying`: a phone buffering toward play is not playing, and the
            // receiver is loaded once, with whatever it is told.
            val wasMeaningToPlay = routing.playWhenReady && !routing.isSettledPaused
            _receiverBuffering.value = false
            status.setConnection(CastConnection.Connected(deviceName, receiver))
            routing.setActive(PlaybackTarget.Cast)
            routing.stopInactive()
            host?.onCastStarted(deviceName, handover, wasMeaningToPlay)
        }

        /**
         * The final snapshot must be taken **before** the routing handle goes back to local: only
         * the cast player knows where the film got to, an idle ExoPlayer would answer zero.
         *
         * [PlaybackReporter.reportStopDetached] carries the encoder kill with it, which is what
         * stops a cast transcode outliving its session. With a screen attached that report is the
         * screen's instead, from the snapshot handed to it.
         *
         * A session ended from the Cast notification usually ends with the receiver already gone, so
         * that final snapshot is invalid. The detached stop then goes out at the **last valid
         * reading** held for the source ([readReceiver]) — never positionless when one exists, since
         * the server reads a positionless stop as a resume position of zero.
         */
        private fun onCastEnded() {
            Timber.i("Cast session ended")
            // Still before routing moves. The zero rule applies here and only here: a torn-down
            // `RemoteCastPlayer` keeps its timeline, so it can claim our item at zero, and no later reading
            // will correct it. Everywhere else a zero from the receiver (a restart from the television's
            // remote) is simply where the film is.
            val raw = detachedReading()
            val last = if (raw.contradicts(lastHeldReading)) raw.copy(isValid = false) else raw
            status.setConnection(CastConnection.None)
            _receiverBuffering.value = false
            stopTicker()
            cancelItemLost()

            val orphaned = detachedSource
            if (host == null && orphaned != null) {
                reporter.reportStopDetached(orphaned, last.takeIf { it.isValid } ?: lastHeldReading ?: last)
            }
            detachedSource = null
            routing.setActive(PlaybackTarget.Local)
            // The receiver is gone but the cast player is not: left alone it keeps its listener,
            // its media items and the `loaded` spec a later subtitle selection would match against.
            routing.stopInactive()
            host?.onCastEnded(last)
        }

        /** Nothing is torn down: the receiver plays on, and the session usually resumes within seconds. */
        private fun onCastSuspended() {
            val connected = status.connection.value as? CastConnection.Connected ?: return
            if (connected.suspended) return
            Timber.i("Cast session suspended; commands wait for it to resume")
            status.setConnection(connected.copy(suspended = true))
        }

        private fun onPlayerEvent(event: PlayerEvent) {
            when (event) {
                is PlayerEvent.RemoteItemMissing -> {
                    if (!isCasting) return
                    cancelItemLost()
                    itemLostJob =
                        tickerScope.launch {
                            delay(ITEM_LOST_GRACE)
                            itemLostJob = null
                            onItemLost(event.lastHeld)
                        }
                }

                PlayerEvent.RemoteItemMissingCleared -> cancelItemLost()

                PlayerEvent.Ended -> onReceiverFinished()

                is PlayerEvent.Buffering -> _receiverBuffering.value = event.isBuffering && isCasting

                else -> Unit
            }
        }

        /**
         * The host is read now, not when the item went missing: a screen that came or went during the
         * grace period is the one that owns the reports by now.
         *
         * Detached, [detachedSource] is cleared as the report is sent — that is what keeps it to one
         * report per source: [onCastEnded] later finds nothing, and the ticker has nothing to tick for.
         * The connection is left alone: the session is still up, so the next open still casts.
         */
        private fun onItemLost(lastHeld: PlaybackSnapshot) {
            if (!isCasting) return
            host?.let { attached ->
                Timber.i("The receiver stopped the item; telling the screen")
                attached.onCastItemLost(lastHeld)
                return
            }
            val orphaned = detachedSource ?: return
            Timber.i("The receiver stopped %s with no screen open; closing its session", orphaned.itemId)
            // Read before the source is forgotten, which forgets its reading too.
            val known = lastHeldReading
            val at = lastHeld.takeUnless { it.contradicts(known) } ?: known ?: lastHeld
            stopTicker()
            detachedSource = null
            reporter.reportStopDetached(orphaned, at)
        }

        /**
         * The receiver played the detached film to its end. Closed here — with no screen, nobody else
         * will — at the **ended** reading, which is what marks it played on the server; never as a loss,
         * which would keep a resume position a few seconds from the end. With a screen attached the end
         * is the screen's (`PlayerViewModel.onEnded`: the played flag, the up-next advance).
         *
         * Only for the detached load ([readReceiver] refuses any other): an end of whatever a new screen
         * has since loaded is not this source's.
         */
        private fun onReceiverFinished() {
            if (!isCasting || host != null) return
            val finished = detachedSource ?: return
            val reading = readReceiver()
            if (!reading.isValid || !reading.hasEnded) return
            Timber.i("The receiver finished %s with no screen open; closing its session", finished.itemId)
            cancelItemLost()
            stopTicker()
            detachedSource = null
            reporter.reportStopDetached(finished, reading)
        }

        private fun cancelItemLost() {
            itemLostJob?.cancel()
            itemLostJob = null
        }

        private fun startTicker() {
            stopTicker()
            val source = detachedSource ?: return
            if (!isCasting) return
            Timber.d("Reporting %s from the detached scope; the screen has gone", source.itemId)
            tickerJob =
                reporter.startReporting(
                    scope = tickerScope,
                    currentSource = { detachedSource },
                    snapshot = ::readReceiver,
                )
        }

        private fun stopTicker() {
            tickerJob?.cancel()
            tickerJob = null
        }

        internal companion object {
            /**
             * Long enough for a receiver to reload or re-report an item it only blinked out of, short
             * enough that a stopped television is noticed while the user is still looking.
             */
            val ITEM_LOST_GRACE = 10.seconds
        }
    }
