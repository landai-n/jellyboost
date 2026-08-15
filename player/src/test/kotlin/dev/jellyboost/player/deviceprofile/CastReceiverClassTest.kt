package dev.jellyboost.player.deviceprofile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CastReceiverClass].
 *
 * The table is the contract: model names are the only capability signal a sender has, so which
 * name lands in which class is exactly the behaviour worth pinning — above all that a stranger
 * lands on the floor, because "unknown receiver keeps today's behaviour" is what makes shipping
 * an allowlist safe at all.
 */
class CastReceiverClassTest {
    @Test
    fun `the published 4K receivers classify as ultra`() {
        listOf(
            "Chromecast Ultra",
            "Chromecast with Google TV",
            "Chromecast Google TV",
            "Google TV Streamer",
            "SHIELD Android TV",
            "SHIELD TV",
            "NVIDIA SHIELD",
        ).forEach { model ->
            CastReceiverClass.fromModelName(model) shouldBe CastReceiverClass.ULTRA_4K
        }
    }

    @Test
    fun `matching ignores case and surrounding whitespace`() {
        CastReceiverClass.fromModelName("  chromecast ULTRA ") shouldBe CastReceiverClass.ULTRA_4K
    }

    @Test
    fun `the 1080p Google TV dongle gets HEVC but not 4K`() {
        CastReceiverClass.fromModelName("Chromecast HD") shouldBe CastReceiverClass.HEVC_1080P
    }

    @Test
    fun `the original dongles and every stranger land on the legacy floor`() {
        // Bare "Chromecast" covers three hardware generations, all 1080p H.264 — it must never
        // match the 4K set by prefix or substring.
        CastReceiverClass.fromModelName("Chromecast") shouldBe CastReceiverClass.LEGACY_1080P
        CastReceiverClass.fromModelName("Some Future Receiver") shouldBe CastReceiverClass.LEGACY_1080P
        CastReceiverClass.fromModelName(null) shouldBe CastReceiverClass.LEGACY_1080P
    }
}
