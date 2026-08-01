package dev.jellyboost.core.ui.component

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Unit tests for [formatRatingBadge], the one piece of pure logic behind the card artwork's rating
 * badge (the rest of `MediaCardArtwork` is layout, which a JVM test cannot see).
 *
 * The badge is 10sp text in the corner of a poster, so the two things that can go wrong are a
 * whole-number rating losing its decimal — which makes a grid of "8" and "7.4" look like two
 * different scales — and a locale that separates decimals with a comma producing a point anyway.
 */
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
