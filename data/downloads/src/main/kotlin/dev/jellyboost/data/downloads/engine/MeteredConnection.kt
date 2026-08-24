package dev.jellyboost.data.downloads.engine

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the network the device would use right now is metered.
 *
 * The Wi-Fi-only download preference is normally enforced by WorkManager's `UNMETERED` constraint on
 * the queue worker, but [SubtitleSidecarTopUp] is driven by the metadata refresher on the application
 * scope, outside it. Anything outside the worker that transfers download bytes must consult this
 * alongside `AppPreferences.downloadOverWifiOnly`.
 */
internal fun interface MeteredConnection {
    /** `true` while the active network is metered (mobile data, a metered hotspot). */
    fun isMetered(): Boolean
}

@Singleton
internal class AndroidMeteredConnection
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MeteredConnection {
        /**
         * No connectivity service reads as unmetered: a state that should not happen on a real device
         * must not silently disable a repair path forever.
         */
        override fun isMetered(): Boolean =
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    }
