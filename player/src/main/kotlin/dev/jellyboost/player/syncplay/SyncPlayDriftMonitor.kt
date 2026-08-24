package dev.jellyboost.player.syncplay

import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.player.session.PlayerHandle
import dev.jellyboost.player.syncplay.time.SyncPlayTimeSync
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Keeps playback on the group's timeline once it has started.
 *
 * Starting in sync is not the same as staying in sync: a stall, a decoder hiccup, a device clock
 * being adjusted underneath us, or simply a player whose rate is not exactly 1.0 all pull a member
 * away from the group by an amount nobody in the room can diagnose. So the position playback
 * *should* be at is recomputed every second from the anchor and the server clock, and a big enough
 * gap is closed with a seek.
 *
 * [MAX_DRIFT_MS] is deliberately coarse. A correction is a visible jump and a re-buffer, so it has
 * to be worth more than the drift it fixes: below two seconds nobody notices the drift, and above
 * it nobody misses the jump. The plan's stretch goal is rate-nudging (`setPlaybackSpeed`) to absorb
 * small drift invisibly; this monitor stays as the safety net underneath it.
 */
@Singleton
internal class SyncPlayDriftMonitor
    @Inject
    constructor(
        private val playerHandle: PlayerHandle,
        private val timeSync: SyncPlayTimeSync,
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    ) {
        /**
         * Corrects drift against [anchor] every [TICK_INTERVAL_MS] until cancelled.
         *
         * Runs for exactly as long as the controller's phase is `Playing`; there is nothing to
         * correct towards while the group is paused or waiting.
         */
        suspend fun monitor(anchor: SyncPlayAnchor) {
            while (currentCoroutineContext().isActive) {
                delay(TICK_INTERVAL_MS)
                correctOnce(anchor)
            }
        }

        /**
         * One drift check.
         *
         * @return the position it seeked to, or `null` when the drift was within tolerance.
         */
        suspend fun correctOnce(anchor: SyncPlayAnchor): Long? =
            withContext(mainDispatcher) {
                val snapshot = playerHandle.snapshot()
                // At the end of an item the position stops advancing on purpose; "correcting" it
                // would seek past the end, over and over, until the controller moves the queue on.
                if (snapshot.hasEnded) return@withContext null
                // A player that is not running is not drifting — it is paused or stalled for a
                // reason the protocol owns elsewhere. Seeking it would jump the frozen frame
                // forward every tick for ever without ever starting playback: a phone call or a
                // headphone unplug pauses ExoPlayer directly (audio-focus handling), the phase
                // stays `Playing`, and without this check the monitor would become a 1 Hz seek
                // loop against the paused frame. Once it runs again, the next tick measures the
                // real gap and closes it.
                if (!snapshot.isPlaying) return@withContext null

                val elapsedMillis = Duration.between(anchor.at, timeSync.serverNow()).toMillis()
                val expectedMillis = anchor.positionMs + elapsedMillis
                val driftMillis = expectedMillis - snapshot.positionMs
                if (abs(driftMillis) <= MAX_DRIFT_MS) return@withContext null

                Timber.i("SyncPlay drift %d ms; seeking to %d", driftMillis, expectedMillis)
                playerHandle.seekTo(expectedMillis)
                expectedMillis
            }

        companion object {
            /** How often drift is measured, in milliseconds. */
            const val TICK_INTERVAL_MS = 1_000L

            /** Drift beyond this many milliseconds is corrected with a seek. */
            const val MAX_DRIFT_MS = 2_000L
        }
    }
