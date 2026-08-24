package dev.jellyboost.player.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which of the two things that can drive the shared player is driving it, and the orderly way of
 * taking it from the other.
 *
 * The device has one `ExoPlayer` and one media session, and two callers with a legitimate claim on
 * both: the video screen and the music queue. Letting either simply prepare over the other loses
 * the *server's* half of the picture — the outgoing session is never stopped, so the dashboard
 * shows two sessions for one device and a transcode's ffmpeg process is left running.
 *
 * ### The invariant
 * **Exactly one stop report per session, issued by the outgoing owner, completed before
 * [claim] returns.** The claimant therefore never has to know what it is displacing or how that
 * session should be closed — it only has to wait. The whole of [claim] runs under one lock, which
 * is what makes that true for two claims arriving at once: the second waits for the first to
 * finish handing over, so a relinquish runs once and the owner recorded at the end is the one that
 * really has the player.
 *
 * ### What a relinquish may not do
 * It may not call back into this class. It runs while the lock is held, so a [claim] or a
 * [release] from inside one would deadlock. In practice a relinquish is exactly three steps — stop
 * report, snapshot what was playing, let go of the player — and none of them is a handover.
 *
 * ### What a relinquish may not assume
 * **A thread.** The callback is invoked inline in whichever context the displacing [claim] was
 * called from — video claims from the player screen's main-thread scope, music from its own
 * background session dispatcher — and this class deliberately does not hop anywhere: it cannot
 * know what each owner's teardown needs (Media3 calls must be on main; a stop report must not
 * be). Every relinquish therefore owns its own marshalling — the player-touching steps hop to the
 * main dispatcher themselves, and both registered closures (`PlaybackSessionController.open`'s
 * and `MusicPlaybackController.relinquishToOther`) do exactly that.
 *
 * ### Re-claiming
 * Claiming for the kind that already owns the player is *not* a handover: it replaces the
 * relinquish callback and nothing else. A video re-negotiation (a quality change, a fallback
 * retry) and a music `play()` over a queue that is already playing both land here, and neither is
 * a session ending — the owner is closing and reopening its own, which it reports for itself.
 */
@Singleton
class PlaybackHandover
    @Inject
    constructor() {
        private val mutex = Mutex()

        private var owner: PlaybackKind? = null
        private var relinquish: (suspend () -> Unit)? = null

        /** Who holds the player right now, for diagnostics and for tests. */
        val currentOwner: PlaybackKind? get() = owner

        /**
         * Takes the player for [kind], after the previous owner has finished letting go of it.
         *
         * @param relinquish what to run when *this* claim is later displaced. Called at most once,
         *   and cleared before it is called, so a second claimant arriving mid-handover finds
         *   nothing left to run.
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
                    // Cleared before it runs: the callback closes a session that is ending, and it
                    // must not be reachable again from any later claim.
                    this.relinquish = null
                    this.owner = null
                    previous.invoke()
                }
                owner = kind
                this.relinquish = relinquish
            }
        }

        /**
         * Gives the player up voluntarily — the owner's own session ended, so nobody should
         * relinquish it on their behalf later.
         *
         * A no-op when [kind] is not the current owner, which is the common case for a video
         * screen torn down after music already took the player: the relinquish that closed its
         * session has already run.
         */
        suspend fun release(kind: PlaybackKind) {
            mutex.withLock { clear(kind) }
        }

        /**
         * [release] from somewhere that cannot suspend — `PlayerViewModel`'s teardown.
         *
         * Skips the clear rather than blocking when a handover is in flight, and that is correct
         * rather than lossy: the in-flight [claim] is *already* running this owner's relinquish,
         * which closes exactly the session this call was trying to disown.
         *
         * @return `true` when the ownership was cleared (or was not this caller's to begin with).
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

/** The two things that can own the shared player. */
enum class PlaybackKind {
    VIDEO,
    MUSIC,
}
