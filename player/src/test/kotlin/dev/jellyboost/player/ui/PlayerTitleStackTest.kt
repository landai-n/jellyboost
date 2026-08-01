package dev.jellyboost.player.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [asTitleAndSubtitle] — the top bar's two-line lockup, which is *derived* from the
 * single label `PlayerViewModel` publishes rather than plumbed as a second field. The split is the
 * inverse of that join, so it is worth pinning: get it wrong and an episode's title silently
 * absorbs its series name.
 */
class PlayerTitleStackTest {
    @Test
    fun `an episode label splits into title and episode line`() {
        val (title, subtitle) = "The Original${PLAYER_LABEL_SEPARATOR}Star Trek · S1 E10".asTitleAndSubtitle()

        title shouldBe "The Original"
        subtitle shouldBe "Star Trek · S1 E10"
    }

    @Test
    fun `a film has no second line`() {
        val (title, subtitle) = "Blade Runner".asTitleAndSubtitle()

        title shouldBe "Blade Runner"
        subtitle shouldBe null
    }

    @Test
    fun `an empty label stays empty rather than becoming a blank second line`() {
        val (title, subtitle) = "".asTitleAndSubtitle()

        title shouldBe ""
        subtitle shouldBe null
    }

    @Test
    fun `a label whose second part is blank draws one line`() {
        val (title, subtitle) = "The Original${PLAYER_LABEL_SEPARATOR}   ".asTitleAndSubtitle()

        title shouldBe "The Original"
        subtitle shouldBe null
    }
}
