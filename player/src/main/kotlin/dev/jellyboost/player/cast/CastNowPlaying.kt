package dev.jellyboost.player.cast

import dev.jellyboost.core.common.Ticks
import dev.jellyboost.core.common.di.MainDispatcher
import dev.jellyboost.player.di.DetachedPlayerScope
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.ticksToMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * What this app has left playing on a receiver with no player screen open — the one thing `:app`'s
 * casting bar needs, and the only cast surface `:app` reads. Names no `com.google.android.gms` type
 * (see [CastAvailability]): on a device without Play services [state] simply stays `null`.
 *
 * **Non-`null` exactly while a session is connected and the coordinator holds a detached source.**
 * With a player screen attached there is nothing to show — that screen is the remote control — and
 * a receiver that dropped the item, or a session that ended, clears it.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CastNowPlaying
    @Inject
    internal constructor(
        private val coordinator: CastSessionCoordinator,
        @DetachedPlayerScope detachedScope: CoroutineScope,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
    ) {
        /** Main-dispatched: every reading is a `PlayerHandle` snapshot, which is main-thread-only. */
        private val scope = CoroutineScope(detachedScope.coroutineContext + mainDispatcher)

        /** A reading on demand, so a tap on the bar is reflected without waiting for the next poll. */
        private val refreshes =
            MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * Polled while anyone is looking: the position has no event, and a pause pressed on the
         * television's remote reaches this app only as a change in the reading. Stops
         * [STOP_TIMEOUT_MS] after the last collector goes, so nothing is read with the app in the
         * background.
         */
        val state: StateFlow<CastingItem?> =
            combine(coordinator.detached, coordinator.connection, coordinator.receiverBuffering, ::Triple)
                .flatMapLatest { (held, connection, buffering) ->
                    val connected = connection as? CastConnection.Connected
                    if (held == null || connected == null) {
                        flowOf(null)
                    } else {
                        merge(polls(), refreshes).map { castingItem(held, connected, buffering) }
                    }
                }.distinctUntilChanged()
                .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        /**
         * [state]'s value computed now rather than cached: a notification tap asks this the moment the
         * activity comes back, before a stopped collection has caught up. Main thread only.
         */
        fun current(): CastingItem? {
            val held = coordinator.detached.value ?: return null
            val connected = coordinator.connection.value as? CastConnection.Connected ?: return null
            return castingItem(held, connected, coordinator.receiverBuffering.value)
        }

        /** Pauses a receiver that means to play (buffering included), plays one that does not. */
        fun togglePlayPause() {
            coordinator.toggleDetachedPlayback()
            refreshes.tryEmit(Unit)
        }

        private fun polls(): Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(POLL_INTERVAL)
                }
            }

        /** An invalid reading keeps the last position seen rather than springing the line back to zero. */
        private fun castingItem(
            held: DetachedCast,
            connected: CastConnection.Connected,
            buffering: Boolean,
        ): CastingItem {
            val reading = coordinator.readReceiver().takeIf { it.isValid } ?: coordinator.lastHeld
            val runtimeMs = held.source.runTimeTicks.ticksToMillis()
            return CastingItem(
                itemId = held.source.itemId.toString(),
                title = held.metadata.title,
                subtitle = held.metadata.subtitle,
                artworkUrl = held.metadata.posterUrl,
                deviceName = connected.deviceName,
                isReconnecting = connected.suspended,
                playWhenReady = coordinator.receiverPlayWhenReady,
                isBuffering = buffering,
                positionMs = reading?.positionMs ?: 0L,
                durationMs = reading?.durationMs?.takeIf { it > 0L } ?: runtimeMs,
                isSettledPaused = coordinator.receiverSettledPaused,
            )
        }

        private companion object {
            val POLL_INTERVAL = 1.seconds
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/**
 * The casting bar's whole input, in plain values.
 *
 * @property itemId the item's id as `UUID.toString()` — what the player route is opened with.
 * @property title `null` when the item's fetch never answered; the bar then leads with the device line.
 * @property deviceName `null` when the framework has not published one; callers say "your TV".
 * @property isReconnecting the session is suspended (a Wi-Fi blip): still casting, commands wait.
 * @property playWhenReady the receiver's **intent** — `true` while playing or buffering toward it.
 *   This, not a snapshot, is what the toggle reverses and what decides Pause over Play.
 * @property isBuffering waiting for data while meaning to play: Pause, with the buffering ring.
 * @property positionMs the last valid reading's; `0` before one has been taken.
 * @property isSettledPaused the receiver reports itself paused under a stale `playWhenReady = true`: the
 *   toggle then plays (`tapPlays`), so the button must say Play. With [playWhenReady], the tap's whole rule.
 */
data class CastingItem(
    val itemId: String,
    val title: String?,
    val subtitle: String?,
    val artworkUrl: String?,
    val deviceName: String?,
    val isReconnecting: Boolean,
    val playWhenReady: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val isSettledPaused: Boolean = false,
) {
    /** Where a player opened from the bar starts if the receiver has let go of the item by then. */
    val positionTicks: Long get() = Ticks.millisToTicks(positionMs)
}

/**
 * A detached source with the metadata captured as it was detached: the holder keeps only the latest
 * item's, and another screen's fetch may replace it while this one plays on.
 */
internal data class DetachedCast(
    val source: PlaybackMediaSource,
    val metadata: CastMetadata,
)
