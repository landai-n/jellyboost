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
 * about it.
 *
 * Shaped like `SyncPlayController`, because the problem is the same one. A cast session is not the
 * player screen's — it starts from the top bar, survives being backgrounded, and outlives the
 * screen that opened the film — so a `@Singleton` with its own scope holds it, and screens
 * [attach][attachHost] and [detach][detachHost] themselves from it. [start] is called from
 * `JellyboostApplication.onCreate`, alongside the other collaborators whose whole value is that
 * they run when no screen does.
 *
 * ### The reporting invariant
 * **This coordinator reports only while no host is attached.** With a screen attached, that screen
 * owns the progress ticker and the stop report exactly as it does for local playback, and a second
 * party sending them would double every stop — and race the encoder kill. When the screen goes away
 * and the receiver plays on, this takes over: [PlaybackReporter.startReporting] on the detached
 * scope, reading the position off the cast player through [RoutingPlayerHandle], and one final stop
 * (which also kills the transcode) when the session ends.
 *
 * ### Transfers
 * Moving what is *already* playing from the phone to the television, and back again on disconnect,
 * belongs to the screen that holds the source — it is a stop report and a re-negotiation, and this
 * class has neither the item nor the resolver. What it owes the screen is the one thing only it can
 * see: where the outgoing player was at the instant playback was routed away from it
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
        /** The receiver this device is connected to, if any. */
        internal val connection: StateFlow<CastConnection> = status.connection

        /** `true` while a receiver is playing, or about to — what a resolve asks before negotiating. */
        internal val isCasting: Boolean get() = status.isCasting

        private var host: CastPlaybackHost? = null

        /**
         * What the receiver is playing, remembered at the moment the screen let go of it.
         *
         * Only non-`null` while nobody is attached, which is exactly when it is the only record of
         * what the reports below are about.
         */
        private var detachedSource: PlaybackMediaSource? = null

        private var tickerJob: Job? = null

        /**
         * The progress ticker's scope: the detached job, driven on the main thread.
         *
         * The job has to be the detached one — the whole point is a session that survives the screen
         * — and the dispatcher has to be the main one, because every tick reads
         * [RoutingPlayerHandle.snapshot], and `PlayerHandle` snapshots are main-thread-only. Built
         * over the detached scope's context rather than a fresh `Job` so the ticker stays a *child*
         * of it: cancellable on its own, and never orphaned.
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

        /** Begins watching for cast sessions. Idempotent; called once, from the application. */
        fun start() {
            monitor.start(sessionListener)
        }

        /**
         * Hands the coordinator the screen that is driving cast playback.
         *
         * Idempotent by construction, and it silences this class's own reporting: from here the
         * host's ticker is the one telling the server where the film is.
         */
        override fun attachHost(host: CastPlaybackHost) {
            this.host = host
            detachedSource = null
            stopTicker()
        }

        /**
         * Gives the screen back while the receiver plays on.
         *
         * The source is taken across on the way out rather than read later: the host is about to
         * stop existing, and without it there would be nothing to key a report on — the position
         * still comes from the cast player itself, tick by tick.
         *
         * Taken **only while a session is live**: every screen detaches through here whether it was
         * casting or not (`releaseSession` cannot know), and a source remembered from an ordinary
         * local session would later be "orphaned" by a failed cast attempt — [onCastEnded] would
         * then report a stop, at position zero, for a film that was never cast, wiping its resume
         * position.
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
         * A receiver appeared.
         *
         * The order is the whole of it. The snapshot comes **first**, off the player that is still
         * playing, because it is what the transfer resumes at and where the outgoing session's stop
         * report belongs; a moment later the only readable player is a cast one at zero. The routing
         * flip comes before [RoutingPlayerHandle.stopInactive] so that the local player's own
         * shutdown events land on a subscription nothing is listening to any more — stopping it
         * first would let its `IsPlayingChanged(false)` reach the screen as if the session it is
         * about to open had failed. Stopping it at all is deliberate: two players must not sound at
         * once, and the local media notification has no business surviving a film that has moved to
         * a television.
         *
         * A start for a session that is **already** connected is dropped. The framework delivers one
         * on `onSessionResumed` after a Wi-Fi blip, and the monitor's own start-time replay can add
         * another; re-running the transfer for either would stop and re-negotiate a stream the
         * receiver is happily playing — off a cast player that may still answer position zero.
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
            // The model→class line is load-bearing diagnostics: a 4K receiver that logs as
            // LEGACY_1080P here is a one-line allowlist fix in CastReceiverClass, not a bug hunt.
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
         * The receiver went away.
         *
         * The final snapshot is taken **before** the routing handle goes back to local, because it
         * is the cast player that knows where the film got to; afterwards the question would be
         * answered by an ExoPlayer that has not played anything.
         *
         * [PlaybackReporter.reportStopDetached] carries the encoder kill with it — `reportStop`
         * calls `stopTranscoding` — which is what stops a cast transcode from outliving the session
         * that started it.
         *
         * With a screen attached none of that happens here and the snapshot goes to it instead: the
         * screen owes the same stop report, at the same position, and it has somewhere to put the
         * film afterwards — back on this device, paused where the television left it.
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
            // The receiver is gone, but the cast player is not: left alone it keeps its listener,
            // its media items and the `loaded` spec a later subtitle selection would match
            // against. Stopping the now-inactive side clears all three; the session itself
            // is already over, so there is nothing this could interrupt.
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
