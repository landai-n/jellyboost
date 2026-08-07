package dev.jellyboost.player.syncplay.socket

import dev.jellyboost.core.network.jellyfinAuthorizationHeader
import dev.jellyboost.player.syncplay.di.SyncPlayScope
import dev.jellyboost.player.syncplay.di.SyncPlaySocketClient
import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.toDomain
import dev.jellyboost.player.syncplay.toEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.ApiSerializer
import org.jellyfin.sdk.model.api.ForceKeepAliveMessage
import org.jellyfin.sdk.model.api.InboundKeepAliveMessage
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [SyncPlaySocket] on a websocket of our own, because the SDK's loses messages.
 *
 * **The defect this exists for** (jellyfin-sdk-kotlin 1.8.12 and master as of 2026-07-31, recorded
 * in DECISIONS.md): `SocketConnection.state` is a **`StateFlow`** whose values include received
 * messages — `OkHttpSocketConnection.kt`:39-43 does `onMessage → _state.value = Message(text)` —
 * and `DefaultSocketApi.messages` decodes JSON in a `.map` over it. A `StateFlow` is conflated, so
 * two frames arriving faster than the decode of the first lose the first, and two *identical*
 * consecutive frames are dropped by its equality check. The server sends every SyncPlay transport
 * action as a back-to-back pair — `SendCommand` then `GroupStateUpdate`, ~2 ms apart — so the
 * command is systematically the frame that loses. On device that is a member sitting still while
 * the group plays. [SdkSyncPlaySocket] is kept next door as the reference implementation of it.
 *
 * **The fix is one rule: nothing but a queue push happens on OkHttp's reader thread.** `onMessage`
 * `trySend`s the raw text into a generously bounded [Channel]; a consumer coroutine decodes and routes.
 * There is no conflation anywhere on the path, and no frame can overtake or erase another.
 *
 * Everything else is deliberately the contract the seam already had: the two streams are cold, one
 * connection is shared between them, it opens on the first collector and closes with the last
 * (`shareIn` + [SharingStarted.WhileSubscribed], where the SDK reference-counted subscribers), and
 * [connectionState] is the same hot signal `SyncPlayController.awaitSocketReady`/`watchSocket`
 * already watch. The streams never *end* while a collector is attached — the controller reads a
 * finished stream as a confirmed connection loss — so a drop is a reconnect, not a completion.
 */
