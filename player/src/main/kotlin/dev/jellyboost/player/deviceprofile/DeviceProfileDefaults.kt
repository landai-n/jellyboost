package dev.jellyboost.player.deviceprofile

import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue

/**
 * Ceilings [DeviceProfileBuilder] (local playback) and [CastDeviceProfile] (Chromecast) both send
 * the server, straight from jellyfin-web's `browserDeviceProfile.js`.
 *
 * The two profiles' *shapes* stay deliberately separate — local playback probes this device's own
 * decoders, casting is a fixed and conservative intersection every receiver satisfies — but the
 * bitrate figures the server negotiates against are the same question asked twice, so the numbers
 * live here once.
 */
internal object DeviceProfileDefaults {
    /** From jellyfin-web's `browserDeviceProfile.js`. */
    const val MAX_STREAMING_BITRATE: Int = 120_000_000
    const val MAX_STATIC_BITRATE: Int = 100_000_000
    const val MAX_MUSIC_TRANSCODING_BITRATE: Int = 384_000

    /**
     * A [ProfileCondition] the server should *prefer* rather than enforce as a hard requirement.
     *
     * Every condition either profile builds is advisory (`isRequired = false`): a wrong or missing
     * value on the source should narrow the server's choice, not refuse to answer at all.
     */
    fun condition(
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
