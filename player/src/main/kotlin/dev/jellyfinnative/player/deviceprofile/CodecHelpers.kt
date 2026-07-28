package dev.jellyfinnative.player.deviceprofile

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import androidx.media3.common.MimeTypes

/**
 * Translation tables between Android's `MediaCodec` vocabulary and Jellyfin's.
 *
 * Reimplemented from jellyfin-android's `player/deviceprofile/CodecHelpers.kt`, but written as
 * lookup maps rather than long `when` chains: the data is the point, and a map is both cheaper to
 * read and exempt from detekt's complexity rules.
 *
 * The `MediaFormat` / `CodecProfileLevel` constants referenced here are compile-time constants, so
 * this object works unchanged in local unit tests despite living on top of the Android SDK.
 */
internal object CodecHelpers {
    /** Android video MIME type → the codec name Jellyfin uses in a device profile. */
    private val VIDEO_CODEC_NAMES: Map<String, String> =
        mapOf(
            MediaFormat.MIMETYPE_VIDEO_MPEG2 to "mpeg2video",
            MediaFormat.MIMETYPE_VIDEO_H263 to "h263",
            MediaFormat.MIMETYPE_VIDEO_MPEG4 to "mpeg4",
            MediaFormat.MIMETYPE_VIDEO_AVC to "h264",
            MediaFormat.MIMETYPE_VIDEO_HEVC to "hevc",
            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION to "hevc",
            MediaFormat.MIMETYPE_VIDEO_VP8 to "vp8",
            MediaFormat.MIMETYPE_VIDEO_VP9 to "vp9",
            MediaFormat.MIMETYPE_VIDEO_AV1 to "av1",
        )

    /** Android audio MIME type → the codec name Jellyfin uses in a device profile. */
    private val AUDIO_CODEC_NAMES: Map<String, String> =
        mapOf(
            MediaFormat.MIMETYPE_AUDIO_AAC to "aac",
            MediaFormat.MIMETYPE_AUDIO_AC3 to "ac3",
            MediaFormat.MIMETYPE_AUDIO_EAC3 to "eac3",
            MediaFormat.MIMETYPE_AUDIO_AMR_WB to "3gpp",
            MediaFormat.MIMETYPE_AUDIO_AMR_NB to "3gpp",
            MediaFormat.MIMETYPE_AUDIO_FLAC to "flac",
            MediaFormat.MIMETYPE_AUDIO_MPEG to "mp3",
            MediaFormat.MIMETYPE_AUDIO_OPUS to "opus",
            MediaFormat.MIMETYPE_AUDIO_RAW to "raw",
            MediaFormat.MIMETYPE_AUDIO_VORBIS to "vorbis",
        )

