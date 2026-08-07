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
 * The server-visible session of a downloaded file that is being watched with a group.
 *
 * Playing from disk normally tells the server nothing — there is no session, no play session id and
 * no bytes to account for. In a SyncPlay group that is the wrong answer (key decision 9 of
 * docs/notes/syncplay-m11-plan.md): the group is watching together, and a member the dashboard
 * cannot see is a member nobody can tell has stalled. `PlaybackReporter` therefore reports a local
 * file whenever [SyncPlayStatusHolder.inGroup] is set, and this class owns the two facts that makes
 * possible — the play session id those reports are keyed on, and the point at which the session
 * ends.
 *
 * It is one method because there is one rule, and it is a *reconciliation* rather than a sequence of
 * events: given what is playing and whether there is a group, the server should either know about a
 * session or not. Both things that can change — the item (a group moving through its queue, a track
 * change that goes back to the server, a session closing) and the membership (joining while a
 * download is already playing, leaving mid-film) — reach the same [reconcile] call, which is why
 * "join a group ten minutes into a downloaded film" needs no path of its own.
 *
 * @see PlaybackInfoResolver.mintPlaySessionId for what a mint costs and why it cannot start an
 *   encoder.
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
         * Brings the server's view of this device in line with [source] and the current group.
         *
         * Idempotent, and cheap when nothing has changed — which matters because it is called on
         * every session open as well as on every membership change.
         *
         * @param source what is playing now, or `null` when nothing is.
         * @param snapshot where the player is, used only for the closing stop report.
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
                    // Only when the file is still the one playing: if the item changed, the ordinary
                    // stop report closed its session already (it was still in the group at the time),
                    // and a second one would report the wrong item at the wrong position.
                    if (local != null && active.isFor(local)) {
                        Timber.i("Left the group mid-item; closing the server session for %s", local.itemId)
                        reporter.reportGroupExitStop(local, snapshot, active.playSessionId)
                    }
                }
            }
        }

        /**
         * Forgets the session the player screen was reporting.
         *
         * Deliberately *not* clearing [SyncPlayStatusHolder.mintedPlaySessionId]: the stop report
         * that closes this session is launched on the reporter's detached scope as the screen goes
         * away, and it reads the id after this returns. The controller clears the holder when the
         * group ends, and the next in-group local item overwrites it with its own mint — which is
         * exactly what forgetting here guarantees, since a re-opened session must never re-use a
         * play session id the server has already been told stopped.
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

        /** What the server has been told about, so a reconciliation can tell "same" from "new". */
        private data class ReportedSession(
            val itemId: UUID,
            val mediaSourceId: String,
            val playSessionId: String?,
        ) {
            fun isFor(source: LocalPlaybackMediaSource): Boolean =
                itemId == source.itemId && mediaSourceId == source.mediaSourceId
        }
    }
