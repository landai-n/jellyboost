package dev.jellyboost.player.ui

import dev.jellyboost.player.cast.CastConnection
import dev.jellyboost.player.cast.CastPlaybackCoordinator
import dev.jellyboost.player.cast.CastPlaybackHost
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The transfer edges must stay callbacks, not derived from [states]: a collector of the connection
 * flow runs only after the coordinator flipped the routing handle, by which time the local position
 * is unreadable. [onStarted]/[onEnded] carry a snapshot taken at the right instant.
 *
 * @param currentSource asked rather than held — the ViewModel's `source` is replaced by every
 *   re-negotiation, so a copy taken here would be the one before it.
 */
internal class PlayerCastBridge(
    private val status: CastStatusHolder,
    private val coordinator: CastPlaybackCoordinator,
    private val currentSource: () -> PlaybackMediaSource?,
    private val onStarted: (deviceName: String?, from: PlaybackSnapshot) -> Unit,
    private val onEnded: (at: PlaybackSnapshot) -> Unit,
) : CastPlaybackHost {
    private var attached = false

    val isCasting: Boolean get() = status.isCasting

    val states: Flow<PlayerCastState> = status.connection.map { it.toPlayerState() }.distinctUntilChanged()

    override val castSource: PlaybackMediaSource? get() = currentSource()

    override fun onCastStarted(
        deviceName: String?,
        from: PlaybackSnapshot,
    ) = onStarted(deviceName, from)

    override fun onCastEnded(at: PlaybackSnapshot) = onEnded(at)

    /** Attaches even with nothing casting, so a session started later finds a player already open. */
    fun attach() {
        if (attached) return
        attached = true
        coordinator.attachHost(this)
    }

    /**
     * Must run *after* the ViewModel's progress ticker is stopped: the coordinator picks the ticker
     * up from here, and two tickers on one session double every position reported to the server.
     */
    fun detach() {
        if (!attached) return
        attached = false
        coordinator.detachHost(this)
    }
}

private fun CastConnection.toPlayerState(): PlayerCastState =
    when (this) {
        CastConnection.None -> PlayerCastState()
        is CastConnection.Connected -> PlayerCastState(isCasting = true, deviceName = deviceName)
    }
