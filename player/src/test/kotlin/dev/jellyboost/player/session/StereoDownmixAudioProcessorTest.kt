package dev.jellyboost.player.session

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
class StereoDownmixAudioProcessorTest {
    private fun processor(surroundPresent: Boolean = false) = StereoDownmixAudioProcessor { surroundPresent }

    private fun pcm16(channels: Int) = AudioFormat(SAMPLE_RATE, channels, C.ENCODING_PCM_16BIT)

    /** Configures, applies the configuration, and folds one 16-bit frame. */
    private fun StereoDownmixAudioProcessor.fold16(
        channels: Int,
        vararg samples: Pair<Int, Short>,
    ): Pair<Short, Short> {
        configure(pcm16(channels))
        flush(StreamMetadata.DEFAULT)
        val frame = ShortArray(channels)
        samples.forEach { (channel, value) -> frame[channel] = value }
        val input = ByteBuffer.allocateDirect(channels * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        frame.forEach(input::putShort)
        input.flip()
        queueInput(input)
        val output = getOutput()
        output.remaining() shouldBe 2 * Short.SIZE_BYTES
        return output.getShort() to output.getShort()
    }

    @Test
    @DisplayName("5.1 16-bit with no surround output folds to 48 kHz 16-bit stereo")
    fun fiveOneFoldsToStereo() {
        val processor = processor()

        val output = processor.configure(pcm16(6))

        processor.isActive shouldBe true
        output shouldBe AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT)
    }

    @Test
    @DisplayName("5.1: front left lands on the left at half amplitude, nothing on the right")
    fun fiveOneFrontLeft() {
        val (left, right) = processor().fold16(6, 0 to 20_000)

        left shouldBe 10_000.toShort()
        right shouldBe 0.toShort()
    }

    @Test
    @DisplayName("5.1: the centre lands on both sides at 0.7071 times the headroom")
    fun fiveOneCentre() {
        val (left, right) = processor().fold16(6, 2 to 20_000)

        left.toInt().shouldBeBetween(7_070, 7_072)
        right.toInt().shouldBeBetween(7_070, 7_072)
    }

    @Test
    @DisplayName("stereo input leaves the processor inactive")
    fun stereoInactive() {
        val processor = processor()

        processor.configure(pcm16(2)) shouldBe AudioFormat.NOT_SET
        processor.isActive shouldBe false
    }

    @Test
    @DisplayName("5.1 with a surround output present leaves the processor inactive")
    fun surroundOutputInactive() {
        val processor = processor(surroundPresent = true)

        processor.configure(pcm16(6)) shouldBe AudioFormat.NOT_SET
        processor.isActive shouldBe false
    }

    @Test
    @DisplayName("6.1 and 7.1 fold to stereo, front left on the left at half amplitude")
    fun sixOneAndSevenOne() {
        for (channels in listOf(7, 8)) {
            val processor = processor()
            processor.configure(pcm16(channels)) shouldBe AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT)

            val (left, right) = processor.fold16(channels, 0 to 20_000)

            left shouldBe 10_000.toShort()
            right shouldBe 0.toShort()
        }
    }

    @Test
    @DisplayName("every count from 3 to 8: front left stays left and front right stays right")
    fun frontPairStaysOnItsSide() {
        for (channels in 3..8) {
            processor().fold16(channels, 0 to 20_000) shouldBe (10_000.toShort() to 0.toShort())
            processor().fold16(channels, 1 to 20_000) shouldBe (0.toShort() to 10_000.toShort())
        }
    }

    @Test
    @DisplayName("5.1, 6.1 and 7.1: the LFE lands on both sides at a quarter")
    fun lfeOnBothSides() {
        for (channels in 6..8) {
            processor().fold16(channels, 3 to 20_000) shouldBe (5_000.toShort() to 5_000.toShort())
        }
    }

    @Test
    @DisplayName("5.1: each back channel stays on its own side")
    fun fiveOneBackChannels() {
        val (backLeftL, backLeftR) = processor().fold16(6, 4 to 20_000)
        val (backRightL, backRightR) = processor().fold16(6, 5 to 20_000)

        backLeftL.toInt().shouldBeBetween(7_070, 7_072)
        backLeftR shouldBe 0.toShort()
        backRightL shouldBe 0.toShort()
        backRightR.toInt().shouldBeBetween(7_070, 7_072)
    }

    @Test
    @DisplayName("loud summed channels saturate rather than wrap")
    fun loudPassageSaturates() {
        val loud = Short.MAX_VALUE
        val (left, right) = processor().fold16(6, 0 to loud, 2 to loud, 3 to loud, 4 to loud)

        left shouldBe Short.MAX_VALUE
        right.toInt().shouldBeBetween(0, Short.MAX_VALUE.toInt())
    }

    /**
     * Pins the reason the processor spells its matrices out: if a Media3 upgrade fixes this, the
     * processor could go back to `createForConstantPower`.
     */
    @Test
    @DisplayName("Media3 1.9.0's own constant-power 5.1 matrix sends the centre to the left only")
    fun media3ConstantPowerIsTransposed() {
        val matrix = ChannelMixingMatrix.createForConstantPower(6, 2)

        matrix.getMixingCoefficient(2, 0) shouldBe (0.7071f plusOrMinus 1e-4f)
        matrix.getMixingCoefficient(2, 1) shouldBe 0f
        matrix.getMixingCoefficient(3, 1) shouldBe 1f
    }

    @Test
    @DisplayName("7.1: each surround pair stays on its own side")
    fun sevenOneSurroundSides() {
        // FL FR FC LFE BL BR SL SR
        processor().fold16(8, 4 to 20_000).second shouldBe 0.toShort()
        processor().fold16(8, 5 to 20_000).first shouldBe 0.toShort()
        processor().fold16(8, 6 to 20_000).second shouldBe 0.toShort()
        processor().fold16(8, 7 to 20_000).first shouldBe 0.toShort()
    }

    @Test
    @DisplayName("nine channels and mono leave the processor inactive without throwing")
    fun unsupportedChannelCounts() {
        for (channels in listOf(1, 9)) {
            val processor = processor()

            processor.configure(pcm16(channels)) shouldBe AudioFormat.NOT_SET
            processor.isActive shouldBe false
        }
    }

    @Test
    @DisplayName("float 5.1 is folded and keeps float encoding")
    fun floatFiveOne() {
        val processor = processor()

        processor.configure(AudioFormat(SAMPLE_RATE, 6, C.ENCODING_PCM_FLOAT)) shouldBe
            AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_FLOAT)
        processor.flush(StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(6 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        floatArrayOf(0.8f, 0f, 0f, 0f, 0f, 0f).forEach(input::putFloat)
        input.flip()
        processor.queueInput(input)
        val output = processor.getOutput()

        output.getFloat() shouldBe (0.4f plusOrMinus 1e-6f)
        output.getFloat() shouldBe (0f plusOrMinus 1e-6f)
    }

    @Test
    @DisplayName("a format the mixer cannot read (24-bit) leaves the processor inactive")
    fun unmixableEncodingInactive() {
        val processor = processor()

        processor.configure(AudioFormat(SAMPLE_RATE, 6, C.ENCODING_PCM_24BIT)) shouldBe AudioFormat.NOT_SET
        processor.isActive shouldBe false
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
