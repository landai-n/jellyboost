package dev.jellyboost.player.syncplay.socket

import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayRequestKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * **This class exists because of one bug, and the first two tests are it.** The SDK's socket routes
 * received messages through a conflated `StateFlow`: of two frames arriving closer together than
 * one decode the first is lost, and two identical consecutive frames are dropped by `StateFlow`'s
 * equality check. The server sends every SyncPlay transport action as
 * a `SendCommand`/`GroupStateUpdate` pair ~2 ms apart — so on the SDK, `back-to-back frames` fails
 * on the command and `identical consecutive commands` fails on the second unpause. Both must pass
 * here, for ever.
 *
 * The frames are the server's own wire format, written out rather than re-encoded from SDK objects:
 * a test that builds its input with the same serializer it asserts on proves only that the
 * serializer is self-consistent. These are what jellyfin 10.11 puts on the socket, field names,
 * discriminators, dates and all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpSyncPlaySocketTest {
    private val webSockets = RecordingWebSockets()
    private val apiClient = mockk<ApiClient>()

    init {
        every { apiClient.clientInfo } returns ClientInfo(name = "Jellyboost", version = "0.1.0")
        every { apiClient.deviceInfo } returns DeviceInfo(id = DEVICE_ID, name = "test tablet")
        every { apiClient.accessToken } returns ACCESS_TOKEN
        every { apiClient.createUrl(any(), any(), any(), any()) } returns SOCKET_URL
    }

    @Test
    fun `a command and the group update behind it both arrive, in order`() =
        runTest {
            val received = connect()

            // The pair the server sends for every transport action, with nothing in between.
            webSockets.deliver(COMMAND_FRAME, STATE_FRAME)
            runCurrent()

            received shouldHaveSize 2
            val command = received[0].shouldBeInstanceOf<SyncPlayCommand>()
            command.type shouldBe SyncPlayCommandType.Unpause
            received[1] shouldBe
                SyncPlayGroupEvent.StateChanged(
                    state = SyncPlayGroupState.Playing,
                    reason = SyncPlayRequestKind.Unpause,
                )
        }

    @Test
    fun `two identical consecutive commands both arrive`() =
        runTest {
            val received = connect()

            webSockets.deliver(COMMAND_FRAME, COMMAND_FRAME)
            runCurrent()

            received shouldHaveSize 2
        }

    @Test
    fun `a command carries the payload the mapping produced`() =
        runTest {
            val received = connect()

            webSockets.deliver(COMMAND_FRAME)
            runCurrent()

            val command = received.single().shouldBeInstanceOf<SyncPlayCommand>()
            command.type shouldBe SyncPlayCommandType.Unpause
            // Proves the wall-clock boundary too: `When` is read as an instant, not as local time.
            command.whenInstant shouldBe Instant.parse("2026-07-30T20:41:03Z")
            command.emittedAt shouldBe Instant.parse("2026-07-30T20:41:00Z")
            command.positionTicks shouldBe 12_000_000L
            command.playlistItemId shouldBe UUID.fromString("44444444-4444-4444-4444-444444444444")
        }

    @Test
    fun `a force keep-alive is answered and never surfaced`() =
        runTest {
            val received = connect()

            webSockets.deliver(KEEP_ALIVE_FRAME)
            runCurrent()

            received.shouldBeEmpty()
            webSockets.current.sent shouldHaveSize 1
            webSockets.current.sent.single() shouldContain "KeepAlive"

            // Half of the announced 60 s timeout, as `DefaultSocketApi` did it.
            advanceTimeBy(30_001)
            runCurrent()
            webSockets.current.sent shouldHaveSize 2
        }

    @Test
    fun `an undecodable frame is dropped and the stream carries on`() =
        runTest {
            val received = connect()

            webSockets.deliver(UNKNOWN_FRAME, COMMAND_FRAME)
            runCurrent()

            received shouldHaveSize 1
            received.single().shouldBeInstanceOf<SyncPlayCommand>()
        }

    @Test
    fun `the socket is connected once it opens, which is what the join waits for`() =
        runTest {
            val socket = OkHttpSyncPlaySocket(apiClient, webSockets, backgroundScope)
            val states = mutableListOf<SyncPlaySocketState>()
            backgroundScope.launch { socket.connectionState.collect { states += it } }
            backgroundScope.launch { socket.commands.collect { } }
            runCurrent()

            states.last() shouldBe SyncPlaySocketState.Connecting

            webSockets.open()
            runCurrent()
            states.last() shouldBe SyncPlaySocketState.Connected
        }

    @Test
    fun `a dropped connection is retried and delivers again`() =
        runTest {
            val received = connect()

            webSockets.fail(IOException("the network went away"))
            runCurrent()
            // The stream must not end — the controller reads a finished stream as a lost group.
            webSockets.connections shouldHaveSize 1

            advanceTimeBy(1_001)
            runCurrent()
            webSockets.connections shouldHaveSize 2

            webSockets.open()
            webSockets.deliver(COMMAND_FRAME)
            runCurrent()
            received shouldHaveSize 1
        }

    @Test
    fun `the socket authenticates as the api client's own session`() =
        runTest {
            connect()

            val authorization = webSockets.requests.single().header("Authorization")
            authorization.shouldBeInstanceOf<String>()
            // Same device id and token as every REST call, or the server attaches this socket to a
            // different session and never addresses this client's SyncPlay messages to it.
            authorization shouldContain DEVICE_ID
            authorization shouldContain ACCESS_TOKEN
            webSockets.requests
                .single()
                .url
                .toString() shouldBe SOCKET_URL
        }

    /**
     * Opens the socket with both streams collected, as `SyncPlayController.performJoin` does.
     *
     * Returns the one list both streams are collected into: the order the frames come out in is
     * what the first test is about, and two separate lists cannot state it.
     */
    private fun TestScope.connect(): List<Any> {
        val socket = OkHttpSyncPlaySocket(apiClient, webSockets, backgroundScope)
        val received = mutableListOf<Any>()
        backgroundScope.launch { socket.commands.collect { received += it } }
        backgroundScope.launch { socket.groupUpdates.collect { received += it } }
        runCurrent()
        webSockets.open()
        runCurrent()
        return received
    }

    private companion object {
        const val SOCKET_URL = "http://server.test/socket"
        const val DEVICE_ID = "device-0001"
        const val ACCESS_TOKEN = "a-token"

        /** `SendCommand` — the frame the SDK's conflation loses. */
        val COMMAND_FRAME =
            """
            {
              "MessageType": "SyncPlayCommand",
              "MessageId": "99999999-9999-9999-9999-999999999999",
              "Data": {
                "GroupId": "11111111-1111-1111-1111-111111111111",
                "PlaylistItemId": "44444444-4444-4444-4444-444444444444",
                "When": "2026-07-30T20:41:03.0000000Z",
                "PositionTicks": 12000000,
                "Command": "Unpause",
                "EmittedAt": "2026-07-30T20:41:00.0000000Z"
              }
            }
            """.trimIndent()

        /** The `GroupStateUpdate` the server sends ~2 ms behind the command. */
        val STATE_FRAME =
            """
            {
              "MessageType": "SyncPlayGroupUpdate",
              "MessageId": "88888888-8888-8888-8888-888888888888",
              "Data": {
                "Type": "StateUpdate",
                "GroupId": "11111111-1111-1111-1111-111111111111",
                "Data": { "State": "Playing", "Reason": "Unpause" }
              }
            }
            """.trimIndent()

        val KEEP_ALIVE_FRAME =
            """
            {
              "MessageType": "ForceKeepAlive",
              "MessageId": "77777777-7777-7777-7777-777777777777",
              "Data": 60
            }
            """.trimIndent()

        /** A message type this SDK has no serializer for — a server newer than the client. */
        val UNKNOWN_FRAME =
            """
            {
              "MessageType": "SomethingThisClientHasNeverHeardOf",
              "MessageId": "66666666-6666-6666-6666-666666666666",
              "Data": {}
            }
            """.trimIndent()
    }
}

