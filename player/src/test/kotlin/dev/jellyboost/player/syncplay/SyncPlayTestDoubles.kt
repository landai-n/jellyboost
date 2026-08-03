package dev.jellyboost.player.syncplay

import dev.jellyboost.player.syncplay.api.SyncPlayApi
import dev.jellyboost.player.syncplay.model.SyncPlayCommand
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import dev.jellyboost.player.syncplay.model.TimeSyncSample
import dev.jellyboost.player.syncplay.socket.SyncPlaySocket
import dev.jellyboost.player.syncplay.socket.SyncPlaySocketState
import dev.jellyboost.player.syncplay.time.SyncPlayTimeSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

// The doubles the SyncPlay coordinator tests are built on.
//
// All of them record rather than stub, because most of what Phase 2 has to be pinned on is *which
// call was made* — an in-group pause must reach the server and must not reach the player, and no
// assertion about a returned value can say that.

/** One call made on [SyncPlayApi], recorded in order. */
sealed interface SyncPlayCall {
    data object GetGroups : SyncPlayCall

    data class CreateGroup(
        val name: String,
    ) : SyncPlayCall

    data class JoinGroup(
        val groupId: UUID,
    ) : SyncPlayCall

    data object LeaveGroup : SyncPlayCall

    data class ReportBuffering(
        val at: Instant,
        val positionTicks: Long,
        val isPlaying: Boolean,
        val playlistItemId: UUID,
    ) : SyncPlayCall

    data class ReportReady(
        val at: Instant,
        val positionTicks: Long,
        val isPlaying: Boolean,
        val playlistItemId: UUID,
    ) : SyncPlayCall

    data class ReportPing(
        val pingMillis: Long,
    ) : SyncPlayCall

    data class SetIgnoreWait(
        val ignoreWait: Boolean,
    ) : SyncPlayCall

    data object RequestPause : SyncPlayCall

    data object RequestUnpause : SyncPlayCall

    data class RequestSeek(
        val positionTicks: Long,
    ) : SyncPlayCall

    data class RequestNextItem(
        val playlistItemId: UUID,
    ) : SyncPlayCall

    data class RequestPreviousItem(
        val playlistItemId: UUID,
    ) : SyncPlayCall

    data class SetPlaylistItem(
        val playlistItemId: UUID,
    ) : SyncPlayCall

    data class SetNewQueue(
        val itemIds: List<UUID>,
        val playingItemPosition: Int,
        val startPositionTicks: Long,
    ) : SyncPlayCall

    data class AddToQueue(
        val itemIds: List<UUID>,
        val mode: SyncPlayQueueMode,
    ) : SyncPlayCall

    data class MovePlaylistItem(
        val playlistItemId: UUID,
        val newIndex: Int,
    ) : SyncPlayCall

    data class RemoveFromPlaylist(
        val playlistItemIds: List<UUID>,
        val clearPlaylist: Boolean,
        val clearPlayingItem: Boolean,
    ) : SyncPlayCall

    data class SetShuffleMode(
        val mode: SyncPlayShuffleMode,
    ) : SyncPlayCall

    data class SetRepeatMode(
        val mode: SyncPlayRepeatMode,
    ) : SyncPlayCall

    data object SampleServerTime : SyncPlayCall
}

