package dev.jellyboost.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.jellyboost.core.common.Routes
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.player.cast.CastExitReason
import dev.jellyboost.player.cast.CastingItem
import dev.jellyboost.player.session.tapPlays

// The sizes live here, not inside the two bar composables, because `AppScaffold` builds
// `LocalAppChromePadding` from them and a screen's first row rests at the edge of the glass only
// while the two agree to the pixel.

/** Below this width four labelled tabs crowd the app actions out, so the chrome moves to the bottom. */
internal val TopNavMinWidth: Dp = 560.dp

/** Above its own margin. */
internal val BottomNavHeight: Dp = 60.dp

/** Left, right, and below the pill (over the navigation bar). */
internal val BottomNavMargin: Dp = 20.dp

/** Above whatever the status bar takes. */
internal val TopNavHeight: Dp = 64.dp

internal val ActionClusterTopGap: Dp = 8.dp

/**
 * Derived, never a literal: an action button lays out at [Dimens.MinTouchTarget] whatever size circle
 * it draws, and a number that drifted from that would let a screen's first row slide under the
 * cluster.
 */
internal val ActionClusterHeight: Dp = ActionClusterTopGap + Dimens.MinTouchTarget

/**
 * Every margin around the chrome's actions is corrected by this: padding applies to the invisible
 * [Dimens.MinTouchTarget] frame, but what the eye lines up is the circle drawn inside it.
 */
internal val ActionFrameOverhang: Dp = (Dimens.MinTouchTarget - Dimens.PillHeightSmall) / 2

internal val ActionClusterEndMargin: Dp = 12.dp

/** That margin as padding on the cluster's frame — see [ActionFrameOverhang]. */
internal val ActionClusterEndPadding: Dp = ActionClusterEndMargin - ActionFrameOverhang

/**
 * `statusBarsPadding()` is only correct while the cutout is inside the status bar — in landscape the
 * notch is a *horizontal* inset, and the brand mark ended up underneath it. Restricted to the top and
 * horizontal sides so it pulls in neither the navigation bar nor the IME, which belong to the screen.
 */
internal val TopChromeInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

internal val topChromeInset: Dp
    @Composable get() = TopChromeInsets.asPaddingValues().calculateTopPadding()

/** A plain function of the measured width, so the breakpoint is unit-testable without a device. */
internal fun useBottomNav(maxWidth: Dp): Boolean = maxWidth < TopNavMinWidth

/** In the order both bars draw them. */
internal enum class TopLevelTab(
    val route: Any,
    val icon: ImageVector,
    @param:StringRes val labelRes: Int,
) {
    HOME(Routes.Home, Icons.Filled.Home, R.string.nav_home),
    LIBRARIES(Routes.Libraries, Icons.Filled.VideoLibrary, R.string.nav_libraries),
    SEARCH(Routes.Search, Icons.Filled.Search, R.string.nav_search),
    DOWNLOADS(Routes.Downloads, Icons.Filled.Download, R.string.nav_downloads),
}

/**
 * Spelled out per tab rather than driven from [TopLevelTab.route] because `hasRoute` is reified on
 * the route type, which the enum's value-typed route cannot supply.
 */
internal fun NavDestination?.isSelected(tab: TopLevelTab): Boolean =
    when (tab) {
        TopLevelTab.HOME -> this?.hasRoute<Routes.Home>() == true
        TopLevelTab.LIBRARIES -> this?.hasRoute<Routes.Libraries>() == true
        TopLevelTab.SEARCH -> this?.hasRoute<Routes.Search>() == true
        TopLevelTab.DOWNLOADS -> this?.hasRoute<Routes.Downloads>() == true
    }

/** The chrome is hidden on every destination this is false for. */
internal fun NavDestination?.isTopLevel(): Boolean = TopLevelTab.entries.any { isSelected(it) }

/**
 * A queue must be loaded and the user must not already be looking at it: on [Routes.Player] the bar
 * would offer transport that fights the video controls for the same player, and [Routes.NowPlaying]
 * is its own full-screen view.
 *
 * **Those two exclusions are the whole rule** — [isTopLevel] is deliberately not part of it, or the
 * bar would vanish on the pushed screens playback starts from. Clearance is handled by
 * `AppScaffold.chromePadding`, which folds the bar's height in whatever the destination.
 */
