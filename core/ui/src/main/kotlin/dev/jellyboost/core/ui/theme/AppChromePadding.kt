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
 * `AppScaffold`. Belongs in a scrollable's `contentPadding`, not a `Modifier.padding`, so the first
 * and last rows rest in the clear while the middle still passes under the glass.
 *
 * The contract: `top` includes the system inset; `bottom` is the nav pill, its margin and the
 * navigation-bar inset on a compact layout, zero on a wide one. **Both are zero wherever the chrome
 * is hidden** — pushed destinations, the auth flow, the player — and those screens manage their own
 * system-bar insets, so they must not consume this on top of that.
 *
 * The values animate, and default to zero outside the scaffold (a preview or a test).
 */
val LocalAppChromePadding: ProvidableCompositionLocal<PaddingValues> =
    compositionLocalOf { PaddingValues(0.dp) }

/**
 * The chrome's half is resolved in the **layout** phase: `calculate*` runs inside a measure pass,
 * which is a snapshot-observing scope of its own, so the running animations invalidate layout rather
 * than recomposing the reading scope on every one of a navigation's ~18 frames.
 *
 * `@Stable` and meant to be `remember`ed, so the identity a lazy list keys its measure policy on
 * does not change either.
 *
 * @param chrome read from [LocalAppChromePadding] at the call site and passed in, which is what
 *   keeps this a plain unit-testable class rather than a composable.
 */
@Stable
// The parameters ARE this PaddingValues' four axes plus two chrome-edge switches; there is nothing
// to bundle that is not already this object (PlayerViewModel precedent for a justified suppression).
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