/** A recording [SyncPlayApi]. */
@Suppress("TooManyFunctions") // Implements the 21-operation facade.
class FakeSyncPlayApi(
    private val clock: Clock = Clock.systemUTC(),
) : SyncPlayApi {
    val calls = mutableListOf<SyncPlayCall>()

    /** Groups [getGroups] answers with. */
    var groups: List<SyncPlayGroupSummary> = emptyList()

    /** The group [createGroup] answers with. */
    var createdGroup: SyncPlayGroupSummary = group()

    /** Thrown by [joinGroup] and [createGroup] when set — the failed-join path. */
    var joinError: Throwable? = null

    /** Thrown by every [getGroups] while set — a rejoin that cannot even see the group list. */
    var getGroupsError: Throwable? = null

    /** Thrown by the next [reportBuffering] and then cleared — one refused call. */
    var failNextBuffering: Throwable? = null

    /** How far the fake server's clock runs ahead of the test clock, in milliseconds. */
    var serverOffsetMillis = 0L

    /** Round-trip the fake exchange reports, in milliseconds. */
    var roundTripMillis = 0L

    /** Thrown by the next [sampleServerTime] and then cleared — one failed exchange. */
    var failNextSample: Throwable? = null

    /** Thrown by every [sampleServerTime] while set — the REST API having stopped answering. */
    var failEverySample: Throwable? = null

    fun clearCalls() = calls.clear()

    /** Every call of type [T], oldest first. */
    inline fun <reified T : SyncPlayCall> callsOf(): List<T> = calls.filterIsInstance<T>()

    override suspend fun getGroups(): List<SyncPlayGroupSummary> {
        calls += SyncPlayCall.GetGroups
        getGroupsError?.let { throw it }
        return groups
    }

    override suspend fun createGroup(name: String): SyncPlayGroupSummary {
        calls += SyncPlayCall.CreateGroup(name)
        joinError?.let { throw it }
        return createdGroup
    }

    override suspend fun joinGroup(groupId: UUID) {
        calls += SyncPlayCall.JoinGroup(groupId)
        joinError?.let { throw it }
    }

    override suspend fun leaveGroup() {
        calls += SyncPlayCall.LeaveGroup
    }

    override suspend fun reportBuffering(
        at: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ) {
        calls += SyncPlayCall.ReportBuffering(at, positionTicks, isPlaying, playlistItemId)
        failNextBuffering?.let {
            failNextBuffering = null
            throw it
        }
    }

    override suspend fun reportReady(
        at: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ) {
        calls += SyncPlayCall.ReportReady(at, positionTicks, isPlaying, playlistItemId)
    }

    override suspend fun reportPing(pingMillis: Long) {
        calls += SyncPlayCall.ReportPing(pingMillis)
    }

    override suspend fun setIgnoreWait(ignoreWait: Boolean) {
        calls += SyncPlayCall.SetIgnoreWait(ignoreWait)
    }

    override suspend fun requestPause() {
        calls += SyncPlayCall.RequestPause
    }

    override suspend fun requestUnpause() {
        calls += SyncPlayCall.RequestUnpause
    }

    override suspend fun requestSeek(positionTicks: Long) {
        calls += SyncPlayCall.RequestSeek(positionTicks)
    }

    override suspend fun requestNextItem(playlistItemId: UUID) {
        calls += SyncPlayCall.RequestNextItem(playlistItemId)
    }

    override suspend fun requestPreviousItem(playlistItemId: UUID) {
        calls += SyncPlayCall.RequestPreviousItem(playlistItemId)
    }

    override suspend fun setPlaylistItem(playlistItemId: UUID) {
        calls += SyncPlayCall.SetPlaylistItem(playlistItemId)
    }

    override suspend fun setNewQueue(
        itemIds: List<UUID>,
        playingItemPosition: Int,
        startPositionTicks: Long,
    ) {
        calls += SyncPlayCall.SetNewQueue(itemIds, playingItemPosition, startPositionTicks)
    }

    override suspend fun addToQueue(
        itemIds: List<UUID>,
        mode: SyncPlayQueueMode,
    ) {
        calls += SyncPlayCall.AddToQueue(itemIds, mode)
    }

    override suspend fun movePlaylistItem(
        playlistItemId: UUID,
        newIndex: Int,
    ) {
        calls += SyncPlayCall.MovePlaylistItem(playlistItemId, newIndex)
    }

    override suspend fun removeFromPlaylist(
        playlistItemIds: List<UUID>,
        clearPlaylist: Boolean,
        clearPlayingItem: Boolean,
    ) {
        calls += SyncPlayCall.RemoveFromPlaylist(playlistItemIds, clearPlaylist, clearPlayingItem)
    }

    override suspend fun setShuffleMode(mode: SyncPlayShuffleMode) {
        calls += SyncPlayCall.SetShuffleMode(mode)
    }

    override suspend fun setRepeatMode(mode: SyncPlayRepeatMode) {
        calls += SyncPlayCall.SetRepeatMode(mode)
    }

    override suspend fun sampleServerTime(): TimeSyncSample {
        calls += SyncPlayCall.SampleServerTime
        failEverySample?.let { throw it }
        failNextSample?.let {
            failNextSample = null
            throw it
        }
        val sent = clock.instant()
        val received = sent.plusMillis(roundTripMillis)
        val serverInstant = sent.plusMillis(serverOffsetMillis + roundTripMillis / 2)
        return TimeSyncSample(
            requestSent = sent,
            serverReceived = serverInstant,
            serverSent = serverInstant,
            responseReceived = received,
        )
    }
}

