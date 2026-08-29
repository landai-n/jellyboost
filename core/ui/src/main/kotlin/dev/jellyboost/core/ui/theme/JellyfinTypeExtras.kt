package dev.jellyboost.core.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Deliberately **additions**, never overrides of `MaterialTheme.typography`: re-tuning a stock role
 * to hit one mock silently restyles every call site that mock never covered.
 *
 * Tracking is in `em`, not `sp`, so it scales with the font size.
 */
object JellyfinTypeExtras {
    /** Callers uppercase the text themselves. */
    val Eyebrow: TextStyle =
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 0.14.em,
        )

    val SectionTitle: TextStyle =
        TextStyle(
            fontSize = 17.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = (-0.01).em,
            lineHeight = 22.sp,
        )

    val SeeAll: TextStyle =
        TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.W500,
        )

    val MPill: TextStyle =
        TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.W600,
            letterSpacing = 0.04.em,
        )

    val HeroTitleCompact: TextStyle =
        TextStyle(
            fontSize = 34.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
            lineHeight = 38.sp,
        )

    val HeroTitleExpanded: TextStyle =
        TextStyle(
            fontSize = 44.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
            lineHeight = 48.sp,
        )

    val ScreenTitle: TextStyle =
        TextStyle(
            fontSize = 28.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
        )

    /**
     * The title of a pane that sits *beside* a rail rather than under a status bar, so it is a step
     * down from [ScreenTitle]: on a two-pane settings window the rail already carries the screen's
     * own 30sp title, and two 28sp headings side by side read as two screens.
     */
    val PaneTitle: TextStyle =
        TextStyle(
            fontSize = 22.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
        )

    val ScreenTitleLarge: TextStyle =
        TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
        )

    val Wordmark: TextStyle =
        TextStyle(
            fontSize = 30.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.02).em,
        )
}
