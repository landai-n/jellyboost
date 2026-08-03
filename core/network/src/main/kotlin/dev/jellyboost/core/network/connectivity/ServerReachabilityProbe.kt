package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.network.ApiClientProvider
import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.di.IoDispatcher
import dev.jellyboost.core.network.model.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "is our server reachable right now?", and rotates through the server's known addresses
 * while doing so (docs/PLAN.md, "Connectivity").
 *
 * A Jellyfin server usually has more than one address — a LAN address, a remote/tunnel address,
 * whatever the user typed at setup — all stored as `ServerAddressEntity` rows. Losing the LAN when
 * leaving the house is not "offline", it is "use the other address", so the probe tries the address
 * currently configured first and then every other candidate, and re-points the shared `ApiClient`
 * at whichever one answers.
 *
 * An address only counts as answering when the server behind it reports the signed-in session's
 * server id (audit NET-01). The stored addresses are routinely private LAN ones that mean a
 * different machine on a different network — the shared client carries the access token, so
 * re-pointing it at whatever 200s `/System/Info/Public` would hand the token to any host squatting
 * that address. A mismatched id is treated exactly like an unreachable address.
 *
 * Every attempt is capped at [PROBE_TIMEOUT_MS]. That cap is the mechanism behind the M6 definition
 * of done — "server-down (Wi-Fi up) degrades without a 30s hang".
 */
@Singleton
class ServerReachabilityProbe
    @Inject
    internal constructor(
        private val probeApi: ServerProbeApi,
        private val serverDao: ServerDao,
        private val sessionStateHolder: SessionStateHolder,
        private val apiClientProvider: ApiClientProvider,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Probes the known addresses in order and returns `true` as soon as one answers **as our
         * server**, having pointed the shared client at it.
         *
         * Returns `false` when nobody is signed in: there is no server to be reachable, and
         * reporting "online" would send the delegating repository down a path with no credentials.
         */
        suspend fun isServerReachable(): Boolean =
            withContext(ioDispatcher) {
                val session =
                    sessionStateHolder.state.value as? SessionState.LoggedIn
                        ?: return@withContext false
                val candidates = candidateAddresses(session)
                if (candidates.isEmpty()) {
                    Timber.d("No server address to probe")
                    return@withContext false
                }

                for (address in candidates) {
                    val probedId =
                        withTimeoutOrNull(PROBE_TIMEOUT_MS) { probeApi.reachableServerId(address) }
                    when {
                        probedId == null -> Unit // Nothing (usable) answered; try the next one.
                        probedId != session.serverId ->
                            // Somebody answered, but not our server — a different Jellyfin
                            // instance, or anything at all squatting a reused LAN address.
                            // Never switch to it.
                            Timber.w("Host at %s answered as server %s, not ours; skipping it", address, probedId)
                        else -> {
                            if (address != apiClientProvider.apiClient.baseUrl) {
                                Timber.i("Server reachable at %s; switching the client over", address)
                                apiClientProvider.useAddress(address)
                            }
                            return@withContext true
                        }
                    }
                }

                Timber.i("None of the %d known server addresses answered", candidates.size)
                false
            }

        /**
         * The addresses to try, in order: the one the client is already using first, then every
         * other address stored for this server. Rotating only matters once the current one failed.
         */
        private suspend fun candidateAddresses(session: SessionState.LoggedIn): List<String> {
            val stored =
                runCatching { serverDao.getAddresses(session.serverId).map { it.address } }
                    .onFailure { Timber.w(it, "Could not read the server's addresses") }
                    .getOrDefault(emptyList())
            return (listOfNotNull(apiClientProvider.apiClient.baseUrl) + stored).distinct()
        }

        companion object {
            /**
             * Per-address budget, in milliseconds.
             *
             * The plan's number: "3s `getPublicSystemInfo`". Long enough for a sleepy LAN server,
             * short enough that a dead one degrades the UI before the user notices.
             */
            const val PROBE_TIMEOUT_MS = 3_000L
        }
    }
