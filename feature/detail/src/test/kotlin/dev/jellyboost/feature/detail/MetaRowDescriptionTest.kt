package dev.jellyboost.feature.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [metaRowDescription] — the one sentence the detail header's metadata row says to a
 * screen reader (accessibility audit 2026-08-05, A11Y-21).
 *
 * The row itself needs a device; what it *says* does not, which is why the ordering, the dropping
 * and the punctuation live in a plain function.
 */
class MetaRowDescriptionTest {
    @Test
    fun `a fully-described series reads as one qualified sentence`() {
        metaRowDescription(
            rating = "Rating 8.6",
            year = "2016",
            certificate = "rated TV-MA",
            facts = listOf("4 seasons"),
        ) shouldBe "Rating 8.6, 2016, rated TV-MA, 4 seasons"
    }

    @Test
    fun `the facts keep the row's own order after the certificate`() {
        metaRowDescription(
            rating = null,
            year = "2021",
            certificate = null,
            facts = listOf("155 min", "4.2 GB", "22 min left"),
        ) shouldBe "2021, 155 min, 4.2 GB, 22 min left"
    }

    @Test
    fun `what the server does not know is left unsaid, never punctuated around`() {
        metaRowDescription(rating = null, year = null, certificate = null, facts = listOf("6 episodes")) shouldBe
            "6 episodes"
        metaRowDescription(rating = "Rating 7.0", year = null, certificate = null, facts = emptyList()) shouldBe
            "Rating 7.0"
    }

    @Test
    fun `a blank answer from the server is dropped like a missing one`() {
        // Jellyfin happily returns "" for a certificate it has no value for; a dangling "rated" or
        // a doubled separator is the kind of thing only a screen-reader user would ever hear.
        metaRowDescription(
            rating = "  ",
            year = "2016",
            certificate = "",
            facts = listOf(" ", "4 seasons"),
        ) shouldBe "2016, 4 seasons"
    }

    @Test
    fun `an item with nothing to say says nothing`() {
        metaRowDescription(rating = null, year = null, certificate = null, facts = emptyList()) shouldBe ""
    }

    @Test
    fun `the parts are separated by a pause, not by the interpunct the row draws`() {
        // `·` is read out as "dot" by some engines and swallowed by others; a comma is a pause in
        // all of them. The row keeps drawing SEPARATOR.
        DESCRIPTION_SEPARATOR shouldBe ", "
        SEPARATOR shouldBe " · "
    }
}
