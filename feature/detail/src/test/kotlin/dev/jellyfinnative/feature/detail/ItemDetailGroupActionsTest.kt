package dev.jellyfinnative.feature.detail

import androidx.lifecycle.SavedStateHandle
import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.DownloadState
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyfinnative.core.common.syncplay.SyncPlaySession
import dev.jellyfinnative.data.ConnectivityRefresher
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.data.downloads.DownloadRepository
import dev.jellyfinnative.data.userdata.UserDataChange
import dev.jellyfinnative.data.userdata.UserDataRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What the detail page offers, and sends, while a SyncPlay group is active (M11 Phase 4).
 *
 * Its own class rather than more of [ItemDetailViewModelTest], which is at detekt's `LargeClass`
 * ceiling — the same split [ItemDetailSelectionTest] already makes for batch selection.
 *
 * The claim underneath every test here is that a group action is a **request**: the session is asked,
 * nothing on this page changes, and the snackbar says only that the ask went out. The control is the
 * whole of the rest of this package, which runs with no group and must be untouched by any of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemDetailGroupActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<JellyfinRepository>()
    private val userDataRepository = mockk<UserDataRepository>()
    private val changes =
        MutableSharedFlow<UserDataChange>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val downloads =
        mockk<DownloadRepository> {
            every { observeStates() } returns MutableStateFlow<Map<String, DownloadState>>(emptyMap())
        }
    private val connectivityRefresher =
        mockk<ConnectivityRefresher> {
            every { connectivityChanged } returns MutableSharedFlow()
        }

    /** The group this device is in; `null` until a test joins one. */
    private val activeGroup = MutableStateFlow<SyncPlayGroupHandle?>(null)
    private val syncPlaySession =
        mockk<SyncPlaySession>(relaxed = true) {
            every { activeGroup } returns this@ItemDetailGroupActionsTest.activeGroup
        }

    private val movie =
        JellyfinItem(id = ITEM_ID, name = "Arrival", type = ItemType.MOVIE, productionYear = 2016)
    private val series = JellyfinItem(id = ITEM_ID, name = "Westworld", type = ItemType.SERIES)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { userDataRepository.changes } returns changes
        coEvery { repository.getSeasons(any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getEpisodes(any(), any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getNextUpForSeries(any()) } returns AppResult.Success(null)
        coEvery { repository.getSimilarItems(any(), any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getSeriesEpisodes(any()) } returns AppResult.Success(emptyList())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `with no group there is nothing for a group to play`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            val model = viewModel()
            advanceUntilIdle()

            // The target exists — a movie is playable by a group — but the actions are drawn only
            // when `activeGroup` is non-null, and asking anyway must reach nothing.
            model.activeGroup.value.shouldBeNull()
            model.onGroupAction(GroupAction.PLAY_FOR_GROUP)
            advanceUntilIdle()

            coVerify(exactly = 0) { syncPlaySession.playForGroup(any(), any()) }
        }

    @Test
    fun `the active group is handed through from the session`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            val model = viewModel()
            advanceUntilIdle()

            activeGroup.value = SyncPlayGroupHandle(id = "group-1", name = "Film night", participantCount = 2)

            model.activeGroup.value?.name shouldBe "Film night"
        }

    @Test
    fun `playing a movie for the group replaces its queue at the resume position`() =
        runTest(dispatcher) {
            val resumable = movie.copy(userData = UserData(playbackPositionTicks = RESUME_TICKS, played = false))
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(resumable)
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.onGroupAction(GroupAction.PLAY_FOR_GROUP)
            advanceUntilIdle()

            // Exactly one id, and no series lookup: web accepts a one-item movie queue as it is, and
            // the episode expansion below must not leak into anything that is not an episode.
            coVerify(exactly = 1) { syncPlaySession.playForGroup(listOf(ITEM_ID), RESUME_TICKS) }
            coVerify(exactly = 0) { repository.getSeriesEpisodes(any()) }
            model.uiState.value.userMessage shouldBe UserMessage.GroupActionSent(GroupAction.PLAY_FOR_GROUP)
        }

    @Test
    fun `play next and add to queue reach the session as the two queue modes`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(movie)
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.onGroupAction(GroupAction.PLAY_NEXT)
            model.onGroupAction(GroupAction.ADD_TO_QUEUE)
            advanceUntilIdle()

            coVerify(exactly = 1) { syncPlaySession.addToGroupQueue(ITEM_ID, next = true) }
            coVerify(exactly = 1) { syncPlaySession.addToGroupQueue(ITEM_ID, next = false) }
        }

    @Test
    fun `a series sends the episode its Play button resolves to, not the series`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getNextUpForSeries(ITEM_ID) } returns
                AppResult.Success(JellyfinItem(id = EPISODE_2, name = "Chestnut", type = ItemType.EPISODE))
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.groupTarget
                ?.id shouldBe EPISODE_2
            model.onGroupAction(GroupAction.PLAY_FOR_GROUP)
            advanceUntilIdle()

            coVerify(exactly = 1) { syncPlaySession.playForGroup(listOf(EPISODE_2), 0L) }
        }

    @Test
    fun `an episode is sent with the rest of its series, the way jellyfin-web expands it`() =
        runTest {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(episode(EPISODE_2, RESUME_TICKS))
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns
                AppResult.Success(
                    listOf(episode(EPISODE_1), episode(EPISODE_2), episode(EPISODE_3), episode(EPISODE_4)),
                )
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.onGroupAction(GroupAction.PLAY_FOR_GROUP)
            advanceUntilIdle()

            // From the chosen episode to the end of the series, in server order, and nothing before
            // it: that is the list web rebuilds locally and then indexes the server's playlist by.
            coVerify(exactly = 1) {
                syncPlaySession.playForGroup(listOf(EPISODE_2, EPISODE_3, EPISODE_4), RESUME_TICKS)
            }
        }

    @Test
    fun `an episode the series listing cannot account for is sent on its own`() =
        runTest {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(episode(EPISODE_2))
            // The failure mode and the absent-from-the-listing mode are the same fallback: whatever
            // the lookup came back with, the target itself is still worth sending.
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns AppResult.Failure(AppError.Network())
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.onGroupAction(GroupAction.PLAY_FOR_GROUP)
            advanceUntilIdle()

            coVerify(exactly = 1) { syncPlaySession.playForGroup(listOf(EPISODE_2), 0L) }
        }

    @Test
    fun `an episode missing from its own series listing falls back to itself`() =
        runTest {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(episode(EPISODE_2))
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns
                AppResult.Success(listOf(episode(EPISODE_1), episode(EPISODE_3)))
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.onGroupAction(GroupAction.PLAY_FOR_GROUP)
            advanceUntilIdle()

            coVerify(exactly = 1) { syncPlaySession.playForGroup(listOf(EPISODE_2), 0L) }
        }

    @Test
    fun `a page with nothing a group can play offers no group target`() =
        runTest(dispatcher) {
            // A series with no next-up and no episodes resolves to nothing; only movies and
            // episodes are in this app's scope at all.
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.groupTarget
                .shouldBeNull()
            model.onGroupAction(GroupAction.ADD_TO_QUEUE)
            advanceUntilIdle()

            coVerify(exactly = 0) { syncPlaySession.addToGroupQueue(any(), any()) }
        }

    /** An episode of [SERIES_ID] — the type and the series link are what drive the expansion. */
    private fun episode(
        id: String,
        positionTicks: Long = 0L,
    ) = JellyfinItem(
        id = id,
        name = "Episode $id",
        type = ItemType.EPISODE,
        seriesId = SERIES_ID,
        userData = UserData(playbackPositionTicks = positionTicks, played = false),
    )

    private fun inAGroup() {
        activeGroup.value = SyncPlayGroupHandle(id = "group-1", name = "Film night", participantCount = 2)
    }

    private fun viewModel() =
        ItemDetailViewModel(
            repository = repository,
            userDataRepository = userDataRepository,
            downloads = downloads,
            connectivityRefresher = connectivityRefresher,
            syncPlaySession = syncPlaySession,
            savedStateHandle = SavedStateHandle(mapOf(ItemDetailViewModel.ARG_ITEM_ID to ITEM_ID)),
        )

    private companion object {
        const val ITEM_ID = "item-1"
        const val SERIES_ID = "series-1"
        const val EPISODE_1 = "episode-1"
        const val EPISODE_2 = "episode-2"
        const val EPISODE_3 = "episode-3"
        const val EPISODE_4 = "episode-4"

        /** A resume position in Jellyfin ticks — 90 seconds in. */
        const val RESUME_TICKS = 900_000_000L
    }
}
