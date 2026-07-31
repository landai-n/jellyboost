package dev.jellyboost.core.datastore

/**
 * Names of the persisted user preferences.
 *
 * Access tokens deliberately do NOT live here: they belong in `SecureCredentialStore`
 * (EncryptedSharedPreferences) and never in DataStore or Room (docs/PLAN.md, ":core:datastore").
 */
object PreferenceKeys {
    const val DATASTORE_NAME = "app_preferences"
    const val SECURE_STORE_NAME = "secure_credentials"

    /** Plain preferences file holding only the device id — see `DeviceIdStore`. */
    const val DEVICE_IDENTITY_STORE_NAME = "device_identity"
    const val DEVICE_ID = "device_id"

    const val DOWNLOAD_OVER_WIFI_ONLY = "download_over_wifi_only"
    const val DOWNLOAD_QUALITY = "download_quality"
    const val FORCE_OFFLINE = "force_offline"
    const val MAX_STREAMING_BITRATE = "max_streaming_bitrate"

    /**
     * Which volume downloads are written to.
     *
     * Named for a *volume* rather than for a URI (it was `download_storage_uri` while unread): the
     * picker that shipped chooses between the app-specific directories `getExternalFilesDirs`
     * reports, which are plain `java.io.File` roots and have no document tree behind them. An
     * arbitrary SAF tree would need a second key holding a persisted URI permission, not this one.
     */
    const val DOWNLOAD_STORAGE_VOLUME = "download_storage_volume"

    // M9 player — segment skip (one key per segment type) and picture-in-picture.
    const val SEGMENT_SKIP_INTRO = "segment_skip_intro"
    const val SEGMENT_SKIP_OUTRO = "segment_skip_outro"
    const val PIP_ON_LEAVE = "pip_on_leave"
}
