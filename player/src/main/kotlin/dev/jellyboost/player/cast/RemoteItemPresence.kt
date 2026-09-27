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
 *
 * **A finish is not a drop.** media3-cast 1.9.0 never reports `STATE_ENDED` (`RemoteCastPlayer` maps the
 * receiver's states to IDLE, BUFFERING and READY only), so a film played to its end looked exactly like
 * one stopped from the television: the item disappears. The receiver's own status still tells them apart
 * (`IDLE` with idle reason `FINISHED`), and the handle passes it to [onFinished]: the held item then
 * *ended* — [PlayerEvent.Ended] once, and an ended reading from then on, which is valid and so never
 * missing — until the next [onLoad].
 */
internal class RemoteItemPresence(
    private val emit: (PlayerEvent) -> Unit,
) {
    private var armed = false

    /** The last reading taken while the receiver held the item; what a dropped item resumes from. */
    private var lastHeld: PlaybackSnapshot? = null

    private var missing = false

    /**
     * The held item's ended reading once the receiver has finished it ([onFinished]), and what every
     * reading is from then until the next [onLoad]: the receiver goes on answering nothing of ours.
     */
    var ended: PlaybackSnapshot? = null
        private set

    /** A new item was loaded, or the old one was stopped: nothing is known about the next one yet. */
    fun onLoad() {
        armed = false
        lastHeld = null
        ended = null
        setMissing(false)
    }

    /**
     * The receiver reports it finished its media (`IDLE` / `FINISHED`); [finished] says which media that
     * is. It is the held item's end only when the item was **armed** since the last [onLoad] — a status
     * left over from the film before (a receiver keeps saying `FINISHED` until the next load is under
     * way) can never be — and either the status names the loaded item, or it names nothing and the item
     * was still held at the last reading.
     *
     * @return the ended reading when it is the held item's end (at its duration, where the server marks it
     *   played), `null` otherwise. [PlayerEvent.Ended] is emitted the first time only.
     */
    fun onFinished(finished: ReceiverFinish): PlaybackSnapshot? {
        val held = lastHeld?.takeIf { armed && ended == null && namesHeldItem(finished) } ?: return ended
        val reading =
            held.copy(
                positionMs = held.durationMs.takeIf { it > 0L } ?: held.positionMs,
                isPlaying = false,
                hasEnded = true,
                isValid = true,
            )
        ended = reading
        Timber.i("The receiver finished the item it was playing")
        // Held again, then ended: a drop noticed a moment before the finish status was only the finish.
        onReading(reading, ready = true)
        emit(PlayerEvent.Ended)
        return reading
    }

    private fun namesHeldItem(finished: ReceiverFinish): Boolean =
        when (finished) {
            ReceiverFinish.LOADED_ITEM -> true
            ReceiverFinish.UNNAMED -> !missing
            ReceiverFinish.OTHER_ITEM -> false
        }

    /**
     * @param reading the handle's own snapshot: [valid][PlaybackSnapshot.isValid] exactly while the
     *   receiver holds the loaded item (or has finished it).
     * @param ready `true` in `STATE_READY` or once ended — the receiver has the media, not a promise
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

/** Which media a receiver's `IDLE` / `FINISHED` status is about, as [RemoteItemPresence.onFinished] needs it. */
internal enum class ReceiverFinish {
    /** The status names the item this app loaded. */
    LOADED_ITEM,

    /** The status names other media: another sender's, which finishing says nothing about ours. */
    OTHER_ITEM,

    /** The status names no media at all. */
    UNNAMED,
}
