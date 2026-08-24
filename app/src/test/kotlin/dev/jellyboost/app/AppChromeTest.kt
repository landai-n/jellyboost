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

/** The 560dp breakpoint picks the bar, and through it the shape of `LocalAppChromePadding`. */
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
        // A literal 44dp would be neither the circle nor the row, and the shortfall would let a
        // screen's first non-scrolling row slide under the Cast and overflow buttons.
        ActionClusterHeight shouldBe 56.dp
        ActionClusterHeight shouldBe ActionClusterTopGap + Dimens.MinTouchTarget
    }
}

/**
 * The three functions have to agree: the bar docks [miniPlayerBottomOffset] above whatever is at the
 * bottom edge, so the padding published for the screen under it must be that same offset plus the
 * bar's height — no more (a visible gap) and no less (the last row vanishes under the glass).
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
        // `&&`ing this with `isTopLevel` would hide the bar on the four pushed screens playback
        // starts from. The two named exclusions are the whole rule.
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
        // Exactly the bar's height plus the gap it docks above the inset the screen applies itself
        // (`miniPlayerBottomOffset(isTopLevel = false)`), or the last track ends under the bar.
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
        val NAV_INSET = 24.dp
    }
}
