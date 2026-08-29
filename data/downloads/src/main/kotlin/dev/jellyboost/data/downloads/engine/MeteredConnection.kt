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
 *
 * **Two answers to "is this metered" coexist deliberately**, and neither replaces the other:
 * this one is a **synchronous one-shot** for a background repair path that has to decide once,
 * before it starts, on whatever thread it is already on. `DownloadRepository.onMeteredNetwork` is a
 * **`Flow`** for a UI that must react while the user watches the network change under it, and it is
 * `false` when there is no network at all so a notice cannot claim the queue is waiting for Wi-Fi on
 * a device that is simply offline. Collecting a flow here would mean a coroutine and a subscription
 * for a single boolean; polling `isMetered()` on a timer there would mean a UI that lags the radio.
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
