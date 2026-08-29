package dev.jellyboost.player.ui

import dev.jellyboost.core.common.model.SubtitleBackground
import dev.jellyboost.core.common.model.SubtitleTextSize
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The arithmetic behind the two subtitle-appearance settings. `SubtitleView` itself is an Android view
 * and cannot be built off a device, so what is pinned here is the part that decides what it is *told*:
 * which options defer to the platform, and that the sizes are a real, ordered ramp.
 *
 * `applyAppearance` reads exactly these two properties and nothing else, so a change that broke the
 * mapping would have to break one of them first.
 */
class SubtitleAppearanceStyleTest {
    @Test
    fun `only SYSTEM defers to the device, for both settings`() {
        // The load-bearing case: a null is what routes to setUserDefaultTextSize/setUserDefaultStyle,
        // which is PlayerView's untouched behaviour and therefore every existing install's appearance.
        SubtitleTextSize.SYSTEM.heightFraction.shouldBeNull()
        SubtitleBackground.SYSTEM.backgroundAlpha.shouldBeNull()

        SubtitleTextSize.entries.filter { it != SubtitleTextSize.SYSTEM }.forEach {
            it.heightFraction.shouldNotBeNull()
        }
        SubtitleBackground.entries.filter { it != SubtitleBackground.SYSTEM }.forEach {
            it.backgroundAlpha.shouldNotBeNull()
        }
    }

    @Test
    fun `both settings default to following the device`() {
        SubtitleTextSize.DEFAULT shouldBe SubtitleTextSize.SYSTEM
        SubtitleBackground.DEFAULT shouldBe SubtitleBackground.SYSTEM
    }

    @Test
    fun `the sizes are a strictly increasing ramp in declaration order`() {
        // Declaration order is what the picker draws, so a ramp that was not monotonic would put
        // "Large" above "Larger" on screen.
        val fractions = SubtitleTextSize.entries.mapNotNull { it.heightFraction }

        fractions.zipWithNext().forEach { (smaller, larger) ->
            (larger.toDouble() - smaller.toDouble()) shouldBeGreaterThan 0.0
        }
    }

    @Test
    fun `NORMAL is Media3's own default text size`() {
        // Not a free choice: the option a user reads as "normal" has to be what the player would draw
        // with no preference at all, or "Normal" would visibly resize the subtitle.
        SubtitleTextSize.NORMAL.heightFraction shouldBe SUBTITLE_VIEW_DEFAULT_TEXT_SIZE_FRACTION
    }

    @Test
    fun `the background ramp runs from clear to opaque, and only the clear one is outlined`() {
        SubtitleBackground.NONE.backgroundAlpha shouldBe 0f
        SubtitleBackground.SOLID.backgroundAlpha shouldBe 1f
        val translucent = SubtitleBackground.TRANSLUCENT.backgroundAlpha.shouldNotBeNull()
        (translucent.toDouble()) shouldBeGreaterThan 0.0
        (1.0 - translucent.toDouble()) shouldBeGreaterThan 0.0

        // The outline is legibility, not decoration: it is what keeps white text readable over a
        // bright frame once the box behind it is gone.
        SubtitleBackground.entries.filter { it.outlined } shouldContainExactly listOf(SubtitleBackground.NONE)
    }

    @Test
    fun `an unknown stored name reads as the device default rather than throwing`() {
        SubtitleTextSize.fromNameOrDefault("GIGANTIC") shouldBe SubtitleTextSize.SYSTEM
        SubtitleTextSize.fromNameOrDefault(null) shouldBe SubtitleTextSize.SYSTEM
        SubtitleTextSize.fromNameOrDefault("LARGE") shouldBe SubtitleTextSize.LARGE

        SubtitleBackground.fromNameOrDefault("RAINBOW") shouldBe SubtitleBackground.SYSTEM
        SubtitleBackground.fromNameOrDefault(null) shouldBe SubtitleBackground.SYSTEM
        SubtitleBackground.fromNameOrDefault("SOLID") shouldBe SubtitleBackground.SOLID
    }

    private companion object {
        /** `androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION`, which the module cannot load here. */
        const val SUBTITLE_VIEW_DEFAULT_TEXT_SIZE_FRACTION = 0.0533f
    }
}
