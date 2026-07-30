package dev.jellyfinnative.player.syncplay.socket

import dev.jellyfinnative.player.syncplay.model.SyncPlayCommand
import dev.jellyfinnative.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyfinnative.player.syncplay.toDomain
import dev.jellyfinnative.player.syncplay.toEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SyncPlaySocket] backed by the SDK's `ApiClient.webSocket`.
 *
 * Deliberately thin: all it does is subscribe and hand each message to the pure mappers in
 * `dev.jellyfinnative.player.syncplay.SyncPlayDtoMapping`, which is where the tests live. The SDK
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
                    // no payload carries nothing to schedule, so it is dropped rather than guessed at.
                    .mapNotNull { it.data?.toDomain() }

        override val connectionState: Flow<SyncPlaySocketState>
            get() = apiClient.webSocket.state.map { it.toDomain() }
    }

private fun SocketApiState.toDomain(): SyncPlaySocketState =
    when (this) {
        is SocketApiState.Connected -> SyncPlaySocketState.Connected
        is SocketApiState.Connecting -> SyncPlaySocketState.Connecting
        is SocketApiState.Disconnected -> SyncPlaySocketState.Disconnected(error)
    }
