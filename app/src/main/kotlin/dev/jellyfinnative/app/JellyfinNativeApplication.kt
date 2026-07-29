package dev.jellyfinnative.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.jellyfinnative.data.userdata.UserDataSyncTrigger
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Owns Hilt's object graph and provides the WorkManager configuration used by the download and
 * user-data sync workers (docs/PLAN.md, ":app").
 */
@HiltAndroidApp
class JellyfinNativeApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Watches the connection so user-data changes made offline reach the server (M8).
     *
     * Injected here rather than from a ViewModel because it has to run whether or not any screen is
     * showing — a device that comes back online with the app in the background is the case that
     * matters, and it is also what makes a pending row survive an app kill.
     */
    @Inject
    lateinit var userDataSyncTrigger: UserDataSyncTrigger

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        userDataSyncTrigger.start()
    }
}
