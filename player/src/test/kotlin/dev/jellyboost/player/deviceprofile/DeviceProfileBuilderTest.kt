package dev.jellyboost.player.deviceprofile

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DeviceProfileBuilder].
 *
 * What is really under test is the contract with the *server*: this profile is what decides
 * between direct play and a transcode for every item, so the assertions are about what the server
 * would conclude from it, not about the object's shape.
 */
class DeviceProfileBuilderTest {
    private var probeCalls = 0

    private fun builder(codecs: DeviceCodecs) =
        DeviceProfileBuilder(
            MediaCodecProbe {
                probeCalls++
                codecs
            },
        )

    @Test
    fun `only advertises video codecs the device can decode`() {
        val profile =
            builder(DeviceCodecs(videoCodecs = setOf("h264"), audioCodecs = setOf("aac")))
                .getDeviceProfile()

        val mkv =
            profile.directPlayProfiles.first { it.container == "mkv" && it.type == DlnaProfileType.VIDEO }
        mkv.videoCodec!! shouldBe "h264"
        mkv.videoCodec!! shouldNotContain "hevc"
    }

    @Test
    fun `advertises the ffmpeg extension's audio codecs even when the device does not report them`() {
        // The device knows nothing about AC3/DTS; the bundled ffmpeg decoder does. Leaving them out
        // would transcode every surround-sound file on the server.
        val profile =
            builder(DeviceCodecs(videoCodecs = setOf("h264"), audioCodecs = emptySet()))
                .getDeviceProfile()

        val mkv =
            profile.directPlayProfiles.first { it.container == "mkv" && it.type == DlnaProfileType.VIDEO }
        mkv.audioCodec!! shouldContain "ac3"
        mkv.audioCodec!! shouldContain "dts"
        mkv.audioCodec!! shouldContain "truehd"
        // Not on the forced list and not reported by the device, so it must be absent.
        mkv.audioCodec!! shouldNotContain "vorbis"
    }

    @Test
    fun `drops a container entirely when nothing in it is playable`() {
        val profile =
            builder(DeviceCodecs(videoCodecs = setOf("vp8"), audioCodecs = emptySet()))
                .getDeviceProfile()

        // webm carries vp8, so it stays; flv only offers mpeg4/h264 video and mp3/aac audio, and
        // aac is force-advertised — so flv survives as an audio-only direct play profile.
        profile.directPlayProfiles.map { it.container } shouldContain "webm"
        profile.directPlayProfiles
            .none { it.container == "flv" && it.type == DlnaProfileType.VIDEO } shouldBe true
    }

    @Test
    fun `restricts a codec to the profiles the device's decoders advertise`() {
        val profile =
            builder(
                DeviceCodecs(
                    videoCodecs = setOf("h264"),
                    audioCodecs = setOf("aac"),
                    videoProfiles = mapOf("h264" to setOf("high", "main")),
                ),
            ).getDeviceProfile()

        val codecProfile = profile.codecProfiles.firstOrNull { it.codec == "h264" && it.container == null }
        codecProfile.shouldNotBeNull()
        codecProfile.conditions
            .single()
            .value!! shouldContain "high"
    }

    @Test
    fun `emits no codec profile when the device reports no profiles for a codec`() {
        val profile =
            builder(DeviceCodecs(videoCodecs = setOf("h264"), audioCodecs = setOf("aac")))
                .getDeviceProfile()

        // An empty EQUALS_ANY condition would forbid every profile, i.e. forbid direct play.
        profile.codecProfiles.none { it.codec == "h264" } shouldBe true
    }

