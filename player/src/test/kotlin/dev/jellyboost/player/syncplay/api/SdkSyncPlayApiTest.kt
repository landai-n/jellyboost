package dev.jellyboost.player.syncplay.api

import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import dev.jellyboost.player.syncplay.model.SyncPlayRepeatMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.client.extensions.timeSyncApi
import org.jellyfin.sdk.api.operations.TimeSyncApi
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupQueueMode
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.QueueRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.SetRepeatModeRequestDto
import org.jellyfin.sdk.model.api.UtcTimeResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import org.jellyfin.sdk.api.operations.SyncPlayApi as SdkSyncPlayOperations

/**
 * Unit tests for [SdkSyncPlayApi] — only the parts that are not pure delegation.
 *
 * Two things earn a test here: the DTO assembly the server is picky about (`SetNewQueue` takes
 * *library* item ids and an index, not playlist-item ids), and everything that touches a clock.
 * The clock cases run with a **non-UTC default zone** because that is the only setup in which
 * treating the SDK's `LocalDateTime` as UTC by mistake would be visible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SdkSyncPlayApiTest {
    private val apiClient = mockk<ApiClient>()
    private val syncPlayOperations = mockk<SdkSyncPlayOperations>()
    private val timeSyncOperations = mockk<TimeSyncApi>()
    private val clock = mockk<Clock>()

    private val api =
        SdkSyncPlayApi(
            apiClient = apiClient,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @BeforeEach
    fun setUp() {
        // The SDK serialises dates as `value.atZone(systemDefault())`, so only a non-UTC default
        // zone can catch a conversion that assumes UTC (see `SdkDateTime.kt`).
        TimeZone.setDefault(TimeZone.getTimeZone(TEST_ZONE))
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.syncPlayApi } returns syncPlayOperations
        every { apiClient.timeSyncApi } returns timeSyncOperations
    }

    @AfterEach
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
        unmockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
    }

    @Test
    fun `ready is stamped as the wall clock reading the server expects`() =
        runTest {
            val request = slot<ReadyRequestDto>()
            coEvery { syncPlayOperations.syncPlayReady(capture(request)) } returns unitResponse()

            api.reportReady(
                at = Instant.parse("2026-07-30T18:41:03Z"),
                positionTicks = 12_000_000L,
                isPlaying = true,
                playlistItemId = SLOT_ID,
            )

            // 18:41:03Z is 20:41:03 in Paris, and 20:41:03 is what the SDK will stamp +02:00 onto.
            request.captured.`when` shouldBe LocalDateTime.of(2026, 7, 30, 20, 41, 3)
            request.captured.`when`
                .atZone(ZoneId.of(TEST_ZONE))
                .toInstant() shouldBe Instant.parse("2026-07-30T18:41:03Z")
            request.captured.positionTicks shouldBe 12_000_000L
            request.captured.isPlaying shouldBe true
            request.captured.playlistItemId shouldBe SLOT_ID
        }

    @Test
    fun `a time sample brackets the call with the device clock and converts the server's readings`() =
        runTest {
            every { clock.instant() } returnsMany
                listOf(
                    Instant.parse("2026-07-30T18:41:00.000Z"),
                    Instant.parse("2026-07-30T18:41:00.400Z"),
                )
            coEvery { timeSyncOperations.getUtcTime() } returns
                Response(
                    content =
                        UtcTimeResponse(
                            // 20:41:00.150 Paris == 18:41:00.150Z, i.e. the server clock is on time.
                            requestReceptionTime = LocalDateTime.of(2026, 7, 30, 20, 41, 0, 150_000_000),
                            responseTransmissionTime = LocalDateTime.of(2026, 7, 30, 20, 41, 0, 250_000_000),
                        ),
                    status = 200,
                    headers = emptyMap(),
                )

            val sample = api.sampleServerTime()

            sample.requestSent shouldBe Instant.parse("2026-07-30T18:41:00.000Z")
            sample.responseReceived shouldBe Instant.parse("2026-07-30T18:41:00.400Z")
            sample.serverReceived shouldBe Instant.parse("2026-07-30T18:41:00.150Z")
            // Reading the response as UTC would make the server look two hours fast.
            sample.offset shouldBe java.time.Duration.ZERO
            sample.offset shouldNotBe java.time.Duration.ofHours(2)
        }

    @Test
    fun `setting a new queue sends library item ids and an index, not playlist slots`() =
        runTest {
            val request = slot<PlayRequestDto>()
            coEvery { syncPlayOperations.syncPlaySetNewQueue(capture(request)) } returns unitResponse()

            api.setNewQueue(itemIds = listOf(ITEM_A, ITEM_B), playingItemPosition = 1, startPositionTicks = 900L)

            request.captured.playingQueue shouldContainExactly listOf(ITEM_A, ITEM_B)
            request.captured.playingItemPosition shouldBe 1
            request.captured.startPositionTicks shouldBe 900L
        }

    @Test
    fun `queueing next is a mode on the queue call, not a separate endpoint`() =
        runTest {
            val request = slot<QueueRequestDto>()
            coEvery { syncPlayOperations.syncPlayQueue(capture(request)) } returns unitResponse()

            api.addToQueue(itemIds = listOf(ITEM_A), mode = SyncPlayQueueMode.QueueNext)

            request.captured.itemIds shouldContainExactly listOf(ITEM_A)
            request.captured.mode shouldBe GroupQueueMode.QUEUE_NEXT
        }

    @Test
    fun `repeat mode is translated into the SDK's spelling`() =
        runTest {
            val request = slot<SetRepeatModeRequestDto>()
            coEvery { syncPlayOperations.syncPlaySetRepeatMode(capture(request)) } returns unitResponse()

            api.setRepeatMode(SyncPlayRepeatMode.One)

            request.captured.mode shouldBe GroupRepeatMode.REPEAT_ONE
        }

    @Test
    fun `a created group comes back as a domain summary`() =
        runTest {
            coEvery { syncPlayOperations.syncPlayCreateGroup(any()) } returns
                Response(
                    content =
                        GroupInfoDto(
                            groupId = GROUP_ID,
                            groupName = "Movie night",
                            state = GroupStateType.IDLE,
                            participants = listOf("ada"),
                            lastUpdatedAt = LocalDateTime.of(2026, 7, 30, 20, 41, 3),
                        ),
                    status = 200,
                    headers = emptyMap(),
                )

            val group = api.createGroup("Movie night")

            group.id shouldBe GROUP_ID
            group.name shouldBe "Movie night"
            group.state shouldBe SyncPlayGroupState.Idle
            group.participants shouldContainExactly listOf("ada")
            group.lastUpdatedAt shouldBe Instant.parse("2026-07-30T18:41:03Z")
        }

    private fun unitResponse() = Response(content = Unit, status = 204, headers = emptyMap())

    private companion object {
        const val TEST_ZONE = "Europe/Paris"

        val GROUP_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ITEM_A: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val ITEM_B: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val SLOT_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
    }
}
