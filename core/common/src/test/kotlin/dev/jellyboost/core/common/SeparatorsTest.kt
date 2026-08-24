package dev.jellyboost.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SeparatorsTest {
    @Test
    fun `the dot separator is a plain space, interpunct, plain space`() {
        Separators.DOT shouldBe " · "
    }

    @Test
    fun `joins facts the way a card or header line expects`() {
        listOf("2016", "TV-MA", "4 seasons").joinToString(Separators.DOT) shouldBe "2016 · TV-MA · 4 seasons"
    }
}
