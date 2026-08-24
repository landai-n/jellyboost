package dev.jellyboost.core.common.model

/**
 * What the player does when playback enters an intro or an outro. Segments are entirely server-derived
 * (`getItemSegments`), and the preference is per segment *type*: skipping a 90-second intro every episode is
 * a very different proposition from skipping the outro, which usually carries the next-episode information.
 */
enum class SegmentSkipMode {
    OFF,

    /** Offer a "Skip intro"/"Skip outro" button while playback is inside the segment. Default. */
    SHOW_BUTTON,

    /**
     * Deliberately "the first time": a user who seeks back into a segment they were just skipped out of is
     * telling the player they wanted to watch it, and an unconditional rule would fight them in a loop.
     */
    AUTO_SKIP,
}

/**
 * The server's `MediaSegmentType` also has `COMMERCIAL`, `PREVIEW`, `RECAP` and `UNKNOWN`; an enum of exactly
 * what is supported keeps the preference keys and the UI copy from answering for types with no behaviour.
 */
enum class MediaSegmentKind {
    INTRO,
    OUTRO,
}
