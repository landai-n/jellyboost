package dev.jellyfinnative.core.datastore

/**
 * Names of the persisted user preferences.
 *
 * Access tokens deliberately do NOT live here: they belong in `SecureCredentialStore`
 * (EncryptedSharedPreferences) and never in DataStore or Room (docs/PLAN.md, ":core:datastore").
 */
object PreferenceKeys {
    const val DATASTORE_NAME = "app_preferences"
    const val SECURE_STORE_NAME = "secure_credentials"

    const val DOWNLOAD_OVER_WIFI_ONLY = "download_over_wifi_only"
    const val FORCE_OFFLINE = "force_offline"
    const val MAX_STREAMING_BITRATE = "max_streaming_bitrate"
    const val DOWNLOAD_STORAGE_URI = "download_storage_uri"

    // M9 player — segment skip (one key per segment type) and picture-in-picture.
    const val SEGMENT_SKIP_INTRO = "segment_skip_intro"
    const val SEGMENT_SKIP_OUTRO = "segment_skip_outro"
    const val PIP_ON_LEAVE = "pip_on_leave"
}