    /**
     * Codec profile constants → the profile names the server matches on.
     *
     * The strings are the ones ffprobe reports, which is what the server compares a file's
     * `VideoProfile` against — spelling matters more than it looks.
     */
    private val VIDEO_PROFILE_NAMES: Map<String, Map<Int, String>> =
        mapOf(
            "mpeg2video" to
                mapOf(
                    CodecProfileLevel.MPEG2ProfileSimple to "simple profile",
                    CodecProfileLevel.MPEG2ProfileMain to "main profile",
                    CodecProfileLevel.MPEG2Profile422 to "422 profile",
                    CodecProfileLevel.MPEG2ProfileSNR to "snr profile",
                    CodecProfileLevel.MPEG2ProfileSpatial to "spatial profile",
                    CodecProfileLevel.MPEG2ProfileHigh to "high profile",
                ),
            "h263" to
                mapOf(
                    CodecProfileLevel.H263ProfileBaseline to "baseline",
                    CodecProfileLevel.H263ProfileH320Coding to "h320 coding",
                    CodecProfileLevel.H263ProfileBackwardCompatible to "backward compatible",
                    CodecProfileLevel.H263ProfileISWV2 to "isw v2",
                    CodecProfileLevel.H263ProfileISWV3 to "isw v3",
                    CodecProfileLevel.H263ProfileHighCompression to "high compression",
                    CodecProfileLevel.H263ProfileInternet to "internet",
                    CodecProfileLevel.H263ProfileInterlace to "interlace",
                    CodecProfileLevel.H263ProfileHighLatency to "high latency",
                ),
            "mpeg4" to
                mapOf(
                    CodecProfileLevel.MPEG4ProfileSimple to "simple profile",
                    CodecProfileLevel.MPEG4ProfileAdvancedSimple to "advanced simple profile",
                    CodecProfileLevel.MPEG4ProfileCore to "core profile",
                    CodecProfileLevel.MPEG4ProfileMain to "main profile",
                    CodecProfileLevel.MPEG4ProfileAdvancedCoding to "advanced coding profile",
                    CodecProfileLevel.MPEG4ProfileAdvancedCore to "advanced core profile",
                    CodecProfileLevel.MPEG4ProfileAdvancedRealTime to "advanced realtime profile",
                    CodecProfileLevel.MPEG4ProfileCoreScalable to "core scalable profile",
                    CodecProfileLevel.MPEG4ProfileSimpleScalable to "simple scalable profile",
                ),
            "h264" to
                mapOf(
                    CodecProfileLevel.AVCProfileBaseline to "baseline",
                    CodecProfileLevel.AVCProfileMain to "main",
                    CodecProfileLevel.AVCProfileExtended to "extended",
                    CodecProfileLevel.AVCProfileHigh to "high",
                    CodecProfileLevel.AVCProfileHigh10 to "high 10",
                    CodecProfileLevel.AVCProfileHigh422 to "high 422",
                    CodecProfileLevel.AVCProfileHigh444 to "high 444",
                    CodecProfileLevel.AVCProfileConstrainedBaseline to "constrained baseline",
                    CodecProfileLevel.AVCProfileConstrainedHigh to "constrained high",
                ),
            "hevc" to
                mapOf(
                    CodecProfileLevel.HEVCProfileMain to "Main",
                    CodecProfileLevel.HEVCProfileMain10 to "Main 10",
                    CodecProfileLevel.HEVCProfileMain10HDR10 to "Main 10 HDR 10",
                    CodecProfileLevel.HEVCProfileMain10HDR10Plus to "Main 10 HDR 10 Plus",
                    CodecProfileLevel.HEVCProfileMainStill to "Main Still",
                ),
            "vp8" to mapOf(CodecProfileLevel.VP8ProfileMain to "main"),
            "vp9" to
                mapOf(
                    CodecProfileLevel.VP9Profile0 to "Profile 0",
                    CodecProfileLevel.VP9Profile1 to "Profile 1",
                    CodecProfileLevel.VP9Profile2 to "Profile 2",
                    CodecProfileLevel.VP9Profile2HDR to "Profile 2",
                    CodecProfileLevel.VP9Profile3 to "Profile 3",
                    CodecProfileLevel.VP9Profile3HDR to "Profile 3",
                ),
        )

    /** Jellyfin subtitle codec → the MIME type ExoPlayer parses it with. */
    private val SUBTITLE_MIME_TYPES: Map<String, String> =
        mapOf(
            "srt" to MimeTypes.APPLICATION_SUBRIP,
            "subrip" to MimeTypes.APPLICATION_SUBRIP,
            "ssa" to MimeTypes.TEXT_SSA,
            "ass" to MimeTypes.TEXT_SSA,
            "ttml" to MimeTypes.APPLICATION_TTML,
            "vtt" to MimeTypes.TEXT_VTT,
            "webvtt" to MimeTypes.TEXT_VTT,
            "idx" to MimeTypes.APPLICATION_VOBSUB,
            "sub" to MimeTypes.APPLICATION_VOBSUB,
            "pgs" to MimeTypes.APPLICATION_PGS,
            "pgssub" to MimeTypes.APPLICATION_PGS,
        )

    fun videoCodecName(mimeType: String): String? = VIDEO_CODEC_NAMES[mimeType]

    fun audioCodecName(mimeType: String): String? = AUDIO_CODEC_NAMES[mimeType]

    fun videoProfileName(
        codec: String,
        profile: Int,
    ): String? = VIDEO_PROFILE_NAMES[codec]?.get(profile)

    /** `null` for a codec ExoPlayer cannot render as text — such a stream is not side-loadable. */
    fun subtitleMimeType(codec: String?): String? = codec?.lowercase()?.let(SUBTITLE_MIME_TYPES::get)
}