    @Test
    fun `caps a codec at the largest frame the device's decoders accept`() {
        // Without this the server hands a 4K file to a decoder that tops out at 1440p, and
        // ExoPlayer falls back to software decode instead of refusing.
        val profile =
            builder(
                DeviceCodecs(
                    videoCodecs = setOf("h264"),
                    audioCodecs = setOf("aac"),
                    videoProfiles = mapOf("h264" to setOf("high", "main")),
                    videoMaxSizes = mapOf("h264" to VideoMaxSize(width = 2560, height = 1440)),
                ),
            ).getDeviceProfile()

        val codecProfile = profile.codecProfiles.firstOrNull { it.codec == "h264" && it.container == null }
        codecProfile.shouldNotBeNull()
        codecProfile.conditions.map { it.property } shouldBe
            listOf(
                ProfileConditionValue.VIDEO_PROFILE,
                ProfileConditionValue.WIDTH,
                ProfileConditionValue.HEIGHT,
            )
        val width = codecProfile.conditions.single { it.property == ProfileConditionValue.WIDTH }
        width.condition shouldBe ProfileConditionType.LESS_THAN_EQUAL
        width.value!! shouldBe "2560"
        val height = codecProfile.conditions.single { it.property == ProfileConditionValue.HEIGHT }
        height.condition shouldBe ProfileConditionType.LESS_THAN_EQUAL
        height.value!! shouldBe "1440"
    }

    @Test
    fun `emits exactly one containerless profile per codec`() {
        // Container-bound codec profiles are dropped by the server when sizing a Dolby Vision
        // transcode, so containerless-and-deduplicated is load-bearing, not tidiness.
        val profile =
            builder(
                DeviceCodecs(
                    videoCodecs = setOf("h264", "hevc"),
                    audioCodecs = setOf("aac"),
                    videoProfiles = mapOf("h264" to setOf("high"), "hevc" to setOf("main")),
                    videoMaxSizes = mapOf("h264" to VideoMaxSize(width = 2560, height = 1440)),
                ),
            ).getDeviceProfile()

        profile.codecProfiles.count { it.codec == "h264" } shouldBe 1
        profile.codecProfiles.count { it.codec == "hevc" } shouldBe 1
        profile.codecProfiles.all { it.container == null } shouldBe true
    }

    @Test
    fun `caps a codec whose decoder profiles are unknown at its frame size alone`() {
        val profile =
            builder(
                DeviceCodecs(
                    videoCodecs = setOf("hevc"),
                    audioCodecs = setOf("aac"),
                    videoMaxSizes = mapOf("hevc" to VideoMaxSize(width = 2560, height = 1440)),
                ),
            ).getDeviceProfile()

        val codecProfile = profile.codecProfiles.firstOrNull { it.codec == "hevc" && it.container == null }
        codecProfile.shouldNotBeNull()
        codecProfile.conditions.map { it.property } shouldBe
            listOf(ProfileConditionValue.WIDTH, ProfileConditionValue.HEIGHT)
        codecProfile.conditions.map { it.value } shouldBe listOf("2560", "1440")
    }

    @Test
    fun `emits no size condition for a codec whose decoders do not report a size`() {
        val profile =
            builder(
                DeviceCodecs(
                    videoCodecs = setOf("h264"),
                    audioCodecs = setOf("aac"),
                    videoProfiles = mapOf("h264" to setOf("high")),
                ),
            ).getDeviceProfile()

        val codecProfile = profile.codecProfiles.firstOrNull { it.codec == "h264" && it.container == null }
        codecProfile.shouldNotBeNull()
        codecProfile.conditions.map { it.property } shouldBe listOf(ProfileConditionValue.VIDEO_PROFILE)
    }

    @Test
    fun `claims ASS and SSA subtitles by default`() {
        val profile = builder(codecs()).getDeviceProfile()

        profile.subtitleProfiles.map { it.format } shouldContain "ass"
        profile.subtitleProfiles.map { it.format } shouldContain "ssa"
    }

    @Test
    fun `drops ASS and SSA when direct play of them is turned off`() {
        val profile = builder(codecs()).getDeviceProfile(directPlayAss = false)

        profile.subtitleProfiles.map { it.format } shouldNotContain "ass"
        profile.subtitleProfiles.map { it.format } shouldNotContain "ssa"
        // The rest of the subtitle support is untouched.
        profile.subtitleProfiles.map { it.format } shouldContain "srt"
    }

