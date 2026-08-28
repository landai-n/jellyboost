package dev.jellyboost.core.ui.theme

import androidx.compose.ui.graphics.Color

/** Lifted from jellyfin-web's dark theme. */
internal object JellyfinColors {
    val Background = Color(0xFF101010)
    val Surface = Color(0xFF202020)
    val SurfaceVariant = Color(0xFF292929)
    val Primary = Color(0xFF00A4DC)
    val Secondary = Color(0xFFAA5CC3)
    val OnPrimary = Color(0xFF000000)
    val OnBackground = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFFFFFFFF)
    val OnSurfaceVariant = Color(0xB3FFFFFF)
    val Error = Color(0xFFCF6679)
    val OnError = Color(0xFF000000)

    /**
     * M3 reserves `outline` for *meaningful* boundaries, so WCAG 1.4.11 asks 3:1 of it. Web's
     * `#3C3C3C` measured 1.72:1 on the background and 1.48:1 on surface; `#6E6E6E` is 3.73:1 and
     * 3.20:1. Decorative seams are `GlassDefaults.Hairline`, not this.
     */
    val Outline = Color(0xFF6E6E6E)
}

/**
 * The light sibling of [JellyfinColors], from the saved M14 design canvas ("light-theme
 * foundations", `design/foundations/colors-light.html`). Not an inversion of [JellyfinColors] — a
 * light page is built from a cool blue-tinted ground with a *white* card on top, where the dark
 * scheme lifts each layer by getting brighter.
 *
 * Every value here is the canvas's, except where the canvas's own choice misses a ratio
 * `ContrastRatioTest` pins; those three keep the canvas hue and move only lightness or alpha, by
 * the minimum the measurement asks for. Each carries its arithmetic below, as the dark palette does.
 */
internal object JellyfinLightColors {
    /** The canvas's cool ground: blue-tinted, not the neutral `#F6F7F8` the branch first shipped. */
    val Background = Color(0xFFEEF1F7)

    /** A card is *whiter* than the page, the mirror of the dark scheme's card being lighter than it. */
    val Surface = Color(0xFFFFFFFF)

    val SurfaceVariant = Color(0xFFE3E8F2)

    /**
     * The canvas's `#0089B8`, darkened along its own hue until it clears WCAG 1.4.3 the way
     * `#00A4DC` does on the dark page (6.65:1). `#0089B8` is 3.52:1 on the page and white on it is
     * 3.98:1, both under the 4.5:1 body text and a filled pill owe; `#00A4DC` is 2.53:1. `#00769E`
     * is the lightest point on that hue's ramp that clears both — 4.54:1 on the page, 5.14:1 on a
     * white card, and white on it is 5.14:1 (see [OnPrimary]).
     */
    val Primary = Color(0xFF00769E)

    /** The canvas's purple, adopted as drawn: 4.53:1 on the page, 5.13:1 on a card (`#AA5CC3` is 3.69:1). */
    val Secondary = Color(0xFF9A4DB4)

    /** White, not black: on [Primary] white is 5.14:1 where black is 4.09:1. */
    val OnPrimary = Color(0xFFFFFFFF)

    /** The canvas's blue-black ink: 15.20:1 on the page, 17.20:1 on a card. */
    val OnBackground = Color(0xFF191B22)
    val OnSurface = Color(0xFF191B22)

    /**
     * The canvas's `#1F2330` at a raised alpha. Its own 60% is 4.05:1 on the page and only 3.06:1
     * on the light scheme's worst text ground — `GlassDefaults.LightChromeFill` over the darkest
     * frame, rgb(171,174,178). 78% is the first step that clears 4.5:1 there (4.53:1); on the page
     * it is 7.09:1, 7.64:1 on a card and 6.73:1 on [SurfaceVariant].
     */
    val OnSurfaceVariant = Color(0xC71F2330)

    /** The canvas's error, which is M3's own light error. 5.78:1 on the page, and white on it is 6.54:1. */
    val Error = Color(0xFFB3261E)

    val OnError = Color(0xFFFFFFFF)

    /**
     * [JellyfinColors.Outline]'s role is a *meaningful* boundary, so WCAG 1.4.11 asks 3:1 of it —
     * the same obligation the dark `#6E6E6E` was derived for. The canvas's `#D4DAE6` is a 1.24:1
     * seam on the page and 1.40:1 on a card, which cannot carry that role, so its hue (220°) and
     * saturation (26.5%) are kept and only its lightness moves, 86.7% → 58%. `#788AB0` is 3.06:1 on
     * the page and 3.46:1 on a card, where `#6E6E6E`'s 3.73:1 / 3.20:1 sits. Decorative seams are
     * `GlassDefaults.Hairline`, not this.
     */
    val Outline = Color(0xFF788AB0)
}
