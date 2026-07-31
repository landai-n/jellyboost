package dev.jellyboost.player.syncplay.socket

import app.cash.turbine.test
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

/**
 * Unit tests for [SdkSyncPlaySocket] — the wiring, not the mapping (that is
 * `SyncPlayDtoMappingTest`).
 *
 * What matters here is that a message the SDK can legally deliver but the app cannot act on —
 * a command with no payload, an unrecognised group update — is *dropped* rather than crashing the
 * subscription. The socket is the only channel a group has; a mapping exception on it would take
 * the whole group session down.
 */
class SdkSyncPlaySocketTest {
    private val socketApi = mockk<SocketApi>()
    private val apiClient = mockk<ApiClient>()
    private val socket = SdkSyncPlaySocket(apiClient)

    init {
        every { apiClient.webSocket } returns socketApi
    }

    @Test
    fun `group updates arrive as domain events`() =
        runTest {
            every { socketApi.subscribe(SyncPlayGroupUpdateMessage::class) } returns
                flowOf(
                    groupUpdateMessage(SyncPlayUserJoinedUpdate(GROUP_ID, "grace")),
                    groupUpdateMessage(SyncPlayNotInGroupUpdate(GROUP_ID, "")),
                )

            socket.groupUpdates.test {
                awaitItem() shouldBe SyncPlayGroupEvent.UserJoined("grace")
                awaitItem() shouldBe SyncPlayGroupEvent.NotInGroup
                awaitComplete()
            }
        }

    @Test
    fun `commands arrive as domain commands`() =
        runTest {
            every { socketApi.subscribe(SyncPlayCommandMessage::class) } returns
                flowOf(SyncPlayCommandMessage(data = sendCommand(), messageId = MESSAGE_ID))

            socket.commands.test {
                awaitItem().type shouldBe SyncPlayCommandType.Unpause
                awaitComplete()
            }
        }

    @Test
    fun `a command message with no payload is dropped`() =
        runTest {
            every { socketApi.subscribe(SyncPlayCommandMessage::class) } returns
                flowOf(
                    SyncPlayCommandMessage(data = null, messageId = MESSAGE_ID),
                    SyncPlayCommandMessage(data = sendCommand(SendCommandType.PAUSE), messageId = MESSAGE_ID),
                )

            socket.commands.test {
                awaitItem().type shouldBe SyncPlayCommandType.Pause
                awaitComplete()
            }
        }

    @Test
    fun `the SDK's connection state is mirrored, cause included`() =
        runTest {
            val state = MutableStateFlow<SocketApiState>(SocketApiState.Disconnected())
            every { socketApi.state } returns state

            socket.connectionState.test {
                awaitItem() shouldBe SyncPlaySocketState.Disconnected(error = null)

                state.value = SocketApiState.Connecting
                awaitItem() shouldBe SyncPlaySocketState.Connecting

                state.value = SocketApiState.Connected
                awaitItem() shouldBe SyncPlaySocketState.Connected

                // Phase 2 tells a dropped connection from an orderly close by this cause.
                val cause = IllegalStateException("socket closed")
                state.value = SocketApiState.Disconnected(cause)
                awaitItem() shouldBe SyncPlaySocketState.Disconnected(cause)

                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun groupUpdateMessage(update: org.jellyfin.sdk.model.api.GroupUpdate) =
        SyncPlayGroupUpdateMessage(data = update, messageId = MESSAGE_ID)

    private fun sendCommand(command: SendCommandType = SendCommandType.UNPAUSE) =
        SendCommand(
            groupId = GROUP_ID,
            playlistItemId = SLOT_ID,
            `when` = LocalDateTime.of(2026, 7, 30, 20, 41, 3),
            positionTicks = 12_000_000L,
            command = command,
            emittedAt = LocalDateTime.of(2026, 7, 30, 20, 41, 0),
        )

    private companion object {
        val GROUP_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val SLOT_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val MESSAGE_ID: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")
    }
}
