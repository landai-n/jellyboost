package dev.jellyboost.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The default locale is pinned for the run: [formatBytes] formats with `Locale.getDefault()` by design, so
 * these assertions would otherwise pass or fail depending on the machine's region.
 */
class ByteFormatTest {
    private val originalLocale: Locale = Locale.getDefault()

    @BeforeEach
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `states a sub-unit size in plain bytes`() {
        formatBytes(500L) shouldBe "500 B"
    }

    @Test
    fun `rolls over into kB at the unit boundary`() {
        formatBytes(1_000L) shouldBe "1.0 kB"
    }

    @Test
    fun `formats a mid-range size in MB`() {
        formatBytes(552_400_000L) shouldBe "552.4 MB"
    }

    @Test
    fun `formats a large size in GB`() {
        formatBytes(4_500_000_000L) shouldBe "4.5 GB"
    }

    @Test
    fun `does not climb past TB`() {
        formatBytes(5_000_000_000_000L) shouldBe "5.0 TB"
    }
}
