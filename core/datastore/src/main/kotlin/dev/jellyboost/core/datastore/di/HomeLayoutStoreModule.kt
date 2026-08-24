package dev.jellyboost.core.datastore.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.core.datastore.HomeLayoutStore
import dev.jellyboost.core.datastore.SharedPreferencesHomeLayoutStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface HomeLayoutStoreModule {
    @Binds
    @Singleton
    fun bindHomeLayoutStore(impl: SharedPreferencesHomeLayoutStore): HomeLayoutStore
}
