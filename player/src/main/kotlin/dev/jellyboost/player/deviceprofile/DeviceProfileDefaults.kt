package dev.jellyboost.player.deviceprofile

import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue

internal object DeviceProfileDefaults {
    /** From jellyfin-web's `browserDeviceProfile.js`. */
    const val MAX_STREAMING_BITRATE: Int = 120_000_000
    const val MAX_STATIC_BITRATE: Int = 100_000_000
    const val MAX_MUSIC_TRANSCODING_BITRATE: Int = 384_000

    /**
     * Every condition either profile builds is advisory (`isRequired = false`): a wrong or missing
     * value on the source should narrow the server's choice, not make it refuse to answer.
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
