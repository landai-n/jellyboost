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
}
