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
 * Recomputes the expected position from the anchor and the server clock, and closes a big enough gap
 * with a seek. [MAX_DRIFT_MS] is coarse on purpose: a correction is a visible jump and a re-buffer,
 * so it must be worth more than the drift it fixes.
 */
@Singleton
internal class SyncPlayDriftMonitor
    @Inject
    constructor(
        private val playerHandle: PlayerHandle,
        private val timeSync: SyncPlayTimeSync,
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    ) {
        /** Must run only while the controller's phase is `Playing`; cancel it otherwise. */
        suspend fun monitor(anchor: SyncPlayAnchor) {
            while (currentCoroutineContext().isActive) {
                delay(TICK_INTERVAL_MS)
                correctOnce(anchor)
            }
        }

        /** @return the position it seeked to, or `null` when the drift was within tolerance. */
        suspend fun correctOnce(anchor: SyncPlayAnchor): Long? =
            withContext(mainDispatcher) {
                val snapshot = playerHandle.snapshot()
                // Both guards prevent a 1 Hz seek loop: an ended item would be seeked past its end
                // every tick, and a player paused outside the protocol (audio focus lost to a call)
                // keeps phase `Playing` while its frozen frame is dragged forward for ever.
                if (snapshot.hasEnded) return@withContext null
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
