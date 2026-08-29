package dev.jellyboost.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
 * Every ratio quoted below is measured over the worst case the scrim can leave — a fully white
 * backdrop under the light ramp — and pinned by `ContrastRatioTest`.
 */
object OverMedia {
    /**
     * The scrim's own ink, used by [JellyfinGradients.BackdropScrim] and
     * [JellyfinGradients.WideHeroScrim]. Slightly blued rather than neutral so the ramp reads as
     * depth behind the picture instead of a grey filter over it.
     */
    val ScrimInk: Color = Color(0xFF0C0E14)

    /** 9.89:1 at the scrim's foot. */
    val Title: Color = Color.White

    /** 6.78:1 at the scrim's foot. */
    val Eyebrow: Color = Color.White.copy(alpha = 0.78f)

    /** 6.29:1 at the scrim's foot; 5.20:1 at the wide wash's mid stop. */
    val Meta: Color = Color.White.copy(alpha = 0.74f)

    /**
     * The certificate pill's edge. 2.48:1 at the scrim's foot and deliberately below WCAG 1.4.11's
     * 3:1: the certificate is read from its label, which clears 4.5:1 on the same ground, and the
     * box only holds it apart from the times beside it.
     */
    val BadgeBorder: Color = Color.White.copy(alpha = 0.32f)

    /**
     * Glass drawn *inside* a copy lockup, so it always lands on artwork the scrim has already taken
     * down: 13.97:1 for [GlassContent] at the scrim's foot. It is **not** strong enough for chrome —
     * over a raw white frame the same fill leaves white at 2.85:1, which is what [ChromeFill] is for.
     */
    val GlassFill: Color = Color(0xFF0A0C12).copy(alpha = 0.42f)

    /**
     * The canvas asks white@22% here; that measures 2.04:1 on [GlassFill], and a ghost pill's edge is
     * its whole boundary, so this keeps [GlassDefaults.DarkGhostBorder]'s 40% — 3.56:1 — for the
     * same reason the light palette moved three of the canvas's own hexes.
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

    /** The brand accent as the dark scheme states it — the light one is darkened for a light page. */
    val Accent: Color = JellyfinColors.Primary

    /** [Accent]'s reasoning for the one glyph that is red rather than blue. */
    val ErrorAccent: Color = JellyfinColors.Error

    /** The brand pill keeps the dark scheme's inversion over media: white fill, `#101010` ink, 19.03:1. */
    val PillFill: Color = JellyfinColors.OnBackground

    val PillInk: Color = JellyfinColors.Background

    /**
     * Whether a hero's artwork dissolves into the page below it rather than ending on a hard edge.
     * Only the dark [JellyfinGradients.BackdropScrim] reaches the page colour, so only there may a
     * row overlap the hero's foot or fade into it — under the light ramp the page starts at the
     * artwork's bottom edge and a fade would be a light band painted over the picture.
     */
    val artworkDissolvesIntoPage: Boolean
        @Composable @ReadOnlyComposable
        get() = !LocalIsLightTheme.current
}
