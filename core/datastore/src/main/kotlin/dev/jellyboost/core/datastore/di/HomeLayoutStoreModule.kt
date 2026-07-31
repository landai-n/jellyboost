package dev.jellyboost.core.datastore.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.core.datastore.HomeLayoutStore
import dev.jellyboost.core.datastore.SharedPreferencesHomeLayoutStore
import javax.inject.Singleton

/**
 * Hilt binding for the home-layout cache.
 *
 * Kept in a file of its own rather than in `DatastoreModule` so that the server-configured home
 * layout — a self-contained, disposable cache — can be added, moved or dropped without touching
 * the module that wires the app's real preferences (the `UserDataModule` precedent in `:data`).
 */
@Module
@InstallIn(SingletonComponent::class)
interface HomeLayoutStoreModule {
    /** Binds [HomeLayoutStore] to its plain-`SharedPreferences` implementation. */
    @Binds
    @Singleton
    fun bindHomeLayoutStore(impl: SharedPreferencesHomeLayoutStore): HomeLayoutStore
}
