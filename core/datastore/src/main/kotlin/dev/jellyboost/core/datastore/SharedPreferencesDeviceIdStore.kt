package dev.jellyboost.core.datastore

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain, not encrypted, because the device id is public by construction; a separate file from the settings
 * DataStore because it must be readable synchronously on the very first access — see [DeviceIdStore].
 */
@Singleton
class SharedPreferencesDeviceIdStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DeviceIdStore {
        private val preferences: SharedPreferences by lazy {
            context.getSharedPreferences(PreferenceKeys.DEVICE_IDENTITY_STORE_NAME, Context.MODE_PRIVATE)
        }

        override fun read(): String? = preferences.getString(PreferenceKeys.DEVICE_ID, null)

        override fun write(id: String) {
            // commit(), not apply(): this runs once per installation, and losing the write to a crash would
            // silently hand the server a different device on the next launch.
            preferences.edit().putString(PreferenceKeys.DEVICE_ID, id).commit()
        }
    }
