package dev.jellyboost.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.datastore.DataStoreAppPreferences
import dev.jellyboost.core.datastore.DeviceIdStore
import dev.jellyboost.core.datastore.EncryptedSecureCredentialStore
import dev.jellyboost.core.datastore.PreferenceKeys
import dev.jellyboost.core.datastore.SecureCredentialStore
import dev.jellyboost.core.datastore.SharedPreferencesDeviceIdStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DatastoreModule {
    @Binds
    @Singleton
    fun bindSecureCredentialStore(impl: EncryptedSecureCredentialStore): SecureCredentialStore

    @Binds
    @Singleton
    fun bindAppPreferences(impl: DataStoreAppPreferences): AppPreferences

    @Binds
    @Singleton
    fun bindDeviceIdStore(impl: SharedPreferencesDeviceIdStore): DeviceIdStore
}

/**
 * There must be exactly one [DataStore] instance per file for the whole process — DataStore enforces that
 * with an exception — which is what `@Singleton` guarantees. A corrupted file is replaced with empty
 * preferences rather than crashing the app on first read.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesDataStoreModule {
    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile(PreferenceKeys.DATASTORE_NAME) },
        )
}
