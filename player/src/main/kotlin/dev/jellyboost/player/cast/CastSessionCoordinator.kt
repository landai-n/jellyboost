package dev.jellyboost.player.cast

import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.player.deviceprofile.CastReceiverClass
import dev.jellyboost.player.di.DetachedPlayerScope
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.session.PlaybackTarget
import dev.jellyboost.player.session.RoutingPlayerHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
            }

        fun start() {
            monitor.start(sessionListener)
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
    }
