package dev.jellyboost.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * A translucent tint drawn *on the page* — a hairline, a progress track, a disabled label, a well.
 * It is the scheme's own ink, so the dark scheme keeps today's white and the light one gets black,
 * and dynamic colour follows the wallpaper's.
 *
 * The two alphas are separate parameters, never one value reused: black loses far more contrast per
 * unit of alpha over a light ground than white gains over a dark one. The disabled pill label is the
 * worked example — 0.48 is 5.00:1 on `#101010` and only 3.04:1 on `#EEF1F7`, so the light side runs
 * at 0.65 (5.08:1) to owe WCAG 1.4.3 the same 4.5:1. Tokens drawn over *media* are not this: video,
 * artwork and the scrims over them stay literal white and black in both schemes.
 *
 * @param lightAlpha defaults to [darkAlpha] only for a tint with no contrast obligation at all.
 */
@Composable
@ReadOnlyComposable
fun pageInk(
    darkAlpha: Float,
    lightAlpha: Float = darkAlpha,
): Color =
    MaterialTheme.colorScheme.onBackground.copy(
        alpha = if (LocalIsLightTheme.current) lightAlpha else darkAlpha,
    )
