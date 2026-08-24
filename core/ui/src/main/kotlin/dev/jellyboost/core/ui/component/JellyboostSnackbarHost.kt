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
 * The `max` in [SnackbarBottomInset] is not a tie-break. On a wide layout the chrome is all at the
 * top and its bottom padding is zero, so reading only that put the snackbar under the gesture bar on
 * the test tablet; mid-navigation the chrome's bottom animates through values below the inset, which
 * would make the snackbar dip and jump back.
 *
 * @param minimumBottomInset a floor for a screen consuming no system insets that must still clear
 *   something it drew itself — the player's transport controls. Zero everywhere else.
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
 * The read is deferred on purpose: [LocalAppChromePadding] animates every frame of a navigation, so
 * resolving it inside [calculateBottomPadding] keeps the per-frame invalidation in the layout phase
 * instead of recomposing the reading scope.
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
 * The effect must key on the message value, never its resolved string: two messages that share their
 * copy and arrive back to back (no `null` between) leave the key unchanged, so the second is never
 * shown *and never consumed*, and the non-null field wedges the screen's snackbar for good.
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

/** Named rather than inlined so `OneShotSnackbarKeyTest` can replay Compose's restart semantics. */
internal fun oneShotSnackbarKey(message: Any?): Any? = message
