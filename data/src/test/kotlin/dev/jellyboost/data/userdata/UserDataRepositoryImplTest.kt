package dev.jellyboost.data.userdata

import android.database.sqlite.SQLiteException
import app.cash.turbine.test
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.database.dao.UserDataDao
import dev.jellyboost.core.database.entities.UserDataEntity
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.core.network.model.SessionState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.operations.ItemsApi
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

/**
 * Unit tests for [UserDataRepositoryImpl] — the local-first write path.
 *
 * The plan makes a specific promise here: the Room row and the event bus come first, the server
 * push is best effort, and a failed push leaves `toBeSynced` set and schedules a retry
 * (docs/PLAN.md, "Data layer"). These tests pin exactly that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserDataRepositoryImplTest {
    private val userDataDao = mockk<UserDataDao>()
    private val apiClient = mockk<ApiClient>()
    private val playStateApi = mockk<PlayStateApi>()
    private val userLibraryApi = mockk<UserLibraryApi>()
    private val itemsApi = mockk<ItemsApi>()
    private val sessionRepository = mockk<SessionRepository>()
    private val syncScheduler = mockk<UserDataSyncScheduler>(relaxUnitFun = true)
    private val connectionState = mockk<ConnectionStateProvider>()
    private val state = MutableStateFlow(ConnectionState.ONLINE)
    private val eventBus = UserDataEventBus()

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val itemUuid = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val itemId = itemUuid.toString()
    private val now = Instant.parse("2026-07-28T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val repository =
        UserDataRepositoryImpl(
            userDataDao = userDataDao,
            apiClient = apiClient,
            sessionRepository = sessionRepository,
            eventBus = eventBus,
            syncScheduler = syncScheduler,
            connectionState = connectionState,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @BeforeEach
    fun setUp() {
        // The SDK serialises its date fields as `value.atZone(systemDefault())`, so a non-UTC
        // default zone is what makes a wrong conversion visible (see `SdkDateTime.kt`).
        TimeZone.setDefault(TimeZone.getTimeZone(TEST_ZONE))
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.playStateApi } returns playStateApi
        every { apiClient.userLibraryApi } returns userLibraryApi
        every { apiClient.itemsApi } returns itemsApi

        every { sessionRepository.sessionState } returns MutableStateFlow(loggedIn())
        every { connectionState.state } returns state

        coEvery { userDataDao.getUserData(any(), any()) } returns null
        coEvery { userDataDao.upsert(any()) } just Runs
        coEvery { userDataDao.clearPendingSync(any(), any(), any()) } returns 1

        coEvery { playStateApi.markPlayedItem(any(), any(), any()) } returns userDataResponse()
        coEvery { playStateApi.markUnplayedItem(any(), any()) } returns userDataResponse()
        coEvery { userLibraryApi.markFavoriteItem(any(), any()) } returns userDataResponse()
        coEvery { userLibraryApi.unmarkFavoriteItem(any(), any()) } returns userDataResponse()
        coEvery { itemsApi.updateItemUserData(any(), any(), any()) } returns userDataResponse()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        TimeZone.setDefault(originalTimeZone)
    }

    // ---- local-first ordering ---------------------------------------------------------------

    @Test
    fun `writes Room with the pending flag before it touches the server`() =
        runTest {
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            repository.setPlayed(itemId, played = true)

            coVerifyOrder {
                userDataDao.upsert(any())
                playStateApi.markPlayedItem(any(), any(), any())
            }
            stored.captured.toBeSynced shouldBe true
            stored.captured.updatedAt shouldBe now
            stored.captured.itemId shouldBe itemUuid
            stored.captured.userId shouldBe userId
        }

    @Test
    fun `publishes the change on the event bus even when the server push fails`() =
        runTest {
            coEvery { playStateApi.markPlayedItem(any(), any(), any()) } throws IOException("offline")

            eventBus.changes.test {
                val result = repository.setPlayed(itemId, played = true)

                val change = awaitItem()
                change.itemId shouldBe itemId
                change.userData.played shouldBe true
                // Local-first: the operation succeeded because the change is durable and on screen.
                result.shouldBeInstanceOf<AppResult.Success<*>>()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clears the pending flag once the server accepts the write`() =
        runTest {
            repository.setPlayed(itemId, played = true)

            // Guarded on the row's own timestamp so a newer local toggle is not declared synced.
            coVerify(exactly = 1) { userDataDao.clearPendingSync(itemUuid, userId, now) }
            verify(exactly = 0) { syncScheduler.enqueue() }
        }

    @Test
    fun `keeps the pending flag and enqueues the sync worker when the push fails`() =
        runTest {
            coEvery { playStateApi.markPlayedItem(any(), any(), any()) } throws IOException("offline")

            repository.setPlayed(itemId, played = true)

            coVerify(exactly = 0) { userDataDao.clearPendingSync(any(), any(), any()) }
            verify(exactly = 1) { syncScheduler.enqueue() }
        }

    @Test
    fun `a server rejection is still only a scheduled retry, never a failed operation`() =
        runTest {
            coEvery { playStateApi.markPlayedItem(any(), any(), any()) } throws IOException("offline")

            val result = repository.setPlayed(itemId, played = true)

            (result as AppResult.Success).value.played shouldBe true
        }

    // ---- setPlayed --------------------------------------------------------------------------

    @Test
    fun `marking watched clears the resume position and stamps the played date`() =
        runTest {
            coEvery { userDataDao.getUserData(any(), any()) } returns
                storedRow(playbackPositionTicks = 12_000_000_000L)
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            val result = repository.setPlayed(itemId, played = true)

            stored.captured.played shouldBe true
            stored.captured.playbackPositionTicks shouldBe 0L
            stored.captured.lastPlayedDate shouldBe now
            (result as AppResult.Success).value.playbackPositionTicks shouldBe 0L
        }

    @Test
    fun `marking unwatched keeps the resume position and calls the unplayed endpoint`() =
        runTest {
            coEvery { userDataDao.getUserData(any(), any()) } returns
                storedRow(played = true, playbackPositionTicks = 12_000_000_000L)
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            repository.setPlayed(itemId, played = false)

            stored.captured.played shouldBe false
            stored.captured.playbackPositionTicks shouldBe 12_000_000_000L
            coVerify(exactly = 1) { playStateApi.markUnplayedItem(itemUuid, userId) }
            coVerify(exactly = 0) { playStateApi.markPlayedItem(any(), any(), any()) }
        }

    /**
     * Regression test for STATUS.md's "Known issues": `datePlayed` used to be built as UTC
     * wall-clock time, which the SDK then stamped the *device's* offset onto — a 10:00Z event went
     * out as `10:00+02:00` and the server stored it two hours early. The value on the wire must be
     * local wall-clock time so that the instant survives the round trip.
     */
    @Test
    fun `sends the played date so the server receives the correct instant`() =
        runTest {
            val sent = slot<LocalDateTime>()
            coEvery { playStateApi.markPlayedItem(any(), any(), capture(sent)) } returns userDataResponse()

            repository.setPlayed(itemId, played = true)

            sent.captured shouldBe LocalDateTime.ofInstant(now, ZoneId.of(TEST_ZONE))
            // What the SDK will actually put on the wire resolves back to the instant we stored.
            sent.captured.atZone(ZoneId.of(TEST_ZONE)).toInstant() shouldBe now
        }

    @Test
    fun `sends the position write's last-played date as the same instant`() =
        runTest {
            val pushed = slot<UpdateUserItemDataDto>()
            coEvery { itemsApi.updateItemUserData(any(), any(), capture(pushed)) } returns userDataResponse()

            repository.setPosition(itemId, positionTicks = 5L)

            pushed.captured.lastPlayedDate
                ?.atZone(ZoneId.of(TEST_ZONE))
                ?.toInstant() shouldBe now
        }

    // ---- setFavorite ------------------------------------------------------------------------

    @Test
    fun `favouriting an item writes locally and marks it on the server`() =
        runTest {
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            val result = repository.setFavorite(itemId, favorite = true)

            stored.captured.isFavorite shouldBe true
            (result as AppResult.Success).value.isFavorite shouldBe true
            coVerify(exactly = 1) { userLibraryApi.markFavoriteItem(itemUuid, userId) }
        }

    @Test
    fun `unfavouriting an item calls the unmark endpoint`() =
        runTest {
            coEvery { userDataDao.getUserData(any(), any()) } returns storedRow(isFavorite = true)

            repository.setFavorite(itemId, favorite = false)

            coVerify(exactly = 1) { userLibraryApi.unmarkFavoriteItem(itemUuid, userId) }
        }

    @Test
    fun `a favourite toggle leaves the watched state alone`() =
        runTest {
            coEvery { userDataDao.getUserData(any(), any()) } returns
                storedRow(played = true, playbackPositionTicks = 42L)
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            repository.setFavorite(itemId, favorite = true)

            stored.captured.played shouldBe true
            stored.captured.playbackPositionTicks shouldBe 42L
        }

    // ---- setPosition ------------------------------------------------------------------------

    @Test
    fun `recording a position pushes the full desired state, not just the position`() =
        runTest {
            coEvery { userDataDao.getUserData(any(), any()) } returns storedRow(isFavorite = true)
            val pushed = slot<UpdateUserItemDataDto>()
            coEvery { itemsApi.updateItemUserData(any(), any(), capture(pushed)) } returns userDataResponse()

            repository.setPosition(itemId, positionTicks = 9_000_000_000L)

            pushed.captured.playbackPositionTicks shouldBe 9_000_000_000L
            pushed.captured.isFavorite shouldBe true
            pushed.captured.played shouldBe false
            pushed.captured.lastPlayedDate.shouldNotBeNull()
        }

    @Test
    fun `a negative position is clamped to zero`() =
        runTest {
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            repository.setPosition(itemId, positionTicks = -1L)

            stored.captured.playbackPositionTicks shouldBe 0L
        }

    // ---- failure modes ----------------------------------------------------------------------

    @Test
    fun `refuses to write anything when no one is signed in`() =
        runTest {
            every { sessionRepository.sessionState } returns MutableStateFlow(SessionState.LoggedOut)

            val result = repository.setPlayed(itemId, played = true)

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Unauthorized>()
            coVerify(exactly = 0) { userDataDao.upsert(any()) }
        }

    @Test
    fun `reports a malformed item id as NotFound`() =
        runTest {
            val result = repository.setFavorite("not-a-uuid", favorite = true)

            (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.NotFound>()
            coVerify(exactly = 0) { userDataDao.upsert(any()) }
        }

    @Test
    fun `a failed local write is a Storage failure and publishes nothing`() =
        runTest {
            coEvery { userDataDao.upsert(any()) } throws SQLiteException("disk full")

            eventBus.changes.test {
                val result = repository.setPlayed(itemId, played = true)

                (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Storage>()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 0) { playStateApi.markPlayedItem(any(), any(), any()) }
        }

    /**
     * A cancelled write has not written anything, so reporting `AppError.Storage` would tell the
     * caller the disk failed — and would swallow the cancellation the parent job is owed. Both
     * Room catches on this path rethrow it (audit ARCH-08).
     */
    @Test
    fun `a cancelled local write propagates instead of being reported as a storage failure`() =
        runTest {
            coEvery { userDataDao.upsert(any()) } throws CancellationException("scope cancelled")

            shouldThrow<CancellationException> { repository.setPlayed(itemId, played = true) }
        }

    @Test
    fun `a cancelled pending-flag clear propagates instead of being swallowed as best effort`() =
        runTest {
            // This one really is best effort — the row simply stays pending and the sync trigger
            // drains it later — but "best effort" must not extend to eating a cancellation.
            coEvery { userDataDao.clearPendingSync(any(), any(), any()) } throws
                CancellationException("scope cancelled")

            shouldThrow<CancellationException> { repository.setPlayed(itemId, played = true) }
        }

    @Test
    fun `edits the stored row rather than replacing it`() =
        runTest {
            coEvery { userDataDao.getUserData(any(), any()) } returns
                storedRow(played = true, isFavorite = true, playbackPositionTicks = 7L)
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            repository.setPosition(itemId, positionTicks = 8L)

            stored.captured.played shouldBe true
            stored.captured.isFavorite shouldBe true
            stored.captured.playbackPositionTicks shouldBe 8L
        }

    // ---- offline: the push is not even attempted ----------------------------------------------

    /**
     * `PlaybackReporter` calls `setPosition` every five seconds, so before M9 an offline session
     * fired one doomed request — and logged one warning stack — per tick (STATUS.md, "Known
     * issues"). The row is pending either way and `UserDataSyncTrigger` drains it on the next
     * return to `ONLINE`, so the request buys nothing.
     *
     * Every *other* test in this class leaves the fixture at [ConnectionState.ONLINE], which is what
     * pins the online branch as unchanged: it still pushes, still clears the flag on success, and
     * still warns plus enqueues on failure.
     */
    @Test
    fun `an offline write never touches the network`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            repository.setPosition(itemId, positionTicks = 5L)

            coVerify(exactly = 0) { itemsApi.updateItemUserData(any(), any(), any()) }
        }

    @Test
    fun `an offline write does not schedule the sync worker per tick`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            repository.setPosition(itemId, positionTicks = 5L)

            // Redundant work: UserDataSyncTrigger drains every pending row on the OFFLINE -> ONLINE
            // edge and at app start.
            verify(exactly = 0) { syncScheduler.enqueue() }
            coVerify(exactly = 0) { userDataDao.clearPendingSync(any(), any(), any()) }
        }

    @Test
    fun `an offline write still stores the pending row and publishes it`() =
        runTest {
            state.value = ConnectionState.OFFLINE_FORCED
            val stored = slot<UserDataEntity>()
            coEvery { userDataDao.upsert(capture(stored)) } just Runs

            eventBus.changes.test {
                repository.setPosition(itemId, positionTicks = 5L)

                val change = awaitItem()
                change.itemId shouldBe itemId
                change.userData.playbackPositionTicks shouldBe 5L
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { userDataDao.upsert(any()) }
            stored.captured.toBeSynced shouldBe true
            stored.captured.playbackPositionTicks shouldBe 5L
        }

    @Test
    fun `a skipped offline push is still a successful operation`() =
        runTest {
            state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE

            val result = repository.setPosition(itemId, positionTicks = 5L)

            // Skipping the push must not invent a failure mode the failing push never had.
            (result as AppResult.Success).value.playbackPositionTicks shouldBe 5L
        }

    @Test
    fun `a write while online still pushes`() =
        runTest {
            state.value = ConnectionState.ONLINE

            repository.setPosition(itemId, positionTicks = 5L)

            coVerify(exactly = 1) { itemsApi.updateItemUserData(itemUuid, userId, any()) }
            coVerify(exactly = 1) { userDataDao.clearPendingSync(itemUuid, userId, now) }
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun loggedIn() =
        SessionState.LoggedIn(
            serverId = UUID.randomUUID(),
            userId = userId,
            userName = "casey",
            serverName = "home",
            serverVersion = "10.11.0",
        )

    private fun storedRow(
        played: Boolean = false,
        isFavorite: Boolean = false,
        playbackPositionTicks: Long = 0L,
    ) = UserDataEntity(
        itemId = itemUuid,
        userId = userId,
        played = played,
        isFavorite = isFavorite,
        playbackPositionTicks = playbackPositionTicks,
        updatedAt = now.minusSeconds(60),
    )

    private companion object {
        /** A fixed non-UTC zone with a non-zero offset all year round. */
        const val TEST_ZONE = "Europe/Paris"
    }

    private fun userDataResponse() =
        Response(
            content =
                UserItemDataDto(
                    playbackPositionTicks = 0L,
                    playCount = 0,
                    isFavorite = false,
                    played = false,
                    key = "key",
                    itemId = itemUuid,
                ),
            status = 200,
            headers = emptyMap(),
        )
}
