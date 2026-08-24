package dev.jellyboost.player.upnext

import dev.jellyboost.player.segments.MediaSegment

/**
 * Decides when the up-next card belongs on screen.
 *
 * Modelled on `SegmentSkipController`, and for the same reason: the whole judgement is a pure
 * function of the position plus one remembered fact, and keeping it out of the ViewModel is what
 * makes it testable without a player. The one remembered fact is the dismissal —
 *
 * > **a dismissal is sticky for the session.** A user who closes the card has said they do not want
 * > the next episode offered for *this* one; re-showing it half a second later, because the position
 * > is still inside the window, would be a card that cannot be closed. Seeking back out of the
 * > window and returning is not a dismissal, so that path shows it again.
 *
 * Stateful and one per playback session; [reset] starts a new one. Constructed by `PlayerViewModel`
 * rather than injected, exactly as `SegmentSkipController` is: "one per playback session" is that
 * class's own lifetime, and a Hilt scope would only be a longer way to say so.
 */
internal class UpNextController {
    /**
     * `true` once the user has closed the card for this session.
     *
     * Readable because the natural end asks it too: closing the card is the one gesture that
     * declines the next episode, so it must also decline the automatic advance at the end —
     * a user who chose to watch the credits being yanked out of them by the arithmetic would
     * make the dismissal a lie.
     */
    var dismissed: Boolean = false
        private set

    /** Forgets the dismissal — called when a new item, or a new source, is opened. */
    fun reset() {
        dismissed = false
    }

    /** The user closed the card; nothing shows it again until [reset]. */
    fun dismiss() {
        dismissed = true
    }

    /**
     * Whether the card should be on screen at [positionMs].
     *
     * Re-decided from scratch on every tick, which is what makes seeking work in both directions:
     * jumping back out of the window hides the card, and returning shows it again.
     *
     * @param outro the item's outro range, when the segments API knew of one. Its start is the
     *   *right* moment — it is where the episode is actually over — and it is why this is preferred
     *   to any arithmetic on the runtime.
     * @param hasNext whether there is a successor to offer at all; `false` is a card that would have
     *   nothing to play.
     */
    fun shouldShow(
        positionMs: Long,
        durationMs: Long,
        outro: MediaSegment?,
        hasNext: Boolean,
    ): Boolean {
        if (!hasNext || dismissed) return false
        val start = windowStartMs(durationMs, outro) ?: return false
        return positionMs >= start
    }

    /**
     * The first millisecond the card may appear at, or `null` when this item has no window at all.
     *
     * The outro's start when there is one. Otherwise the last [UP_NEXT_FALLBACK_MS] of the item —
     * but only for something long enough that the tail is a *tail*: on a three-minute extra, sixty
     * seconds is a third of the item, and a card offering the next thing for that long is covering
     * content rather than following it. Below the floor there is no window, and no card.
     */
    private fun windowStartMs(
        durationMs: Long,
        outro: MediaSegment?,
    ): Long? {
        if (outro != null) return outro.startMs
        if (durationMs <= UP_NEXT_MIN_DURATION_MS) return null
        return durationMs - UP_NEXT_FALLBACK_MS
    }

    private companion object {
        /**
         * How long before the end the card appears when the item has no outro segment.
         *
         * Sixty seconds rather than thirty: on a library with no segment data at all every episode
         * takes this fallback, and a streaming drama's credits run one to two minutes, so thirty
         * seconds puts the card deep inside them. Sixty still trails a short credits roll rather than covering
         * content, and the card is dismissible either way. The *right* trigger stays the outro
         * segment above; this number only carries the library the server has not analysed.
         */
        const val UP_NEXT_FALLBACK_MS = 60_000L

        /**
         * Shorter than this and the fallback window is not offered at all.
         *
         * Two minutes is the floor at which "the last sixty seconds" is still a minority of the
         * item. An item *with* an outro is exempt: the server has said where the ending is, and a
         * short item with a real outro is a short item that really does end there.
         */
        const val UP_NEXT_MIN_DURATION_MS = 120_000L
    }
}
