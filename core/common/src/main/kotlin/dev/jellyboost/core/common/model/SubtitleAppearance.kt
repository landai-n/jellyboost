package dev.jellyboost.core.common.model

/**
 * How large Media3 draws a subtitle, as a fraction of the video's height.
 *
 * Fractional rather than absolute, because the same subtitle is read on a 7" tablet held close and on a
 * TV across a room, and only a fraction of the picture means the same thing on both.
 *
 * **Media3's own renderer only.** With *Styled ASS subtitles* on, an ASS/SSA track is drawn by libass in
 * its own view from the sizes its script declares, and neither this nor [SubtitleBackground] touches it.
 * Everything else — SubRip, WebVTT, and ASS/SSA whenever that switch is off — goes through
 * `SubtitleView` and is styled from here.
 */
enum class SubtitleTextSize(
    /**
     * `null` defers to the device's own caption size, which is what `PlayerView` does untouched and
     * therefore what every install had before this preference existed.
     *
     * [NORMAL] is `SubtitleView.DEFAULT_TEXT_SIZE_FRACTION` and is deliberately *not* the default: a user
     * who set a caption size in Android's accessibility settings meant it, and overriding that silently
     * is the accessibility regression this enum exists to avoid.
     */
    val heightFraction: Float?,
) {
    SYSTEM(heightFraction = null),

    SMALL(heightFraction = 0.04f),

    NORMAL(heightFraction = 0.0533f),

    LARGE(heightFraction = 0.07f),

    LARGER(heightFraction = 0.09f),
    ;

    companion object {
        val DEFAULT: SubtitleTextSize = SYSTEM

        /** A name this build does not know — a downgrade, a renamed constant — reads as a fresh install. */
        fun fromNameOrDefault(name: String?): SubtitleTextSize = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * What Media3 draws *behind* a subtitle.
 *
 * @property backgroundAlpha alpha of the black box behind the glyphs, `0f`..`1f`; `null` defers to the
 *   device's caption style, as [SubtitleTextSize.SYSTEM] does.
 * @property outlined whether the glyphs carry an outline. Tied to the background rather than offered
 *   separately because it is only load-bearing when there is no box: white-on-white is unreadable, and
 *   an outline is what keeps [NONE] legible over a bright frame.
 */
enum class SubtitleBackground(
    val backgroundAlpha: Float?,
    val outlined: Boolean,
) {
    SYSTEM(backgroundAlpha = null, outlined = false),

    NONE(backgroundAlpha = 0f, outlined = true),

    TRANSLUCENT(backgroundAlpha = 0.6f, outlined = false),

    SOLID(backgroundAlpha = 1f, outlined = false),
    ;

    companion object {
        val DEFAULT: SubtitleBackground = SYSTEM

        /** As [SubtitleTextSize.fromNameOrDefault]. */
        fun fromNameOrDefault(name: String?): SubtitleBackground = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
