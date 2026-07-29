package dev.jellyfinnative.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppPreferences] on Jetpack DataStore.
 *
 * A read failure (a truncated file after a crash, a revoked-storage edge case) degrades to the
 * defaults instead of propagating: these preferences gate app-wide behaviour, and a broken
 * settings file must not be able to take the whole UI down with it. Writes are not swallowed —
 * a setting the user toggled that did not stick is something the caller should see.
 */
@Singleton
class DataStoreAppPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : AppPreferences {
        /** The stored preferences, with a read failure degraded to "no preferences set". */
        private val preferences: Flow<Preferences> =
            dataStore.data.catch { error ->
                if (error is IOException) {
                    Timber.w(error, "Could not read app preferences; falling back to defaults")
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }

        override val forceOffline: Flow<Boolean> = preferences.map { it[FORCE_OFFLINE] == true }

        override suspend fun setForceOffline(enabled: Boolean) {
            dataStore.edit { it[FORCE_OFFLINE] = enabled }
        }

        // `?: true` rather than `== true`: an unset Wi-Fi-only preference means "on", which is the
        // one default in this file that is not simply `false` (see AppPreferences' KDoc).
        override val downloadOverWifiOnly: Flow<Boolean> =
            preferences.map { it[DOWNLOAD_OVER_WIFI_ONLY] ?: DEFAULT_WIFI_ONLY }

        override suspend fun setDownloadOverWifiOnly(enabled: Boolean) {
            dataStore.edit { it[DOWNLOAD_OVER_WIFI_ONLY] = enabled }
        }

        // M9 player ---------------------------------------------------------------------------

        override val introSkipMode: Flow<SegmentSkipMode> = preferences.map { it.skipMode(SEGMENT_SKIP_INTRO) }

        override suspend fun setIntroSkipMode(mode: SegmentSkipMode) {
            dataStore.edit { it[SEGMENT_SKIP_INTRO] = mode.name }
        }

        override val outroSkipMode: Flow<SegmentSkipMode> = preferences.map { it.skipMode(SEGMENT_SKIP_OUTRO) }

        override suspend fun setOutroSkipMode(mode: SegmentSkipMode) {
            dataStore.edit { it[SEGMENT_SKIP_OUTRO] = mode.name }
        }

        override val pipOnLeave: Flow<Boolean> = preferences.map { it[PIP_ON_LEAVE] ?: DEFAULT_PIP_ON_LEAVE }

        override suspend fun setPipOnLeave(enabled: Boolean) {
            dataStore.edit { it[PIP_ON_LEAVE] = enabled }
        }

        /**
         * The stored skip mode, degraded to the default rather than throwing.
         *
         * Enums are persisted by `name`, which survives reordering; a name that no longer exists —
         * a downgrade, or a renamed constant — reads as "unset", which is the same safe answer a
         * fresh install gets.
         */
        private fun Preferences.skipMode(key: Preferences.Key<String>): SegmentSkipMode =
            this[key]?.let { stored -> SegmentSkipMode.entries.firstOrNull { it.name == stored } }
                ?: DEFAULT_SKIP_MODE

        private companion object {
            val FORCE_OFFLINE = booleanPreferencesKey(PreferenceKeys.FORCE_OFFLINE)
            val DOWNLOAD_OVER_WIFI_ONLY = booleanPreferencesKey(PreferenceKeys.DOWNLOAD_OVER_WIFI_ONLY)
            val SEGMENT_SKIP_INTRO = stringPreferencesKey(PreferenceKeys.SEGMENT_SKIP_INTRO)
            val SEGMENT_SKIP_OUTRO = stringPreferencesKey(PreferenceKeys.SEGMENT_SKIP_OUTRO)
            val PIP_ON_LEAVE = booleanPreferencesKey(PreferenceKeys.PIP_ON_LEAVE)

            /** Downloads are Wi-Fi-only until the user says otherwise. */
            const val DEFAULT_WIFI_ONLY = true

            /** Segments offer a button and never move playback on their own until asked. */
            val DEFAULT_SKIP_MODE = SegmentSkipMode.SHOW_BUTTON

            /** Leaving the app mid-film floats the video rather than losing it. */
            const val DEFAULT_PIP_ON_LEAVE = true
        }
    }
