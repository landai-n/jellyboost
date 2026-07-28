package dev.jellyfinnative.core.datastore.di

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
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.core.datastore.DataStoreAppPreferences
import dev.jellyfinnative.core.datastore.EncryptedSecureCredentialStore
import dev.jellyfinnative.core.datastore.PreferenceKeys
import dev.jellyfinnative.core.datastore.SecureCredentialStore
import javax.inject.Singleton

/**
 * Hilt bindings for `:core:datastore`.
 */
@Module
@InstallIn(SingletonComponent::class)
interface DatastoreModule {
    /**
     * Binds [SecureCredentialStore] to its `EncryptedSharedPreferences`-backed implementation.
     */
    @Binds
    @Singleton
    fun bindSecureCredentialStore(impl: EncryptedSecureCredentialStore): SecureCredentialStore

    /** Binds [AppPreferences] to its DataStore-backed implementation (M6). */
    @Binds
    @Singleton
    fun bindAppPreferences(impl: DataStoreAppPreferences): AppPreferences
}

/**
 * Provides the single preferences [DataStore] the app writes its settings to.
 *
 * There must be exactly one instance per file for the whole process — DataStore enforces that
 * with an exception — which is what `@Singleton` here guarantees. A corrupted file is replaced
 * with empty preferences rather than crashing the app on first read.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferencesDataStoreModule {
    /** The `app_preferences` DataStore. */
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
