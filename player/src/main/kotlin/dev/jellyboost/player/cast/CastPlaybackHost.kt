package dev.jellyboost.player.cast

import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot

/**
 * The screen driving a cast session, as [CastSessionCoordinator] sees it.
 *
 * Two things pass through here, and they are two different kinds of thing. [castSource] is a
 * *question* the coordinator asks on the way out — what is playing, so it can keep reporting it
 * once the screen is gone. The two callbacks are *events* it pushes in: a receiver appeared, a
 * receiver went away.
 *
 * Both callbacks carry a [PlaybackSnapshot] rather than leaving the host to take one, and that is
 * the whole reason they exist (M12 Phase 3). A session start flips
 * [RoutingPlayerHandle][dev.jellyboost.player.session.RoutingPlayerHandle] to the cast player and
 * stops the local one, so by the time any collector of
 * [CastStatusHolder.connection][CastStatusHolder.connection] runs, the position the film had
 * reached on this device is no longer readable from anywhere — a snapshot taken then would be the
 * receiver's, which is zero. The coordinator is the one object that is there at the right instant,
 * on both edges, so it reads the outgoing player and hands the reading over.
 *
 * Default no-op bodies because the two are genuinely optional: a host that only wants to be
 * reported for while it is away — which is all Phase 2 needed — implements neither.
 */
interface CastPlaybackHost {
    /** What the receiver is playing, or `null` when the host has nothing open. */
    val castSource: PlaybackMediaSource?

    /**
     * A receiver has taken over, and the film is still on this device.
     *
     * @param deviceName the receiver's friendly name, or `null` when the framework has not
     *   published one.
     * @param from where the **local** player had got to, read before playback was routed away from
     *   it. It is where the receiver should resume, and the position the outgoing session's stop
     *   report belongs at.
     */
    fun onCastStarted(
        deviceName: String?,
        from: PlaybackSnapshot,
    ): Unit = Unit

    /**
     * The receiver has gone, and this device is in charge again.
     *
     * @param at where the **cast** player had got to, read before routing went back to local. Only
     *   ever called on an attached host: with nobody attached the coordinator ends the session
     *   itself, which is the invariant that keeps the server from being told twice that a film
     *   stopped.
     */
    fun onCastEnded(at: PlaybackSnapshot): Unit = Unit
}

/**
 * Where a screen hands its cast session over, and takes it back.
 *
 * An interface over [CastSessionCoordinator] rather than the coordinator itself, for one reason:
 * `PlayerViewModel` has to be constructible without one — in a test, and as the default that keeps
 * every pre-M12 fixture compiling — and the coordinator cannot be built without the Cast
 * framework's session manager behind it. [NoCastPlaybackCoordinator] is that default, and it says
 * exactly what a build with no cast session has: nothing to hand anything to.
 */
interface CastPlaybackCoordinator {
    /**
     * Hands over the screen that is driving cast playback.
     *
     * Idempotent by construction, and it silences the coordinator's own reporting: from here the
     * host's ticker is the one telling the server where the film is.
     */
    fun attachHost(host: CastPlaybackHost)

    /**
     * Gives the screen back while the receiver plays on.
     *
     * Ignored unless [host] is the attached one, so a stale ViewModel's teardown cannot detach the
     * screen that replaced it.
     */
    fun detachHost(host: CastPlaybackHost)
}

/** The coordinator a player with no Cast stack behind it gets: one that goes nowhere. */
object NoCastPlaybackCoordinator : CastPlaybackCoordinator {
    override fun attachHost(host: CastPlaybackHost) = Unit

    override fun detachHost(host: CastPlaybackHost) = Unit
}