internal fun showsMiniPlayer(
    musicState: MusicPlaybackState,
    onPlayer: Boolean,
    onNowPlaying: Boolean,
    castingBarShown: Boolean = false,
): Boolean = musicState is MusicPlaybackState.Active && !onPlayer && !onNowPlaying && !castingBarShown

/**
 * The casting bar takes [MiniPlayer]'s slot whenever this app has a film on a receiver and no player
 * screen is open for it — [casting] is non-`null` exactly then — except on the two full-screen
 * players, as the music bar is: [Routes.Player] *is* the remote control, and [Routes.NowPlaying] has
 * its own transport where the bar would dock.
 *
 * **Cast wins the slot.** Music never casts (M13), but it can play on this device while a film plays
 * on the television once the film's screen has closed; the two bars would stack in one slot, and the
 * television is the session that has no other way back. Pass the result to [showsMiniPlayer].
 */
internal fun showsCastingBar(
    casting: CastingItem?,
    onPlayer: Boolean,
    onNowPlaying: Boolean,
): Boolean = casting != null && !onPlayer && !onNowPlaying

/**
 * The casting bar went away and the user did not send it: what to announce, politely, naming the
 * device it was on. Without it the bar slides out in silence and a TalkBack user loses the film.
 *
 * @property deviceName `null` when the framework never named the receiver; the copy says "your TV".
 * @property reason [CastExitReason.FINISHED] only for a film the receiver played to its end on its
 *   own; [CastExitReason.STOPPED] for the television dropping it, the item going missing, or the cast
 *   session simply ending — "stopped" is accurate for all three, so they share one wording.
 */
internal data class CastingStopped(
    val deviceName: String?,
    val reason: CastExitReason = CastExitReason.STOPPED,
)

/**
 * `non-null → null` of [CastNowPlaying][dev.jellyboost.player.cast.CastNowPlaying]'s item is the
 * receiver dropping the film (stopped from the television, or left idle), the receiver finishing it
 * on its own, or the cast session ending. **The one silent exit is the player route**: opening the
 * player — from the bar, the notification, or any other film — attaches a screen, which clears the
 * item because that screen is now the remote control. A route change alone (the item still
 * non-`null`) is not an exit at all.
 *
 * @param onPlayer whether the player route is on top *now*: it is by the time a screen it hosts attaches.
 * @param reason which of the two the last clear was — `CastNowPlaying.lastExitReason()`'s value at
 *   the instant [current] turned `null`; unread, and irrelevant, whenever this returns `null`.
 */
internal fun castingStopped(
    previous: CastingItem?,
    current: CastingItem?,
    onPlayer: Boolean,
    reason: CastExitReason = CastExitReason.STOPPED,
): CastingStopped? =
    if (previous != null && current == null && !onPlayer) {
        CastingStopped(previous.deviceName, reason)
    } else {
        null
    }

/** What the casting bar's one button does next — [MiniPlayer]'s transport rule, and the player's. */
internal enum class CastingBarAction {
    PLAY,
    PAUSE,

    /**
     * Drawn and spoken as Play, but the tap **opens the player** instead of toggling: the receiver has let
     * go of the item ([CastingItem.receiverLetGo]) and holds another sender's media or nothing, which the
     * coordinator refuses to pause or play. The player then sends the film back from the bar's position,
     * as the player's own Play does in the same window.
     */
    OPEN_PLAYER,
}

/**
 * **The label is the tap's own rule** ([tapPlays], which the toggle runs): a receiver settled paused
 * under a stale `playWhenReady = true` is played by the tap, so the button says Play. **Buffering keeps
 * a Pause action**, exactly as the player's transport does: buffering means waiting while meaning to
 * play, so the tap that answers it is Pause. Reads the receiver's intent, never a snapshot, which is
 * all zeroes for seconds after every load. A receiver letting go of the item overrides all of it:
 * its intent is no longer about our film ([CastingBarAction.OPEN_PLAYER]).
 */
internal fun castingBarAction(
    playWhenReady: Boolean,
    isBuffering: Boolean,
    isSettledPaused: Boolean = false,
    receiverLetGo: Boolean = false,
): CastingBarAction =
    when {
        receiverLetGo -> CastingBarAction.OPEN_PLAYER
        isBuffering || !tapPlays(playWhenReady, isSettledPaused) -> CastingBarAction.PAUSE
        else -> CastingBarAction.PLAY
    }
