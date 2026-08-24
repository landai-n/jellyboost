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
 * The `contentPadding` every one of this module's browse screens hands its scrolling list.
 *
 * These four screens are *pushed* destinations, which would ordinarily mean no app chrome over them
 * at all and the navigation-bar inset applied by hand (`LibraryGridScreen`'s convention).
 * `MiniPlayer` breaks that: the bar follows the music queue rather than the destination, so it docks
 * over an album, an artist, a playlist and the music library — the very screens playback starts
 * from — and `LocalAppChromePadding`'s bottom is non-zero there (`AppScaffold.showsMiniPlayer`).
 * Without consuming it, a list's last track comes to rest underneath the bar.
 *
 * So both halves are added here, and only here: the inset the screen owns, plus the chrome's bottom
 * — which on a pushed destination is exactly the part of the bar that floats *above* that inset,
 * and zero whenever no queue is loaded.
 *
 * The chrome's own half is read in the **layout** phase rather than in composition, which is what
 * [ChromeAwarePadding] exists for: the value animates every frame of a navigation, and reading it
 * here would invalidate the whole screen ~18 times per transition.
 *
 * @param bottom the screen's own spacing below its last row, before either inset.
 * @param top the screen's own spacing above its first row; the chrome's top is *not* taken, since a
 *   pushed destination gets no top chrome and each of these screens insets its own header instead.
 * @param horizontal side margins, for the grid — see [ChromeAwarePadding]'s `start`/`end`.
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
