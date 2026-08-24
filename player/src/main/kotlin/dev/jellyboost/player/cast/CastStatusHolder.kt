package dev.jellyboost.player.cast

import dev.jellyboost.player.deviceprofile.CastReceiverClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one fact about casting that things outside `cast/` need: whether we are.
 *
 * Modelled on `SyncPlayStatusHolder`, and for the same two reasons. It breaks a dependency cycle —
 * `CastSessionCoordinator` drives `RoutingPlayerHandle` and the reporter, while `PlayerViewModel`
 * only needs to know whether the next resolve should be negotiated for a receiver — and it keeps
 * every `com.google.android.gms` type behind it, which is what lets a ViewModel test construct one
 * on a machine with no Cast stack at all.
 *
 * Written only by [CastSessionCoordinator]; read by anyone.
 */
@Singleton
internal class CastStatusHolder
    @Inject
    constructor() {
        private val _connection = MutableStateFlow<CastConnection>(CastConnection.None)

        /** The receiver this device is connected to, if any. */
        val connection: StateFlow<CastConnection> = _connection.asStateFlow()

        /** `true` while a receiver is playing, or about to. */
        val isCasting: Boolean get() = _connection.value is CastConnection.Connected

        /**
         * The connected receiver's capability class; the conservative floor when not casting.
         *
         * Read by `PlaybackInfoResolver` at negotiation time, so a `castTarget` resolve claims
         * exactly what the receiver on the other side of the room was classified as.
         */
        val receiver: CastReceiverClass
            get() = (_connection.value as? CastConnection.Connected)?.receiver ?: CastReceiverClass.LEGACY_1080P

        /** Publishes the session state. Called by [CastSessionCoordinator] only. */
        fun setConnection(connection: CastConnection) {
            _connection.value = connection
        }
    }

/**
 * Whether there is a receiver, and which one — the Cast state playback reasons about.
 *
 * Narrower than `CastDeviceState`, deliberately: that one describes the *discovery* world the route
 * button draws (are there devices, is one connecting), while this one is about the only distinction
 * playback makes.
 */
internal sealed interface CastConnection {
    /** Playing here. */
    data object None : CastConnection

    /**
     * Playing on a receiver.
     *
     * @property deviceName the receiver's friendly name; `null` when the framework has not published
     *   one, which callers show as a generic "casting" rather than an empty label.
     * @property receiver the hardware's capability class, resolved from its model name once at
     *   session start. Defaults to the conservative floor so tests and callers that only care
     *   about connectedness need not name it.
     */
    data class Connected(
        val deviceName: String?,
        val receiver: CastReceiverClass = CastReceiverClass.LEGACY_1080P,
    ) : CastConnection
}
