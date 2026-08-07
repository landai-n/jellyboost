package dev.jellyboost.core.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Unit tests for [hostForLog].
 *
 * The property under test is not "parses URLs" — it is "whatever a user typed, the log line gets a
 * host and only a host". So the cases are the shapes the server-setup field actually accepts
 * (docs/PLAN.md, "ServerSetup"), plus the ones that must not throw.
 */
class HostForLogTest {
    @ParameterizedTest
    @CsvSource(
        "https://jellyfin.example.com:8096/, jellyfin.example.com",
        "http://192.168.1.5:8096,           192.168.1.5",
        "192.168.1.5:8096,                  192.168.1.5",
        "jellyfin.example.com,              jellyfin.example.com",
        "https://jellyfin.example.com,      jellyfin.example.com",
        "http://[2001:db8::1]:8096,         2001:db8::1",
        "https://example.com/jellyfin,      example.com",
    )
    fun `the host survives, the scheme and port do not`(
        address: String,
        expected: String,
    ) {
        hostForLog(address) shouldBe expected
    }

    @Test
    fun `nothing usable reads as a placeholder rather than as null`() {
        hostForLog(null) shouldBe "<none>"
        hostForLog("") shouldBe "<none>"
        hostForLog("   ") shouldBe "<none>"
        hostForLog("https://") shouldBe "<none>"
        hostForLog(":8096") shouldBe "<none>"
    }
}
