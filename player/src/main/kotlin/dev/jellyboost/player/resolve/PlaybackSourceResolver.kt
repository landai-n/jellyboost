package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.player.model.PlaybackMediaSource
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rule:
 * 1. **A completed download always wins**, whatever the connection is doing.
 * 2. Otherwise, online: negotiate with the server, under the browse-call ceiling.
 * 3. Otherwise, offline with nothing on disk: fail immediately rather than hanging on a socket timeout.
 *
 * Three requests skip rule 1: `enableDirectPlay == false` ([DecoderFallbackHandler]'s verdict on bytes this
 * device cannot decode — the same bytes are on disk), [PlaybackResolveRequest.forceRemote] (a track the
 * downloaded file does not hold), and [PlaybackResolveRequest.castTarget] (the receiver cannot open a file on
 * this device; serving the download over a local HTTP server is deliberately not attempted).
 */
@Singleton
internal class PlaybackSourceResolver
    @Inject
    constructor(
        private val local: LocalPlaybackResolver,
        private val remote: PlaybackInfoResolver,
        private val connectionState: ConnectionStateProvider,
    ) {
        suspend fun resolve(request: PlaybackResolveRequest): AppResult<PlaybackMediaSource> {
            if (request.enableDirectPlay != false && !request.forceRemote && !request.castTarget) {
                local.resolve(request)?.let { return AppResult.Success(it) }
            }

            if (!connectionState.state.value.isOnline) {
                Timber.i("No local copy of %s and no connection; playback cannot start", request.itemId)
                return AppResult.Failure(AppError.Network())
            }

            return withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { remote.resolve(request) }
                ?: run {
                    Timber.w(
                        "Playback resolve for %s exceeded %d ms; giving up rather than hanging",
                        request.itemId,
                        RESOLVE_TIMEOUT_MS,
                    )
                    // Demotes the server so the *next* call takes the offline path instead of paying the ceiling again.
                    connectionState.reportFailure()
                    AppResult.Failure(AppError.Network())
                }
        }

        companion object {
            /** Milliseconds; deliberately the browse-call ceiling — the user is waiting behind this one. */
            const val RESOLVE_TIMEOUT_MS = JellyfinRepository.ONLINE_CALL_TIMEOUT_MS
        }
    }
