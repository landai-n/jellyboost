package dev.jellyboost.player.cast

import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.player.deviceprofile.CastReceiverClass
import dev.jellyboost.player.di.DetachedPlayerScope
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.PlaybackTarget
import dev.jellyboost.player.session.PlayerEvent
import dev.jellyboost.player.session.RoutingPlayerHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
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
 */
@Singleton
class CastSessionCoordinator
    @Inject
    internal constructor(
        private val monitor: CastSessionMonitor,
        private val routing: RoutingPlayerHandle,
        private val reporter: PlaybackReporter,
        private val status: CastStatusHolder,
        @DetachedPlayerScope detachedScope: CoroutineScope,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
    ) : CastPlaybackCoordinator {
        internal val connection: StateFlow<CastConnection> = status.connection

        /** `true` while a receiver is playing, or about to — what a resolve asks before negotiating. */
        internal val isCasting: Boolean get() = status.isCasting

        private var host: CastPlaybackHost? = null

        /** Non-`null` only while nobody is attached: then it is the only record the reports have. */
        private var detachedSource: PlaybackMediaSource? = null

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

        /** Silences this class's own reporting: from here the host's ticker owns the reports. */
        override fun attachHost(host: CastPlaybackHost) {
            this.host = host
            detachedSource = null
            stopTicker()
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
            status.setConnection(CastConnection.Connected(deviceName, receiver))
            routing.setActive(PlaybackTarget.Cast)
            routing.stopInactive()
            host?.onCastStarted(deviceName, handover)
        }

        /**
         * The final snapshot must be taken **before** the routing handle goes back to local: only
         * the cast player knows where the film got to, an idle ExoPlayer would answer zero.
         *
         * [PlaybackReporter.reportStopDetached] carries the encoder kill with it, which is what
         * stops a cast transcode outliving its session. With a screen attached that report is the
         * screen's instead, from the snapshot handed to it.
         */
        private fun onCastEnded() {
            Timber.i("Cast session ended")
            val last = routing.snapshot()
            status.setConnection(CastConnection.None)
            stopTicker()
            cancelItemLost()

            val orphaned = detachedSource
            if (host == null && orphaned != null) {
                reporter.reportStopDetached(orphaned, last)
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
            stopTicker()
            detachedSource = null
            reporter.reportStopDetached(orphaned, lastHeld)
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
                    snapshot = { routing.snapshot() },
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
