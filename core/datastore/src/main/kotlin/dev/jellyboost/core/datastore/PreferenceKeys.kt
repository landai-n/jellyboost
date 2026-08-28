package dev.jellyboost.core.datastore

/**
 * Access tokens deliberately do NOT live here: they belong in `SecureCredentialStore` and never in DataStore
 * or Room.
 */
object PreferenceKeys {
    const val DATASTORE_NAME = "app_preferences"
    const val SECURE_STORE_NAME = "secure_credentials"

    const val DEVICE_IDENTITY_STORE_NAME = "device_identity"
    const val DEVICE_ID = "device_id"

    const val DOWNLOAD_OVER_WIFI_ONLY = "download_over_wifi_only"
    const val DOWNLOAD_QUALITY = "download_quality"
    const val FORCE_OFFLINE = "force_offline"
    const val MAX_STREAMING_BITRATE = "max_streaming_bitrate"

    /**
     * Named for a *volume* rather than a URI: the picker chooses between the `getExternalFilesDirs` roots,
     * which are plain `File`s with no document tree behind them. An SAF tree would need its own key holding a
     * persisted URI permission.
     */
    const val DOWNLOAD_STORAGE_VOLUME = "download_storage_volume"

    const val SEGMENT_SKIP_INTRO = "segment_skip_intro"
    const val SEGMENT_SKIP_OUTRO = "segment_skip_outro"
    const val PIP_ON_LEAVE = "pip_on_leave"

    const val THEME_MODE = "theme_mode"
    const val DYNAMIC_COLOR_ENABLED = "dynamic_color_enabled"
}
