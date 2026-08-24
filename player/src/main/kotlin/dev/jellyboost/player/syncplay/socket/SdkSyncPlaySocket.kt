package dev.jellyboost.player.syncplay.socket

import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.toDomain
import dev.jellyboost.player.syncplay.toEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncPlaySocket] backed by the SDK's `ApiClient.webSocket`.
 *
 * **Unbound — kept as the reference implementation of an SDK defect, not used.**
 * `OkHttpSyncPlaySocket` is what `SyncPlayModule` binds, because the SDK's socket delivers received
 * messages through a conflated `StateFlow` (`SocketConnection.state`, fed by
 * `OkHttpSocketConnection.onMessage`) and decodes them in a `.map` over it: of any two frames
 * arriving closer together than one decode, the first is lost, and two identical consecutive frames
 * are dropped outright. The server sends every SyncPlay transport action as a `SendCommand` /
 * `GroupStateUpdate` pair ~2 ms apart, so the command is the frame that loses. This class is what
 * a future SDK bump has to be re-verified against; its tests still run.
 *
 * Deliberately thin: all it does is subscribe and hand each message to the pure mappers in
 * `dev.jellyboost.player.syncplay.SyncPlayDtoMapping`, which is where the tests live. The SDK
 * owns the socket itself — connecting on first subscriber, reconnecting, and the keep-alive ping —
 * so there is nothing here to schedule or retry.
 *
 * `subscribe<T>()` filters the socket's shared message flow by type, so subscribing twice (as this
 * class does, once per stream) still uses one connection.
 */
@Singleton
internal class SdkSyncPlaySocket
    @Inject
    constructor(
        private val apiClient: ApiClient,
    ) : SyncPlaySocket {
        override val groupUpdates: Flow<SyncPlayGroupEvent>
            get() =
                apiClient.webSocket
                    .subscribe<SyncPlayGroupUpdateMessage>()
                    .map { it.data.toEvent() }

        override val commands: Flow<SyncPlayCommand>
            get() =
                apiClient.webSocket
                    .subscribe<SyncPlayCommandMessage>()
                    // `SyncPlayCommandMessage.data` is nullable in the SDK's schema; a command with
                    // no payload carries nothing to schedule, so it is dropped rather than guessed
                    // at — but loudly. A command that arrives and vanishes here is indistinguishable
                    // from one the server never sent, and that difference is the whole of a member
                    // silently failing to start.
                    .mapNotNull { message ->
                        message.data?.toDomain()
                            ?: null.also { Timber.w("A SyncPlay command message arrived with no payload") }
                    }

        override val connectionState: Flow<SyncPlaySocketState>
            get() = apiClient.webSocket.state.map { it.toDomain() }
    }

private fun SocketApiState.toDomain(): SyncPlaySocketState =
    when (this) {
        is SocketApiState.Connected -> SyncPlaySocketState.Connected
        is SocketApiState.Connecting -> SyncPlaySocketState.Connecting
        is SocketApiState.Disconnected -> SyncPlaySocketState.Disconnected(error)
    }
