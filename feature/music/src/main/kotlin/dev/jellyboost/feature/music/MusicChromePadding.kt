package dev.jellyboost.feature.music

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellyboost.core.ui.theme.ChromeAwarePadding
import dev.jellyboost.core.ui.theme.LocalAppChromePadding

/**
 * Pushed destinations normally take no app chrome, but `MiniPlayer` follows the music *queue* rather
 * than the destination, so it docks over exactly these screens and `LocalAppChromePadding`'s bottom
 * is non-zero here. Unconsumed, a list's last track rests underneath the bar.
 *
 * The chrome's half must be resolved in the **layout** phase ([ChromeAwarePadding]): the value
 * animates every frame of a navigation, and reading it in composition invalidates the whole screen
 * ~18 times per transition.
 *
 * @param top the chrome's top is *not* taken — a pushed destination gets no top chrome, and each of
 *   these screens insets its own header.
 */
@Composable
internal fun musicListContentPadding(
    bottom: Dp,
    top: Dp = 0.dp,
    horizontal: Dp = 0.dp,
): PaddingValues {
    val chrome = LocalAppChromePadding.current
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return remember(chrome, bottom, top, horizontal, navigationBarInset) {
        ChromeAwarePadding(
            chrome = chrome,
            top = top,
            bottom = bottom + navigationBarInset,
            start = horizontal,
            end = horizontal,
            takeChromeBottom = true,
        )
    }
}
