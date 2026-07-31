package dev.jellyboost.core.common.model

/**
 * What the player does when playback enters an intro or an outro (docs/PLAN.md, "M9 Polish" →
 * segment skip; "Playback pipeline" → "Media segments (M9): `getItemSegments(INTRO/OUTRO)` → skip
 * button; per-type pref; server-only").
 *
 * It lives in `:core:common` rather than in `:core:datastore` because both ends need it and they do
 * not see each other: the preference store persists it and `:player` acts on it, while the settings
 * screen will render it. `:core:common` is the one module all three already depend on.
 *
 * The preference is per segment *type* — skipping a 90-second intro every episode is a very
 * different proposition from skipping the outro, which is where the "next episode" information
 * usually is — so there is one of these for [MediaSegmentKind.INTRO] and one for
 * [MediaSegmentKind.OUTRO].
 */
enum class SegmentSkipMode {
    /** Never react to this segment type; nothing is drawn. */
    OFF,

    /** Offer a "Skip intro"/"Skip outro" button while playback is inside the segment. Default. */
    SHOW_BUTTON,

    /**
     * Seek past the segment automatically the first time playback enters it.
     *
     * Deliberately "the first time": a user who seeks back into a segment they were just skipped
     * out of is telling the player they wanted to watch it, and an unconditional rule would fight
     * them in a loop (see `SegmentSkipController`).
     */
    AUTO_SKIP,
}

/**
 * The segment types this client acts on.
 *
 * The server's `MediaSegmentType` also has `COMMERCIAL`, `PREVIEW`, `RECAP` and `UNKNOWN`; the plan
 * scopes M9 to intro and outro, and an enum of exactly what is supported keeps the preference keys
 * and the UI copy from having to answer for types with no behaviour behind them.
 */
enum class MediaSegmentKind {
    INTRO,
    OUTRO,
}
