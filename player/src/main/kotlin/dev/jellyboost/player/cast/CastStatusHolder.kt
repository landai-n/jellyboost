package dev.jellyboost.player.cast

import dev.jellyboost.player.deviceprofile.CastReceiverClass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Written only by [CastSessionCoordinator]; read by anyone. Keeps every `com.google.android.gms`
 * type behind it, so a ViewModel test constructs one with no Cast stack present.
 */
@Singleton
internal class CastStatusHolder
    @Inject
    constructor() {
        private val _connection = MutableStateFlow<CastConnection>(CastConnection.None)

        val connection: StateFlow<CastConnection> = _connection.asStateFlow()

        val isCasting: Boolean get() = _connection.value is CastConnection.Connected

        /** Falls back to the conservative capability floor when nothing is connected. */
        val receiver: CastReceiverClass
            get() = (_connection.value as? CastConnection.Connected)?.receiver ?: CastReceiverClass.LEGACY_1080P

        fun setConnection(connection: CastConnection) {
            _connection.value = connection
        }
    }

/**
 * Deliberately narrower than `CastDeviceState`, which describes discovery (are there devices, is one
 * connecting); this is only the distinction playback makes.
 */
internal sealed interface CastConnection {
    data object None : CastConnection

    /**
     * @property deviceName `null` when the framework has not published one — callers show a generic
     *   "casting" rather than an empty label.
     * @property receiver resolved from the model name once at session start.
     */
    data class Connected(
        val deviceName: String?,
        val receiver: CastReceiverClass = CastReceiverClass.LEGACY_1080P,
    ) : CastConnection
}
