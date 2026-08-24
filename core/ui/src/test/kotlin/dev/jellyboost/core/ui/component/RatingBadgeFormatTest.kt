package dev.jellyboost.core.ui.component

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

class RatingBadgeFormatTest {
    @Test
    fun `a whole rating keeps its decimal place`() {
        formatRatingBadge(8f, Locale.US) shouldBe "8.0"
    }

    @Test
    fun `a rating with one decimal is unchanged`() {
        formatRatingBadge(7.4f, Locale.US) shouldBe "7.4"
    }

    @Test
    fun `extra precision is rounded away rather than truncated`() {
        formatRatingBadge(7.46f, Locale.US) shouldBe "7.5"
        formatRatingBadge(7.44f, Locale.US) shouldBe "7.4"
    }

    @Test
    fun `the decimal separator follows the locale`() {
        formatRatingBadge(8.2f, Locale.GERMANY) shouldBe "8,2"
    }

    @Test
    fun `a perfect ten still fits the badge`() {
        formatRatingBadge(10f, Locale.US) shouldBe "10.0"
    }
}
