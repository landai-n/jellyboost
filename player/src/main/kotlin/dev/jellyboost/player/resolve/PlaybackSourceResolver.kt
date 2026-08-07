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
 * Decides *where* an item is played from, and is the only thing `PlayerViewModel` asks.
 *
 * ### The rule
 * 1. **A completed download always wins** — whatever the connection is doing.
 * 2. Otherwise, online: negotiate with the server, exactly as M5 did.
 * 3. Otherwise, offline with nothing on disk: fail immediately with a network error.
 *
 * Rule 1 is the milestone's whole point and is recorded in DECISIONS.md (2026-07-29): the plan says
 * "downloaded media visible and playable in the same screens", and a user who deliberately put a
 * film on their device does not expect a full-bandwidth stream of it just because Wi-Fi happens to
 * be up. It also makes the offline behaviour identical to the online one instead of a special mode,
 * so the path the M8 device walk exercises is the path that runs every day.
 *
 * Rule 3 is what stops the Play button from hanging when the app already knows it is offline: a
 * `PlaybackInfo` POST fired into a dead network would sit on the SDK's socket timeout behind a
 * spinner with no cancel.
 *
 * Rule 2 carries the other half of that guarantee. A server that died *after* the last browse call
 * still reads as online — nothing has probed it since — so the resolve gets the same ceiling
 * [JellyfinRepository.ONLINE_CALL_TIMEOUT_MS] puts on every browse call, and a resolve that hits it
 * reports the failure so the reachability probe demotes the server before the user taps Play again.
 *
 * ### The two exceptions to rule 1
 * A request that has explicitly forbidden direct play is [DecoderFallbackHandler]'s
 * "this device cannot decode these bytes" verdict, and the bytes on disk are the same bytes. Such a
 * request skips the local copy and goes to the server, which is the only party that can transcode —
 * and offline it surfaces the error instead of looping over a file that has already failed.
 *
 * [PlaybackResolveRequest.forceRemote] is the second, and it is the user asking rather than a
 * failure: online, the pickers offer every track of the *source*, and one the downloaded file does
 * not hold can only come from the server. Rule 1 would hand back the same file and the same tracks,
 * which is precisely the loop `PlayerMessage.TrackUnavailableOffline` was invented to stop. Offline
 * it fails like any other request with nothing to reach, and the caller falls back to the file.
 *
 * [PlaybackResolveRequest.castTarget] joins it on the same line, for a blunter reason: the file on
 * disk is on *this* device, and the receiver that would have to open it is on the other side of the
 * network. Casting therefore always streams from the server, and serving the download over a local
 * HTTP server is explicitly out of scope (docs/notes/chromecast-m12-plan.md, key decision 3).
 */
@Singleton
internal class PlaybackSourceResolver
    @Inject
    constructor(
        private val local: LocalPlaybackResolver,
        private val remote: PlaybackInfoResolver,
        private val connectionState: ConnectionStateProvider,
    ) {
        /** Resolves [request] into something the player can open, wherever it lives. */
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
                    // Demotes the server so the *next* call takes the offline path immediately
                    // instead of paying the ceiling again.
                    connectionState.reportFailure()
                    AppResult.Failure(AppError.Network())
                }
        }

        companion object {
            /**
             * Ceiling on the playback negotiation, in milliseconds.
             *
             * Deliberately the same number as every browse call rather than one of its own: a
             * `PlaybackInfo` POST is the one server call the user is actively waiting behind, so it
             * has no business being allowed to run longer than the calls that merely fill a grid.
             */
            const val RESOLVE_TIMEOUT_MS = JellyfinRepository.ONLINE_CALL_TIMEOUT_MS
        }
    }
