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
 * The player's half of casting: one object between [PlayerViewModel] and the Cast session.
 *
 * The same decomposition [PlayerSyncPlayBridge] got, for the same reason — the ViewModel is the
 * longest class in the module (audit ARCH-10) and this keeps a whole subsystem down to a question
 * ([isCasting]), a flow ([states]) and two edges. It also does one thing that bridge does not: it
 * **is** the [CastPlaybackHost]. `PlayerViewModel` cannot implement that interface itself without
 * the Cast package's vocabulary appearing in a public class's supertypes, and the host is one
 * property and two callbacks — small enough that forwarding them costs less than the coupling
 * would.
 *
 * ### Where the transfer edges come from
 * Not from [states]. A collector of the connection flow always runs *after* the coordinator has
 * flipped the routing handle, and by then the position the film reached on this device is
 * unreadable — the only player anyone can ask is the receiver's, sitting at zero. So the two edges
 * arrive as callbacks carrying a snapshot the coordinator took at the right instant, and the flow
 * is left to do what a flow is good at: drawing the state.
 *
 * @param currentSource what the receiver is playing, asked rather than held — the ViewModel's
 *   `source` is replaced by every re-negotiation, and a copy taken here would be the one before it.
 */
internal class PlayerCastBridge(
    private val status: CastStatusHolder,
    private val coordinator: CastPlaybackCoordinator,
    private val currentSource: () -> PlaybackMediaSource?,
    private val onStarted: (deviceName: String?, from: PlaybackSnapshot) -> Unit,
    private val onEnded: (at: PlaybackSnapshot) -> Unit,
) : CastPlaybackHost {
    private var attached = false

    /** `true` while a television has the film — what every resolve asks before negotiating. */
    val isCasting: Boolean get() = status.isCasting

    /** The receiver as the screen draws it; conflated, so a re-connect to the same one is nothing. */
    val states: Flow<PlayerCastState> = status.connection.map { it.toPlayerState() }.distinctUntilChanged()

    override val castSource: PlaybackMediaSource? get() = currentSource()

    override fun onCastStarted(
        deviceName: String?,
        from: PlaybackSnapshot,
    ) = onStarted(deviceName, from)

    override fun onCastEnded(at: PlaybackSnapshot) = onEnded(at)

    /**
     * Offers this screen to the cast session.
     *
     * A no-op with nothing casting beyond recording the offer — the coordinator keeps the host
     * either way, which is what lets a session started *later* find a player already open, and what
     * makes "the coordinator reports only when no host is attached" true from the first frame
     * rather than from the first connection.
     */
    fun attach() {
        if (attached) return
        attached = true
        coordinator.attachHost(this)
    }

    /**
     * Takes the screen back while the receiver plays on.
     *
     * The coordinator picks the progress ticker up from here, so this must happen *after* the
     * ViewModel's own has been stopped: two tickers reporting the same session would double every
     * position the server is told about.
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
