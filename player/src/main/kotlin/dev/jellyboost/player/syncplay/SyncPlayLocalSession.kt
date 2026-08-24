package dev.jellyboost.player.syncplay

import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.resolve.PlaybackInfoResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playing from disk normally tells the server nothing, but a group member the dashboard cannot see is one
 * nobody can tell has stalled — so `PlaybackReporter` reports a local file while in a group, keyed on the play
 * session id minted here.
 *
 * Deliberately a *reconciliation*, not events: both an item change and a membership change reach [reconcile],
 * so "join a group ten minutes into a downloaded film" needs no path of its own.
 *
 * @see PlaybackInfoResolver.mintPlaySessionId for what a mint costs and why it cannot start an encoder.
 */
@Singleton
internal class SyncPlayLocalSession
    @Inject
    constructor(
        private val resolver: PlaybackInfoResolver,
        private val statusHolder: SyncPlayStatusHolder,
        private val reporter: PlaybackReporter,
    ) {
        /** Guards the mint: two reconciliations racing would open two sessions for one file. */
        private val mutex = Mutex()

        private var reported: ReportedSession? = null

        /**
         * Must stay idempotent and cheap when nothing has changed: it runs on every session open and every
         * membership change.
         *
         * @param snapshot used only for the closing stop report.
         */
        suspend fun reconcile(
            source: PlaybackMediaSource?,
            snapshot: PlaybackSnapshot,
        ) = mutex.withLock {
            val local = source as? LocalPlaybackMediaSource
            val active = reported

            when {
                local != null && statusHolder.inGroup.value -> {
                    if (active?.isFor(local) != true) mint(local)
                }

                active != null -> {
                    reported = null
                    statusHolder.setMintedPlaySessionId(null)
                    // Only when the file is still the one playing: on an item change the ordinary stop report
                    // already closed that session, and a second would report the wrong item and position.
                    if (local != null && active.isFor(local)) {
                        Timber.i("Left the group mid-item; closing the server session for %s", local.itemId)
                        reporter.reportGroupExitStop(local, snapshot, active.playSessionId)
                    }
                }
            }
        }

        /**
         * Deliberately does *not* clear [SyncPlayStatusHolder.mintedPlaySessionId]: the closing stop report runs
         * on the reporter's detached scope and reads the id after this returns. Forgetting here is what stops a
         * re-opened session re-using an id the server was already told stopped.
         */
        fun onSessionClosed() {
            reported = null
        }

        private suspend fun mint(local: LocalPlaybackMediaSource) {
            val playSessionId = resolver.mintPlaySessionId(local.itemId, local.mediaSourceId)
            statusHolder.setMintedPlaySessionId(playSessionId)
            reported = ReportedSession(local.itemId, local.mediaSourceId, playSessionId)
            Timber.i("Reporting %s to the server as a group member (session %s)", local.itemId, playSessionId)
        }

        private data class ReportedSession(
            val itemId: UUID,
            val mediaSourceId: String,
            val playSessionId: String?,
        ) {
            fun isFor(source: LocalPlaybackMediaSource): Boolean =
                itemId == source.itemId && mediaSourceId == source.mediaSourceId
        }
    }
