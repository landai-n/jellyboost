package dev.jellyboost.core.network.connectivity

import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.database.dao.ServerDao
import dev.jellyboost.core.network.ApiClientProvider
import dev.jellyboost.core.network.SessionStateHolder
import dev.jellyboost.core.network.hostForLog
import dev.jellyboost.core.network.model.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers "is our server reachable right now?", rotating through the server's known addresses: losing the
 * LAN when leaving the house is not "offline", it is "use the other address", so the probe tries the
 * configured address first, then every other stored candidate, and re-points the shared `ApiClient`.
 *
 * An address counts as answering **only** when the server behind it reports the signed-in session's server
 * id. Stored addresses are routinely private LAN ones that mean a different machine on a different network,
 * and the shared client carries the access token — re-pointing it at whatever 200s `/System/Info/Public`
 * would hand that token to any host squatting the address. A mismatched id is treated as unreachable.
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
         * `false` when nobody is signed in: there is no server to be reachable, and reporting "online" would send
         * the delegating repository down a path with no credentials.
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
                            // Somebody answered, but not our server — a different instance, or anything
                            // squatting a reused LAN address. Never switch to it.
                            //
                            // Debug, and host only, here and below: this log is a list of where the user's
                            // server lives, and it is what they paste when connectivity misbehaves.
                            Timber.d(
                                "Host %s answered as server %s, not ours; skipping it",
                                hostForLog(address),
                                probedId,
                            )
                        else -> {
                            if (address != apiClientProvider.apiClient.baseUrl) {
                                Timber.d("Server reachable at %s; switching the client over", hostForLog(address))
                                apiClientProvider.useAddress(address)
                            }
                            return@withContext true
                        }
                    }
                }

                Timber.d("None of the %d known server addresses answered", candidates.size)
                false
            }

        /** The address the client is already using first; rotating only matters once that one failed. */
        private suspend fun candidateAddresses(session: SessionState.LoggedIn): List<String> {
            // `runCatchingUnlessCancelled`, not `runCatching`: the plain one swallows the CancellationException
            // that unwinds a cancelled probe, turning "the caller gave up" into "the server has no addresses".
            val stored =
                runCatchingUnlessCancelled { serverDao.getAddresses(session.serverId).map { it.address } }
                    .onFailure { Timber.w(it, "Could not read the server's addresses") }
                    .getOrDefault(emptyList())
            return (listOfNotNull(apiClientProvider.apiClient.baseUrl) + stored).distinct()
        }

        companion object {
            /** Long enough for a sleepy LAN server, short enough that a dead one degrades the UI unnoticed. */
            const val PROBE_TIMEOUT_MS = 3_000L
        }
    }
