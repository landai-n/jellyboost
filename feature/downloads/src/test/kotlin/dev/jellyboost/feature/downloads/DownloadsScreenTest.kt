package dev.jellyboost.feature.downloads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for what `DownloadsScreen.kt` decides outside of composition.
 *
 * [queueRowCompact] is the breakpoint behind `QueueRow`'s two-tier phone layout (device-verified
 * defect: a 360dp queue row crushed its title to ~4 characters, "Hous…", under four 48dp action
 * buttons). It is extracted from the screen's `BoxWithConstraints` specifically so the breakpoint
 * itself is checkable without a Compose test harness.
 *
 * [chromePinned] is the second decision taken from the same constraints: whether the header,
 * summary and tab row are pinned above an inner-scrolling list, or scroll with it as one page. It
 * needs height as well as width — the two were fused until a landscape phone pinned chrome over a
 * list with no room left to scroll in.
 *
 * [ChromeAwarePadding] is the third: what the screen hands `Modifier.padding` and its lists'
 * `contentPadding` so that the app chrome's *animating* inset is resolved in the layout phase rather
 * than read in composition (audit 2026-08-08, PERF-20). Its arithmetic is checkable here for the
 * same reason the breakpoints are — no Compose harness needed to state what the numbers should be.
 */
class DownloadsScreenTest {
    @Test
    fun `a 360dp phone width is compact`() {
        queueRowCompact(360.dp) shouldBe true
    }

    @Test
    fun `just under the breakpoint is still compact`() {
        queueRowCompact(479.dp) shouldBe true
    }

    @Test
    fun `the breakpoint itself is not compact`() {
        // COMPACT_MAX_WIDTH is exclusive: `maxWidth < COMPACT_MAX_WIDTH`, not `<=`.
        queueRowCompact(480.dp) shouldBe false
    }

    @Test
    fun `a tablet width is never compact`() {
        queueRowCompact(711.dp) shouldBe false
    }

    @Test
    fun `a tablet in landscape pins its chrome`() {
        // test tablet, landscape: wide, and far taller than the chrome needs.
        chromePinned(maxWidth = 1600.dp, maxHeight = 1000.dp) shouldBe true
    }

    @Test
    fun `a tablet in portrait pins its chrome`() {
        chromePinned(maxWidth = 1000.dp, maxHeight = 1600.dp) shouldBe true
    }

    @Test
    fun `a phone in landscape does not pin its chrome`() {
        // The reported defect: wide enough for the tablet summary, nowhere near tall enough to pin
        // it — the whole window was chrome and the queue could not be scrolled to.
        chromePinned(maxWidth = 800.dp, maxHeight = 360.dp) shouldBe false
    }

    @Test
    fun `a phone in portrait does not pin its chrome`() {
        chromePinned(maxWidth = 360.dp, maxHeight = 740.dp) shouldBe false
    }

    @Test
    fun `the height threshold itself pins`() {
        // PINNED_CHROME_MIN_HEIGHT is inclusive: `maxHeight >= PINNED_CHROME_MIN_HEIGHT`.
        chromePinned(maxWidth = 800.dp, maxHeight = 480.dp) shouldBe true
        chromePinned(maxWidth = 800.dp, maxHeight = 479.dp) shouldBe false
    }

    @Test
    fun `height alone never pins a compact width`() {
        chromePinned(maxWidth = 479.dp, maxHeight = 2000.dp) shouldBe false
    }

    // ---- The deferred chrome padding (audit 2026-08-08, PERF-20) --------------------------------

    @Test
    fun `the chrome's top edge is taken alone for the outer box`() {
        val padding = ChromeAwarePadding(chrome = chrome(top = 96.dp, bottom = 104.dp), takeChromeTop = true)

        padding.calculateTopPadding() shouldBe 96.dp
        // The bottom half belongs to whichever list is drawn, so the box must not also reserve it.
        padding.calculateBottomPadding() shouldBe 0.dp
    }

    @Test
    fun `a list's padding adds its own spacing to the chrome's bottom edge`() {
        val padding =
            ChromeAwarePadding(
                chrome = chrome(top = 96.dp, bottom = 104.dp),
                top = 8.dp,
                bottom = 12.dp,
                takeChromeBottom = true,
            )

        // The list's own top is its own: the chrome's top is already on the outer box.
        padding.calculateTopPadding() shouldBe 8.dp
        padding.calculateBottomPadding() shouldBe 116.dp
    }

    @Test
    fun `the read follows the chrome rather than being captured`() {
        // The whole point: `AppScaffold` publishes a padding whose values animate every frame of a
        // navigation, and this class exists so that read happens in the layout phase. A captured
        // value would freeze the padding at whatever the transition's first frame happened to be.
        var top = 96.dp
        val animating =
            object : PaddingValues {
                override fun calculateTopPadding(): Dp = top

                override fun calculateBottomPadding(): Dp = 0.dp

                override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

                override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
            }
        val padding = ChromeAwarePadding(chrome = animating, takeChromeTop = true)

        padding.calculateTopPadding() shouldBe 96.dp
        top = 0.dp
        padding.calculateTopPadding() shouldBe 0.dp
    }

    @Test
    fun `this screen never pads horizontally`() {
        val padding = ChromeAwarePadding(chrome = chrome(top = 96.dp, bottom = 104.dp), takeChromeTop = true)

        padding.calculateLeftPadding(LayoutDirection.Ltr) shouldBe 0.dp
        padding.calculateRightPadding(LayoutDirection.Ltr) shouldBe 0.dp
        padding.calculateLeftPadding(LayoutDirection.Rtl) shouldBe 0.dp
        padding.calculateRightPadding(LayoutDirection.Rtl) shouldBe 0.dp
    }

    private fun chrome(
        top: Dp,
        bottom: Dp,
    ): PaddingValues = PaddingValues(top = top, bottom = bottom)
}
