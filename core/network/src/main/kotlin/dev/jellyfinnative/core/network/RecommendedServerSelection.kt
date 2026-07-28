package dev.jellyfinnative.core.network

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore

/**
 * Picks the address candidate to connect to, following jellyfin-android's
 * `setup/ConnectionHelper.kt` scoring rules:
 *
 * * the first `GREAT` candidate wins outright;
 * * otherwise the first `GOOD` candidate is accepted;
 * * otherwise nothing is usable, and the rejected candidates are split into *unreachable*
 *   (`systemInfo` failed — the address never answered) and *incompatible* (it answered, but
 *   with the wrong product or an unsupported version) so the setup screen can say which is
 *   which.
 *
 * Kept pure and free of SDK I/O precisely so this branching can be unit-tested; an empty
 * [servers] list yields an [AppError.ServerResolution] with two empty lists, which the UI
 * renders as the generic "no server found" message.
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
