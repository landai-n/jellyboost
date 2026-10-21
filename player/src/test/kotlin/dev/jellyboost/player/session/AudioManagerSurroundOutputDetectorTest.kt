package dev.jellyboost.player.session

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class AudioManagerSurroundOutputDetectorTest {
    private fun device(
        type: Int,
        vararg channelCounts: Int,
    ): AudioDeviceInfo =
        mockk {
            every { this@mockk.type } returns type
            every { this@mockk.channelCounts } returns channelCounts
        }

    private fun detectorWith(vararg outputs: AudioDeviceInfo): AudioManagerSurroundOutputDetector {
        val audioManager = mockk<AudioManager>()
        every { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } returns outputs.toList().toTypedArray()
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager
        return AudioManagerSurroundOutputDetector(context)
    }

    @Test
    @DisplayName("HDMI reporting six channels is surround")
    fun hdmiSixChannels() {
        detectorWith(device(AudioDeviceInfo.TYPE_HDMI, 2, 6)).surroundOutputPresent() shouldBe true
    }

    @Test
    @DisplayName("HDMI that reports no channel counts is assumed capable")
    fun hdmiUnknownCounts() {
        detectorWith(device(AudioDeviceInfo.TYPE_HDMI)).surroundOutputPresent() shouldBe true
    }

    @Test
    @DisplayName("HDMI reporting stereo only is not surround")
    fun hdmiStereoOnly() {
        detectorWith(device(AudioDeviceInfo.TYPE_HDMI, 1, 2)).surroundOutputPresent() shouldBe false
    }

    @Test
    @DisplayName("the built-in speaker and Bluetooth A2DP are never surround")
    fun speakerAndBluetooth() {
        detectorWith(
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 8),
        ).surroundOutputPresent() shouldBe false
    }

    @Test
    @DisplayName("a USB headset is never surround")
    fun usbHeadset() {
        detectorWith(device(AudioDeviceInfo.TYPE_USB_HEADSET, 8)).surroundOutputPresent() shouldBe false
    }

    @Test
    @DisplayName("a dock with a multichannel sink alongside the speaker is surround")
    fun dockAlongsideSpeaker() {
        detectorWith(
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, 2),
            device(AudioDeviceInfo.TYPE_DOCK, 2, 6, 8),
        ).surroundOutputPresent() shouldBe true
    }

    @Test
    @DisplayName("no audio service means no surround output")
    fun noAudioService() {
        val context = mockk<Context>()
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns null

        AudioManagerSurroundOutputDetector(context).surroundOutputPresent() shouldBe false
    }

    @Test
    @DisplayName("HDMI eARC counts only on API 31 and later")
    fun hdmiEarcGuarded() {
        isSurroundCapable(AudioDeviceInfo.TYPE_HDMI_EARC, intArrayOf(6), sdkInt = 31) shouldBe true
        isSurroundCapable(AudioDeviceInfo.TYPE_HDMI_EARC, intArrayOf(6), sdkInt = 30) shouldBe false
    }

    @Test
    @DisplayName("wired headsets and Bluetooth LE / SCO are never surround")
    fun otherPersonalOutputs() {
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        ).forEach { type -> isSurroundCapable(type, intArrayOf(), sdkInt = 36) shouldBe false }
    }
}
