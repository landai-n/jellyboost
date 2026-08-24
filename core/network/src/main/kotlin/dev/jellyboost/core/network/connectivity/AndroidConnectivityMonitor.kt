package dev.jellyboost.core.network.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signal is `NET_CAPABILITY_INTERNET` and deliberately **not** `NET_CAPABILITY_VALIDATED`: a self-hosted
 * Jellyfin on a LAN with no internet uplink is the app's bread-and-butter case, and treating an unvalidated
 * Wi-Fi as "no network" would lock exactly that setup offline. Whether the server answers over this network
 * is [ServerReachabilityProbe]'s question.
 */
@Singleton
class AndroidConnectivityMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ConnectivityMonitor {
        /**
         * Shared so the system callback is registered **once** however many collectors there are; `callbackFlow`
         * is cold, so each collector would otherwise hold its own registration for the life of the process.
         *
         * `replay = 1` keeps the seed meaningful — the callbacks report only *changes*, so a late subscriber
         * would sit with no value until the network next moved. `replayExpirationMillis = 0` clears that cache
         * once the upstream stops, so a subscriber arriving later is never handed a verdict from before it.
         */
        override val hasNetwork: Flow<Boolean> =
            callbackFlow {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                if (manager == null) {
                    // No connectivity service at all: assume a network rather than locking the app offline forever.
                    Timber.w("No ConnectivityManager; assuming the network is up")
                    send(true)
                    awaitClose { }
                    return@callbackFlow
                }

                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trySend(manager.isUsable(network))
                        }

                        override fun onLost(network: Network) {
                            trySend(false)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            trySend(capabilities.carriesInternet())
                        }

                        override fun onUnavailable() {
                            trySend(false)
                        }
                    }

                // Seed with the state at collection time; the callbacks only report *changes*.
                send(manager.isUsable(manager.activeNetwork))
                manager.registerDefaultNetworkCallback(callback)

                awaitClose { manager.unregisterNetworkCallback(callback) }
            }.distinctUntilChanged()
                .conflate()
                .shareIn(
                    scope = appScope,
                    started =
                        SharingStarted.WhileSubscribed(
                            stopTimeoutMillis = UNSUBSCRIBE_GRACE_MS,
                            replayExpirationMillis = 0L,
                        ),
                    replay = 1,
                )

        private fun ConnectivityManager.isUsable(network: Network?): Boolean =
            network != null && getNetworkCapabilities(network).carriesInternet()

        private fun NetworkCapabilities?.carriesInternet(): Boolean =
            this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        private companion object {
            /** Re-registering across a momentary gap would cost a pair of binder calls for nothing. */
            const val UNSUBSCRIBE_GRACE_MS = 5_000L
        }
    }
