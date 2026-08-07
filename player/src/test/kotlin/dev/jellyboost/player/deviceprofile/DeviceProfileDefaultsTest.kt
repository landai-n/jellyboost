package dev.jellyboost.player.deviceprofile

import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DeviceProfileDefaults] (DUP-14) — the ceilings [DeviceProfileBuilder] and
 * [CastDeviceProfile] both send the server.
 */
class DeviceProfileDefaultsTest {
    @Test
    fun `both device profiles land on the same server ceilings`() {
        val local = DeviceProfileBuilder(mediaCodecProbe()).getDeviceProfile()
        val cast = CastDeviceProfile.build()

        local.maxStreamingBitrate shouldBe DeviceProfileDefaults.MAX_STREAMING_BITRATE
        local.maxStaticBitrate shouldBe DeviceProfileDefaults.MAX_STATIC_BITRATE
        local.musicStreamingTranscodingBitrate shouldBe DeviceProfileDefaults.MAX_MUSIC_TRANSCODING_BITRATE

        cast.maxStreamingBitrate shouldBe DeviceProfileDefaults.MAX_STREAMING_BITRATE
        cast.maxStaticBitrate shouldBe DeviceProfileDefaults.MAX_STATIC_BITRATE
        cast.musicStreamingTranscodingBitrate shouldBe DeviceProfileDefaults.MAX_MUSIC_TRANSCODING_BITRATE
    }

    @Test
    fun `a condition is always advisory, never required`() {
        val condition =
            DeviceProfileDefaults.condition(
                ProfileConditionType.LESS_THAN_EQUAL,
                ProfileConditionValue.WIDTH,
                "1920",
            )

        condition.condition shouldBe ProfileConditionType.LESS_THAN_EQUAL
        condition.property shouldBe ProfileConditionValue.WIDTH
        condition.value shouldBe "1920"
        condition.isRequired shouldBe false
    }

    /** A probe reporting no decoders at all — this test only cares about the profile's ceilings. */
    private fun mediaCodecProbe(): MediaCodecProbe = MediaCodecProbe { DeviceCodecs() }
}
