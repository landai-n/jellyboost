package dev.jellyboost.feature.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LoginScreenTest {
    @Test
    @DisplayName("a numeric code is spelled out one character at a time")
    fun numericCodeIsSpaced() {
        spacedOutCode("482913") shouldBe "4 8 2 9 1 3"
    }

    @Test
    @DisplayName("an alphanumeric code is spaced the same way")
    fun alphanumericCodeIsSpaced() {
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
