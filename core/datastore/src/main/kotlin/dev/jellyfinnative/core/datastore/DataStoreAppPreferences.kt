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
        override val forceOffline: Flow<Boolean> =
            dataStore.data
                .catch { error ->
                    if (error is IOException) {
                        Timber.w(error, "Could not read app preferences; falling back to defaults")
                        emit(emptyPreferences())
                    } else {
                        throw error
                    }
                }.map { it[FORCE_OFFLINE] == true }

        override suspend fun setForceOffline(enabled: Boolean) {
            dataStore.edit { it[FORCE_OFFLINE] = enabled }
        }

        private companion object {
            val FORCE_OFFLINE = booleanPreferencesKey(PreferenceKeys.FORCE_OFFLINE)
        }
    }
