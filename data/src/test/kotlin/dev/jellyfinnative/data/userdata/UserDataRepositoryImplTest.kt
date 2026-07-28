package dev.jellyfinnative.data.userdata

import app.cash.turbine.test
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.database.dao.UserDataDao
import dev.jellyfinnative.core.database.entities.UserDataEntity
import dev.jellyfinnative.core.network.SessionRepository
import dev.jellyfinnative.core.network.model.SessionState
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
import java.time.ZoneOffset
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
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    @BeforeEach
    fun setUp() {
        mockkStatic("org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt")
        every { apiClient.playStateApi } returns playStateApi
        every { apiClient.userLibraryApi } returns userLibraryApi
        every { apiClient.itemsApi } returns itemsApi

        every { sessionRepository.sessionState } returns MutableStateFlow(loggedIn())

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

    @Test
    fun `sends the played date to the server as wall-clock UTC`() =
        runTest {
            repository.setPlayed(itemId, played = true)

            coVerify {
                playStateApi.markPlayedItem(
                    itemUuid,
                    userId,
                    java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                )
            }
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
            coEvery { userDataDao.upsert(any()) } throws IllegalStateException("disk full")

            eventBus.changes.test {
                val result = repository.setPlayed(itemId, played = true)

                (result as AppResult.Failure).error.shouldBeInstanceOf<AppError.Storage>()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            coVerify(exactly = 0) { playStateApi.markPlayedItem(any(), any(), any()) }
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