/** A [WebSocket.Factory] the test drives by hand — no HTTP stack, no threads but the test's. */
private class RecordingWebSockets : WebSocket.Factory {
    val connections = mutableListOf<RecordingWebSocket>()
    val requests = mutableListOf<Request>()

    val current: RecordingWebSocket get() = connections.last()

    override fun newWebSocket(
        request: Request,
        listener: WebSocketListener,
    ): WebSocket {
        requests += request
        return RecordingWebSocket(request, listener).also { connections += it }
    }

    fun open() = current.listener.onOpen(current, current.handshake())

    fun deliver(vararg frames: String) = frames.forEach { current.listener.onMessage(current, it) }

    fun fail(error: Throwable) = current.listener.onFailure(current, error, null)
}

private class RecordingWebSocket(
    private val outgoing: Request,
    val listener: WebSocketListener,
) : WebSocket {
    val sent = mutableListOf<String>()

    fun handshake(): Response =
        Response
            .Builder()
            .request(outgoing)
            .protocol(Protocol.HTTP_1_1)
            .code(101)
            .message("Switching Protocols")
            .build()

    override fun request(): Request = outgoing

    override fun queueSize(): Long = 0

    override fun send(text: String): Boolean = sent.add(text)

    override fun send(bytes: ByteString): Boolean = true

    override fun close(
        code: Int,
        reason: String?,
    ): Boolean = true

    override fun cancel() = Unit
}
