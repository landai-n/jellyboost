package dev.jellyboost.player.syncplay

import dev.jellyboost.player.syncplay.model.SyncPlayCommandType
import dev.jellyboost.player.syncplay.model.SyncPlayGroupEvent
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueEntry
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayQueueUpdateReason
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import dev.jellyboost.player.syncplay.model.SyncPlayRequestKind
import dev.jellyboost.player.syncplay.model.SyncPlayShuffleMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupQueueMode
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupStateUpdate
import org.jellyfin.sdk.model.api.GroupUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.PlaybackRequestType
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayQueueItem
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import org.jellyfin.sdk.model.api.UtcTimeResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

/**
 * Unit tests for the single SDK↔domain boundary, `SyncPlayDtoMapping`.
 *
 * jellyfin-sdk 1.8.12 deserializes every date field into a `LocalDateTime` in
 * [ZoneId.systemDefault], not UTC — the mistake that shifts progress reports, and SyncPlay
 * commands, by two hours if left uncorrected.
 *
 * So the zone here is always explicit and never UTC: `Europe/Paris`, +02:00 on the test date.
 * Any conversion that quietly assumes UTC fails these tests by exactly two hours.
 */
class SyncPlayDtoMappingTest {
    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @BeforeEach
    fun setUp() {
        // Deliberately different from PARIS: the default-zone tests would pass by accident if the
        // ambient zone happened to match the explicit one.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @AfterEach
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    // Time boundary ---------------------------------------------------------------------------

    @Test
    fun `a command's when is read as local wall clock, not as UTC`() {
        val command = sendCommand(whenAt = LocalDateTime.of(2026, 7, 30, 20, 41, 3))

        val mapped = command.toDomain(PARIS)

        mapped.whenInstant shouldBe Instant.parse("2026-07-30T18:41:03Z")
        // The failure this pins: reading 20:41:03 as if it were already UTC.
        mapped.whenInstant shouldNotBe Instant.parse("2026-07-30T20:41:03Z")
    }

    @Test
    fun `emittedAt crosses the same boundary as when`() {
        val command =
            sendCommand(
                whenAt = LocalDateTime.of(2026, 7, 30, 20, 41, 3),
                emittedAt = LocalDateTime.of(2026, 7, 30, 20, 41, 0),
            )

        command.toDomain(PARIS).emittedAt shouldBe Instant.parse("2026-07-30T18:41:00Z")
    }

    @Test
    fun `an instant sent to the server round trips through the same zone`() {
        val instant = Instant.parse("2026-07-30T18:41:03Z")

        val wallClock = instant.toSdkWallClock(PARIS)

        wallClock shouldBe LocalDateTime.of(2026, 7, 30, 20, 41, 3)
        sendCommand(whenAt = wallClock).toDomain(PARIS).whenInstant shouldBe instant
    }

    @Test
    fun `the default zone is the system zone, so it round trips too`() {
        // The default path (no explicit zone) must stay symmetric even though the ambient zone
        // here (New York, -04:00) is neither UTC nor PARIS.
        val instant = Instant.parse("2026-07-30T18:41:03Z")

        val mapped = sendCommand(whenAt = instant.toSdkWallClock()).toDomain()

        mapped.whenInstant shouldBe instant
        instant.toSdkWallClock() shouldBe LocalDateTime.of(2026, 7, 30, 14, 41, 3)
    }

    @Test
    fun `a UTC time response becomes an NTP sample with the server's readings converted`() {
        val requestSent = Instant.parse("2026-07-30T18:41:00.000Z")
        val responseReceived = Instant.parse("2026-07-30T18:41:00.400Z")
        val response =
            UtcTimeResponse(
                requestReceptionTime = LocalDateTime.of(2026, 7, 30, 20, 41, 0, 150_000_000),
                responseTransmissionTime = LocalDateTime.of(2026, 7, 30, 20, 41, 0, 250_000_000),
            )

        val sample = response.toSample(requestSent, responseReceived, PARIS)

        sample.requestSent shouldBe requestSent
        sample.responseReceived shouldBe responseReceived
        sample.serverReceived shouldBe Instant.parse("2026-07-30T18:41:00.150Z")
        sample.serverSent shouldBe Instant.parse("2026-07-30T18:41:00.250Z")
    }

    // Command mapping -------------------------------------------------------------------------

    @Test
    fun `every send command type has a domain counterpart`() {
        val mapped = SendCommandType.entries.map { sendCommand(command = it).toDomain(PARIS).type }

        mapped shouldContainExactly
            listOf(
                SyncPlayCommandType.Unpause,
                SyncPlayCommandType.Pause,
                SyncPlayCommandType.Stop,
                SyncPlayCommandType.Seek,
            )
    }

    @Test
    fun `a command without a position keeps the position absent rather than guessing zero`() {
        // Seeking to 0 and "no position given" are different instructions to the scheduler.
        sendCommand(positionTicks = null).toDomain(PARIS).positionTicks.shouldBeNull()
        sendCommand(positionTicks = 0L).toDomain(PARIS).positionTicks shouldBe 0L
    }

    // Group update mapping --------------------------------------------------------------------

    @Test
    fun `a group joined update carries the whole group`() {
        val update = SyncPlayGroupJoinedUpdate(groupId = GROUP_ID, data = groupInfo())

        val event = update.toEvent(PARIS).shouldBeInstanceOf<SyncPlayGroupEvent.Joined>()

        with(event.group) {
            id shouldBe GROUP_ID
            name shouldBe "Movie night"
            participants shouldContainExactly listOf("ada", "grace")
            state shouldBe SyncPlayGroupState.Waiting
            lastUpdatedAt shouldBe Instant.parse("2026-07-30T18:41:03Z")
        }
    }

    @Test
    fun `a group left update names the group we are no longer in`() {
        SyncPlayGroupLeftUpdate(groupId = GROUP_ID, data = GROUP_ID.toString()).toEvent(PARIS) shouldBe
            SyncPlayGroupEvent.Left(GROUP_ID)
    }

    @Test
    fun `a state update keeps the reason the group moved`() {
        val update =
            SyncPlayStateUpdate(
                groupId = GROUP_ID,
                data = GroupStateUpdate(state = GroupStateType.WAITING, reason = PlaybackRequestType.BUFFER),
            )

        update.toEvent(PARIS) shouldBe
            SyncPlayGroupEvent.StateChanged(
                state = SyncPlayGroupState.Waiting,
                reason = SyncPlayRequestKind.Buffer,
            )
    }

    @Test
    fun `every group state has a domain counterpart`() {
        val mapped =
            GroupStateType.entries.map {
                SyncPlayStateUpdate(GROUP_ID, GroupStateUpdate(it, PlaybackRequestType.PLAY))
                    .toEvent(PARIS)
                    .shouldBeInstanceOf<SyncPlayGroupEvent.StateChanged>()
                    .state
            }

        mapped shouldContainExactly
            listOf(
                SyncPlayGroupState.Idle,
                SyncPlayGroupState.Waiting,
                SyncPlayGroupState.Paused,
                SyncPlayGroupState.Playing,
            )
    }

    @Test
    fun `every playback request type has a domain counterpart`() {
        // Pins that none of them collapses onto the wrong kind, which a copy-pasted `when` arm
        // easily does.
        val mapped =
            PlaybackRequestType.entries.map {
                SyncPlayStateUpdate(GROUP_ID, GroupStateUpdate(GroupStateType.PLAYING, it))
                    .toEvent(PARIS)
                    .shouldBeInstanceOf<SyncPlayGroupEvent.StateChanged>()
                    .reason
            }

        mapped.distinct().size shouldBe PlaybackRequestType.entries.size
        mapped.first() shouldBe SyncPlayRequestKind.Play
        mapped.last() shouldBe SyncPlayRequestKind.IgnoreWait
    }

    @Test
    fun `a queue update carries the slots, the playing one and both modes`() {
        val update = SyncPlayPlayQueueUpdate(groupId = GROUP_ID, data = playQueue())

        val queue =
            update
                .toEvent(PARIS)
                .shouldBeInstanceOf<SyncPlayGroupEvent.QueueChanged>()
                .queue

        queue.entries shouldContainExactly
            listOf(
                SyncPlayQueueEntry(itemId = ITEM_A, playlistItemId = SLOT_A),
                SyncPlayQueueEntry(itemId = ITEM_B, playlistItemId = SLOT_B),
            )
        queue.playingItemIndex shouldBe 1
        queue.playingEntry shouldBe SyncPlayQueueEntry(itemId = ITEM_B, playlistItemId = SLOT_B)
        queue.startPositionTicks shouldBe 12_000_000L
        queue.isPlaying shouldBe true
        queue.shuffleMode shouldBe SyncPlayShuffleMode.Shuffle
        queue.repeatMode shouldBe SyncPlayRepeatMode.All
        queue.reason shouldBe SyncPlayQueueUpdateReason.NextItem
        queue.lastUpdate shouldBe Instant.parse("2026-07-30T18:41:03Z")
    }

    @Test
    fun `an out of range playing index yields no playing entry rather than throwing`() {
        // The server sends index 0 with an empty playlist when a group is created but idle.
        val update = SyncPlayPlayQueueUpdate(GROUP_ID, playQueue(playlist = emptyList(), playingItemIndex = 0))

        update
            .toEvent(PARIS)
            .shouldBeInstanceOf<SyncPlayGroupEvent.QueueChanged>()
            .queue.playingEntry
            .shouldBeNull()
    }

    @Test
    fun `every queue update reason has a domain counterpart`() {
        val mapped =
            PlayQueueUpdateReason.entries.map {
                SyncPlayPlayQueueUpdate(GROUP_ID, playQueue(reason = it))
                    .toEvent(PARIS)
                    .shouldBeInstanceOf<SyncPlayGroupEvent.QueueChanged>()
                    .queue.reason
            }

        mapped.distinct().size shouldBe PlayQueueUpdateReason.entries.size
    }

    @Test
    fun `membership updates carry the other user's display name`() {
        SyncPlayUserJoinedUpdate(GROUP_ID, "grace").toEvent(PARIS) shouldBe SyncPlayGroupEvent.UserJoined("grace")
        SyncPlayUserLeftUpdate(GROUP_ID, "grace").toEvent(PARIS) shouldBe SyncPlayGroupEvent.UserLeft("grace")
    }

    @Test
    fun `the three error updates map to their own events`() {
        SyncPlayNotInGroupUpdate(GROUP_ID, "").toEvent(PARIS) shouldBe SyncPlayGroupEvent.NotInGroup
        SyncPlayGroupDoesNotExistUpdate(GROUP_ID, "").toEvent(PARIS) shouldBe SyncPlayGroupEvent.GroupGone
        SyncPlayLibraryAccessDeniedUpdate(GROUP_ID, "").toEvent(PARIS) shouldBe
            SyncPlayGroupEvent.LibraryAccessDenied
    }

    @Test
    fun `every group update subtype the SDK can deliver is mapped`() {
        // Pins that no two subtypes collapse onto the same event, which an exhaustive but
        // copy-pasted `when` would do.
        val events =
            listOf<GroupUpdate>(
                SyncPlayGroupJoinedUpdate(GROUP_ID, groupInfo()),
                SyncPlayGroupLeftUpdate(GROUP_ID, GROUP_ID.toString()),
                SyncPlayStateUpdate(GROUP_ID, GroupStateUpdate(GroupStateType.IDLE, PlaybackRequestType.PLAY)),
                SyncPlayPlayQueueUpdate(GROUP_ID, playQueue()),
                SyncPlayUserJoinedUpdate(GROUP_ID, "grace"),
                SyncPlayUserLeftUpdate(GROUP_ID, "ada"),
                SyncPlayNotInGroupUpdate(GROUP_ID, ""),
                SyncPlayGroupDoesNotExistUpdate(GROUP_ID, ""),
                SyncPlayLibraryAccessDeniedUpdate(GROUP_ID, ""),
            ).map { it.toEvent(PARIS) }

        events.map { it::class }.distinct().size shouldBe events.size
    }

    // Domain -> SDK ---------------------------------------------------------------------------

    @Test
    fun `queue, shuffle and repeat modes translate back to the SDK's names`() {
        SyncPlayQueueMode.Queue.toSdk() shouldBe GroupQueueMode.QUEUE
        SyncPlayQueueMode.QueueNext.toSdk() shouldBe GroupQueueMode.QUEUE_NEXT
        SyncPlayShuffleMode.Sorted.toSdk() shouldBe GroupShuffleMode.SORTED
        SyncPlayShuffleMode.Shuffle.toSdk() shouldBe GroupShuffleMode.SHUFFLE
        SyncPlayRepeatMode.None.toSdk() shouldBe GroupRepeatMode.REPEAT_NONE
        SyncPlayRepeatMode.One.toSdk() shouldBe GroupRepeatMode.REPEAT_ONE
        SyncPlayRepeatMode.All.toSdk() shouldBe GroupRepeatMode.REPEAT_ALL
    }

    // Fixtures --------------------------------------------------------------------------------

    private fun sendCommand(
        whenAt: LocalDateTime = PARIS_WALL_CLOCK,
        emittedAt: LocalDateTime = PARIS_WALL_CLOCK,
        positionTicks: Long? = 12_000_000L,
        command: SendCommandType = SendCommandType.UNPAUSE,
    ) = SendCommand(
        groupId = GROUP_ID,
        playlistItemId = SLOT_A,
        `when` = whenAt,
        positionTicks = positionTicks,
        command = command,
        emittedAt = emittedAt,
    )

    private fun groupInfo() =
        GroupInfoDto(
            groupId = GROUP_ID,
            groupName = "Movie night",
            state = GroupStateType.WAITING,
            participants = listOf("ada", "grace"),
            lastUpdatedAt = PARIS_WALL_CLOCK,
        )

    private fun playQueue(
        playlist: List<SyncPlayQueueItem> =
            listOf(
                SyncPlayQueueItem(itemId = ITEM_A, playlistItemId = SLOT_A),
                SyncPlayQueueItem(itemId = ITEM_B, playlistItemId = SLOT_B),
            ),
        playingItemIndex: Int = 1,
        reason: PlayQueueUpdateReason = PlayQueueUpdateReason.NEXT_ITEM,
    ) = PlayQueueUpdate(
        reason = reason,
        lastUpdate = PARIS_WALL_CLOCK,
        playlist = playlist,
        playingItemIndex = playingItemIndex,
        startPositionTicks = 12_000_000L,
        isPlaying = true,
        shuffleMode = GroupShuffleMode.SHUFFLE,
        repeatMode = GroupRepeatMode.REPEAT_ALL,
    )

    private companion object {
        /** +02:00 on the test date — a wrong conversion is off by exactly two hours. */
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** 18:41:03 UTC, written the way a Paris-local SDK would hand it over. */
        val PARIS_WALL_CLOCK: LocalDateTime = LocalDateTime.of(2026, 7, 30, 20, 41, 3)

        val GROUP_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ITEM_A: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val ITEM_B: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val SLOT_A: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val SLOT_B: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
    }
}
