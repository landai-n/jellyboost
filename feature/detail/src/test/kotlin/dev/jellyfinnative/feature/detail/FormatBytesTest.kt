package dev.jellyfinnative.feature.detail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Unit tests for [formatBytes] — the media file size shown on [MetadataLine].
 *
 * This is the third copy of the same formatter (`:feature:settings`, `:feature:downloads` each keep
 * their own — see the KDoc on `formatBytes` for why); these boundary cases mirror the ones the other
 * two copies would need, since the function itself is pure and testable outside Compose.
 *
 * The function formats with `Locale.getDefault()` by design (it should read the way the user's own
 * device does), so the default locale is pinned for the run — otherwise these assertions would pass
 * or fail depending on the machine's region (e.g. a comma instead of a dot for the decimal point).
 */
class FormatBytesTest {
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