/**
 * A [SyncPlaySocket] whose two streams are driven by the test.
 *
 * The streams are cold and hand-built rather than plain `MutableSharedFlow`s because the controller's
 * loss detection is defined in terms of the *collection* ending: [endStreams] completes them and
 * [failStreams] throws inside them, which is what the SDK's socket does when its own reconnection
 * gives up.
 */
class FakeSyncPlaySocket : SyncPlaySocket {
    private val groupEvents = MutableSharedFlow<SyncPlayGroupEvent>(extraBufferCapacity = 32)
    private val commandEvents = MutableSharedFlow<SyncPlayCommand>(extraBufferCapacity = 32)
    private val streamEnd = CompletableDeferred<Throwable?>()

    /** Collectors currently subscribed — "the socket is open" as the SDK reference-counts it. */
    var collectors = 0
        private set

    private val connection = MutableStateFlow<SyncPlaySocketState>(SyncPlaySocketState.Connected)

    override val groupUpdates: Flow<SyncPlayGroupEvent> = stream(groupEvents)

    override val commands: Flow<SyncPlayCommand> = stream(commandEvents)

    override val connectionState: Flow<SyncPlaySocketState> = connection

    suspend fun emit(event: SyncPlayGroupEvent) = groupEvents.emit(event)

    suspend fun emit(command: SyncPlayCommand) = commandEvents.emit(command)

    /** Publishes a socket state without ending anything — the flap the controller must ignore. */
    fun setConnectionState(state: SyncPlaySocketState) {
        connection.value = state
    }

    /** Ends both collections normally, as the SDK does when it stops trying to reconnect. */
    fun endStreams() {
        streamEnd.complete(null)
    }

    /** Ends both collections with [error]. */
    fun failStreams(error: Throwable) {
        streamEnd.complete(error)
    }

    private fun <T> stream(source: MutableSharedFlow<T>): Flow<T> =
        channelFlow {
            collectors++
            val relay = launch { source.collect { send(it) } }
            val error = streamEnd.await()
            relay.cancel()
            error?.let { throw it }
        }.onCompletion { collectors-- }
}

/** A [SyncPlayPlaybackHost] that records what it was asked to open. */
class FakeSyncPlayPlaybackHost : SyncPlayPlaybackHost {
    val loaded = mutableListOf<Pair<UUID, Long>>()

    /** What [loadItem] answers — `false` is "this item cannot be opened here". */
    var loadSucceeds = true

    /** Thrown by [loadItem] when set — the host's own scope dying mid-load (audit SP-02). */
    var loadError: Throwable? = null

    var snapshot = SyncPlayHostSnapshot(itemId = null, positionTicks = 0L, isPlaying = false)

    override suspend fun loadItem(
        itemId: UUID,
        startPositionTicks: Long,
    ): Boolean {
        loaded += itemId to startPositionTicks
        loadError?.let { throw it }
        if (loadSucceeds) snapshot = snapshot.copy(itemId = itemId, positionTicks = startPositionTicks)
        return loadSucceeds
    }

    override fun snapshot(): SyncPlayHostSnapshot = snapshot
}

/**
 * A [Clock] that reads the coroutine test scheduler's virtual time.
 *
 * Everything SyncPlay schedules is "wait until this instant", so a wall-clock reading and a
 * `delay()` have to agree exactly or the tests measure nothing.
 */
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val origin: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = origin.plusMillis(scheduler.currentTime)
}

/**
 * A [SyncPlayTimeSync] that already believes the server clock runs [offsetMillis] ahead of [clock].
 *
 * Built from a real sample rather than by poking the estimator, so the tests exercise the same path
 * the pinger does.
 */
fun timeSyncWithOffset(
    clock: Clock,
    offsetMillis: Long,
): SyncPlayTimeSync =
    SyncPlayTimeSync(clock).apply {
        if (offsetMillis == 0L) return@apply
        val now = clock.instant()
        record(
            TimeSyncSample(
                requestSent = now,
                serverReceived = now.plusMillis(offsetMillis),
                serverSent = now.plusMillis(offsetMillis),
                responseReceived = now,
            ),
        )
    }

/** A group summary with sensible defaults. */
fun group(
    id: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1"),
    name: String = "Film night",
    participants: List<String> = listOf("casey"),
    state: SyncPlayGroupState = SyncPlayGroupState.Idle,
    lastUpdatedAt: Instant = Instant.parse("2026-07-30T18:00:00Z"),
) = SyncPlayGroupSummary(id, name, participants, state, lastUpdatedAt)
