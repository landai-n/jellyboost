package dev.jellyboost.player.session

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(UnstableApi::class)
class PlayerAudioTrackBufferSizeTest {
    private fun sixChannelBufferBytes(minBufferSizeInBytes: Int): Int =
        PLAYER_AUDIO_TRACK_BUFFER_SIZE.getBufferSizeInBytes(
            minBufferSizeInBytes,
            C.ENCODING_PCM_16BIT,
            DefaultAudioSink.OUTPUT_MODE_PCM,
            SIX_CHANNEL_FRAME_BYTES,
            SAMPLE_RATE,
            Format.NO_VALUE,
            // maxAudioTrackPlaybackSpeed =
            1.0,
        )

    @Test
    @DisplayName("a six-channel track gets between 500 ms and 750 ms of buffer")
    fun sixChannelBufferWithinBounds() {
        // 62.5 ms of 6-channel 16-bit 48 kHz.
        sixChannelBufferBytes(minBufferSizeInBytes = 36_000).shouldBeBetween(FIVE_HUNDRED_MS, SEVEN_FIFTY_MS)
    }

    @Test
    @DisplayName("a tiny platform minimum still gets the 500 ms floor")
    fun tinyMinimumKeepsFloor() {
        sixChannelBufferBytes(minBufferSizeInBytes = 1_200).shouldBeBetween(FIVE_HUNDRED_MS, SEVEN_FIFTY_MS)
    }

    @Test
    @DisplayName("a large platform minimum is cut to the 750 ms ceiling")
    fun largeMinimumHitsCeiling() {
        sixChannelBufferBytes(minBufferSizeInBytes = 100_000) shouldBe SEVEN_FIFTY_MS
    }

    @Test
    @DisplayName("between floor and ceiling the platform minimum is multiplied by 8")
    fun midRangeMinimumIsMultiplied() {
        // 320 000 bytes, rounded up to whole 12-byte frames by Media3.
        sixChannelBufferBytes(minBufferSizeInBytes = 40_000) shouldBe 320_004
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val SIX_CHANNEL_FRAME_BYTES = 12
        const val FIVE_HUNDRED_MS = 288_000
        const val SEVEN_FIFTY_MS = 432_000
    }
}
