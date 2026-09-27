package dev.jellyboost.player.cast

import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import java.util.UUID

/**
 * The callbacks carry a [PlaybackSnapshot] because the coordinator is the only caller standing at the routing
 * edge: once routing has flipped, the outgoing player's position is no longer readable from anywhere.
 */
interface CastPlaybackHost {
    val castSource: PlaybackMediaSource?

    /**
     * The last [valid][PlaybackSnapshot.isValid] reading the host took for [castSource], `null` when it
     * has none. Handed to the coordinator as the host detaches, so a session that ends before the
     * coordinator reads the receiver itself is still closed at a position someone vouched for.
     */
    val lastValidReading: PlaybackSnapshot? get() = null

    /** @param from the **local** player's position before routing away: the resume point and the stop report's. */
    fun onCastStarted(
        deviceName: String?,
        from: PlaybackSnapshot,
    ): Unit = Unit

    /**
     * Only ever called on an attached host; with none attached the coordinator ends the session itself, so the
     * server is never told twice that a film stopped.
     *
     * @param at where the **cast** player was before routing went back to local.
     */
    fun onCastEnded(at: PlaybackSnapshot): Unit = Unit

    /**
     * The receiver let go of the item while the session stayed connected (stopped from the television,
     * unloaded when idle), and has not taken it back within the coordinator's grace period. Like
     * [onCastEnded], only ever called on an attached host — the stop report is then the host's; with
     * none attached the coordinator sends it itself.
     *
     * @param lastHeld the last reading taken while the receiver still held the item — always valid.
     */
    fun onCastItemLost(lastHeld: PlaybackSnapshot): Unit = Unit
}

/**
 * An interface, not [CastSessionCoordinator] itself: `PlayerViewModel` must be constructible without the Cast
 * framework, via [NoCastPlaybackCoordinator].
 */
interface CastPlaybackCoordinator {
    /** Idempotent, and silences the coordinator's own reporting: from here the host's ticker reports to the server. */
    fun attachHost(host: CastPlaybackHost)

    /** Ignored unless [host] is the attached one, so a stale ViewModel's teardown cannot detach its replacement. */
    fun detachHost(host: CastPlaybackHost)

    /**
     * What a screen opening [itemId] may adopt instead of negotiating the item again: the source this
     * app left playing on the receiver when its last screen went, **if the receiver still holds it**.
     * `null` means open as usual. Asking changes nothing; adopting is attaching with that source as the
     * host's [CastPlaybackHost.castSource], which is what tells [attachHost] it is not an orphan.
     */
    fun heldSourceFor(itemId: UUID): CastReceiverHold? = null
}

/**
 * A live receiver session a screen can take over without touching the receiver.
 *
 * @property source the very source the receiver was loaded with — its play session id is the one the
 *   server is already tracking, and the one every later progress and stop report must carry.
 * @property reading the receiver's reading as the hold was checked; [PlaybackSnapshot.isValid] is `false`
 *   while it is still buffering the item.
 * @property isBuffering waiting for data while meaning to play — what the screen's transport shows first.
 * @property lastValidReading the freshest valid reading anyone took for [source] — [reading] itself when
 *   that is valid. What the adopting screen's position starts from, and what a session ending before
 *   the screen reads a valid position of its own is closed and brought home at. **Never** the source's
 *   `startPositionTicks`: that is where the film was first sent, not where it is.
 */
data class CastReceiverHold(
    val source: PlaybackMediaSource,
    val reading: PlaybackSnapshot,
    val isBuffering: Boolean,
    val lastValidReading: PlaybackSnapshot? = reading.takeIf { it.isValid },
)

object NoCastPlaybackCoordinator : CastPlaybackCoordinator {
    override fun attachHost(host: CastPlaybackHost) = Unit

    override fun detachHost(host: CastPlaybackHost) = Unit
}
