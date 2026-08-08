package dev.jellyboost.player.session

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.di.MainDispatcher
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.ticksToMillis
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.resolve.ExoMediaSourceFactory
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.resolve.PlaybackSourceResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
internal class PlaybackSessionController
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
        /**
         * The video⇄music arbiter (M13, key decision 3).
         *
         * This is where video *actually starts* — the one place every open, transfer and
         * re-negotiation funnels through on its way to `prepare` — so it is where the claim
         * belongs. Defaulted for the same reason [castStatus] is: it holds nothing and depends on
         * nothing, so a test constructs the pre-M13 behaviour exactly (no other owner ever exists,
         * so no relinquish ever runs); Hilt always passes the singleton the music controller
         * shares.
         */
        private val handover: PlaybackHandover = PlaybackHandover(),
        /**
         * Where player calls made from *inside the relinquish closure* are marshalled to.
         *
         * The closure registered with [handover] runs inline in whichever context the next
         * claimant calls `claim` from — for a music claim that is the music session's own
         * background dispatcher — and Media3 throws off the main thread. Every relinquish owns
         * its marshalling ([PlaybackHandover]'s contract); this is video's. Defaulted for the
         * same reason [castStatus] is: no existing test triggers a relinquish, and one that does
         * passes its own dispatcher. Deliberately not `.immediate` — resolving the immediate view
         * initializes the platform Main dispatcher at construction, which a plain-JVM test cannot
         * do, and the closure's hop is rare enough that an extra post is irrelevant.
         */
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
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

            // Immediately before `prepare`, and it suspends until whatever held the player has
            // finished closing its own server session — a music queue's stop report lands before
            // this film's start report, which is the whole point (key decision 3). Claiming for a
            // kind that already owns the player only replaces the callback, so a re-negotiation
            // does not report itself stopped. The closure runs inline in the *claimant's* context
            // — music's background session scope — so its player calls hop to the main thread
            // themselves; the report in between stays off it, and the ordering (stop report
            // completed before the player is let go) is untouched.
            handover.claim(PlaybackKind.VIDEO) {
                val snapshot = withContext(mainDispatcher) { playerHandle.snapshot() }
                reporter.reportStop(resolved, snapshot)
                withContext(mainDispatcher) { playerHandle.stop() }
            }

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

        /**
         * The video session ended on its own terms, so nobody should close it on video's behalf
         * later (M13).
         *
         * Called from `PlayerViewModel`'s teardown, which has already issued the stop report on
         * the detached scope. Without it the relinquish registered by [open] would still be armed,
         * and the next music `play()` — minutes or hours later — would re-report a stop for a
         * session that has been closed since the user left the player screen.
         *
         * Non-suspending because the teardown is not a coroutine; see
         * [PlaybackHandover.releaseNow] for what it does when a handover is already in flight.
         */
        fun endVideoSession() {
            handover.releaseNow(PlaybackKind.VIDEO)
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
internal sealed interface SessionOpenResult {
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
