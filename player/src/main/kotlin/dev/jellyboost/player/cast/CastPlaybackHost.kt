package dev.jellyboost.player.cast

import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot

/**
 * The callbacks carry a [PlaybackSnapshot] because the coordinator is the only caller standing at the routing
 * edge: once routing has flipped, the outgoing player's position is no longer readable from anywhere.
 */
interface CastPlaybackHost {
    val castSource: PlaybackMediaSource?

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
}

object NoCastPlaybackCoordinator : CastPlaybackCoordinator {
    override fun attachHost(host: CastPlaybackHost) = Unit

    override fun detachHost(host: CastPlaybackHost) = Unit
}
