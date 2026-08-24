package dev.jellyboost.feature.detail

import dev.jellyboost.core.ui.component.formatRatingBadge
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Pins the detail header onto `:core:ui`'s rating formatter.
 *
 * A private `formatRating` hardcoding `Locale.US`, while the cards on the very same screen use the
 * locale-aware [formatRatingBadge], would put `8.6` in the starred header fact and `8,6` on every
 * card beside it on a German device, and `metaRowDescription` would speak the wrong separator to
 * TalkBack.
 *
 * `RatingBadgeFormatTest` in `:core:ui` owns the *formatting* rules — one decimal place always,
 * half-up rounding, the separator per locale. This file owns the thing that can regress: that the
 * detail screen is asking that function rather than one of its own. The German case is the one the
 * mismatch is visible in, so it is asserted here as well as there.
 */
class DetailRatingFormatTest {
    @Test
    fun `the header formats a rating in the device locale, not in en-US`() {
        formatRatingBadge(8.6f, Locale.GERMANY) shouldBe "8,6"
        formatRatingBadge(8.6f, Locale.US) shouldBe "8.6"
    }

    @Test
    fun `a whole rating still keeps its decimal place, as the cards' badges do`() {
        // The reason the shared function exists at all: "8" beside a neighbouring "7.4" reads as
        // two different scales.
        formatRatingBadge(8f, Locale.US) shouldBe "8.0"
        formatRatingBadge(8f, Locale.GERMANY) shouldBe "8,0"
    }

    @Test
    fun `the spoken metadata sentence carries the same separator the row draws`() {
        // The row must not draw the card-formatted number while the description builds its
        // sentence from a US-formatted one, or TalkBack and the screen disagree. Both sides come
        // from the same call, which this composes end to end.
        val rating = formatRatingBadge(8.6f, Locale.GERMANY)
        metaRowDescription(
            rating = "Bewertung $rating",
            year = "2016",
            certificate = null,
            facts = emptyList(),
        ) shouldBe "Bewertung 8,6, 2016"
    }
}
