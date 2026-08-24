package dev.jellyboost.player.deviceprofile

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

internal data class VideoMaxSize(
    val width: Int,
    val height: Int,
)

/**
 * Codec names in Jellyfin's vocabulary, not the platform's.
 *
 * @param videoProfiles an empty set means "profiles unknown": no codec profile condition is emitted.
 * @param videoMaxSizes an absent entry means "size unknown": no size condition is emitted.
 */
internal data class DeviceCodecs(
    val videoCodecs: Set<String> = emptySet(),
    val audioCodecs: Set<String> = emptySet(),
    val videoProfiles: Map<String, Set<String>> = emptyMap(),
    val videoMaxSizes: Map<String, VideoMaxSize> = emptyMap(),
)

/** An interface because `MediaCodecList` is a throwing stub in local unit tests. */
internal fun interface MediaCodecProbe {
    fun probe(): DeviceCodecs
}

/**
 * Encoders are skipped: encoding HEVC says nothing about decoding it. Decoders for the same codec are unioned.
 *
 * Frame sizes are the exception: where a codec has any hardware decoder, only hardware decoders count — a
 * software decoder accepting 4K is not a reason to hand this device a 4K file and let it stutter.
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

        private fun profileNames(
            codecInfo: MediaCodecInfo,
            mimeType: String,
            videoCodec: String,
        ): List<String> =
            codecInfo
                .getCapabilitiesForType(mimeType)
                .profileLevels
                .mapNotNull { CodecHelpers.videoProfileName(videoCodec, it.profile) }

        /** A decoder that names no size at all is not counted. */
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

        private fun maxSize(
            codecInfo: MediaCodecInfo,
            mimeType: String,
        ): VideoMaxSize? =
            codecInfo
                .getCapabilitiesForType(mimeType)
                .videoCapabilities
                ?.let { VideoMaxSize(it.supportedWidths.upper, it.supportedHeights.upper) }

        /** The platform only answers this from API 29 on; below it, decoder name prefixes stand in. */
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
            /** Prefixes that mean "software" on API 26–28. */
            private val SOFTWARE_DECODER_PREFIXES = listOf("c2.android.", "OMX.google.", "OMX.SEC.")
        }
    }
