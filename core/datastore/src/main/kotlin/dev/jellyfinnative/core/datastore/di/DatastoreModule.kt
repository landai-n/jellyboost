package dev.jellyfinnative.core.datastore.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyfinnative.core.datastore.EncryptedSecureCredentialStore
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
}
