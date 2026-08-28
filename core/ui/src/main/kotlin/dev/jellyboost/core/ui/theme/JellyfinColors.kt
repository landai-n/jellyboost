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
 * The light sibling of [JellyfinColors]. Not an inversion of it — a light page is built from a
 * near-white ground with a *white* card on top, where the dark scheme lifts each layer by getting
 * brighter, and the brand hues are darkened rather than reused because contrast runs the other way:
 * the same colour that clears 4.5:1 on `#101010` fails it on `#F6F7F8`.
 */
internal object JellyfinLightColors {
    val Background = Color(0xFFF6F7F8)

    /** A card is *whiter* than the page, the mirror of the dark scheme's card being lighter than it. */
    val Surface = Color(0xFFFFFFFF)

    val SurfaceVariant = Color(0xFFECEEF0)

    /**
     * The brand blue's own hue and saturation, taken down until it clears WCAG 1.4.3 the way
     * `#00A4DC` does on the dark page (6.65:1). `#00A4DC` itself measures 2.67:1 on `#F6F7F8`; the
     * eyeballed neighbour `#007CA8` is 4.40:1, still under the 4.5:1 body text owes. `#00769E` is
     * 4.79:1 on the page and 5.14:1 on a white card, and white on it is 5.14:1 — see [OnPrimary].
     */
    val Primary = Color(0xFF00769E)

    /** `#AA5CC3`'s hue and saturation, taken down the same way: 8.37:1 on the page (`#AA5CC3` is 3.89:1). */
    val Secondary = Color(0xFF6B2F7F)

    /** White, not black: on [Primary] white is 5.14:1 where black is 4.09:1. */
    val OnPrimary = Color(0xFFFFFFFF)

    val OnBackground = Color(0xFF101010)
    val OnSurface = Color(0xFF101010)

    /** Black@72%, the mirror of the dark scheme's white@70%: 8.98:1 on the page, 9.29:1 on a card. */
    val OnSurfaceVariant = Color(0xB8000000)

    /** M3's own light error. 6.09:1 on the page, and white on it is 6.54:1. */
    val Error = Color(0xFFB3261E)

    val OnError = Color(0xFFFFFFFF)

    /**
     * [JellyfinColors.Outline]'s role, re-derived: 3:1 of a *meaningful* boundary (WCAG 1.4.11)
     * against a light ground rather than a dark one. `#6E6E6E` would be 4.85:1 on the page but a
     * harder line than the dark scheme draws; `#858585` is 3.44:1 on the page and 3.69:1 on a white
     * card, which is where `#6E6E6E`'s 3.73:1 / 3.20:1 sits. Decorative seams are
     * `GlassDefaults.Hairline`, not this.
     */
    val Outline = Color(0xFF858585)
}
