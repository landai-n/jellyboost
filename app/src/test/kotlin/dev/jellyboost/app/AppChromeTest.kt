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
 * The breakpoint is 560dp, the width below which four labelled tabs would crowd the app actions
 * out, and it is the one number the whole frame hangs off: it picks the bar, and through it the
 * shape of `LocalAppChromePadding`. Pinning it here keeps it a plain function of the measured
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
        // non-scrolling row: a literal 44dp would be neither the circle nor the row — and the
        // shortfall would let the search field slide under the Cast and overflow buttons.
        ActionClusterHeight shouldBe 56.dp
        ActionClusterHeight shouldBe ActionClusterTopGap + Dimens.MinTouchTarget
    }
}

/**
 * Unit tests for [showsMiniPlayer], [miniPlayerBottomOffset] and [chromeBottomTarget] — the three
 * plain functions behind the mini-player's visibility, its docking position and the clearance a
 * screen underneath it has to reserve, split out from `NavDestination` and from
 * composition so they are testable without either (the same reasoning [AppChromeTest] documents for
 * [useBottomNav]).
 *
 * The three have to agree, and that is most of what is pinned here: the bar docks
 * [miniPlayerBottomOffset] above whatever is at the bottom edge, so the padding published for the
 * screen under it must be that same offset's worth plus the bar's height — no more (a visible gap
 * under the last row) and no less (the last row disappears under the glass).
 */
class MiniPlayerVisibilityTest {
    @Test
    @DisplayName("an active queue shows the bar, off both Player and NowPlaying")
    fun activeQueueOffPlayerAndNowPlayingShowsTheBar() {
        showsMiniPlayer(activeState(), onPlayer = false, onNowPlaying = false) shouldBe true
    }

    @Test
    @DisplayName("the destination is not part of the rule beyond those two: a pushed screen shows the bar")
    fun theRuleIsTheTwoDestinationsRatherThanTopLevelness() {
        // `&&`ing this with `isTopLevel` would hide the bar on exactly the four screens playback
        // starts from — the music library, an album, an artist, a playlist, all pushed — so a tap
        // on Play would show nothing until the user navigated back to a tab. This function is the
        // whole rule; the clearance argument is [chromeBottomTarget]'s job, pinned below.
        //
        // Nothing here says "pushed" because this function never took a destination: what it pins
        // is that its only two exclusions are the two named ones, whatever else is on screen.
        showsMiniPlayer(activeState(), onPlayer = false, onNowPlaying = false) shouldBe true
        showsMiniPlayer(activeState(), onPlayer = true, onNowPlaying = false) shouldBe false
        showsMiniPlayer(activeState(), onPlayer = false, onNowPlaying = true) shouldBe false
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

    // ---- The clearance the bar's new reach makes necessary ------------------------------------

    @Test
    @DisplayName("a pushed screen reserves the bar's own extent above the inset it applies itself")
    fun aPushedScreenReservesTheBarsExtent() {
        // The half `:feature:music`'s four browse screens add to their list's `contentPadding` on
        // top of the navigation-bar inset they apply by hand. It has to be exactly the bar's height
        // plus the gap it docks above that inset (`miniPlayerBottomOffset(isTopLevel = false)`), or
        // the last track ends under the bar.
        chromeBottomTarget(
            isTopLevel = false,
            bottomNav = true,
            showMiniPlayer = true,
            navigationBarInset = NAV_INSET,
        ) shouldBe MiniPlayerHeight + MiniPlayerGap
    }

    @Test
    @DisplayName("a pushed screen with no queue reserves nothing at all")
    fun aPushedScreenWithNoQueueReservesNothing() {
        chromeBottomTarget(
            isTopLevel = false,
            bottomNav = true,
            showMiniPlayer = false,
            navigationBarInset = NAV_INSET,
        ) shouldBe 0.dp
    }

    @Test
    @DisplayName("a compact tab stacks the bar on top of the pill's own reservation")
    fun aCompactTabStacksTheBarOnThePill() {
        chromeBottomTarget(
            isTopLevel = true,
            bottomNav = true,
            showMiniPlayer = true,
            navigationBarInset = NAV_INSET,
        ) shouldBe NAV_INSET + BottomNavMargin + BottomNavHeight + MiniPlayerHeight + MiniPlayerGap
    }

    @Test
    @DisplayName("the pill's reservation is unchanged when no queue is loaded")
    fun theBottomPillsReservationIsUnchangedWithoutAQueue() {
        chromeBottomTarget(
            isTopLevel = true,
            bottomNav = true,
            showMiniPlayer = false,
            navigationBarInset = NAV_INSET,
        ) shouldBe NAV_INSET + BottomNavMargin + BottomNavHeight
    }

    @Test
    @DisplayName("the wide layout has no pill, so the bar is the whole reservation")
    fun theWideLayoutReservesOnlyTheBar() {
        chromeBottomTarget(
            isTopLevel = true,
            bottomNav = false,
            showMiniPlayer = true,
            navigationBarInset = NAV_INSET,
        ) shouldBe MiniPlayerHeight + MiniPlayerGap
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

    private companion object {
        /** A stand-in for whatever the window's navigation bar takes — a gesture pill's height. */
        val NAV_INSET = 24.dp
    }
}
