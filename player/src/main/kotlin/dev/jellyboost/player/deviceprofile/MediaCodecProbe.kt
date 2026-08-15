package dev.jellyboost.player.deviceprofile

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** The largest frame the device should be asked to decode for one video codec. */
internal data class VideoMaxSize(
    val width: Int,
    val height: Int,
)

/**
 * What this device can actually decode, expressed in Jellyfin's vocabulary.
 *
 * @param videoCodecs Jellyfin codec names with at least one decoder on the device.
 * @param audioCodecs likewise for audio; note that [DeviceProfileBuilder] adds the codecs the
 *   bundled ffmpeg extension handles on top of these.
 * @param videoProfiles per video codec, the profile names the device's decoders advertise. An
 *   empty set means "profiles unknown", and no codec profile is emitted for it.
 * @param videoMaxSizes per video codec, the largest frame the device should be asked to decode.
 *   An absent entry means "size unknown", and no size condition is emitted for it.
 */
internal data class DeviceCodecs(
    val videoCodecs: Set<String> = emptySet(),
    val audioCodecs: Set<String> = emptySet(),
    val videoProfiles: Map<String, Set<String>> = emptyMap(),
    val videoMaxSizes: Map<String, VideoMaxSize> = emptyMap(),
)

/**
 * Reads the device's decoder list.
 *
 * Exists as an interface purely so [DeviceProfileBuilder] — whose output is what decides between
 * direct play and a transcode for every single item — can be unit tested against a known set of
 * codecs. `MediaCodecList` itself is a throwing stub in local unit tests.
 */
internal fun interface MediaCodecProbe {
    fun probe(): DeviceCodecs
}

/**
 * [MediaCodecProbe] over `MediaCodecList(REGULAR_CODECS)`.
 *
 * Encoders are skipped: a device that can *encode* HEVC tells us nothing about whether it can play
 * an HEVC file. Capabilities for the same codec reported by several decoders are merged, because
 * the union is what the device as a whole supports.
 *
 * Frame sizes are the one exception to that union: where a codec has any hardware decoder, only the
 * hardware decoders count. A software decoder that accepts 4K is not a reason to hand this device a
 * 4K file — that is exactly the silent fallback to software decode this probe exists to prevent.
 */
@Singleton
internal class PlatformMediaCodecProbe
    @Inject
    constructor() : MediaCodecProbe {
        override fun probe(): DeviceCodecs {
            val videoCodecs = mutableSetOf<String>()
            val audioCodecs = mutableSetOf<String>()
            val videoProfiles = mutableMapOf<String, MutableSet<String>>()
            val hardwareMaxSizes = mutableMapOf<String, VideoMaxSize>()
            val softwareMaxSizes = mutableMapOf<String, VideoMaxSize>()

            for (codecInfo in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (codecInfo.isEncoder) continue

                for (mimeType in codecInfo.supportedTypes) {
                    when (val videoCodec = CodecHelpers.videoCodecName(mimeType)) {
                        null -> CodecHelpers.audioCodecName(mimeType)?.let(audioCodecs::add)
                        else -> {
                            videoCodecs += videoCodec
                            videoProfiles
                                .getOrPut(videoCodec) { mutableSetOf() }
                                .addAll(profileNames(codecInfo, mimeType, videoCodec))
                            recordMaxSize(codecInfo, mimeType, videoCodec, hardwareMaxSizes, softwareMaxSizes)
                        }
                    }
                }
            }

            // Hardware wins per codec; a codec with software decoders only keeps what it has.
            val videoMaxSizes = softwareMaxSizes + hardwareMaxSizes
            Timber.d(
                "Device decodes video %s (max %s), audio %s",
                videoCodecs,
                videoMaxSizes,
                audioCodecs,
            )
            return DeviceCodecs(
                videoCodecs = videoCodecs,
                audioCodecs = audioCodecs,
                videoProfiles = videoProfiles.mapValues { (_, profiles) -> profiles.toSet() },
                videoMaxSizes = videoMaxSizes,
            )
        }

        /** The Jellyfin profile names one decoder advertises for [videoCodec]. */
        private fun profileNames(
            codecInfo: MediaCodecInfo,
            mimeType: String,
            videoCodec: String,
        ): List<String> =
            codecInfo
                .getCapabilitiesForType(mimeType)
                .profileLevels
                .mapNotNull { CodecHelpers.videoProfileName(videoCodec, it.profile) }

        /**
         * Files one decoder's largest frame for [videoCodec] under whichever of
         * [hardwareMaxSizes] / [softwareMaxSizes] it belongs to, merged with what its siblings
         * already reported. A decoder that names no size at all is simply not counted.
         */
        private fun recordMaxSize(
            codecInfo: MediaCodecInfo,
            mimeType: String,
            videoCodec: String,
            hardwareMaxSizes: MutableMap<String, VideoMaxSize>,
            softwareMaxSizes: MutableMap<String, VideoMaxSize>,
        ) {
            val maxSize = maxSize(codecInfo, mimeType) ?: return
            val sizes = if (isHardware(codecInfo)) hardwareMaxSizes else softwareMaxSizes
            sizes[videoCodec] = sizes[videoCodec]?.union(maxSize) ?: maxSize
        }

        /** The largest frame one decoder accepts for [mimeType], or `null` if it does not say. */
        private fun maxSize(
            codecInfo: MediaCodecInfo,
            mimeType: String,
        ): VideoMaxSize? =
            codecInfo
                .getCapabilitiesForType(mimeType)
                .videoCapabilities
                ?.let { VideoMaxSize(it.supportedWidths.upper, it.supportedHeights.upper) }

        /**
         * Whether [codecInfo] decodes in hardware.
         *
         * The platform only answers that question from API 29 on; below it, the name prefixes the
         * platform's own software decoders have always used stand in.
         */
        private fun isHardware(codecInfo: MediaCodecInfo): Boolean =
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> codecInfo.isHardwareAccelerated
                else -> SOFTWARE_DECODER_PREFIXES.none { codecInfo.name.startsWith(it, ignoreCase = true) }
            }

        private fun VideoMaxSize.union(other: VideoMaxSize) =
            VideoMaxSize(
                width = maxOf(width, other.width),
                height = maxOf(height, other.height),
            )

        private companion object {
            /** Decoder name prefixes that mean "software" on API 26–28. */
            private val SOFTWARE_DECODER_PREFIXES = listOf("c2.android.", "OMX.google.", "OMX.SEC.")
        }
    }
