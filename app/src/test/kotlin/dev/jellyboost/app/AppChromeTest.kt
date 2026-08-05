package dev.jellyboost.app

import androidx.compose.ui.unit.dp
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.common.music.MusicRepeatMode
import dev.jellyboost.core.ui.theme.Dimens
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [useBottomNav] — which of the app's two navigation layouts a window of a given
 * width gets.
 *
 * The breakpoint is the same 560dp the combined app bar used to decide whether its four tabs could
 * afford labels, and it is the one number the whole frame hangs off: it picks the bar, and through
 * it the shape of `LocalAppChromePadding`. Pinning it here keeps it a plain function of the measured
 * width — testable without a device, the same way `homeThumbCardWidth` and `librariesMinCellWidth`
 * are.
 */
class AppChromeTest {
    @Test
    @DisplayName("a phone-width window gets the floating bottom pill")
    fun phoneWidthUsesTheBottomNav() {
        useBottomNav(360.dp) shouldBe true
    }

    @Test
    @DisplayName("just under the breakpoint still gets the bottom pill")
    fun justUnderTheBreakpointUsesTheBottomNav() {
        useBottomNav(559.dp) shouldBe true
    }

    @Test
    @DisplayName("the breakpoint itself switches to the top nav")
    fun theBreakpointItselfUsesTheTopNav() {
        useBottomNav(TopNavMinWidth) shouldBe false
        useBottomNav(560.dp) shouldBe false
    }

    @Test
    @DisplayName("the test tablet portrait width uses the top nav")
    fun tabletPortraitUsesTheTopNav() {
        useBottomNav(711.dp) shouldBe false
    }

    @Test
    @DisplayName("the action cluster reserves its gap plus a full touch target, not a magic 44dp")
    fun theActionClusterHeightIsDerivedFromWhatItDraws() {
        // The cluster is one row of app actions under `ActionClusterTopGap`, and every action lays
        // out `Dimens.MinTouchTarget` around the smaller circle it draws (`JellyfinButtons.kt`).
        // Pinned because this is the number `AppScaffold` keeps clear of a compact screen's first,
        // non-scrolling row: it used to be a literal 44dp — neither the circle nor the row — and the
        // shortfall is what let the search field slide under the Cast and overflow buttons.
        ActionClusterHeight shouldBe 56.dp
        ActionClusterHeight shouldBe ActionClusterTopGap + Dimens.MinTouchTarget
    }
}

/**
 * Unit tests for [showsMiniPlayer] and [miniPlayerBottomOffset] — the two plain functions behind
 * the M13 Phase 4 mini-player's visibility and docking position, split out from `NavDestination`
 * so they are testable without one (the same reasoning [AppChromeTest] documents for [useBottomNav]).
 */
class MiniPlayerVisibilityTest {
    @Test
    @DisplayName("an active queue shows the bar, off both Player and NowPlaying")
    fun activeQueueOffPlayerAndNowPlayingShowsTheBar() {
        showsMiniPlayer(activeState(), onPlayer = false, onNowPlaying = false) shouldBe true
    }

    @Test
    @DisplayName("idle hides the bar however the destination")
    fun idleHidesTheBar() {
        showsMiniPlayer(MusicPlaybackState.Idle, onPlayer = false, onNowPlaying = false) shouldBe false
    }

    @Test
    @DisplayName("the video player hides the bar even mid-queue")
    fun onPlayerHidesTheBar() {
        showsMiniPlayer(activeState(), onPlayer = true, onNowPlaying = false) shouldBe false
    }

    @Test
    @DisplayName("the now-playing screen hides its own docked bar")
    fun onNowPlayingHidesTheBar() {
        showsMiniPlayer(activeState(), onPlayer = false, onNowPlaying = true) shouldBe false
    }

    @Test
    @DisplayName("stacks above the floating pill when one is showing")
    fun docksAboveTheBottomNavPill() {
        miniPlayerBottomOffset(isTopLevel = true, bottomNav = true) shouldBe
            BottomNavMargin + BottomNavHeight + MiniPlayerGap
    }

    @Test
    @DisplayName("sits at the window's bottom edge on the wide layout")
    fun sitsAtTheBottomEdgeOnTheWideLayout() {
        miniPlayerBottomOffset(isTopLevel = true, bottomNav = false) shouldBe MiniPlayerGap
    }

    @Test
    @DisplayName("sits at the bottom edge off the top-level destinations too")
    fun sitsAtTheBottomEdgeOffTopLevel() {
        miniPlayerBottomOffset(isTopLevel = false, bottomNav = true) shouldBe MiniPlayerGap
    }

    private fun activeState() =
        MusicPlaybackState.Active(
            queue = listOf(JellyfinItem(id = "t1", name = "Track 1", type = ItemType.AUDIO)),
            currentIndex = 0,
            isPlaying = true,
            positionMs = 0L,
            durationMs = 0L,
            shuffleEnabled = false,
            repeatMode = MusicRepeatMode.OFF,
        )
}
