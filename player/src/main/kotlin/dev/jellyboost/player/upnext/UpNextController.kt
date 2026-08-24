package dev.jellyboost.player.upnext

import dev.jellyboost.player.segments.MediaSegment

/**
 * Decides when the up-next card belongs on screen. One per playback session; [reset] starts a new one.
 *
 * **A dismissal is sticky for the session** — re-showing the card because the position is still
 * inside the window would make it uncloseable. Seeking out of the window and back is not a dismissal.
 */
internal class UpNextController {
    /**
     * Readable because the natural end asks it too: dismissing the card declines the next episode,
     * so it must also decline the automatic advance.
     */
    var dismissed: Boolean = false
        private set

    /** Called when a new item, or a new source, is opened. */
    fun reset() {
        dismissed = false
    }

    fun dismiss() {
        dismissed = true
    }

    /**
     * Re-decided from scratch on every tick, which is what makes seeking work in both directions.
     *
     * @param outro when the segments API knew of one; its start is where the episode is actually
     *   over, so it is always preferred to arithmetic on the runtime.
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

    /** The first millisecond the card may appear at, or `null` when this item has no window. */
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
         * The fallback when there is no outro segment. Sixty rather than thirty seconds because a
         * streaming drama's credits run one to two minutes.
         */
        const val UP_NEXT_FALLBACK_MS = 60_000L

        /**
         * Below this the fallback window is not offered: two minutes is where the last sixty seconds
         * is still a minority of the item. An item *with* an outro is exempt.
         */
        const val UP_NEXT_MIN_DURATION_MS = 120_000L
    }
}
