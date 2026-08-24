package dev.jellyboost.player.deviceprofile

import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
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
 * So this one is static, pure, and deliberately conservative — the floor is the intersection every
 * Cast receiver since the first-generation dongle satisfies: H.264 High up to level 4.2 and 1080p
 * with AAC or MP3 in `mp4`, VP8/VP9 in `webm`, and an HLS transcode to H.264 + AAC in `ts` segments
 * for everything else. On top of that floor, a receiver classified HEVC-capable by
 * [CastReceiverClass] (model-name allowlist — the only signal a sender has; see that class's doc)
 * additionally direct-plays HEVC Main/Main 10 in `mp4` up to its class's resolution ceiling. Direct
 * play only:
 * the transcode target is identical in every class, because CAF's TS demuxer is H.264-only and the
 * fMP4 segments HEVC would need were device-measured broken on the reference Ultra (below).
 * H.264 itself stays at the 1080p floor everywhere — correct even for the Ultra, whose published
 * 4K decode is HEVC/VP9 only. An unknown model changes nothing at all.
 *
 * Audio is capped at **stereo AAC**, and that cap is device-measured rather than a guess: on a real
 * Chromecast Ultra (Default Media Receiver, CC1AD845) every stream whose audio was AAC with more
 * than 2 channels failed with CAF `detailedErrorCode: 104` (`MEDIA_SRC_NOT_SUPPORTED`) — in HLS-ts
 * *and* progressive mp4 alike, so the container was never the variable. AC3/EAC3 5.1 passthrough
 * also failed (`LOAD_FAILED`), and HLS-fMP4 (`SegmentContainer=mp4`) does not work at all on this
 * receiver at either channel count — it accepts the load but never opens a media session, so it is
 * not a fallback worth adopting. Stereo AAC is the one combination that played in every cell of that
 * matrix, which is why it is the ceiling here rather than a per-receiver detail — in **every**
 * receiver class, including the 4K ones: the audio floor is a measured fact about the Default
 * Media Receiver, not spec-sheet conservatism, so relaxing it per model (for a receiver that
 * *does* take 5.1) would need its own measurement on that hardware first.
 *
 * The profiles are constants, one per receiver class; [build] picks one and stamps the quality
 * picker's cap onto it.
 */
internal object CastDeviceProfile {
    /** Shown next to the session in the server's Dashboard → Devices, beside the local "Jellyboost". */
    const val PROFILE_NAME: String = "Jellyboost Chromecast"

    /**
     * Builds the profile to send with a cast `PlaybackInfo` request.
     *
     * @param maxStreamingBitrate cap from the quality picker; `null` keeps the profile's own
     *   ceiling. Lowering it below the file's bitrate is what makes the server transcode, exactly
     *   as it does for local playback.
     * @param receiver the connected receiver's [class][CastReceiverClass]. HEVC-capable classes
     *   gain HEVC **direct play** and nothing else — the transcode target and the audio ceilings
     *   are identical in every class, because those are bounded by CAF's TS demuxer and the
     *   measured stereo floor respectively, not by the receiver's video decoder.
     */
    fun build(
        maxStreamingBitrate: Int? = null,
        receiver: CastReceiverClass = CastReceiverClass.LEGACY_1080P,
    ): DeviceProfile {
        val base = PROFILES.getValue(receiver)
        return when (maxStreamingBitrate) {
            null -> base
            else -> base.copy(maxStreamingBitrate = maxStreamingBitrate)
        }
    }

    /**
     * What HEVC a receiver class may be handed directly, or `null` for none.
     *
     * @param level Jellyfin compares `VideoLevel` numerically as ffprobe reports it: `123` is
     *   HEVC level 4.1 (the 1080p60 tier), `153` is 5.1 (the 4K60 tier).
     */
    private data class HevcCeiling(
        val maxWidth: String,
        val maxHeight: String,
        val level: String,
    )

    private val HEVC_CEILINGS: Map<CastReceiverClass, HevcCeiling?> =
        mapOf(
            CastReceiverClass.LEGACY_1080P to null,
            CastReceiverClass.HEVC_1080P to HevcCeiling(maxWidth = "1920", maxHeight = "1080", level = "123"),
            CastReceiverClass.ULTRA_4K to HevcCeiling(maxWidth = "3840", maxHeight = "2160", level = "153"),
        )

    /**
     * One profile per receiver class, built once.
     *
     * Deliberately no `containerProfiles` in any of them: [DeviceProfileBuilder] emits one per
     * container with an empty condition list, which constrains nothing — the containers a receiver
     * accepts are already named by its direct-play profiles, and repeating them as conditionless
     * container profiles would only be more of the profile for the server to walk.
     */
    private val PROFILES: Map<CastReceiverClass, DeviceProfile> by lazy {
        CastReceiverClass.entries.associateWith { receiver ->
            val hevc = HEVC_CEILINGS.getValue(receiver)
            DeviceProfile(
                name = PROFILE_NAME,
                directPlayProfiles = directPlayProfiles(hevc),
                transcodingProfiles = TRANSCODING_PROFILES,
                containerProfiles = emptyList(),
                codecProfiles = codecProfiles(hevc),
                subtitleProfiles = SUBTITLE_PROFILES,
                maxStreamingBitrate = DeviceProfileDefaults.MAX_STREAMING_BITRATE,
                maxStaticBitrate = DeviceProfileDefaults.MAX_STATIC_BITRATE,
                musicStreamingTranscodingBitrate = DeviceProfileDefaults.MAX_MUSIC_TRANSCODING_BITRATE,
            )
        }
    }

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
     *
     * [hevc] non-`null` adds `hevc` to the `mp4` entry — direct play is the *only* thing an
     * HEVC-capable class gains, because HEVC over the HLS transcode would need fMP4 segments,
     * which the reference receiver was measured to accept-and-never-play (class doc).
     */
    private fun directPlayProfiles(hevc: HevcCeiling?) =
        listOf(
            DirectPlayProfile(
                type = DlnaProfileType.VIDEO,
                container = "mp4",
                videoCodec = if (hevc != null) "h264,hevc" else "h264",
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

    /** The one channel count the Default Media Receiver was measured to accept for AAC, anywhere. */
    private const val MAX_AUDIO_CHANNELS = "2"

    /**
     * The ceiling on a direct-played H.264 stream, plus the stereo cap on AAC wherever it turns up.
     *
     * The `h264`/`mp4` entry: without it "H.264 in mp4" would also claim High 10, 4:2:2 and 4K
     * files, none of which a Cast receiver's baseline decoder touches — and the server, having been
     * told they are fine, hands them over rather than transcoding.
     *
     * The two `aac` entries exist because "direct play" has two shapes that both carry an AAC track
     * past this profile's [DIRECT_PLAY_PROFILES] container/codec check unchallenged: a video's audio
     * track (`VIDEO_AUDIO`) and an audio-only file (`AUDIO`). Device-measured on a real Chromecast
     * Ultra: AAC with more than 2 channels fails with CAF error 104 in every container tried, so both
     * shapes need the same [ProfileConditionValue.AUDIO_CHANNELS] cap or one of them quietly ships a
     * file the receiver rejects.
     *
     * With [hevc] non-`null` an `hevc`/`mp4` entry joins for the same reason the `h264`/`mp4` one
     * exists: without it "HEVC in mp4" would also claim High-tier profiles, resolutions past the
     * class's ceiling, and Dolby Vision — whose bitstreams report "Main 10" but need a DV pipeline,
     * so [ProfileConditionValue.VIDEO_RANGE_TYPE] pins the range to what the dongles render
     * (HDR10/HLG tone-mapped where the screen lacks it, DV transcoded rather than black-screened).
     */
    private fun codecProfiles(hevc: HevcCeiling?) =
        listOfNotNull(
            hevc?.let(::hevcCodecProfile),
            CodecProfile(
                type = CodecType.VIDEO,
                codec = "h264",
                container = "mp4",
                applyConditions = emptyList(),
                conditions =
                    listOf(
                        DeviceProfileDefaults.condition(
                            ProfileConditionType.EQUALS_ANY,
                            ProfileConditionValue.VIDEO_PROFILE,
                            "high|main|baseline|constrained baseline",
                        ),
                        DeviceProfileDefaults.condition(
                            ProfileConditionType.LESS_THAN_EQUAL,
                            ProfileConditionValue.VIDEO_LEVEL,
                            MAX_H264_LEVEL,
                        ),
                        DeviceProfileDefaults.condition(
                            ProfileConditionType.LESS_THAN_EQUAL,
                            ProfileConditionValue.WIDTH,
                            MAX_WIDTH,
                        ),
                        DeviceProfileDefaults.condition(
                            ProfileConditionType.LESS_THAN_EQUAL,
                            ProfileConditionValue.HEIGHT,
                            MAX_HEIGHT,
                        ),
                    ),
            ),
            CodecProfile(
                type = CodecType.VIDEO_AUDIO,
                codec = "aac",
                applyConditions = emptyList(),
                conditions =
                    listOf(
                        DeviceProfileDefaults.condition(
                            ProfileConditionType.LESS_THAN_EQUAL,
                            ProfileConditionValue.AUDIO_CHANNELS,
                            MAX_AUDIO_CHANNELS,
                        ),
                    ),
            ),
            CodecProfile(
                type = CodecType.AUDIO,
                codec = "aac",
                applyConditions = emptyList(),
                conditions =
                    listOf(
                        DeviceProfileDefaults.condition(
                            ProfileConditionType.LESS_THAN_EQUAL,
                            ProfileConditionValue.AUDIO_CHANNELS,
                            MAX_AUDIO_CHANNELS,
                        ),
                    ),
            ),
        )

    /** The HEVC ceiling of a capable class, expressed the way the server checks a file against it. */
    private fun hevcCodecProfile(ceiling: HevcCeiling): CodecProfile =
        CodecProfile(
            type = CodecType.VIDEO,
            codec = "hevc",
            container = "mp4",
            applyConditions = emptyList(),
            conditions =
                listOf(
                    DeviceProfileDefaults.condition(
                        ProfileConditionType.EQUALS_ANY,
                        ProfileConditionValue.VIDEO_PROFILE,
                        "main|main 10",
                    ),
                    DeviceProfileDefaults.condition(
                        ProfileConditionType.LESS_THAN_EQUAL,
                        ProfileConditionValue.VIDEO_LEVEL,
                        ceiling.level,
                    ),
                    DeviceProfileDefaults.condition(
                        ProfileConditionType.LESS_THAN_EQUAL,
                        ProfileConditionValue.WIDTH,
                        ceiling.maxWidth,
                    ),
                    DeviceProfileDefaults.condition(
                        ProfileConditionType.LESS_THAN_EQUAL,
                        ProfileConditionValue.HEIGHT,
                        ceiling.maxHeight,
                    ),
                    DeviceProfileDefaults.condition(
                        ProfileConditionType.EQUALS_ANY,
                        ProfileConditionValue.VIDEO_RANGE_TYPE,
                        "SDR|HDR10|HLG",
                    ),
                ),
        )

    /**
     * What we ask the server to transcode *to*.
     *
     * HLS with `ts` segments rather than the local profile's wide audio list: the Cast Application
     * Framework's own player is what consumes this, and it decodes H.264 + AAC in MPEG-TS
     * everywhere. Verified against the server: the returned `TranscodingUrl` is a `master.m3u8`
     * with `SegmentContainer=ts`.
     *
     * `maxAudioChannels = "2"` puts `TranscodingMaxAudioChannels=2` on that same `TranscodingUrl` —
     * device-measured on a real Chromecast Ultra: without it the server was transcoding 5.1 sources
     * to 5.1 AAC, which the receiver rejects with CAF error 104 (see the class doc).
     */
    private val TRANSCODING_PROFILES =
        listOf(
            TranscodingProfile(
                type = DlnaProfileType.VIDEO,
                container = "ts",
                videoCodec = "h264",
                audioCodec = "aac",
                protocol = MediaStreamProtocol.HLS,
                maxAudioChannels = MAX_AUDIO_CHANNELS,
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
     * the very same `subrip` stream comes back as `Stream.vtt` (probed against the server).
     * `CastSpecMapper` relies on that: every side-loaded cast track is WebVTT.
     *
     * Image subtitles (PGS, DVB) are omitted altogether rather than declared. A format the profile
     * does not mention cannot be delivered externally, so the server burns it into the video — which
     * is the only way a receiver with no subtitle renderer of its own can show one.
     */
    private val SUBTITLE_PROFILES =
        listOf("vtt", "webvtt").map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EXTERNAL) }
}
