package dev.jellyboost.player.cast

import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.PlayerEvent
import timber.log.Timber

/**
 * Notices a receiver letting go of the item this app loaded on it, for [CastPlayerHandle]; kept free
 * of every Cast type so the rules below are unit-testable.
 *
 * **Armed only once the receiver has held the item while ready** (or ended) since the last [onLoad].
 * Right after a load the cast player's reading is invalid for several seconds — the receiver has not
 * reported the new item yet, and a placeholder can briefly claim it before that — so "held at some
 * point" is not enough; "held and ready to play" is a receiver that really had it. A load that never
 * gets that far is not reported here: the receiver answers a failed load with an error.
 *
 * Only the edges are emitted — [PlayerEvent.RemoteItemMissing] once when a held item disappears,
 * [PlayerEvent.RemoteItemMissingCleared] once when it comes back or a new load starts. Deciding that
 * a missing item is *gone* is the coordinator's job, after a grace period.
 */
internal class RemoteItemPresence(
    private val emit: (PlayerEvent) -> Unit,
) {
    private var armed = false

    /** The last reading taken while the receiver held the item; what a dropped item resumes from. */
    private var lastHeld: PlaybackSnapshot? = null

    private var missing = false

    /** A new item was loaded, or the old one was stopped: nothing is known about the next one yet. */
    fun onLoad() {
        armed = false
        lastHeld = null
        setMissing(false)
    }

    /**
     * @param reading the handle's own snapshot: [valid][PlaybackSnapshot.isValid] exactly while the
     *   receiver holds the loaded item (or has finished it).
     * @param ready `true` in `STATE_READY` or `STATE_ENDED` — the receiver has the media, not a promise
     *   of it.
     */
    fun onReading(
        reading: PlaybackSnapshot,
        ready: Boolean,
    ) {
        if (reading.isValid) {
            lastHeld = reading
            if (ready) armed = true
            setMissing(false)
        } else if (armed) {
            setMissing(true)
        }
    }

    private fun setMissing(now: Boolean) {
        if (now == missing) return
        if (now) {
            val held = lastHeld ?: return
            missing = true
            Timber.i("The receiver no longer holds the item it was playing at %d ms", held.positionMs)
            emit(PlayerEvent.RemoteItemMissing(held))
        } else {
            missing = false
            emit(PlayerEvent.RemoteItemMissingCleared)
        }
    }
}
