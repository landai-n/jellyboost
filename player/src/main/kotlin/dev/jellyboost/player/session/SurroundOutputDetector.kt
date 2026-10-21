package dev.jellyboost.player.session

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Whether an output media plays through can render more than two channels: the devices media is
 * routed to on API 33+, every connected output before that.
 *
 * Routing is read when a track is configured, so a dock plugged in mid-film takes effect on the
 * next prepare or track change.
 */
internal fun interface SurroundOutputDetector {
    fun surroundOutputPresent(): Boolean
}

internal class AudioManagerSurroundOutputDetector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SurroundOutputDetector {
        override fun surroundOutputPresent(): Boolean {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            return mediaOutputIsSurround(audioManager, Build.VERSION.SDK_INT, ::mediaAttributes)
        }
    }

private fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

// Lint cannot follow a guard on a parameter; `sdkInt` is a parameter so the JVM tests can reach it.
@SuppressLint("NewApi")
internal fun mediaOutputIsSurround(
    audioManager: AudioManager,
    sdkInt: Int,
    mediaAttributes: () -> AudioAttributes,
): Boolean {
    val outputs =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            audioManager.getAudioDevicesForAttributes(mediaAttributes())
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).asList()
        }
    return outputs.any { isSurroundCapable(it.type, it.channelCounts, sdkInt) }
}

/** An empty [channelCounts] means the device did not say, which is read as capable. */
internal fun isSurroundCapable(
    type: Int,
    channelCounts: IntArray,
    sdkInt: Int,
): Boolean = type in multichannelOutputTypes(sdkInt) && (channelCounts.isEmpty() || channelCounts.any { it > 2 })

// Lint cannot follow a guard on a parameter; `sdkInt` is a parameter so the JVM tests can reach it.
@SuppressLint("InlinedApi")
private fun multichannelOutputTypes(sdkInt: Int): Set<Int> =
    buildSet {
        add(AudioDeviceInfo.TYPE_HDMI)
        add(AudioDeviceInfo.TYPE_HDMI_ARC)
        add(AudioDeviceInfo.TYPE_USB_DEVICE)
        add(AudioDeviceInfo.TYPE_DOCK)
        add(AudioDeviceInfo.TYPE_LINE_DIGITAL)
        add(AudioDeviceInfo.TYPE_AUX_LINE)
        if (sdkInt >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_HDMI_EARC)
    }
