package dev.jellyboost.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The handful of type styles the 2026 refresh needs that have no Material 3 role.
 *
 * These are deliberately **additions**, not overrides of `MaterialTheme.typography`: every existing
 * screen is written against the stock M3 roles, and re-tuning `titleMedium` or `labelSmall` to hit
 * one mock would silently restyle dozens of call sites that were never part of this pass. A
 * separate object keeps the refresh's type opt-in and greppable — `JellyfinTypeExtras.Eyebrow`
 * says which design the style came from, `MaterialTheme.typography.labelSmall` does not.
 *
 * Tracking is expressed in `em` rather than `sp` so it scales with the font size the way the CSS
 * the mocks were authored in does.
 */
object JellyfinTypeExtras {
    /** Small tracked-out label above a section — callers uppercase the text themselves. */
    val Eyebrow: TextStyle =
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 0.14.em,
        )

    /** Heading of a content row or a settings group. */
    val SectionTitle: TextStyle =
        TextStyle(
            fontSize = 17.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = (-0.01).em,
            lineHeight = 22.sp,
        )

    /** The "See all" affordance at the end of a section heading. */
    val SeeAll: TextStyle =
        TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.W500,
        )

    /** Text inside a mini outlined metadata badge — rating, resolution, codec. */
    val MPill: TextStyle =
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 0.04.em,
        )

    /** Hero title on a compact (phone-width) layout. */
    val HeroTitleCompact: TextStyle =
        TextStyle(
            fontSize = 34.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
            lineHeight = 38.sp,
        )

    /** Hero title once there is room for it — tablets, and landscape. */
    val HeroTitleExpanded: TextStyle =
        TextStyle(
            fontSize = 44.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
            lineHeight = 48.sp,
        )

    /** Title of a top-level screen. */
    val ScreenTitle: TextStyle =
        TextStyle(
            fontSize = 28.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
        )

    /** [ScreenTitle] on wide layouts, where the same size reads a step too small. */
    val ScreenTitleLarge: TextStyle =
        TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
        )

    /** The "Jellyboost" wordmark on the auth screens and in the wide nav bar. */
    val Wordmark: TextStyle =
        TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
        )
}
