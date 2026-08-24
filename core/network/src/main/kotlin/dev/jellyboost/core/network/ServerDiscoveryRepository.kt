package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.network.model.DiscoveredServer
import dev.jellyboost.core.network.model.ResolvedServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Nothing is persisted here: a resolved server reaches Room only once the user signs in on it ([AuthRepository]). */
@Singleton
class ServerDiscoveryRepository
    @Inject
    internal constructor(
        private val apiFacade: JellyfinApiFacade,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * The flow is finite — the SDK stops after its discovery window. An announcement carrying an
         * unparseable server id is dropped with a warning rather than failing the whole flow.
         */
        fun discoverLocalServers(): Flow<DiscoveredServer> =
            apiFacade
                .discoverLocalServers()
                .mapNotNull { info ->
                    val id = info.id.toUUIDOrNull()
                    if (id == null) {
                        Timber.w("Ignoring discovered server %s with unparseable id", info.name)
                        null
                    } else {
                        DiscoveredServer(id = id, name = info.name, address = info.address)
                    }
                }.flowOn(ioDispatcher)

        /**
         * Expands [input] (a hostname, an IP, a full URL…) into candidate base URLs, probes them
         * all, and returns the best usable one.
         *
         * Failure carries [AppError.ServerResolution] with the addresses that could not be
         * reached and those that answered but are not a compatible Jellyfin server, which is what
         * the setup screen's error copy is built from.
         */
        @Suppress(
            "ReturnCount",
        )
        suspend fun resolveServerAddress(input: String): AppResult<ResolvedServer> {
            // Debug, and host only, on every line below that holds an address: this path knows where a
            // user's server lives, and its logs end up pasted into bug reports. See [hostForLog].
            Timber.d("Resolving server address for '%s'", hostForLog(input))

            val candidates =
                when (val result = runCatchingApi { apiFacade.getAddressCandidates(input) }) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> return result
                }

            if (candidates.isEmpty()) {
                Timber.d("Input '%s' produced no address candidates", hostForLog(input))
                return AppResult.Failure(AppError.ServerResolution())
            }

            val recommended =
                when (val result = runCatchingApi { apiFacade.getRecommendedServers(candidates) }) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> return result
                }

            val selected =
                when (val result = selectRecommendedServer(recommended)) {
                    is AppResult.Success -> result.value
                    is AppResult.Failure -> {
                        Timber.d("No usable server among candidates %s", candidates.map(::hostForLog))
                        return result
                    }
                }

            val systemInfo = selected.systemInfo.getOrNull()
            val serverId = systemInfo?.id?.toUUIDOrNull()
            if (systemInfo == null || serverId == null) {
                // Stays a warning — a real server-side fault — but the address is still reduced to its host.
                Timber.w("Server at %s answered without a usable id", hostForLog(selected.address))
                return AppResult.Failure(AppError.Server(statusCode = null))
            }

            Timber.d(
                "Resolved %s (score %s, version %s)",
                hostForLog(selected.address),
                selected.score,
                systemInfo.version,
            )

            return AppResult.Success(
                ResolvedServer(
                    serverId = serverId,
                    name = systemInfo.serverName.orEmpty().ifBlank { selected.address },
                    version = systemInfo.version,
                    address = selected.address,
                ),
            )
        }
    }
