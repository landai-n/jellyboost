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
 * [ConnectivityMonitor] on `ConnectivityManager.registerDefaultNetworkCallback`.
 *
 * The default-network callback is what makes the airplane-mode switch feel instant: the system
 * delivers `onLost` within milliseconds, so the app flips to its offline path without polling and
 * without waiting for a request to time out.
 *
 * The signal is `NET_CAPABILITY_INTERNET` and deliberately **not** `NET_CAPABILITY_VALIDATED`:
 * a self-hosted Jellyfin on a LAN with no internet uplink is the app's bread-and-butter case, and
 * treating an unvalidated Wi-Fi as "no network" would lock exactly that setup offline. Whether the
 * server answers over this network is [ServerReachabilityProbe]'s question, not this class's.
 */
@Singleton
class AndroidConnectivityMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ConnectivityMonitor {
        /**
         * The system callback, registered **once** however many collectors there are.
         *
         * `callbackFlow` is cold, so without sharing each collector would register its own
         * `NetworkCallback` — a binder round trip and a system-side registration per subscriber.
         * There are two, both for the life of the process (`ConnectionStateProvider` combines this
         * into its state *and* re-probes the server on every network change), so the app would
         * permanently hold two registrations delivering the same edges.
         *
         * `replay = 1` is what keeps the seed meaningful: the callbacks report only *changes*, so a
         * late subscriber that missed the initial `send` would otherwise sit with no value until
         * the network next moved. `replayExpirationMillis = 0` clears that cache as soon as the
         * upstream stops, so a subscriber arriving after a quiet period is never handed a verdict
         * from before it — it waits the millisecond for a fresh seed instead.
         */
        override val hasNetwork: Flow<Boolean> =
            callbackFlow {
                val manager = context.getSystemService(ConnectivityManager::class.java)
                if (manager == null) {
                    // No connectivity service at all (should not happen on a real device): assume a
                    // network rather than locking the app offline forever.
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
            /**
             * How long the registration outlives its last collector.
             *
             * Only ever exercised in tests and at process teardown today — the app's collectors are
             * process-lifetime — but unregistering and re-registering across a momentary gap would
             * cost a pair of binder calls for nothing.
             */
            const val UNSUBSCRIBE_GRACE_MS = 5_000L
        }
    }
