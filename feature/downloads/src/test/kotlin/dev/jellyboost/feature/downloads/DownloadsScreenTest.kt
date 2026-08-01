package dev.jellyboost.feature.downloads

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
}
