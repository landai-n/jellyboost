package dev.jellyboost.feature.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for what `LoginScreen.kt` decides outside of composition.
 *
 * [spacedOutCode] is the Quick Connect code as a screen reader has to hear it: the six digit boxes
 * are one semantic node rather than six bare glyphs (accessibility audit 2026-08-05, F3), and the
 * one thing that node must not do is hand TTS a number — "482913" spoken as four hundred and
 * eighty-two thousand nine hundred and thirteen is not a code anybody can type into another device.
 */
class LoginScreenTest {
    @Test
    @DisplayName("a numeric code is spelled out one character at a time")
    fun numericCodeIsSpaced() {
        spacedOutCode("482913") shouldBe "4 8 2 9 1 3"
    }

    @Test
    @DisplayName("an alphanumeric code is spaced the same way")
    fun alphanumericCodeIsSpaced() {
        // The server's code length and alphabet are its own business — the row sizes itself to
        // whatever comes back, and so does this.
        spacedOutCode("A7B2") shouldBe "A 7 B 2"
    }

    @Test
    @DisplayName("whitespace inside a code is dropped rather than doubled")
    fun whitespaceIsDropped() {
        spacedOutCode("482 913") shouldBe "4 8 2 9 1 3"
    }

    @Test
    @DisplayName("an empty code produces an empty spelling, not a stray separator")
    fun emptyCode() {
        spacedOutCode("") shouldBe ""
    }
}
