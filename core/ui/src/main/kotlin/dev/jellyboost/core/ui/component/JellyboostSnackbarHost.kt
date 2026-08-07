package dev.jellyboost.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.LocalAppChromePadding

/**
 * The app's snackbar: the [PillSnackbar] and the one rule about where it sits.
 *
 * Five screens used to spell this host out for themselves and had drifted into four different
 * answers to the same question — `navigationBarsPadding()` on the two pushed destinations,
 * `LocalAppChromePadding`'s bottom on the downloads screen, a hardcoded padding in the player, and
 * nothing at all on the SyncPlay groups screen, whose snackbar therefore sat under the gesture bar
 * (audit DUP-3). None of the four is wrong for the screen that wrote it; what was wrong is that
 * each is only correct *there*, so a screen that changed category silently got the other screen's
 * bug.
 *
 * [SnackbarBottomInset] is the rule that is correct in every category at once, because it asks the
 * two questions in the right order and takes the larger answer:
 *
 * - **Is there floating chrome over this screen?** A top-level destination has `:app`'s nav pill
 *   above the bottom edge, and [LocalAppChromePadding]'s `bottom` is the pill, its margin and the
 *   navigation-bar inset together — so the snackbar floats just above the pill.
 * - **Otherwise, where is the gesture bar?** On a pushed destination the chrome is hidden and that
 *   padding is zero by contract, so the navigation-bar inset is what keeps the pill off the
 *   gesture bar. It is the same value `navigationBarsPadding()` was applying by hand.
 *
 * The `max` is not a tie-break, it is the fix for two real cases. On a **wide** layout the chrome
 * is all at the top and its bottom padding is zero, so a screen that read only the chrome padding
 * (the downloads screen) put its snackbar under the gesture bar on exactly the tablet this app is
 * tested on. And **mid-navigation** the chrome's bottom animates through values smaller than the
 * inset, which used to let the snackbar dip under the gesture bar and jump back up.
 *
 * @param minimumBottomInset a floor for a screen that consumes no system insets at all and must
 *   still clear something it drew itself — the player's transport controls. Zero everywhere else.
 */
@Composable
fun JellyboostSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    minimumBottomInset: Dp = 0.dp,
) {
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val chromePadding = LocalAppChromePadding.current
    SnackbarHost(
        hostState = hostState,
        modifier =
            modifier.padding(
                SnackbarBottomInset(
                    chromePadding = chromePadding,
                    navigationBarInset = navigationBarInset,
                    minimumInset = minimumBottomInset,
                ),
            ),
    ) { data -> PillSnackbar(snackbarData = data) }
}

/**
 * The bottom inset rule of [JellyboostSnackbarHost], as a [PaddingValues] whose read is deferred.
 *
 * Deferred on purpose: [LocalAppChromePadding] animates every frame of a navigation, and reading
 * it in composition re-invalidates the reading scope once per frame. Resolving it inside
 * [calculateBottomPadding] moves the read to the layout phase, where only the host's own placement
 * depends on it.
 */
@Stable
internal class SnackbarBottomInset(
    private val chromePadding: PaddingValues,
    private val navigationBarInset: Dp,
    private val minimumInset: Dp = 0.dp,
) : PaddingValues {
    override fun calculateBottomPadding(): Dp =
        maxOf(chromePadding.calculateBottomPadding(), navigationBarInset, minimumInset)

    override fun calculateTopPadding(): Dp = 0.dp

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

/**
 * A [SnackbarHostState] that shows each one-shot [message] exactly once and then consumes it.
 *
 * Five screens hold a nullable "user message" in their state, show it, and call back to have it
 * cleared. All five wrote the same `remember` + `LaunchedEffect` pair, and the detail screen's copy
 * carried a bug the others were one refactor away from: it keyed the effect on the **resolved
 * string**.
 *
 * That is a real wedge, not a cosmetic one (audit HYG-8). When a second message arrives before the
 * first has been consumed — a batch finishing while a download failure is still on screen — the
 * state goes straight from one message to the other with no `null` between them. If the two happen
 * to share their copy, the resolved string never changes, so `LaunchedEffect` never restarts: the
 * second message is never shown *and never consumed*, and because the field stays non-null the
 * screen can never show another snackbar for the rest of its life.
 *
 * Keying on the message value fixes it: two distinct messages are two distinct keys whatever they
 * happen to say, so the effect restarts, shows the second, and consumes it. The resolution to text
 * still happens in composition — where `stringResource` can be read and the device's locale
 * applies — but it is no longer what decides *whether* anything happens.
 *
 * @param message the ViewModel's current one-shot message, `null` when there is none.
 * @param onShown clears it; called after the snackbar has been shown or dismissed.
 * @param text resolves the message to its copy. `@Composable` because that is where a `UiText`, a
 *   `stringResource` or a `pluralStringResource` can be read.
 */
@Composable
fun <T : Any> rememberOneShotSnackbar(
    message: T?,
    onShown: () -> Unit,
    text: @Composable (T) -> String,
): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    val resolved = message?.let { text(it) }
    val currentOnShown by rememberUpdatedState(onShown)

    LaunchedEffect(oneShotSnackbarKey(message)) {
        if (resolved != null) {
            hostState.showSnackbar(resolved)
            currentOnShown()
        }
    }
    return hostState
}

/**
 * What [rememberOneShotSnackbar] keys its effect on: the message itself, never its resolved copy.
 *
 * Named rather than inlined so the rule has somewhere to be tested — see
 * `OneShotSnackbarKeyTest`, which replays Compose's restart semantics over this function and over
 * the copy-keyed alternative it replaces.
 */
internal fun oneShotSnackbarKey(message: Any?): Any? = message
