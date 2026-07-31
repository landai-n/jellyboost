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

/**
 * Hilt bindings for the M4 user-data layer.
 *
 * Kept out of `DataModule` so the two milestones do not edit the same file: M3 and M4 are built on
 * parallel worktree branches.
 */
@Module
@InstallIn(SingletonComponent::class)
interface UserDataModule {
    /** Binds the local-first user-data repository. */
    @Binds
    @Singleton
    fun bindUserDataRepository(impl: UserDataRepositoryImpl): UserDataRepository

    /** Binds retry scheduling to WorkManager. */
    @Binds
    @Singleton
    fun bindUserDataSyncScheduler(impl: WorkManagerUserDataSyncScheduler): UserDataSyncScheduler

    companion object {
        /**
         * The clock every `updatedAt` / `lastPlayedDate` stamp comes from.
         *
         * Injected rather than called statically so the sync ordering — which is entirely about
         * comparing timestamps — is testable with a fixed clock.
         */
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
