package dev.jellyboost.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.jellyboost.core.database.DatabaseConstants
import dev.jellyboost.core.database.JellyfinDatabase
import dev.jellyboost.core.database.RoomTransactionRunner
import dev.jellyboost.core.database.TransactionRunner
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.database.dao.UserDataDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideJellyfinDatabase(
        @ApplicationContext context: Context,
    ): JellyfinDatabase =
        Room
            .databaseBuilder(
                context,
                JellyfinDatabase::class.java,
                DatabaseConstants.DATABASE_NAME,
            ).build()

    @Provides
    fun provideServerDao(database: JellyfinDatabase): ServerDao = database.serverDao()

    @Provides
    fun provideUserDao(database: JellyfinDatabase): UserDao = database.userDao()

    @Provides
    fun provideUserDataDao(database: JellyfinDatabase): UserDataDao = database.userDataDao()

    @Provides
    fun provideItemDao(database: JellyfinDatabase): ItemDao = database.itemDao()

    @Provides
    fun provideLibraryViewDao(database: JellyfinDatabase): LibraryViewDao = database.libraryViewDao()

    @Provides
    fun provideDownloadDao(database: JellyfinDatabase): DownloadDao = database.downloadDao()

    /** The seam `:data` uses to make a read-decide-write sequence atomic without seeing the database itself. */
    @Provides
    @Singleton
    fun provideTransactionRunner(database: JellyfinDatabase): TransactionRunner = RoomTransactionRunner(database)
}
