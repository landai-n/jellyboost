package dev.jellyfinnative.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
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

        private companion object {
            val FORCE_OFFLINE = booleanPreferencesKey(PreferenceKeys.FORCE_OFFLINE)
            val DOWNLOAD_OVER_WIFI_ONLY = booleanPreferencesKey(PreferenceKeys.DOWNLOAD_OVER_WIFI_ONLY)

            /** Downloads are Wi-Fi-only until the user says otherwise. */
            const val DEFAULT_WIFI_ONLY = true
        }
    }
