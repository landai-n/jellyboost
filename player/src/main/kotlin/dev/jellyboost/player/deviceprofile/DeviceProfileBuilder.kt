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
 * Builds the `DeviceProfile` the server negotiates playback against.
 *
 * This is the single most consequential object in the playback pipeline: it is what makes the
 * server answer "direct play" instead of spinning up ffmpeg. Reimplemented from jellyfin-android's
 * `player/deviceprofile/DeviceProfileBuilder.kt` — a hardware probe crossed with a hand-maintained
 * container/codec matrix — and deliberately **not** Findroid's permissive "direct play everything"
 * profile, which pushes decode failures onto the device.
 *
 * The external-player and web-codec-capabilities outputs of the original are dropped: this app has
 * no external player hand-off and no WebView to feed.
 *
 * The expensive part — the `MediaCodecList` probe and the container/codec cross-product — happens
 * once, lazily, and is then reused; [getDeviceProfile] only re-derives when a caller asks for
 * settings that differ from the defaults.
 */
@Singleton
internal class DeviceProfileBuilder
    @Inject
    constructor(
        private val probe: MediaCodecProbe,
    ) {
        private val codecs: DeviceCodecs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { probe.probe() }

        /** Video codecs, per container, that both the container supports and the device decodes. */
        private val supportedVideoCodecs: List<List<String>> by lazy {
            AVAILABLE_VIDEO_CODECS.map { forContainer -> forContainer.filter { it in codecs.videoCodecs } }
        }

        /**
         * Audio codecs, per container, that the device decodes **or** the bundled ffmpeg extension
         * handles. The forced list is why AC3/E-AC3/DTS/TrueHD files direct-play on devices whose
         * `MediaCodecList` never mentions them.
         */
        private val supportedAudioCodecs: List<List<String>> by lazy {
            AVAILABLE_AUDIO_CODECS.map { forContainer ->
                forContainer.filter { it in codecs.audioCodecs || it in FORCED_AUDIO_CODECS }
            }
        }

        private val defaultProfile: DeviceProfile by lazy { build(directPlayAss = DEFAULT_DIRECT_PLAY_ASS) }

        /** The default profile's twin for the transcode pass; see [getDeviceProfile]. */
        private val hlsSubtitleProfile: DeviceProfile by lazy {
            build(directPlayAss = DEFAULT_DIRECT_PLAY_ASS, hlsTextSubtitles = true)
        }

        /**
         * The profile to send with a `PlaybackInfo` request.
         *
         * @param maxStreamingBitrate cap from the quality picker. Lowering it below a file's
         *   bitrate is what forces the server to transcode. `null` keeps the profile's own
         *   120 Mbps ceiling.
         * @param directPlayAss whether ASS/SSA subtitles are claimed as directly renderable.
         *   Advertising them avoids a full transcode of subtitled content, at the cost of
         *   ExoPlayer's approximate SSA rendering.
         * @param hlsTextSubtitles whether text subtitles should be asked for as **in-manifest HLS
         *   renditions** instead of side-loaded files. Only meaningful for a negotiation that is
         *   already known to end in a transcode — see [subtitleProfiles] for why the two shapes
         *   cannot be advertised together, and `PlaybackInfoResolver` for who asks for which.
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
         * Restricts [videoCodec] to what the device's decoders actually handle: the profiles they
         * advertise — a device that only decodes H.264 High should not be handed a High 4:4:4
         * file — and the largest frame they accept, which also caps the size the server transcodes
         * *to*. Without the size conditions a 4K source either direct-plays or transcodes at full
         * width into a hardware decoder that tops out below it, and ExoPlayer falls back to
         * software decode.
         *
         * One profile per codec, with **no container**, and that is load-bearing rather than
         * convenience: the server was measured (10.11.11) dropping container-bound codec profiles
         * when sizing a Dolby Vision transcode — the same conditions bound to `mkv` produced a
         * 3840-wide stream where the containerless shape produced 2560 — and a decoder's limits do
         * not depend on the container anyway.
         *
         * Either half may be unknown; `null` only when both are.
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
         * What the server may do with each subtitle format.
         *
         * The embedded half is the same either way — a subtitle that travels inside the container
         * is ExoPlayer's to demux and nothing about it drifts.
         *
         * The other half is exclusive, and that is a fact about the *server*, not a design choice
         * (measured against 10.11.11): offered both an `External` and an `Hls` profile
         * for the same format, `StreamBuilder.GetExternalSubtitleProfile` returns the first match in
         * profile order and that is always External. Asking for HLS renditions therefore means
         * advertising **no** text External profile at all — which is exactly why this variant is
         * reserved for a negotiation already known to end in a transcode. Sent for a direct-played
         * file with a sidecar `.srt`, the server would find no way to deliver it and fall back to
         * `Encode`, burning it in and transcoding a file that needed no transcode.
         *
         * ASS/SSA drop out of the external half too when [hlsTextSubtitles] is set: the server
         * converts them to WebVTT for the rendition, which loses positioning and styling ExoPlayer's
         * SSA renderer largely ignores anyway.
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
             * ASS/SSA is claimed by default. ExoPlayer's SSA renderer ignores most positioning and
             * styling, but the alternative is burning subtitles in — a full transcode of otherwise
             * direct-playable content, which is a far worse default on a tablet.
             */
            const val DEFAULT_DIRECT_PLAY_ASS: Boolean = true

            /**
             * Containers ExoPlayer can demux.
             *
             * The three codec tables below are index-aligned with this list; changing one without
             * the others silently mislabels a container's codecs.
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
             * Audio codecs advertised regardless of what `MediaCodecList` says, because the
             * bundled `org.jellyfin.media3:media3-ffmpeg-decoder` extension decodes them in
             * software. Without this list every AC3 or DTS track would force a transcode.
             */
            private val FORCED_AUDIO_CODECS =
                PCM_CODECS + listOf("alac", "aac", "ac3", "eac3", "dts", "mlp", "truehd")

            /**
             * The one format an HLS subtitle rendition is ever served in — the server writes
             * `stream.vtt` segments with an `X-TIMESTAMP-MAP` header, whatever the source codec was.
             */
            private const val HLS_SUBTITLE_FORMAT = "vtt"

            private val EMBEDDED_SUBTITLES = listOf("dvbsub", "pgssub", "srt", "subrip", "ttml")
            private val EXTERNAL_SUBTITLES = listOf("srt", "subrip", "ttml", "vtt", "webvtt")
            private val SSA_SUBTITLES = listOf("ssa", "ass")

            /**
             * What we ask the server to transcode *to*.
             *
             * H.264 in an HLS stream, because that is the one combination every Android device
             * since API 26 decodes in hardware. The wide audio codec list keeps the server from
             * re-encoding audio it does not have to.
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
