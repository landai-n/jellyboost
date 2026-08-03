package dev.jellyboost.player.session

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.ticksToMillis
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.resolve.ExoMediaSourceFactory
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.resolve.PlaybackSourceResolver
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a [PlaybackResolveRequest] into a prepared stream, and re-negotiates one without stranding
 * the last.
 *
 * Extracted from `PlayerViewModel` (audit ARCH-10): resolve → build a media item → prepare is a
 * fixed sequence with no UI state in it, and it was the largest thing standing between the ViewModel
 * and its actual subject, which is *what to do* with the outcome.
 *
 * Everything here is `suspend` and nothing here launches. That is deliberate, and it is the fix for
 * the ordering bug the audit found: a re-negotiation used to be two coroutines racing — one killing
 * the outgoing transcode, one asking the server for the next — so the server could receive the new
 * `PlaybackInfo` before the `stopEncodingProcess` for the old one, which is exactly the stranded
 * ffmpeg process the whole dance exists to prevent. Sequential `suspend` calls in one coroutine
 * cannot get that wrong.
 *
 * [open] deliberately stops at `prepare`. Publishing the new source, re-applying the session's rate
 * and reporting the start all belong to the caller, because a player event arriving during the first
 * buffer would otherwise be attributed to the source that was just replaced; `open` returns without
 * suspending after `prepare`, which leaves the caller no window at all to do it in.
 */
@Singleton
class PlaybackSessionController
    @Inject
    constructor(
        private val resolver: PlaybackSourceResolver,
        private val mediaSourceFactory: ExoMediaSourceFactory,
        private val playerHandle: PlayerHandle,
        private val reporter: PlaybackReporter,
        /**
         * Whether a receiver is in charge, re-read at prepare time.
         *
         * A cast session can start or end while [PlaybackSourceResolver.resolve] is on the wire, and
         * the routing handle follows it immediately — so a stream negotiated for one side would be
         * prepared on the other (audit CAST-04). Defaulted so that everything with no interest in
         * casting (tests included) gets a holder that never casts and the check never fires; Hilt
         * always injects the singleton the coordinator writes.
         */
        private val castStatus: CastStatusHolder = CastStatusHolder(),
    ) {
        /**
         * Resolves [request] and hands the result to the player.
         *
         * If the cast state changed underneath the resolve — the request said `castTarget = true`
         * but the session is gone, or the other way round — the result is thrown away and the item
         * re-resolved for where playback will actually happen. Preparing it anyway would hand the
         * receiver a stream negotiated against this device's decoders (or a `file://` path it cannot
         * reach), and the error path while casting has no fallback ladder to recover with.
         *
         * @param playWhenReady whether to start playing once buffered — `false` preserves a paused
         *   state across a re-resolve, and a restore that was paused when the process died.
         */
        suspend fun open(
            request: PlaybackResolveRequest,
            playWhenReady: Boolean,
        ): SessionOpenResult {
            var effective = request
            var outcome = resolver.resolve(effective)
            var reroutes = 0
            while (outcome is AppResult.Success &&
                castStatus.isCasting != effective.castTarget &&
                reroutes < MAX_CAST_REROUTES
            ) {
                reroutes++
                val casting = castStatus.isCasting
                Timber.i(
                    "Cast session %s mid-resolve; re-negotiating %s for %s",
                    if (casting) "started" else "ended",
                    effective.itemId,
                    if (casting) "the receiver" else "this device",
                )
                effective = effective.copy(castTarget = casting)
                outcome = resolver.resolve(effective)
            }
            val resolved =
                when (val result = outcome) {
                    is AppResult.Failure -> return SessionOpenResult.ResolveFailed(result.error)
                    is AppResult.Success -> result.value
                }

            val spec = mediaSourceFactory.create(resolved) ?: return SessionOpenResult.UnsupportedSource

            // The source travels alongside the spec: a cast receiver has to be told more about the
            // negotiation than its URL says, and this is the one place both are in hand
            // (`PlayerHandle.prepare`, M12 Phase 2). Locally the overload drops it.
            playerHandle.prepare(
                source = resolved,
                spec = spec,
                startPositionMs = resolved.startPositionTicks.ticksToMillis(),
                playWhenReady = playWhenReady,
            )
            return SessionOpenResult.Opened(resolved)
        }

        /**
         * Reopens the item under new terms, stopping the outgoing transcode **first**.
         *
         * Every re-negotiation goes through here — quality change, a track change the server has to
         * perform, and both fallback ladders — so the order only exists once and cannot be forgotten
         * at a call site.
         */
        suspend fun reopen(
            previous: PlaybackMediaSource,
            request: PlaybackResolveRequest,
            playWhenReady: Boolean,
        ): SessionOpenResult {
            reporter.stopTranscoding(previous)
            return open(request, playWhenReady)
        }

        private companion object {
            /**
             * How many times [open] will chase a cast state that changes mid-resolve. One flip is
             * the real-world case; the cap only exists so a session flapping faster than the server
             * answers cannot spin the loop forever.
             */
            const val MAX_CAST_REROUTES = 2
        }
    }

/** What one [PlaybackSessionController.open] attempt produced. */
sealed interface SessionOpenResult {
    /** The player is prepared on [source]; the caller owns publishing and reporting it. */
    data class Opened(
        val source: PlaybackMediaSource,
    ) : SessionOpenResult

    /** Nothing to play: the resolve itself failed, and [error] says why in the domain's terms. */
    data class ResolveFailed(
        val error: AppError,
    ) : SessionOpenResult

    /** The source resolved, but nothing on this device can be pointed at it. */
    data object UnsupportedSource : SessionOpenResult
}
