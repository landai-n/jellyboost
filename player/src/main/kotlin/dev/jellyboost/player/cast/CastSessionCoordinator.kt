package dev.jellyboost.player.cast

import dev.jellyboost.player.di.DetachedPlayerScope
import dev.jellyboost.player.di.MainDispatcher
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
 * ### What it does not do yet
 * Flipping the routing handle is all a session start does here. Moving what is *already* playing
 * from the phone to the television, and back again on disconnect, is a transfer — a stop report,
 * then a re-negotiation at the position reached — and it belongs to the screen that holds the
 * source (docs/notes/chromecast-m12-plan.md, Phase 3).
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
    ) {
        /** The receiver this device is connected to, if any. */
        val connection: StateFlow<CastConnection> = status.connection

        /** `true` while a receiver is playing, or about to — what a resolve asks before negotiating. */
        val isCasting: Boolean get() = status.isCasting

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
                override fun onSessionStarted(deviceName: String?) = onCastStarted(deviceName)

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
        internal fun attachHost(host: CastPlaybackHost) {
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
         * @param host ignored unless it is the attached one, so a stale ViewModel's teardown cannot
         *   detach the screen that replaced it.
         */
        internal fun detachHost(host: CastPlaybackHost) {
            if (this.host !== host) return
            this.host = null
            detachedSource = host.castSource
            startTicker()
        }

        private fun onCastStarted(deviceName: String?) {
            Timber.i("Cast session started on %s", deviceName ?: "an unnamed receiver")
            status.setConnection(CastConnection.Connected(deviceName))
            routing.setActive(PlaybackTarget.Cast)
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
         */
        private fun onCastEnded() {
            Timber.i("Cast session ended")
            status.setConnection(CastConnection.None)
            stopTicker()

            val orphaned = detachedSource
            if (host == null && orphaned != null) {
                reporter.reportStopDetached(orphaned, routing.snapshot())
            }
            detachedSource = null
            routing.setActive(PlaybackTarget.Local)
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

/**
 * The screen driving a cast session, as the coordinator sees it.
 *
 * One property, because one thing is genuinely handed over: *what* is playing. Where it has got to
 * is asked of the player, which is still there after the screen is not.
 */
internal interface CastPlaybackHost {
    /** What the receiver is playing, or `null` when the host has nothing open. */
    val castSource: PlaybackMediaSource?
}
