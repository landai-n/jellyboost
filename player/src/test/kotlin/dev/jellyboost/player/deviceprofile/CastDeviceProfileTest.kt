package dev.jellyboost.player.deviceprofile

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CastDeviceProfile].
 *
 * Like [DeviceProfileBuilderTest], what is under test is the contract with the *server*: every
 * assertion here is about the conclusion the server draws, because the cost of getting one wrong is
 * a television showing a black screen with no error anywhere on this device.
 */
class CastDeviceProfileTest {
    private val profile = CastDeviceProfile.build()

    @Test
    fun `is named so the dashboard tells a cast session apart from a local one`() {
        profile.name shouldBe "Jellyboost Chromecast"
        profile.name shouldNotBe DeviceProfileBuilder.PROFILE_NAME
    }

    @Test
    fun `direct-plays H264 with AAC or MP3 in mp4`() {
        val mp4 = profile.directPlayProfiles.single { it.container == "mp4" && it.type == DlnaProfileType.VIDEO }

        mp4.videoCodec shouldBe "h264"
        mp4.audioCodec!! shouldContain "aac"
        mp4.audioCodec!! shouldContain "mp3"
    }

    @Test
    fun `direct-plays VP8 and VP9 in webm`() {
        val webm = profile.directPlayProfiles.single { it.container == "webm" && it.type == DlnaProfileType.VIDEO }

        webm.videoCodec!! shouldContain "vp8"
        webm.videoCodec!! shouldContain "vp9"
    }

    @Test
    fun `claims nothing a receiver's baseline decoder cannot handle`() {
        val videoCodecs = profile.directPlayProfiles.filter { it.type == DlnaProfileType.VIDEO }.map { it.videoCodec }

        // HEVC and AV1 are the two the plan explicitly defers: a receiver's capability flags do not
        // report them reliably, and claiming one that is missing costs the user the film.
        videoCodecs.joinToString() shouldNotContain "hevc"
        videoCodecs.joinToString() shouldNotContain "av1"
        // mkv is not on the list even though most receivers demux it; the ones that do not fail
        // silently, and a remux costs the server almost nothing.
        profile.directPlayProfiles.none { it.container == "mkv" } shouldBe true
    }

    @Test
    fun `caps a direct-played H264 stream at High level 4-2 and 1080p`() {
        val h264 = profile.codecProfiles.single { it.codec == "h264" }
        val conditions = h264.conditions.associateBy { it.property }

        conditions[ProfileConditionValue.VIDEO_PROFILE].shouldNotBeNull().value shouldBe
            "high|main|baseline|constrained baseline"
        conditions[ProfileConditionValue.VIDEO_LEVEL].shouldNotBeNull().let {
            it.condition shouldBe ProfileConditionType.LESS_THAN_EQUAL
            it.value shouldBe CastDeviceProfile.MAX_H264_LEVEL
        }
        conditions[ProfileConditionValue.WIDTH].shouldNotBeNull().value shouldBe "1920"
        conditions[ProfileConditionValue.HEIGHT].shouldNotBeNull().value shouldBe "1080"
    }

    @Test
    fun `transcodes to H264 and AAC as HLS with ts segments`() {
        val video = profile.transcodingProfiles.single { it.type == DlnaProfileType.VIDEO }

        video.protocol shouldBe MediaStreamProtocol.HLS
        video.container shouldBe "ts"
        video.videoCodec shouldBe "h264"
        // One audio codec, not the local profile's long list: the Cast receiver's own player is what
        // consumes this, and AAC is the one it decodes everywhere.
        video.audioCodec shouldBe "aac"
        // Device-measured on a real Chromecast Ultra: AAC above 2 channels fails with CAF error 104,
        // so the server is told to keep the transcode's audio to stereo.
        video.maxAudioChannels shouldBe "2"
    }

    @Test
    fun `caps a direct-played video's AAC track at stereo`() {
        val videoAudio = profile.codecProfiles.single { it.type == CodecType.VIDEO_AUDIO && it.codec == "aac" }
        val conditions = videoAudio.conditions.associateBy { it.property }

        conditions[ProfileConditionValue.AUDIO_CHANNELS].shouldNotBeNull().let {
            it.condition shouldBe ProfileConditionType.LESS_THAN_EQUAL
            it.value shouldBe "2"
        }
    }

    @Test
    fun `caps a direct-played audio-only file's AAC track at stereo`() {
        val audio = profile.codecProfiles.single { it.type == CodecType.AUDIO && it.codec == "aac" }
        val conditions = audio.conditions.associateBy { it.property }

        conditions[ProfileConditionValue.AUDIO_CHANNELS].shouldNotBeNull().let {
            it.condition shouldBe ProfileConditionType.LESS_THAN_EQUAL
            it.value shouldBe "2"
        }
    }

    @Test
    fun `asks for WebVTT and only WebVTT, so the server converts subrip rather than delivering it`() {
        profile.subtitleProfiles.map { it.format } shouldContainExactlyInAnyOrder listOf("vtt", "webvtt")
        profile.subtitleProfiles.forEach { it.method shouldBe SubtitleDeliveryMethod.EXTERNAL }
    }

    @Test
    fun `omits image subtitles entirely, which is what makes the server burn them in`() {
        // A format the profile does not mention cannot be delivered externally, and a Cast receiver
        // has no PGS renderer — burned in is the only way the user sees one at all.
        val formats = profile.subtitleProfiles.map { it.format }
        formats.none { it in listOf("pgssub", "pgs", "dvbsub", "dvdsub") } shouldBe true
    }

    @Test
    fun `declares no container profiles, since a conditionless one constrains nothing`() {
        profile.containerProfiles.shouldBeEmpty()
    }

    @Test
    fun `the quality picker's cap replaces the profile's own ceiling`() {
        CastDeviceProfile.build().maxStreamingBitrate shouldBe CastDeviceProfile.MAX_STREAMING_BITRATE
        CastDeviceProfile.build(maxStreamingBitrate = 4_000_000).maxStreamingBitrate shouldBe 4_000_000
    }

    @Test
    fun `capping the bitrate changes nothing else`() {
        val capped = CastDeviceProfile.build(maxStreamingBitrate = 4_000_000)

        capped.directPlayProfiles shouldBe profile.directPlayProfiles
        capped.transcodingProfiles shouldBe profile.transcodingProfiles
        capped.codecProfiles shouldBe profile.codecProfiles
        capped.subtitleProfiles shouldBe profile.subtitleProfiles
    }
}
