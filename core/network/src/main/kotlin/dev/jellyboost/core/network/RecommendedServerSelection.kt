package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore

/**
 * Follows jellyfin-android's `setup/ConnectionHelper.kt` scoring rules: the first `GREAT` candidate wins
 * outright, otherwise the first `GOOD` one is accepted, otherwise nothing is usable and the rejects are split
 * into *unreachable* (`systemInfo` failed — never answered) and *incompatible* (answered, wrong product or
 * unsupported version) so the setup screen can say which is which.
 *
 * Pure and free of SDK I/O so the branching can be unit-tested.
 */
internal fun selectRecommendedServer(servers: List<RecommendedServerInfo>): AppResult<RecommendedServerInfo> {
    val winner =
        servers.firstOrNull { it.score == RecommendedServerInfoScore.GREAT }
            ?: servers.firstOrNull { it.score == RecommendedServerInfoScore.GOOD }

    if (winner != null) {
        return AppResult.Success(winner)
    }

    val (unreachable, incompatible) =
        servers.partition { candidate -> candidate.systemInfo.getOrNull() == null }

    return AppResult.Failure(
        AppError.ServerResolution(
            unreachableAddresses = unreachable.map { it.address },
            incompatibleAddresses = incompatible.map { it.address },
        ),
    )
}
