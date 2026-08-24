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
 * **Unbound — kept as the reference implementation of an SDK defect, not used.** `SyncPlayModule`
 * binds `OkHttpSyncPlaySocket` instead: the SDK's socket delivers messages through a conflated
 * `StateFlow` and decodes them in a `.map` over it, so of two frames arriving closer together than
 * one decode the first is lost, and identical consecutive frames are dropped outright. The server
 * sends every transport action as a `SendCommand`/`GroupStateUpdate` pair ~2 ms apart, so the command
 * is what loses. Re-verify this against every SDK bump; its tests still run.
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
                    // `data` is nullable in the SDK's schema; a payload-less command is dropped
                    // loudly, since silently it is indistinguishable from one never sent.
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
