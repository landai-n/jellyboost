package dev.jellyfinnative.player.deviceprofile

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this device can actually decode, expressed in Jellyfin's vocabulary.
 *
 * @param videoCodecs Jellyfin codec names with at least one decoder on the device.
 * @param audioCodecs likewise for audio; note that [DeviceProfileBuilder] adds the codecs the
 *   bundled ffmpeg extension handles on top of these.
 * @param videoProfiles per video codec, the profile names the device's decoders advertise. An
 *   empty set means "profiles unknown", and no codec profile is emitted for it.
 */
data class DeviceCodecs(
    val videoCodecs: Set<String> = emptySet(),
    val audioCodecs: Set<String> = emptySet(),
    val videoProfiles: Map<String, Set<String>> = emptyMap(),
)

/**
 * Reads the device's decoder list.
 *
 * Exists as an interface purely so [DeviceProfileBuilder] — whose output is what decides between
 * direct play and a transcode for every single item — can be unit tested against a known set of
 * codecs. `MediaCodecList` itself is a throwing stub in local unit tests.
 */
fun interface MediaCodecProbe {
    fun probe(): DeviceCodecs
}

/**
 * [MediaCodecProbe] over `MediaCodecList(REGULAR_CODECS)`.
 *
 * Encoders are skipped: a device that can *encode* HEVC tells us nothing about whether it can play
 * an HEVC file. Capabilities for the same codec reported by several decoders are merged, because
 * the union is what the device as a whole supports.
 */
@Singleton
internal class PlatformMediaCodecProbe
    @Inject
    constructor() : MediaCodecProbe {
        override fun probe(): DeviceCodecs {
            val videoCodecs = mutableSetOf<String>()
            val audioCodecs = mutableSetOf<String>()
            val videoProfiles = mutableMapOf<String, MutableSet<String>>()

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
                        }
                    }
                }
            }

            Timber.d("Device decodes video %s, audio %s", videoCodecs, audioCodecs)
            return DeviceCodecs(
                videoCodecs = videoCodecs,
                audioCodecs = audioCodecs,
                videoProfiles = videoProfiles.mapValues { (_, profiles) -> profiles.toSet() },
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
    }
