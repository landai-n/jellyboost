package dev.jellyboost.player.deviceprofile

import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.ContainerProfile
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `DeviceProfile` the server negotiates against: a hardware probe crossed with a hand-maintained
 * container/codec matrix, deliberately **not** a permissive "direct play everything" profile, which
 * pushes decode failures onto the device.
 *
 * The `MediaCodecList` probe and the cross-product happen once, lazily; [getDeviceProfile]
 * re-derives only for settings that differ from the defaults.
 */
@Singleton
internal class DeviceProfileBuilder
    @Inject
    constructor(
        private val probe: MediaCodecProbe,
    ) {
        private val codecs: DeviceCodecs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { probe.probe() }

        private val supportedVideoCodecs: List<List<String>> by lazy {
            AVAILABLE_VIDEO_CODECS.map { forContainer -> forContainer.filter { it in codecs.videoCodecs } }
        }

        /**
         * [FORCED_AUDIO_CODECS] is why AC3/E-AC3/DTS/TrueHD files direct-play on devices whose
         * `MediaCodecList` never mentions them.
         */
        private val supportedAudioCodecs: List<List<String>> by lazy {
            AVAILABLE_AUDIO_CODECS.map { forContainer ->
                forContainer.filter { it in codecs.audioCodecs || it in FORCED_AUDIO_CODECS }
            }
        }

        private val defaultProfile: DeviceProfile by lazy { build(directPlayAss = DEFAULT_DIRECT_PLAY_ASS) }

        private val hlsSubtitleProfile: DeviceProfile by lazy {
            build(directPlayAss = DEFAULT_DIRECT_PLAY_ASS, hlsTextSubtitles = true)
        }

        /**
         * @param maxStreamingBitrate lowering it below a file's bitrate is what forces a transcode;
         *   `null` keeps the profile's own 120 Mbps ceiling.
         * @param directPlayAss claiming ASS/SSA avoids a full transcode of subtitled content, at the
         *   cost of ExoPlayer's approximate SSA rendering.
         * @param hlsTextSubtitles only meaningful for a negotiation already known to end in a
         *   transcode — see [subtitleProfiles] for why the two shapes cannot be advertised together.
         */
        fun getDeviceProfile(
            maxStreamingBitrate: Int? = null,
            directPlayAss: Boolean = DEFAULT_DIRECT_PLAY_ASS,
            hlsTextSubtitles: Boolean = false,
        ): DeviceProfile {
            val base =
                when {
                    directPlayAss != DEFAULT_DIRECT_PLAY_ASS -> build(directPlayAss, hlsTextSubtitles)
                    hlsTextSubtitles -> hlsSubtitleProfile
                    else -> defaultProfile
                }
            return when (maxStreamingBitrate) {
                null -> base
                else -> base.copy(maxStreamingBitrate = maxStreamingBitrate)
            }
        }

        private fun build(
            directPlayAss: Boolean,
            hlsTextSubtitles: Boolean = false,
        ): DeviceProfile {
            val containerProfiles = mutableListOf<ContainerProfile>()
            val directPlayProfiles = mutableListOf<DirectPlayProfile>()
            val playableVideoCodecs = mutableSetOf<String>()

            SUPPORTED_CONTAINER_FORMATS.forEachIndexed { index, container ->
                val videoCodecs = supportedVideoCodecs[index]
                val audioCodecs = supportedAudioCodecs[index]

                if (videoCodecs.isNotEmpty()) {
                    containerProfiles +=
                        ContainerProfile(type = DlnaProfileType.VIDEO, container = container, conditions = emptyList())
                    directPlayProfiles +=
                        DirectPlayProfile(
                            type = DlnaProfileType.VIDEO,
                            container = container,
                            videoCodec = videoCodecs.joinToString(","),
                            audioCodec = audioCodecs.joinToString(","),
                        )
                    playableVideoCodecs += videoCodecs
                }

                if (audioCodecs.isNotEmpty()) {
                    containerProfiles +=
                        ContainerProfile(type = DlnaProfileType.AUDIO, container = container, conditions = emptyList())
                    directPlayProfiles +=
                        DirectPlayProfile(
                            type = DlnaProfileType.AUDIO,
                            container = container,
                            audioCodec = audioCodecs.joinToString(","),
                        )
                }
            }

            return DeviceProfile(
                name = PROFILE_NAME,
                directPlayProfiles = directPlayProfiles,
                transcodingProfiles = TRANSCODING_PROFILES,
                containerProfiles = containerProfiles,
                codecProfiles = playableVideoCodecs.sorted().mapNotNull(::codecProfile),
                subtitleProfiles = subtitleProfiles(directPlayAss, hlsTextSubtitles),
                maxStreamingBitrate = DeviceProfileDefaults.MAX_STREAMING_BITRATE,
                maxStaticBitrate = DeviceProfileDefaults.MAX_STATIC_BITRATE,
                musicStreamingTranscodingBitrate = DeviceProfileDefaults.MAX_MUSIC_TRANSCODING_BITRATE,
            )
        }

        /**
         * The size conditions also cap what the server transcodes *to*: without them a 4K source
         * reaches a hardware decoder that tops out below it and ExoPlayer falls back to software.
         *
         * One profile per codec with **no container**, which is load-bearing: the server (10.11.11)
         * was measured dropping container-bound codec profiles when sizing a Dolby Vision transcode —
         * the same conditions bound to `mkv` produced a 3840-wide stream where the containerless
         * shape produced 2560.
         */
        private fun codecProfile(videoCodec: String): CodecProfile? {
            val profiles = codecs.videoProfiles[videoCodec]?.takeIf { it.isNotEmpty() }
            val maxSize = codecs.videoMaxSizes[videoCodec]
            val conditions =
                buildList {
                    profiles?.let {
                        add(
                            DeviceProfileDefaults.condition(
                                ProfileConditionType.EQUALS_ANY,
                                ProfileConditionValue.VIDEO_PROFILE,
                                it.joinToString("|"),
                            ),
                        )
                    }
                    maxSize?.let {
                        add(
                            DeviceProfileDefaults.condition(
                                ProfileConditionType.LESS_THAN_EQUAL,
                                ProfileConditionValue.WIDTH,
                                it.width.toString(),
                            ),
                        )
                        add(
                            DeviceProfileDefaults.condition(
                                ProfileConditionType.LESS_THAN_EQUAL,
                                ProfileConditionValue.HEIGHT,
                                it.height.toString(),
                            ),
                        )
                    }
                }
            if (conditions.isEmpty()) return null
            return CodecProfile(
                type = CodecType.VIDEO,
                container = null,
                codec = videoCodec,
                applyConditions = emptyList(),
                conditions = conditions,
            )
        }

        /**
         * External and HLS are mutually exclusive, and that is a fact about the *server* (10.11.11):
         * offered both profiles for one format, `StreamBuilder.GetExternalSubtitleProfile` returns
         * the first match in profile order, which is always External. Asking for HLS renditions
         * therefore means advertising **no** text External profile at all — and that shape sent for
         * a direct-played file with a sidecar `.srt` makes the server fall back to `Encode`,
         * transcoding a file that needed no transcode.
         */
        private fun subtitleProfiles(
            directPlayAss: Boolean,
            hlsTextSubtitles: Boolean,
        ): List<SubtitleProfile> {
            val embedded = if (directPlayAss) EMBEDDED_SUBTITLES + SSA_SUBTITLES else EMBEDDED_SUBTITLES
            val external = if (directPlayAss) EXTERNAL_SUBTITLES + SSA_SUBTITLES else EXTERNAL_SUBTITLES
            return buildList {
                embedded.mapTo(this) { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EMBED) }
                if (hlsTextSubtitles) {
                    add(SubtitleProfile(format = HLS_SUBTITLE_FORMAT, method = SubtitleDeliveryMethod.HLS))
                } else {
                    external.mapTo(this) { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EXTERNAL) }
                }
            }
        }

        companion object {
            /** Shown next to the session in the server's Dashboard → Devices. */
            const val PROFILE_NAME: String = "Jellyboost"

            /**
             * ExoPlayer's SSA renderer ignores most positioning and styling, but the alternative is
             * burning subtitles in — a full transcode of otherwise direct-playable content.
             */
            const val DEFAULT_DIRECT_PLAY_ASS: Boolean = true

            /**
             * The codec tables below are index-aligned with this list; changing one without the
             * others silently mislabels a container's codecs.
             */
            private val SUPPORTED_CONTAINER_FORMATS =
                listOf("mp4", "fmp4", "webm", "mkv", "mp3", "ogg", "wav", "mpegts", "flv", "aac", "flac", "3gp")

            private val AVAILABLE_VIDEO_CODECS =
                listOf(
                    // mp4
                    listOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
                    // fmp4
                    listOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp9"),
                    // webm
                    listOf("vp8", "vp9", "av1"),
                    // mkv
                    listOf("mpeg1video", "mpeg2video", "h263", "mpeg4", "h264", "hevc", "av1", "vp8", "vp9"),
                    // mp3
                    emptyList(),
                    // ogg
                    emptyList(),
                    // wav
                    emptyList(),
                    // mpegts
                    listOf("mpeg1video", "mpeg2video", "mpeg4", "h264", "hevc"),
                    // flv
                    listOf("mpeg4", "h264"),
                    // aac
                    emptyList(),
                    // flac
                    emptyList(),
                    // 3gp
                    listOf("h263", "mpeg4", "h264", "hevc"),
                )

            /** PCM variants ExoPlayer decodes without any codec at all. */
            private val PCM_CODECS =
                listOf(
                    "pcm_s8",
                    "pcm_s16be",
                    "pcm_s16le",
                    "pcm_s24le",
                    "pcm_s32le",
                    "pcm_f32le",
                    "pcm_alaw",
                    "pcm_mulaw",
                )

            private val AVAILABLE_AUDIO_CODECS =
                listOf(
                    // mp4
                    listOf("mp1", "mp2", "mp3", "aac", "alac", "ac3", "opus"),
                    // fmp4
                    listOf("mp3", "aac", "ac3", "eac3"),
                    // webm
                    listOf("vorbis", "opus"),
                    // mkv
                    PCM_CODECS +
                        listOf(
                            "mp1",
                            "mp2",
                            "mp3",
                            "aac",
                            "vorbis",
                            "opus",
                            "flac",
                            "alac",
                            "ac3",
                            "eac3",
                            "dts",
                            "mlp",
                            "truehd",
                        ),
                    // mp3
                    listOf("mp3"),
                    // ogg
                    listOf("vorbis", "opus", "flac"),
                    // wav
                    PCM_CODECS,
                    // mpegts
                    PCM_CODECS + listOf("mp1", "mp2", "mp3", "aac", "ac3", "eac3", "dts", "mlp", "truehd"),
                    // flv
                    listOf("mp3", "aac"),
                    // aac
                    listOf("aac"),
                    // flac
                    listOf("flac"),
                    // 3gp
                    listOf("3gpp", "aac", "flac"),
                )

            /**
             * Advertised regardless of `MediaCodecList` because the bundled
             * `media3-ffmpeg-decoder` extension decodes them in software; without this list every
             * AC3 or DTS track would force a transcode.
             */
            private val FORCED_AUDIO_CODECS =
                PCM_CODECS + listOf("alac", "aac", "ac3", "eac3", "dts", "mlp", "truehd")

            /**
             * The only format a rendition is served in: the server writes `stream.vtt` segments with
             * an `X-TIMESTAMP-MAP` header whatever the source codec was.
             */
            private const val HLS_SUBTITLE_FORMAT = "vtt"

            private val EMBEDDED_SUBTITLES = listOf("dvbsub", "pgssub", "srt", "subrip", "ttml")
            private val EXTERNAL_SUBTITLES = listOf("srt", "subrip", "ttml", "vtt", "webvtt")
            private val SSA_SUBTITLES = listOf("ssa", "ass")

            /**
             * H.264 in HLS: the one combination every Android device since API 26 decodes in
             * hardware. The wide audio list keeps the server from re-encoding audio it need not.
             */
            private val TRANSCODING_PROFILES =
                listOf(
                    TranscodingProfile(
                        type = DlnaProfileType.VIDEO,
                        container = "ts",
                        videoCodec = "h264",
                        audioCodec = "mp1,mp2,mp3,aac,ac3,eac3,dts,mlp,truehd",
                        protocol = MediaStreamProtocol.HLS,
                        conditions = emptyList(),
                    ),
                    TranscodingProfile(
                        type = DlnaProfileType.VIDEO,
                        container = "mkv",
                        videoCodec = "h264",
                        audioCodec =
                            AVAILABLE_AUDIO_CODECS[SUPPORTED_CONTAINER_FORMATS.indexOf("mkv")]
                                .joinToString(","),
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
        }
    }
