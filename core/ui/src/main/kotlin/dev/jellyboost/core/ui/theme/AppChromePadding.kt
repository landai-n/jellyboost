package dev.jellyboost.core.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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

/**
 * A fixed inset plus one or both edges of [LocalAppChromePadding], with the chrome's half read in
 * the **layout** phase rather than in composition (audit 2026-08-08, PERF-20).
 *
 * `AppScaffold` publishes the chrome padding as a stable object whose `calculate*` methods read two
 * running animations, precisely so that a consumer can defer the read — the composition local's own
 * KDoc above spells out that reading the values in composition invalidates the reading scope on
 * every one of a navigation's ~18 frames, and on a `BoxWithConstraints` modifier that costs a full
 * subcomposition pass.
 *
 * A consumer resolves its `PaddingValues` where it is *used* instead: `Modifier.padding` and a lazy
 * list's `contentPadding` both call `calculate*` inside their measure pass, which is a
 * snapshot-observing scope of its own, so the animation invalidates layout rather than composition.
 *
 * `@Stable`, and meant to be `remember`ed by its caller, so the identity a lazy list keys its
 * measure policy on does not change either.
 *
 * Hoisted here from the two private copies the PERF-20 wave left in `:feature:downloads` and
 * `:feature:search` — both of which said in prose that a shared home beside [LocalAppChromePadding]
 * was the obvious next step, and that the hoist was deliberately not part of that change. This is
 * that step. `SnackbarBottomInset` is the third relative of the shape and stays where it is: it
 * reads the same local, but it is a snackbar's *offset* rather than a list's content padding.
 *
 * @param chrome the value read from [LocalAppChromePadding] at the call site. Passed in rather than
 *   read here because this is a plain class, not a composable — which is also what makes it
 *   unit-testable (`DownloadsScreenTest`).
 * @param top a fixed inset added above whatever the chrome contributes.
 * @param bottom the same at the other end.
 * @param start a fixed leading inset. The chrome contributes nothing horizontally — it is a band at
 *   each end of the window — so this is a plain pass-through, there purely so a *grid* can express
 *   its side margins in the same `contentPadding` object rather than having to split them into a
 *   `Modifier.padding` that would no longer scroll with the content (`:feature:music`'s library
 *   grid is the first caller; the list-shaped callers leave both at zero and pad their rows).
 * @param end the same at the trailing edge.
 * @param takeChromeTop whether the chrome's top edge is added to [top].
 * @param takeChromeBottom whether the chrome's bottom edge is added to [bottom].
 */
@Stable
// A PaddingValues implementation: the parameters ARE the type's four axes plus the two
// chrome-edge switches its KDoc explains — there is nothing to bundle that is not already
// this one object. The PlayerViewModel precedent (DECISIONS.md 2026-08-03) for a justified
// suppression.
@Suppress("LongParameterList")
class ChromeAwarePadding(
    private val chrome: PaddingValues,
    private val top: Dp = 0.dp,
    private val bottom: Dp = 0.dp,
    private val start: Dp = 0.dp,
    private val end: Dp = 0.dp,
    private val takeChromeTop: Boolean = false,
    private val takeChromeBottom: Boolean = false,
) : PaddingValues {
    override fun calculateTopPadding(): Dp = top + if (takeChromeTop) chrome.calculateTopPadding() else 0.dp

    override fun calculateBottomPadding(): Dp = bottom + if (takeChromeBottom) chrome.calculateBottomPadding() else 0.dp

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) start else end

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) end else start
}
