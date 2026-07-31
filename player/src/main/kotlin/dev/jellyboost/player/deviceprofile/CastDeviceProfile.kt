package dev.jellyboost.player.deviceprofile

import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

/**
 * The `DeviceProfile` the server negotiates a **cast** session against.
 *
 * [DeviceProfileBuilder] cannot serve here and it is not a matter of tuning: that profile is built
 * from this device's `MediaCodecList`, and while casting the bytes are decoded by a receiver on the
 * other side of the room. Advertising the tablet's decoders for a Chromecast is how a file that
 * plays perfectly in the hand becomes a black television screen.
 *
 * So this one is static, pure, and deliberately conservative — the intersection every Cast receiver
 * since the first-generation dongle satisfies (docs/notes/chromecast-m12-plan.md, key decision 2):
 * H.264 High up to level 4.2 and 1080p with AAC or MP3 in `mp4`, VP8/VP9 in `webm`, and an HLS
 * transcode to H.264 + AAC in `ts` segments for everything else. Detecting a particular receiver's
 * 4K/HEVC/AC3 support is explicitly deferred: `CastDevice`'s capability flags do not report it
 * reliably, a wrong guess costs the user the film rather than some quality, and the quality picker
 * already gives back the control this costs.
 *
 * The whole object is a constant; [build] only stamps the quality picker's cap onto it.
 */
object CastDeviceProfile {
    /** Shown next to the session in the server's Dashboard → Devices, beside the local "Jellyboost". */
    const val PROFILE_NAME: String = "Jellyboost Chromecast"

    /**
     * Builds the profile to send with a cast `PlaybackInfo` request.
     *
     * @param maxStreamingBitrate cap from the quality picker; `null` keeps the profile's own
     *   ceiling. Lowering it below the file's bitrate is what makes the server transcode, exactly
     *   as it does for local playback.
     */
    fun build(maxStreamingBitrate: Int? = null): DeviceProfile =
        when (maxStreamingBitrate) {
            null -> PROFILE
            else -> PROFILE.copy(maxStreamingBitrate = maxStreamingBitrate)
        }

    /**
     * Deliberately no `containerProfiles`.
     *
     * [DeviceProfileBuilder] emits one per container with an empty condition list, which constrains
     * nothing: the containers a receiver accepts are already named by [DIRECT_PLAY_PROFILES], and
     * repeating them as conditionless container profiles would only be more of the profile for the
     * server to walk.
     */
    private val PROFILE: DeviceProfile by lazy {
        DeviceProfile(
            name = PROFILE_NAME,
            directPlayProfiles = DIRECT_PLAY_PROFILES,
            transcodingProfiles = TRANSCODING_PROFILES,
            containerProfiles = emptyList(),
            codecProfiles = CODEC_PROFILES,
            subtitleProfiles = SUBTITLE_PROFILES,
            maxStreamingBitrate = MAX_STREAMING_BITRATE,
            maxStaticBitrate = MAX_STATIC_BITRATE,
            musicStreamingTranscodingBitrate = MAX_MUSIC_TRANSCODING_BITRATE,
        )
    }

    /** The same ceiling the local profile takes from jellyfin-web's `browserDeviceProfile.js`. */
    const val MAX_STREAMING_BITRATE: Int = 120_000_000
    private const val MAX_STATIC_BITRATE = 100_000_000
    private const val MAX_MUSIC_TRANSCODING_BITRATE = 384_000

    /** The one H.264 level every Cast receiver decodes, expressed the way the server compares it. */
    const val MAX_H264_LEVEL: String = "42"
    private const val MAX_WIDTH = "1920"
    private const val MAX_HEIGHT = "1080"

