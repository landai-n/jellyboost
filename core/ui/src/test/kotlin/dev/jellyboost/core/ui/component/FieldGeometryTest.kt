package dev.jellyboost.core.ui.component

import dev.jellyboost.core.ui.theme.Dimens
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The one number [JellyfinTextField]'s equal-height promise rests on.
 *
 * A trailing slot holds a 48dp `IconButton` (password reveal, search clear) and lays out *inside*
 * the well, so the well has to be at least that tall or the button stretches it — which is exactly
 * how the password field came to stand taller than the username field above it. The composed half
 * (two real fields measuring the same) is `ChipAndFieldA11yTest`.
 */
class FieldGeometryTest {
    @Test
    @DisplayName("the well is tall enough to hold a full touch target, so a trailing button cannot stretch it")
    fun theWellHoldsATouchTarget() {
        FieldMinHeight shouldBeGreaterThanOrEqualTo Dimens.MinTouchTarget
    }
}