    @Test
    fun `offers both embedded and external delivery for text subtitles`() {
        val profile = builder(codecs()).getDeviceProfile()

        profile.subtitleProfiles
            .any { it.format == "srt" && it.method == SubtitleDeliveryMethod.EMBED } shouldBe true
        profile.subtitleProfiles
            .any { it.format == "srt" && it.method == SubtitleDeliveryMethod.EXTERNAL } shouldBe true
    }

    @Test
    fun `never offers HLS subtitle delivery unless it is asked for`() {
        // The everyday profile is the one a direct play is negotiated against, and there the HLS
        // shape would be actively harmful — see the variant below.
        val profile = builder(codecs()).getDeviceProfile()

        profile.subtitleProfiles.none { it.method == SubtitleDeliveryMethod.HLS } shouldBe true
    }

    @Test
    fun `the transcode variant swaps external delivery for one HLS rendition profile`() {
        val profile = builder(codecs()).getDeviceProfile(hlsTextSubtitles = true)

        profile.subtitleProfiles.filter { it.method == SubtitleDeliveryMethod.HLS } shouldBe
            listOf(SubtitleProfile(format = "vtt", method = SubtitleDeliveryMethod.HLS))
        // Not "as well as": offered both, the server picks External every time (10.11.11), so the
        // only way to be given renditions is to leave it nothing else to choose.
        profile.subtitleProfiles.none { it.method == SubtitleDeliveryMethod.EXTERNAL } shouldBe true
    }

    @Test
    fun `the transcode variant leaves embedded delivery exactly as it was`() {
        // A subtitle that travels inside the container has never drifted; only the side-loaded ones
        // were being fixed, and the embedded half is what keeps a direct-playable mkv direct-played.
        val subject = builder(codecs())

        subject.getDeviceProfile(hlsTextSubtitles = true).subtitleProfiles.filter {
            it.method == SubtitleDeliveryMethod.EMBED
        } shouldBe
            subject.getDeviceProfile().subtitleProfiles.filter { it.method == SubtitleDeliveryMethod.EMBED }
    }

    @Test
    fun `the knob turned off is the profile that was always sent`() {
        val subject = builder(codecs())

        subject.getDeviceProfile(hlsTextSubtitles = false) shouldBe subject.getDeviceProfile()
    }

    @Test
    fun `asks for h264 over HLS when the server has to transcode`() {
        val profile = builder(codecs()).getDeviceProfile()

        val video = profile.transcodingProfiles.filter { it.type == DlnaProfileType.VIDEO }
        video.map { it.container } shouldBe listOf("ts", "mkv")
        video.forEach { transcoding ->
            transcoding.videoCodec shouldBe "h264"
            transcoding.protocol shouldBe MediaStreamProtocol.HLS
        }
    }

    @Test
    fun `defaults to the 120 Mbps streaming ceiling`() {
        builder(codecs()).getDeviceProfile().maxStreamingBitrate shouldBe 120_000_000
    }

    @Test
    fun `applies a bitrate cap from the quality picker`() {
        // A cap below the file's bitrate is what makes the server transcode — the mechanism the
        // milestone's forced-transcode check relies on.
        val profile = builder(codecs()).getDeviceProfile(maxStreamingBitrate = 3_000_000)

        profile.maxStreamingBitrate shouldBe 3_000_000
        // Everything else must be identical, or the cap would change the negotiation twice over.
        profile.directPlayProfiles shouldBe builder(codecs()).getDeviceProfile().directPlayProfiles
    }

    @Test
    fun `probes the hardware once, however many profiles are asked for`() {
        val subject = builder(codecs())

        subject.getDeviceProfile()
        subject.getDeviceProfile(maxStreamingBitrate = 3_000_000)
        subject.getDeviceProfile(directPlayAss = false)

        probeCalls shouldBe 1
    }

    private fun codecs() =
        DeviceCodecs(
            videoCodecs = setOf("h264", "hevc"),
            audioCodecs = setOf("aac", "mp3"),
        )
}
