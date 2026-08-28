package dev.jellyboost.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A read failure degrades to the defaults instead of propagating: these preferences gate app-wide behaviour,
 * and a broken settings file must not take the UI down. Writes are **not** swallowed — a setting the user
 * toggled that did not stick is something the caller should see.
 */
@Singleton
class DataStoreAppPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : AppPreferences {
        private val preferences: Flow<Preferences> =
            dataStore.data.catch { error ->
                if (error is IOException) {
                    Timber.w(error, "Could not read app preferences; falling back to defaults")
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }

        /**
         * DataStore re-emits the **whole** `Preferences` snapshot on every `edit`, so without this a single
         * write fans a new value out of every flow below — including [downloadStorageVolumeId], whose collector
         * restarts a full `File.walk` of the downloads tree. A helper, so a preference added later cannot forget.
         */
        private fun <T> preference(read: (Preferences) -> T): Flow<T> = preferences.map(read).distinctUntilChanged()

        override val forceOffline: Flow<Boolean> = preference { it[FORCE_OFFLINE] == true }

        override suspend fun setForceOffline(enabled: Boolean) {
            dataStore.edit { it[FORCE_OFFLINE] = enabled }
        }

        // `?: true` rather than `== true`: an unset Wi-Fi-only preference means "on".
        override val downloadOverWifiOnly: Flow<Boolean> =
            preference { it[DOWNLOAD_OVER_WIFI_ONLY] ?: DEFAULT_WIFI_ONLY }

        override suspend fun setDownloadOverWifiOnly(enabled: Boolean) {
            dataStore.edit { it[DOWNLOAD_OVER_WIFI_ONLY] = enabled }
        }

        // A stored name this build does not know decodes to ORIGINAL, which is what a fresh install gets.
        override val downloadQuality: Flow<DownloadQuality> =
            preference { DownloadQuality.fromNameOrDefault(it[DOWNLOAD_QUALITY]) }

        override suspend fun setDownloadQuality(quality: DownloadQuality) {
            dataStore.edit { it[DOWNLOAD_QUALITY] = quality.name }
        }

        // A blank stored id reads as "unset": an empty string can only come from a bad write.
        override val downloadStorageVolumeId: Flow<String?> =
            preference { it[DOWNLOAD_STORAGE_VOLUME]?.takeIf(String::isNotBlank) }

        override suspend fun setDownloadStorageVolumeId(volumeId: String?) {
            dataStore.edit { store ->
                // Removing the key rather than storing "" keeps "the default" and "a corrupted value"
                // from ever looking alike on the read side.
                if (volumeId.isNullOrBlank()) {
                    store.remove(DOWNLOAD_STORAGE_VOLUME)
                } else {
                    store[DOWNLOAD_STORAGE_VOLUME] = volumeId
                }
            }
        }

        override val introSkipMode: Flow<SegmentSkipMode> = preference { it.skipMode(SEGMENT_SKIP_INTRO) }

        override suspend fun setIntroSkipMode(mode: SegmentSkipMode) {
            dataStore.edit { it[SEGMENT_SKIP_INTRO] = mode.name }
        }

        override val outroSkipMode: Flow<SegmentSkipMode> = preference { it.skipMode(SEGMENT_SKIP_OUTRO) }

        override suspend fun setOutroSkipMode(mode: SegmentSkipMode) {
            dataStore.edit { it[SEGMENT_SKIP_OUTRO] = mode.name }
        }

        override val pipOnLeave: Flow<Boolean> = preference { it[PIP_ON_LEAVE] ?: DEFAULT_PIP_ON_LEAVE }

        override suspend fun setPipOnLeave(enabled: Boolean) {
            dataStore.edit { it[PIP_ON_LEAVE] = enabled }
        }

        // A stored name this build does not know decodes to SYSTEM, which is what a fresh install gets.
        override val themeMode: Flow<ThemeMode> = preference { ThemeMode.fromNameOrDefault(it[THEME_MODE]) }

        override suspend fun setThemeMode(mode: ThemeMode) {
            dataStore.edit { it[THEME_MODE] = mode.name }
        }

        override val dynamicColorEnabled: Flow<Boolean> =
            preference { it[DYNAMIC_COLOR_ENABLED] ?: DEFAULT_DYNAMIC_COLOR }

        override suspend fun setDynamicColorEnabled(enabled: Boolean) {
            dataStore.edit { it[DYNAMIC_COLOR_ENABLED] = enabled }
        }

        override val styledAssSubtitles: Flow<Boolean> =
            preference { it[STYLED_ASS_SUBTITLES] ?: DEFAULT_STYLED_ASS_SUBTITLES }

        override suspend fun setStyledAssSubtitles(enabled: Boolean) {
            dataStore.edit { it[STYLED_ASS_SUBTITLES] = enabled }
        }

        // A non-positive stored value reads as "never measured": sending a zero or negative cap to the
        // server is worse than sending none.
        override val maxStreamingBitrate: Flow<Int?> =
            preference { it[MAX_STREAMING_BITRATE]?.takeIf { bitrate -> bitrate > 0 } }

        override suspend fun setMaxStreamingBitrate(bitrate: Int?) {
            dataStore.edit { store ->
                // Removing rather than storing 0: "nothing learned yet" and "learned the link carries
                // nothing" must not look alike on the read side.
                if (bitrate == null) {
                    store.remove(MAX_STREAMING_BITRATE)
                } else {
                    store[MAX_STREAMING_BITRATE] = bitrate
                }
            }
        }

        /**
         * Enums are persisted by `name`, which survives reordering; a name that no longer exists — a downgrade
         * or a renamed constant — reads as "unset", the same safe answer a fresh install gets.
         */
        private fun Preferences.skipMode(key: Preferences.Key<String>): SegmentSkipMode =
            this[key]?.let { stored -> SegmentSkipMode.entries.firstOrNull { it.name == stored } }
                ?: DEFAULT_SKIP_MODE

        private companion object {
            val FORCE_OFFLINE = booleanPreferencesKey(PreferenceKeys.FORCE_OFFLINE)
            val DOWNLOAD_OVER_WIFI_ONLY = booleanPreferencesKey(PreferenceKeys.DOWNLOAD_OVER_WIFI_ONLY)
            val DOWNLOAD_QUALITY = stringPreferencesKey(PreferenceKeys.DOWNLOAD_QUALITY)
            val DOWNLOAD_STORAGE_VOLUME = stringPreferencesKey(PreferenceKeys.DOWNLOAD_STORAGE_VOLUME)
            val SEGMENT_SKIP_INTRO = stringPreferencesKey(PreferenceKeys.SEGMENT_SKIP_INTRO)
            val SEGMENT_SKIP_OUTRO = stringPreferencesKey(PreferenceKeys.SEGMENT_SKIP_OUTRO)
            val PIP_ON_LEAVE = booleanPreferencesKey(PreferenceKeys.PIP_ON_LEAVE)
            val MAX_STREAMING_BITRATE = intPreferencesKey(PreferenceKeys.MAX_STREAMING_BITRATE)
            val THEME_MODE = stringPreferencesKey(PreferenceKeys.THEME_MODE)
            val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey(PreferenceKeys.DYNAMIC_COLOR_ENABLED)
            val STYLED_ASS_SUBTITLES = booleanPreferencesKey(PreferenceKeys.STYLED_ASS_SUBTITLES)

            const val DEFAULT_WIFI_ONLY = true

            val DEFAULT_SKIP_MODE = SegmentSkipMode.SHOW_BUTTON

            const val DEFAULT_PIP_ON_LEAVE = true

            const val DEFAULT_DYNAMIC_COLOR = false

            const val DEFAULT_STYLED_ASS_SUBTITLES = false
        }
    }
