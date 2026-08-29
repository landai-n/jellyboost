package dev.jellyboost.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the app's floating chrome is currently drawn over full-bleed artwork rather than over the
 * page.
 *
 * Hoisted rather than derived, because neither side can see the other: the chrome is a *sibling* of
 * the nav host, so it cannot read what the screen under it draws, and the screen cannot read the
 * chrome. The one screen that puts artwork under the chrome writes this and clears it on dispose;
 * every other screen leaves it `false`, which is the ground the theme's own glass is built for.
 *
 * It is not a theme bit and must never be treated as one — the answer changes as a hero scrolls out
 * from under the bars, in both schemes.
 */
@Stable
class ChromeBackdrop {
    var overMedia: Boolean by mutableStateOf(false)
        private set

    /** Named for the direction it travels: the screen tells the frame, never the other way round. */
    fun reportOverMedia(value: Boolean) {
        overMedia = value
    }
}

/**
 * `static`: the instance is provided once by the app frame and never swapped — what changes is the
 * [ChromeBackdrop.overMedia] state inside it, which invalidates only the readers of that state.
 */
val LocalChromeBackdrop = staticCompositionLocalOf { ChromeBackdrop() }

/**
 * The screen half of [ChromeBackdrop]: it lives here rather than in the screen so that the clearing
 * on dispose — the part every other screen depends on and no screen can see — has one implementation
 * and one test (`ChromeBackdropTest`).
 *
 * @param overMedia read inside the effect, not at the call site, so a screen can hand over a
 *   `derivedStateOf` without recomposing itself each time the answer flips.
 */
@Composable
fun ReportChromeBackdrop(overMedia: () -> Boolean) {
    val backdrop = LocalChromeBackdrop.current
    val current by rememberUpdatedState(overMedia)
    LaunchedEffect(backdrop) {
        snapshotFlow { current() }.collect(backdrop::reportOverMedia)
    }
    DisposableEffect(backdrop) {
        onDispose { backdrop.reportOverMedia(false) }
    }
}