@Singleton
internal class OkHttpSyncPlaySocket
    @Inject
    constructor(
        private val apiClient: ApiClient,
        @SyncPlaySocketClient private val webSockets: WebSocket.Factory,
        @SyncPlayScope scope: CoroutineScope,
    ) : SyncPlaySocket {
        private val _connectionState =
            MutableStateFlow<SyncPlaySocketState>(SyncPlaySocketState.Disconnected(error = null))

        override val connectionState: Flow<SyncPlaySocketState> = _connectionState.asStateFlow()

        /**
         * Every SyncPlay frame the server sent, decoded, in arrival order.
         *
         * Shared rather than per-stream so that collecting both [groupUpdates] and [commands] uses
         * one socket — one server session, as the SDK's reference counting gave us. `replay = 0`
         * with the default (suspending) buffer: a slow collector delays the next frame, it never
         * loses it.
         */
        private val frames: SharedFlow<OutboundWebSocketMessage> =
            connectionLoop().shareIn(scope, SharingStarted.WhileSubscribed(), replay = 0)

        override val groupUpdates: Flow<SyncPlayGroupEvent> =
            frames
                .filterIsInstance<SyncPlayGroupUpdateMessage>()
                .map { it.data.toEvent() }

        override val commands: Flow<SyncPlayCommand> =
            frames
                .filterIsInstance<SyncPlayCommandMessage>()
                // `SyncPlayCommandMessage.data` is nullable in the SDK's schema; a command with no
                // payload carries nothing to schedule, so it is dropped rather than guessed at —
                // but loudly, exactly as the SDK-backed implementation did.
                .mapNotNull { message ->
                    message.data?.toDomain()
                        ?: null.also { Timber.w("A SyncPlay command message arrived with no payload") }
                }

        /**
         * One connection after another, for as long as anything is collecting.
         *
         * Backoff is 1 s → 2 s → 4 s → capped at 10 s, reset as soon as an attempt actually opens,
         * so a flapping network re-establishes quickly and a server that is simply down is not
         * hammered. Nothing is re-sent on a reconnect: SyncPlay has no subscription message, the
         * server pushes to group members unconditionally. Failures are logged and retried rather
         * than propagated — ending this flow would tell the controller the group is lost.
         */
        private fun connectionLoop(): Flow<OutboundWebSocketMessage> =
            channelFlow {
                var backoff = INITIAL_RETRY
                while (isActive) {
                    val opened = AtomicBoolean(false)
                    val failure = runCatching { connection(opened).collect { send(it) } }.exceptionOrNull()
                    if (failure is CancellationException) throw failure
                    _connectionState.value = SyncPlaySocketState.Disconnected(failure)
                    if (opened.get()) backoff = INITIAL_RETRY
                    if (failure == null) {
                        Timber.i("The SyncPlay websocket closed; reconnecting in %s", backoff)
                    } else {
                        Timber.w(failure, "The SyncPlay websocket failed; reconnecting in %s", backoff)
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(MAX_RETRY)
                }
            }

        /**
         * A single connection: open it, drain it, end when it does.
         *
         * The flow completes when the server closes the socket and fails when the connection does —
         * either way [connectionLoop] takes it from there. [opened] is set from OkHttp's thread,
         * hence the atomic; it is what tells a reconnect from a first attempt.
         */
        private fun connection(opened: AtomicBoolean): Flow<OutboundWebSocketMessage> =
            callbackFlow {
                val request = socketRequest()
                Timber.d("Opening the SyncPlay websocket at %s", request.url)
                _connectionState.value = SyncPlaySocketState.Connecting
                // Generously bounded: the reader thread must never block or drop under any
                // *healthy* load — this queue is the whole difference from the SDK's conflated
                // state flow — but a consumer wedged on network I/O for minutes must not grow the
                // heap without limit either (audit SP-19). SyncPlay traffic is a few frames a
                // second at its busiest, so the cap is minutes of backlog; hitting it is logged
                // in [listener] rather than absorbed silently.
                val raw = Channel<String>(RAW_FRAME_BUFFER)
                val socket = webSockets.newWebSocket(request, listener(opened, raw))
                launch {
                    // The close cause reaches us through the channel, so the frames queued before
                    // a failure are decoded and delivered before the connection is given up on.
                    close(runCatching { route(raw, socket) }.exceptionOrNull())
                }
                awaitClose {
                    raw.close()
                    socket.cancel()
                    // "Disconnected when nothing is collecting" is part of the seam's contract, and
                    // this is also the cancellation path — where `connectionLoop` never runs again
                    // and so never gets to say so itself.
                    _connectionState.value = SyncPlaySocketState.Disconnected(error = null)
                }
            }

        /**
         * The only code that runs on OkHttp's threads.
         *
         * It hands the frame to [raw] and returns. Everything else — decoding, mapping, delivery,
         * the keep-alive reply — happens in [route], on a coroutine.
         */
        private fun listener(
            opened: AtomicBoolean,
            raw: Channel<String>,
        ): WebSocketListener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    opened.set(true)
                    Timber.i("The SyncPlay websocket is connected")
                    _connectionState.value = SyncPlaySocketState.Connected
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    if (!raw.trySend(text).isSuccess) {
                        Timber.w("SyncPlay frame queue full (%d); dropping a frame", RAW_FRAME_BUFFER)
                    }
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    raw.close()
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    raw.close()
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    raw.close(t)
                }
            }

        /**
         * Decodes each queued frame and sends the SyncPlay ones downstream.
         *
         * Returns when the connection ended cleanly; throws what closed it otherwise. Frames the
         * app has no use for are ignored here rather than filtered downstream, so a busy server
         * session (library scans, playback state for other clients) costs one `when` branch.
         */
        private suspend fun ProducerScope<OutboundWebSocketMessage>.route(
            raw: ReceiveChannel<String>,
            socket: WebSocket,
        ) {
            var keepAlive: Job? = null
            try {
                for (text in raw) {
                    val message = decode(text) ?: continue
                    when (message) {
                        is SyncPlayCommandMessage -> {
                            Timber.d(
                                "SyncPlay frame: command %s when=%s emitted=%s",
                                message.data?.command,
                                message.data?.`when`,
                                message.data?.emittedAt,
                            )
                            send(message)
                        }

                        is SyncPlayGroupUpdateMessage -> {
                            Timber.d("SyncPlay frame: group update %s", message.data::class.simpleName)
                            send(message)
                        }

                        // The server dictates the cadence; a socket that stops answering is reaped
                        // along with this client's session, which would take the group membership
                        // with it.
                        is ForceKeepAliveMessage -> {
                            keepAlive?.cancel()
                            keepAlive = launch { sendKeepAlives(socket, message.data) }
                        }

                        else -> Unit
                    }
                }
            } finally {
                // Explicit because the ticker never returns on its own, and it is a child of the
                // connection's scope rather than of this coroutine: left running it would keep the
                // connection flow from ever completing.
                keepAlive?.cancel()
            }
        }

        /** Answers `ForceKeepAlive` every *timeout/2*, as `DefaultSocketApi.resetKeepAliveTicker` did. */
        private suspend fun sendKeepAlives(
            socket: WebSocket,
            timeoutSeconds: Int,
        ) {
            val period = (timeoutSeconds.seconds / 2).coerceAtLeast(MIN_KEEP_ALIVE)
            Timber.d("SyncPlay websocket keep-alive every %s (the server allows %ds)", period, timeoutSeconds)
            val payload = ApiSerializer.encodeSocketMessage(InboundKeepAliveMessage())
            while (true) {
                socket.send(payload)
                delay(period)
            }
        }

        /**
         * The socket request: same URL, same credentials, same session as every REST call.
         *
         * Read from the [ApiClient] on every attempt rather than cached, so a re-pointed client (a
         * server switch, a re-issued token) is picked up by the next reconnect. The device id and
         * the access token are what make the server attach this socket to *our* session — a socket
         * authenticated as anything else is not sent this client's SyncPlay messages at all.
         *
         * No same-origin guard around [jellyfinAuthorizationHeader] here (unlike
         * `JellyfinAuthInterceptor`): the URL comes straight from `apiClient.createUrl`, not from a
         * caller-supplied or redirect-followed one, so it is always this same [ApiClient]'s own
         * server and there is no other origin the header could leak to.
         */
        private fun socketRequest(): Request =
            Request
                .Builder()
                .url(apiClient.createUrl(SOCKET_PATH))
                .header("Authorization", jellyfinAuthorizationHeader(apiClient))
                .build()

        /**
         * The SDK's own decoder, off the socket thread.
         *
         * A frame this client cannot read must not take the connection down with it — the server
         * sends every session everything, and one unknown message type would otherwise cost the
         * whole group. The raw `MessageType` is logged because that is the only thing that makes
         * such a frame identifiable after the fact.
         */
        private fun decode(text: String): OutboundWebSocketMessage? =
            runCatching { ApiSerializer.decodeSocketMessage(text) }
                .onFailure { Timber.w(it, "Could not decode a websocket frame of type %s", rawMessageType(text)) }
                .getOrNull()

        private fun rawMessageType(text: String): String =
            runCatching {
                ApiSerializer.json
                    .parseToJsonElement(text)
                    .jsonObject[MESSAGE_TYPE]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull() ?: "unknown"

        private companion object {
            const val SOCKET_PATH = "/socket"

            /** The SDK's sealed-class discriminator for `OutboundWebSocketMessage`. */
            const val MESSAGE_TYPE = "MessageType"

            val INITIAL_RETRY: Duration = 1.seconds
            val MAX_RETRY: Duration = 10.seconds

            /**
             * Frames queued between OkHttp's reader thread and the routing coroutine.
             *
             * Far beyond any healthy backlog (SyncPlay is a handful of frames per action), but a
             * ceiling all the same, so a wedged consumer costs bounded memory in this
             * process-lifetime singleton rather than the heap (audit SP-19).
             */
            const val RAW_FRAME_BUFFER = 256

            /** Floor on the keep-alive period, in case a server ever announces an absurd timeout. */
            val MIN_KEEP_ALIVE: Duration = 1.seconds
        }
    }
