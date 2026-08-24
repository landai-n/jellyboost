package dev.jellyboost.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.data.userdata.UserDataRepository
import dev.jellyboost.data.userdata.UserDataRepositoryImpl
import dev.jellyboost.data.userdata.UserDataSyncScheduler
import dev.jellyboost.data.userdata.WorkManagerUserDataSyncScheduler
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface UserDataModule {
    @Binds
    @Singleton
    fun bindUserDataRepository(impl: UserDataRepositoryImpl): UserDataRepository

    @Binds
    @Singleton
    fun bindUserDataSyncScheduler(impl: WorkManagerUserDataSyncScheduler): UserDataSyncScheduler

    companion object {
        /**
         * Injected rather than called statically so the sync ordering — entirely about comparing
         * timestamps — is testable with a fixed clock.
         */
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
