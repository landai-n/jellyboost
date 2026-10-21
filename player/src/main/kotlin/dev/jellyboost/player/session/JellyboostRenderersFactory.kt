package dev.jellyboost.player.session

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider

/**
 * The 250 ms default leaves a six-channel track one sub-second stall from an audible gap. 750 ms
 * stays the ceiling because the AudioFlinger start threshold is the full buffer.
 */
@UnstableApi
internal val PLAYER_AUDIO_TRACK_BUFFER_SIZE: DefaultAudioTrackBufferSizeProvider =
    DefaultAudioTrackBufferSizeProvider
        .Builder()
        .setMinPcmBufferDurationUs(500_000)
        .setMaxPcmBufferDurationUs(750_000)
        .setPcmBufferMultiplicationFactor(8)
        .build()

/** Only the audio sink differs from Media3's; it is shared by the platform and ffmpeg renderers. */
@UnstableApi
internal class JellyboostRenderersFactory(
    context: Context,
    private val surroundOutput: SurroundOutputDetector,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioTrackBufferSizeProvider(PLAYER_AUDIO_TRACK_BUFFER_SIZE)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(StereoDownmixAudioProcessor(surroundOutput)),
            ).build()
}
