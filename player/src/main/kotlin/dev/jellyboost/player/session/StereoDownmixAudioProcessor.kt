package dev.jellyboost.player.session

import androidx.media3.common.audio.AudioMixingUtil
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Folds 3–8 channel PCM to stereo in-app whenever no output can render it as surround.
 *
 * Every coefficient carries a 0.5 headroom: the summed channels would otherwise saturate 16-bit
 * output on loud passages, and the platform's own fold applies a comparable gain. A format it
 * cannot fold leaves it inactive rather than throwing, because a sink configuration failure is a
 * fatal playback error.
 */
@UnstableApi
internal class StereoDownmixAudioProcessor(
    private val surroundOutput: SurroundOutputDetector,
) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val foldable =
            AudioMixingUtil.canMix(inputAudioFormat) &&
                inputAudioFormat.channelCount in FOLDS &&
                !surroundOutput.surroundOutputPresent()
        return if (foldable) {
            AudioFormat(inputAudioFormat.sampleRate, STEREO, inputAudioFormat.encoding)
        } else {
            AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val matrix = FOLDS.getValue(inputAudioFormat.channelCount)
        val framesToMix = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val outputBuffer = replaceOutputBuffer(framesToMix * outputAudioFormat.bytesPerFrame)
        AudioMixingUtil.mix(
            inputBuffer,
            inputAudioFormat,
            outputBuffer,
            outputAudioFormat,
            matrix,
            framesToMix,
            // accumulate =
            false,
            // clipFloatOutput =
            true,
        )
        outputBuffer.flip()
    }

    /**
     * Media3's constant-power coefficients, spelled out per output channel rather than taken from
     * `ChannelMixingMatrix.createForConstantPower`: in 1.9.0 that stores its stereo rows
     * output-major while `AudioMixingUtil.mix` reads them input-major, which sends the centre to
     * the left only and the LFE to the right at full scale.
     */
    private companion object {
        const val STEREO = 2
        const val HEADROOM = 0.5f
        const val FRONT = 1.0f
        const val SIDE = 0.7071f
        const val LFE = 0.5f

        /**
         * Left and right rows, following ffmpeg's default layout for each count: 2.1 at three
         * channels is folded as 3.0, which is harmless. A count alone cannot tell 6.1 back from
         * 6.1 side, so seven channels assume ffmpeg's default 6.1.
         */
        val ROWS: List<Pair<FloatArray, FloatArray>> =
            listOf(
                // FL FR FC
                floatArrayOf(FRONT, 0f, SIDE) to floatArrayOf(0f, FRONT, SIDE),
                // FL FR FC BC (ffmpeg's 4.0; quad folds its rears to a phantom centre)
                floatArrayOf(FRONT, 0f, SIDE, SIDE) to floatArrayOf(0f, FRONT, SIDE, SIDE),
                // FL FR FC SL SR
                floatArrayOf(FRONT, 0f, SIDE, SIDE, 0f) to floatArrayOf(0f, FRONT, SIDE, 0f, SIDE),
                // FL FR FC LFE BL BR
                floatArrayOf(FRONT, 0f, SIDE, LFE, SIDE, 0f) to floatArrayOf(0f, FRONT, SIDE, LFE, 0f, SIDE),
                // FL FR FC LFE BC SL SR
                floatArrayOf(FRONT, 0f, SIDE, LFE, SIDE, SIDE, 0f) to
                    floatArrayOf(0f, FRONT, SIDE, LFE, SIDE, 0f, SIDE),
                // FL FR FC LFE BL BR SL SR
                floatArrayOf(FRONT, 0f, SIDE, LFE, SIDE, 0f, SIDE, 0f) to
                    floatArrayOf(0f, FRONT, SIDE, LFE, 0f, SIDE, 0f, SIDE),
            )

        val FOLDS: Map<Int, ChannelMixingMatrix> by lazy {
            ROWS.associate { (left, right) -> left.size to foldOf(left, right) }
        }

        fun foldOf(
            left: FloatArray,
            right: FloatArray,
        ): ChannelMixingMatrix =
            ChannelMixingMatrix(
                left.size,
                STEREO,
                FloatArray(left.size * STEREO) { if (it % STEREO == 0) left[it / STEREO] else right[it / STEREO] },
            ).scaleBy(HEADROOM)
    }
}
