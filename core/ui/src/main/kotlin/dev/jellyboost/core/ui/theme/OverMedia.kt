package dev.jellyboost.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The ink and the glass of anything drawn *on* a picture — a hero backdrop, a detail backdrop, the
 * film frame. None of it branches on the scheme: image territory is dark-scrimmed in both, so what
 * is drawn on it is read against the picture and never against the page.
 *
 * That is the saved canvas's `screens/home-light.html` doctrine, and it is why these are literals
 * rather than [pageInk] or a `colorScheme` role. `PlayerControls`'s own over-media tokens are the
 * same rule applied to video; this object is the rule applied to artwork.
 *
 * **Every ratio quoted here is measured on one ground** — the scrim's plateau over a fully white
 * backdrop, the worst case a scrim can leave — and every one of them is pinned by
 * `ContrastRatioTest`. The plateau is what [Modifier.backdropScrim] and [Modifier.wideHeroWash] both
 * hold everywhere a lockup is drawn, which is what makes one ground the honest answer rather than a
 * flattering altitude. The dark scheme's `#101010` is a shade lighter than [ScrimInk], so the
 * numbers below are the dark side's — the binding case in both axes.
 */
object OverMedia {
    /**
     * The scrim's own ink on the light side, used by [Modifier.backdropScrim] and
     * [Modifier.wideHeroWash]. Slightly blued rather than neutral so the ramp reads as depth behind
     * the picture instead of a grey filter over it.
     */
    val ScrimInk: Color = Color(0xFF0C0E14)

    /** 9.67:1 on the plateau. */
    val Title: Color = Color.White

    /** 6.65:1 on the plateau. */
    val Eyebrow: Color = Color.White.copy(alpha = 0.78f)

    /** 6.19:1 on the plateau — the weakest text the doctrine draws, and what sized the plateau. */
    val Meta: Color = Color.White.copy(alpha = 0.74f)

    /**
     * The certificate pill's edge, and only that: 2.46:1 on the plateau, deliberately below WCAG
     * 1.4.11's 3:1 because the certificate is read from its label, which clears 4.5:1 on the same
     * ground, and the box only holds it apart from the times beside it. A chip or a pill takes
     * [GlassBorder] instead — those are controls, and an edge that says where a control is owes 3:1.
     */
    val BadgeBorder: Color = Color.White.copy(alpha = 0.32f)

    /**
     * Glass drawn *inside* a copy lockup, so it always lands on artwork the scrim has already taken
     * down: 13.81:1 for [GlassContent] on the plateau. It is **not** strong enough for chrome — over
     * a raw white frame the same fill leaves white at 2.85:1, which is what [ChromeFill] is for.
     */
    val GlassFill: Color = Color(0xFF0A0C12).copy(alpha = 0.42f)

    /**
     * The canvas asks white@22% here; that measures 2.03:1 on [GlassFill], and the edge of a ghost
     * pill or a chip is its whole boundary, so this keeps [GlassDefaults.DarkGhostBorder]'s 40% —
     * 3.54:1 — for the same reason the light palette moved three of the canvas's own hexes.
     */
    val GlassBorder: Color = GlassDefaults.DarkGhostBorder

    val GlassContent: Color = Color.White

    /**
     * Chrome floats over *unscrimmed* artwork (the hero's top corner, a backdrop's first rows), so it
     * takes the dark scheme's chrome tint whatever the page is doing: 7.70:1 for [GlassContent] over
     * a white frame, the ratio `ContrastRatioTest` already pins for the dark chrome.
     */
    val ChromeFill: Color = GlassDefaults.DarkChromeFill

    val ChromeBorder: Color = GlassDefaults.DarkHairline

    /**
     * The brand accent as the dark scheme states it — the light one is darkened for a light page.
     * 3.38:1 on the scrim's plateau, which is the only ground it is drawn on (the hero's eyebrow dot
     * and the detail lockup's rating star, both graphics owing WCAG 1.4.11's 3:1).
     */
    val Accent: Color = JellyfinColors.Primary

    /**
     * [Accent]'s reasoning for the one glyph that is red rather than blue — but **not** the dark
     * scheme's own `#CF6679`, which measures 2.15:1 on [ChromeFill] over a white frame and would
     * have been a regression on the light chrome it replaced. This is the tint the error banner's
     * dark side already uses, at 3.89:1 on the same ground.
     */
    val ErrorAccent: Color = Color(0xFFF0A3AE)

    /** The brand pill keeps the dark scheme's inversion over media: white fill, `#101010` ink, 19.03:1. */
    val PillFill: Color = JellyfinColors.OnBackground

    val PillInk: Color = JellyfinColors.Background

    /**
     * Whether a hero's artwork dissolves into the page below it rather than ending on a hard edge.
     * Only the dark [Modifier.backdropScrim] reaches the page colour, so only there may a row
     * overlap the hero's foot or fade into it — under the light ramp the page starts at the
     * artwork's bottom edge and a fade would be a light band painted over the picture.
     */
    val artworkDissolvesIntoPage: Boolean
        @Composable @ReadOnlyComposable
        get() = !LocalIsLightTheme.current
}