    /**
     * What the receiver plays untouched.
     *
     * `mkv` is absent on purpose even though most receivers demux it: the ones that do not fail
     * silently, and a remux to `mp4` costs the server nothing next to the re-encode a wrong guess
     * would eventually force anyway.
     */
    private val DIRECT_PLAY_PROFILES =
        listOf(
            DirectPlayProfile(
                type = DlnaProfileType.VIDEO,
                container = "mp4",
                videoCodec = "h264",
                audioCodec = "aac,mp3",
            ),
            DirectPlayProfile(
                type = DlnaProfileType.VIDEO,
                container = "webm",
                videoCodec = "vp8,vp9",
                audioCodec = "vorbis,opus",
            ),
            DirectPlayProfile(type = DlnaProfileType.AUDIO, container = "mp4", audioCodec = "aac"),
            DirectPlayProfile(type = DlnaProfileType.AUDIO, container = "mp3", audioCodec = "mp3"),
        )

    /**
     * The ceiling on a direct-played H.264 stream.
     *
     * Without it "H.264 in mp4" would also claim High 10, 4:2:2 and 4K files, none of which a Cast
     * receiver's baseline decoder touches — and the server, having been told they are fine, hands
     * them over rather than transcoding.
     */
    private val CODEC_PROFILES =
        listOf(
            CodecProfile(
                type = CodecType.VIDEO,
                codec = "h264",
                container = "mp4",
                applyConditions = emptyList(),
                conditions =
                    listOf(
                        condition(
                            ProfileConditionType.EQUALS_ANY,
                            ProfileConditionValue.VIDEO_PROFILE,
                            "high|main|baseline|constrained baseline",
                        ),
                        condition(
                            ProfileConditionType.LESS_THAN_EQUAL,
                            ProfileConditionValue.VIDEO_LEVEL,
                            MAX_H264_LEVEL,
                        ),
                        condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.WIDTH, MAX_WIDTH),
                        condition(ProfileConditionType.LESS_THAN_EQUAL, ProfileConditionValue.HEIGHT, MAX_HEIGHT),
                    ),
            ),
        )

    /**
     * What we ask the server to transcode *to*.
     *
     * HLS with `ts` segments rather than the local profile's wide audio list: the Cast Application
     * Framework's own player is what consumes this, and it decodes H.264 + AAC in MPEG-TS
     * everywhere. Verified against the dev server (2026-07-31): the returned `TranscodingUrl` is a
     * `master.m3u8` with `SegmentContainer=ts`.
     */
    private val TRANSCODING_PROFILES =
        listOf(
            TranscodingProfile(
                type = DlnaProfileType.VIDEO,
                container = "ts",
                videoCodec = "h264",
                audioCodec = "aac",
                protocol = MediaStreamProtocol.HLS,
                conditions = emptyList(),
            ),
            TranscodingProfile(
                type = DlnaProfileType.AUDIO,
                container = "mp3",
                videoCodec = "",
                audioCodec = "mp3",
                protocol = MediaStreamProtocol.HTTP,
                conditions = emptyList(),
            ),
        )

    /**
     * WebVTT, and nothing else.
     *
     * The format list is not a statement about the *source* — it decides what the server converts
     * a text subtitle **into** before handing over a delivery URL. Declaring `srt`/`subrip` here
     * makes it deliver `Stream.subrip`, which a Cast receiver cannot parse; with only `vtt` declared
     * the very same `subrip` stream comes back as `Stream.vtt` (probed against the dev server,
     * 2026-07-31). `CastSpecMapper` relies on that: every side-loaded cast track is WebVTT.
     *
     * Image subtitles (PGS, DVB) are omitted altogether rather than declared. A format the profile
     * does not mention cannot be delivered externally, so the server burns it into the video — which
     * is the only way a receiver with no subtitle renderer of its own can show one.
     */
    private val SUBTITLE_PROFILES =
        listOf("vtt", "webvtt").map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EXTERNAL) }

    private fun condition(
        condition: ProfileConditionType,
        property: ProfileConditionValue,
        value: String,
    ): ProfileCondition =
        ProfileCondition(
            condition = condition,
            property = property,
            value = value,
            isRequired = false,
        )
}
