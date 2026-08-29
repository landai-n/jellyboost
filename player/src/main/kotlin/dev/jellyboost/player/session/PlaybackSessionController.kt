package dev.jellyboost.player.session

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.IoDispatcher
import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.player.cast.CastStatusHolder
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
 * Everything here is `suspend` and nothing here launches: as two racing coroutines, a
 * re-negotiation could reach the server with the new `PlaybackInfo` before `stopEncodingProcess`
 * for the old one, stranding an ffmpeg process. Keep it sequential.
 *
 * [open] deliberately stops at `prepare` and does not suspend afterwards — publishing the source,
 * re-applying the rate and reporting the start belong to the caller, in the window where no player
 * event can yet be attributed to the source that was just replaced.
 */
@Singleton
internal class PlaybackSessionController
    @Inject
    @Suppress("LongParameterList")
    constructor(
        private val resolver: PlaybackSourceResolver,
        private val mediaSourceFactory: ExoMediaSourceFactory,
        private val playerHandle: PlayerHandle,
        private val reporter: PlaybackReporter,
        /**
         * Whether a receiver is in charge, re-read at prepare time: a cast session can start or end
         * while [PlaybackSourceResolver.resolve] is on the wire. The default never casts, so tests
         * never fire the check; Hilt injects the singleton the coordinator writes.
         */
        private val castStatus: CastStatusHolder = CastStatusHolder(),
        private val handover: PlaybackHandover = PlaybackHandover(),
        /**
         * Where player calls made from *inside the relinquish closure* are marshalled to: that
         * closure runs inline in the claimant's context (music's background dispatcher) and Media3
         * throws off the main thread. Deliberately not `.immediate` — resolving the immediate view
         * initializes the platform Main dispatcher, which a plain-JVM test cannot do.
         */
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
        /**
         * Where the spec is built, because that is where the attached fonts are read. No default,
         * unlike [mainDispatcher]: nothing stops a plain-JVM test from naming its own, and a default
         * would let a caller forget the hop that keeps those file reads off the UI thread.
         */
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        /**
         * A cast state that changed underneath the resolve forces a re-resolve: preparing anyway
         * would hand the receiver a stream negotiated against this device's decoders (or a `file://`
         * path it cannot reach), and casting has no fallback ladder to recover with.
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

            // On IO, not here: building the spec reads every attached font off disk, and `open` runs on
            // the main thread — `prepare` below has to, since Media3 binds the player to this looper.
            val spec =
                withContext(ioDispatcher) { mediaSourceFactory.create(resolved) }
                    ?: return SessionOpenResult.UnsupportedSource

            // Must stay immediately before `prepare`: it suspends until whatever held the player
            // has closed its own server session, so a music queue's stop report lands before this
            // film's start report. Inside the closure the stop report must complete before the
            // player is let go.
            handover.claim(PlaybackKind.VIDEO) {
                val snapshot = withContext(mainDispatcher) { playerHandle.snapshot() }
                reporter.reportStop(resolved, snapshot)
                withContext(mainDispatcher) { playerHandle.stop() }
            }

            // The source travels alongside the spec: a cast receiver needs more of the negotiation
            // than its URL carries. The local overload drops it.
            playerHandle.prepare(
                source = resolved,
                spec = spec,
                startPositionMs = resolved.startPositionTicks.ticksToMillis(),
                playWhenReady = playWhenReady,
            )
            return SessionOpenResult.Opened(resolved)
        }

        /**
         * Reopens the item under new terms, stopping the outgoing transcode **first**. Every
         * re-negotiation goes through here so that order cannot be forgotten at a call site.
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
         * Must be called from `PlayerViewModel`'s teardown: otherwise the relinquish registered by
         * [open] stays armed and the next music `play()` re-reports a stop for a closed session.
         */
        fun endVideoSession() {
            handover.releaseNow(PlaybackKind.VIDEO)
        }

        private companion object {
            /** One flip is the real case; the cap only stops a flapping session spinning forever. */
            const val MAX_CAST_REROUTES = 2
        }
    }

internal sealed interface SessionOpenResult {
    /** The player is prepared on [source]; the caller owns publishing and reporting it. */
    data class Opened(
        val source: PlaybackMediaSource,
    ) : SessionOpenResult

    data class ResolveFailed(
        val error: AppError,
    ) : SessionOpenResult

    /** The source resolved, but nothing on this device can be pointed at it. */
    data object UnsupportedSource : SessionOpenResult
}
