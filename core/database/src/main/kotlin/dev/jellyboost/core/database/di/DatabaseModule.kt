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
import dev.jellyboost.core.database.dao.DownloadDao
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.dao.LibraryViewDao
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.database.dao.UserDao
import dev.jellyboost.core.database.dao.UserDataDao
import javax.inject.Singleton

/** Provides the singleton [JellyfinDatabase] and its DAOs to the rest of the app. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** Builds the single [JellyfinDatabase] instance used across the app's lifetime. */
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

    /** Exposes [JellyfinDatabase.serverDao] for injection. */
    @Provides
    fun provideServerDao(database: JellyfinDatabase): ServerDao = database.serverDao()

    /** Exposes [JellyfinDatabase.userDao] for injection. */
    @Provides
    fun provideUserDao(database: JellyfinDatabase): UserDao = database.userDao()

    /** Exposes [JellyfinDatabase.userDataDao] for injection (M4). */
    @Provides
    fun provideUserDataDao(database: JellyfinDatabase): UserDataDao = database.userDataDao()

    /** Exposes [JellyfinDatabase.itemDao] for injection (M6). */
    @Provides
    fun provideItemDao(database: JellyfinDatabase): ItemDao = database.itemDao()

    /** Exposes [JellyfinDatabase.libraryViewDao] for injection (M6). */
    @Provides
    fun provideLibraryViewDao(database: JellyfinDatabase): LibraryViewDao = database.libraryViewDao()

    /** Exposes [JellyfinDatabase.downloadDao] for injection (M7). */
    @Provides
    fun provideDownloadDao(database: JellyfinDatabase): DownloadDao = database.downloadDao()
}
