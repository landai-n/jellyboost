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
 * The `DeviceProfile` a **cast** session is negotiated against. [DeviceProfileBuilder] cannot serve
 * here: it is built from *this* device's `MediaCodecList`, and advertising a tablet's decoders for a
 * receiver is how a file that plays in the hand becomes a black television screen.
 *
 * The floor is the intersection every receiver since the first dongle satisfies: H.264 High level
 * 4.2 at 1080p with AAC or MP3 in `mp4`, VP8/VP9 in `webm`, and an HLS transcode to H.264 + AAC in
 * `ts` for everything else. An HEVC-capable [CastReceiverClass] adds HEVC **direct play** only: the
 * transcode target is identical in every class because CAF's TS demuxer is H.264-only, and the fMP4
 * segments HEVC would need were measured broken on the reference Ultra.
 *
 * **Audio is capped at stereo AAC everywhere, and that is device-measured, not conservatism.** On a
 * Chromecast Ultra (Default Media Receiver CC1AD845) every AAC stream with more than 2 channels
 * failed with CAF `detailedErrorCode: 104`, in HLS-ts *and* progressive mp4 alike; AC3/EAC3 5.1
 * passthrough failed too, and HLS-fMP4 never opened a media session at all. Relaxing this per model
 * needs its own measurement on that hardware first.
 */
internal object CastDeviceProfile {
    /** Shown next to the session in the server's Dashboard → Devices. */
    const val PROFILE_NAME: String = "Jellyboost Chromecast"

    /**
     * @param maxStreamingBitrate `null` keeps the profile's own ceiling; lowering it below the
     *   file's bitrate is what makes the server transcode.
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
     * @param level compared numerically as ffprobe reports it: `123` is HEVC level 4.1 (1080p60),
     *   `153` is 5.1 (4K60).
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
     * Deliberately no `containerProfiles`: a conditionless one constrains nothing, and the
     * containers a receiver accepts are already named by its direct-play profiles.
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

    /** The one H.264 level every Cast receiver decodes, as the server compares it. */
    const val MAX_H264_LEVEL: String = "42"
    private const val MAX_WIDTH = "1920"
    private const val MAX_HEIGHT = "1080"

    /**
     * `mkv` is absent on purpose even though most receivers demux it: the ones that do not fail
     * silently. [hevc] non-`null` adds `hevc` to the `mp4` entry and nothing else — HEVC over the
     * HLS transcode would need the fMP4 segments the reference receiver never plays.
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
     * Without the `h264`/`mp4` entry "H.264 in mp4" would also claim High 10, 4:2:2 and 4K files,
     * which the server would then hand over untranscoded.
     *
     * **Both** `aac` entries are needed: `VIDEO_AUDIO` and `AUDIO` are the two shapes that carry an
     * AAC track past the container/codec check, and AAC above 2 channels fails on the receiver
     * (CAF error 104), so a missing cap on either quietly ships a file it rejects.
     *
     * The `hevc` entry likewise pins [ProfileConditionValue.VIDEO_RANGE_TYPE]: Dolby Vision reports
     * "Main 10" but needs a DV pipeline, so it must transcode rather than black-screen.
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
     * HLS with `ts` segments: CAF's own player decodes H.264 + AAC in MPEG-TS everywhere.
     * `maxAudioChannels = "2"` puts `TranscodingMaxAudioChannels=2` on the `TranscodingUrl` —
     * without it the server transcodes 5.1 sources to 5.1 AAC, which the receiver rejects (104).
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
     * The format list decides what the server converts a text subtitle **into**, not what the source
     * is: declaring `srt` makes it deliver `Stream.subrip`, which a receiver cannot parse, while
     * with only `vtt` the same stream comes back as `Stream.vtt`. `CastSpecMapper` relies on it.
     *
     * Image subtitles are omitted rather than declared: a format the profile does not mention cannot
     * be delivered externally, so the server burns it in — the only way a receiver can show one.
     */
    private val SUBTITLE_PROFILES =
        listOf("vtt", "webvtt").map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EXTERNAL) }
}
