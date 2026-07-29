package dev.jellyfinnative.core.common.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Unit tests for the forgiving decode of jellyfin-web's `homesectionN` values. */
class HomeSectionTypeTest {
    @Test
    fun `decodes every value jellyfin-web can write`() {
        HomeSectionType.entries.forEach { section ->
            HomeSectionType.fromServerValue(section.serverValue) shouldBe section
        }
    }

    @Test
    fun `ignores case and surrounding whitespace`() {
        HomeSectionType.fromServerValue("LatestMedia") shouldBe HomeSectionType.LATEST_MEDIA
        HomeSectionType.fromServerValue("  nextup ") shouldBe HomeSectionType.NEXT_UP
    }

    @Test
    fun `accepts the legacy folders alias for the libraries row`() {
        HomeSectionType.fromServerValue("folders") shouldBe HomeSectionType.SMALL_LIBRARY_TILES
    }

    @Test
    fun `a missing value is not an error`() {
        // The normal case: a user who never opened Settings → Home has no keys at all, and the
        // caller answers `null` with that slot's default.
        HomeSectionType.fromServerValue(null).shouldBeNull()
        HomeSectionType.fromServerValue("").shouldBeNull()
        HomeSectionType.fromServerValue("   ").shouldBeNull()
    }

    @Test
    fun `an unknown value decodes to null rather than throwing`() {
        // A newer server, or a hand-written value: fall back to the default, never crash the home
        // screen over a string.
        HomeSectionType.fromServerValue("holographicsuite").shouldBeNull()
    }
}
