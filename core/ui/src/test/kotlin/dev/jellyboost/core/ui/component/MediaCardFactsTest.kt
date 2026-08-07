package dev.jellyboost.core.ui.component

import dev.jellyboost.core.common.model.ItemType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the card description builder (accessibility audit 2026-08-05, CR-6).
 *
 * Every card in the app is now a single semantics node whose whole content is the string
 * [describe] returns — the artwork, the title, the badges and the progress bar are all silent. That
 * makes this function the only thing standing between a screen-reader user and a wall of unlabelled
 * boxes, and a regression in it is invisible on screen: the app looks perfect and says nothing.
 * Hence a pin per rule.
 */
class MediaCardFactsTest {
    @Test
    fun `the sentence runs type, title, subtitle, progress, then state`() {
        val description =
            MediaCardFacts(
                title = "Arrival",
                typeLabel = "Movie",
                subtitle = "2016",
                progressLabel = "45% watched",
                stateLabels = listOf("Rating 8.0 out of 10", "Downloaded"),
            ).describe()

        description shouldBe "Movie, Arrival, 2016, 45% watched, Rating 8.0 out of 10, Downloaded"
    }

    @Test
    fun `a title on its own is the whole sentence`() {
        MediaCardFacts(title = "Arrival").describe() shouldBe "Arrival"
    }

    @Test
    fun `the overlay badge is dropped when the subtitle already spells it out`() {
        val description =
            MediaCardFacts(
                title = "Westworld",
                typeLabel = "Episode",
                subtitle = "S1 · E10 · The Bicameral Mind",
                badge = "S1 · E10",
            ).describe()

        description shouldBe "Episode, Westworld, S1 · E10 · The Bicameral Mind"
    }

    @Test
    fun `the overlay badge is kept when there is no subtitle to carry it`() {
        val description =
            MediaCardFacts(
                title = "Dune",
                typeLabel = "Movie",
                badge = "4K",
            ).describe()

        description shouldBe "Movie, Dune, 4K"
    }

    @Test
    fun `a blank subtitle counts as no subtitle, so the badge survives`() {
        MediaCardFacts(title = "Dune", subtitle = "  ", badge = "4K").describe() shouldBe "Dune, 4K"
    }

    @Test
    fun `blank and repeated parts are dropped rather than spoken`() {
        val description =
            MediaCardFacts(
                title = "Dune",
                typeLabel = "Movie",
                // A season whose name is its series' name is a real shape in Jellyfin.
                subtitle = "Dune",
                progressLabel = "",
                stateLabels = listOf("Downloaded", "Downloaded"),
            ).describe()

        description shouldBe "Movie, Dune, Downloaded"
    }

    @Test
    fun `the time-left chip is a progress label like any other`() {
        MediaCardFacts(title = "Dune", progressLabel = "48m left").describe() shouldBe "Dune, 48m left"
    }

    @Test
    fun `progress is spoken in whole percent`() {
        progressPercent(0f) shouldBe 0
        progressPercent(0.454f) shouldBe 45
        progressPercent(0.456f) shouldBe 46
        progressPercent(1f) shouldBe 100
    }

    @Test
    fun `progress past either end of the bar is clamped, not announced`() {
        progressPercent(-0.2f) shouldBe 0
        progressPercent(1.4f) shouldBe 100
    }

    @Test
    fun `a card title stays on one line until text is large`() {
        cardTitleMaxLines(fontScale = 1.0f) shouldBe 1
        cardTitleMaxLines(fontScale = 1.3f) shouldBe 1
    }

    @Test
    fun `an accessibility font scale buys the title a second line`() {
        cardTitleMaxLines(fontScale = 1.31f) shouldBe 2
        cardTitleMaxLines(fontScale = 2.0f) shouldBe 2
    }

    @Test
    fun `every playable kind has its own word`() {
        val labels =
            listOf(ItemType.MOVIE, ItemType.SERIES, ItemType.SEASON, ItemType.EPISODE)
                .map { itemTypeLabelRes(it).shouldNotBeNull() }

        labels.distinct() shouldHaveSize labels.size
    }

    @Test
    fun `containers are not named — "Folder, Movies" is noise`() {
        itemTypeLabelRes(ItemType.COLLECTION_FOLDER).shouldBeNull()
        itemTypeLabelRes(ItemType.FOLDER).shouldBeNull()
        itemTypeLabelRes(ItemType.UNKNOWN).shouldBeNull()
    }

    // ---- the shared join (audit DUP-8) -----------------------------------------------------------

    @Test
    fun `parts are joined by a pause, not by the punctuation a row draws`() {
        describeParts("Rating 8.6", "2016", "rated TV-MA") shouldBe "Rating 8.6, 2016, rated TV-MA"
    }

    @Test
    fun `a blank part is dropped rather than punctuated around`() {
        // The home hero's defect: a certificate the server answered "" for was announced as
        // "Rated , 22 minutes left", because that one assembler skipped the trim.
        describeParts("S1 · E4", "", "22 minutes left") shouldBe "S1 · E4, 22 minutes left"
        describeParts("S1 · E4", "   ", "22 minutes left") shouldBe "S1 · E4, 22 minutes left"
    }

    @Test
    fun `a null part is dropped like a blank one`() {
        describeParts("2016", null, "4 seasons") shouldBe "2016, 4 seasons"
    }

    @Test
    fun `surrounding whitespace is trimmed off a part that is kept`() {
        describeParts("  2016  ", " 4 seasons ") shouldBe "2016, 4 seasons"
    }

    @Test
    fun `a repeated part is said once`() {
        describeParts("Westworld", "Westworld", "2016") shouldBe "Westworld, 2016"
    }

    @Test
    fun `a part that is only a duplicate after trimming still collapses`() {
        describeParts("Westworld", "  Westworld  ") shouldBe "Westworld"
    }

    @Test
    fun `nothing to say says nothing`() {
        describeParts() shouldBe ""
        describeParts(null, "", "  ") shouldBe ""
        describeParts(emptyList()) shouldBe ""
    }
}
