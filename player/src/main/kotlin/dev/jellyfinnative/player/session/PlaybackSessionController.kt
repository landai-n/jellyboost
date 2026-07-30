package dev.jellyfinnative.player.session

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.player.model.PlaybackMediaSource
import dev.jellyfinnative.player.model.ticksToMillis
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.ExoMediaSourceFactory
import dev.jellyfinnative.player.resolve.PlaybackResolveRequest
import dev.jellyfinnative.player.resolve.PlaybackSourceResolver
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
    ) {
        /**
         * Resolves [request] and hands the result to the player.
         *
         * @param playWhenReady whether to start playing once buffered — `false` preserves a paused
         *   state across a re-resolve, and a restore that was paused when the process died.
         */
        suspend fun open(
            request: PlaybackResolveRequest,
            playWhenReady: Boolean,
        ): SessionOpenResult {
            val resolved =
                when (val result = resolver.resolve(request)) {
                    is AppResult.Failure -> return SessionOpenResult.ResolveFailed(result.error)
                    is AppResult.Success -> result.value
                }

            val spec = mediaSourceFactory.create(resolved) ?: return SessionOpenResult.UnsupportedSource

            playerHandle.prepare(
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
