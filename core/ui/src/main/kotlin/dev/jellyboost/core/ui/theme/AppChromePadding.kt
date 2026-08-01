package dev.jellyboost.core.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * How much of a top-level screen the app's floating chrome covers, provided by `:app`'s
 * `AppScaffold` (DECISIONS.md 2026-08-01, the 2026-refresh chrome).
 *
 * The refresh's navigation does not occupy a `Scaffold` slot any more: the bottom nav is a pill
 * floating over the content, the wide layout's nav bar is a translucent row drawn on top of it, and
 * on a compact layout a small cluster of glass action buttons floats in the top-right corner. All
 * three are *siblings* of the nav host rather than ancestors, so nothing shrinks the screen below
 * them and content scrolls under them by design.
 *
 * That is what this value is for. A top-level screen adds it to the `contentPadding` of whatever it
 * scrolls — `LazyColumn`, `LazyVerticalGrid`, or the padding of a plain `Column` — so its first and
 * last rows come to rest in the clear while the middle of the list still passes under the glass.
 * Padding rather than a size: `contentPadding` scrolls away, a `Modifier.padding` would not.
 *
 * ### The contract
 * - `top` — the height of the chrome band at the top of the window, **system inset included**: on a
 *   wide layout the nav bar's own height plus the status bar; on a compact one the status bar plus
 *   the floating action cluster.
 * - `bottom` — the floating nav pill's height, its margin below it, and the navigation-bar inset,
 *   on a compact layout; zero on a wide one, where the chrome is all at the top.
 * - Both are **zero** wherever the chrome is hidden: every pushed destination, the auth flow, and
 *   the player. Those screens manage their own system-bar insets exactly as they did before the
 *   refresh, and must not consume this on top of that.
 *
 * The values animate over the same clock as a screen transition, so a screen reading them during a
 * navigation sees them move rather than jump.
 *
 * Defaults to zero, which is what a preview, a test, or any screen composed outside the scaffold
 * gets — reading it is therefore always safe and never needs a null check.
 */
val LocalAppChromePadding: ProvidableCompositionLocal<PaddingValues> =
    compositionLocalOf { PaddingValues(0.dp) }
