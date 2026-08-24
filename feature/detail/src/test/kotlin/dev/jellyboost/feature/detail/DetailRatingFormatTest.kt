package dev.jellyboost.feature.detail

import dev.jellyboost.core.ui.component.formatRatingBadge
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Pins that the detail header asks `:core:ui`'s [formatRatingBadge] rather than a private
 * `Locale.US` formatter — which would put `8.6` in the header fact and `8,6` on the cards beside it
 * on a German device. `:core:ui`'s `RatingBadgeFormatTest` owns the formatting rules themselves.
 */
class DetailRatingFormatTest {
    @Test
    fun `the header formats a rating in the device locale, not in en-US`() {
        formatRatingBadge(8.6f, Locale.GERMANY) shouldBe "8,6"
        formatRatingBadge(8.6f, Locale.US) shouldBe "8.6"
    }

    @Test
    fun `a whole rating still keeps its decimal place, as the cards' badges do`() {
        // "8" beside a neighbouring "7.4" reads as two different scales.
        formatRatingBadge(8f, Locale.US) shouldBe "8.0"
        formatRatingBadge(8f, Locale.GERMANY) shouldBe "8,0"
    }

    @Test
    fun `the spoken metadata sentence carries the same separator the row draws`() {
        // If the row drew the card-formatted number while the description used a US-formatted one,
        // TalkBack and the screen would disagree.
        val rating = formatRatingBadge(8.6f, Locale.GERMANY)
        metaRowDescription(
            rating = "Bewertung $rating",
            year = "2016",
            certificate = null,
            facts = emptyList(),
        ) shouldBe "Bewertung 8,6, 2016"
    }
}
