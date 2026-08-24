package dev.jellyboost.player.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arbitrates the one `ExoPlayer` and media session between the video screen and the music queue.
 *
 * **The invariant: exactly one stop report per session, issued by the outgoing owner, completed
 * before [claim] returns.** All of [claim] runs under one lock, so concurrent claims serialise.
 *
 * A relinquish callback may not call back into this class — the lock is held, so [claim] or
 * [release] from inside one deadlocks — and may not assume a thread: it runs inline in whichever
 * context the displacing [claim] came from, so it owns its own marshalling (Media3 on main, stop
 * reports off it).
 *
 * Claiming for the kind that already owns the player is not a handover: it only replaces the
 * callback, since that owner is closing and reopening a session it reports for itself.
 */
@Singleton
class PlaybackHandover
    @Inject
    constructor() {
        private val mutex = Mutex()

        private var owner: PlaybackKind? = null
        private var relinquish: (suspend () -> Unit)? = null

        val currentOwner: PlaybackKind? get() = owner

        /**
         * Returns only after the previous owner has finished letting go.
         *
         * @param relinquish run when *this* claim is later displaced; called at most once, and
         *   cleared before it is called.
         */
        suspend fun claim(
            kind: PlaybackKind,
            relinquish: suspend () -> Unit,
        ) {
            mutex.withLock {
                val previousOwner = owner
                val previous = if (previousOwner != null && previousOwner != kind) this.relinquish else null
                if (previous != null) {
                    Timber.i("Playback handover: %s is taking the player from %s", kind, previousOwner)
                    // Cleared before it runs: no later claim may reach this callback again.
                    this.relinquish = null
                    this.owner = null
                    previous.invoke()
                }
                owner = kind
                this.relinquish = relinquish
            }
        }

        /** A no-op when [kind] is no longer the owner: its relinquish has already run. */
        suspend fun release(kind: PlaybackKind) {
            mutex.withLock { clear(kind) }
        }

        /**
         * [release] from somewhere that cannot suspend. Skipping the clear when a handover is in
         * flight is safe: that [claim] is already running this owner's relinquish.
         *
         * @return `true` when ownership was cleared (or was never this caller's).
         */
        fun releaseNow(kind: PlaybackKind): Boolean {
            if (!mutex.tryLock()) {
                Timber.d("A handover is in flight; leaving the %s claim to it", kind)
                return false
            }
            try {
                clear(kind)
            } finally {
                mutex.unlock()
            }
            return true
        }

        private fun clear(kind: PlaybackKind) {
            if (owner != kind) return
            owner = null
            relinquish = null
        }
    }

enum class PlaybackKind {
    VIDEO,
    MUSIC,
}
