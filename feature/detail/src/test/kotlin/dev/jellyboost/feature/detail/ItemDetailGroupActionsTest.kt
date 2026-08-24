package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The claim under every test: in a group **everything this page starts is started for the group** —
 * Play is a `SetNewQueue` and not a navigation, nothing changes locally, and the snackbar says only
 * that the ask went out. The rest of the package is the control, running with no group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ItemDetailGroupActionsTest : ItemDetailViewModelFixture() {
    @BeforeEach
    fun setUpEpisodes() {
        coEvery { repository.getEpisodes(any(), any()) } returns AppResult.Success(emptyList())
        coEvery { repository.getSeriesEpisodes(any()) } returns AppResult.Success(emptyList())
    }

    @Test
    fun `with no group a play is a navigation and the session hears nothing`() =
        runTest(dispatcher) {
            val resumable = movie.copy(userData = UserData(playbackPositionTicks = RESUME_TICKS, played = false))
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(resumable)
            val model = viewModel()
            advanceUntilIdle()

            withNavigations(model) { navigations ->
                model.activeGroup.value.shouldBeNull()
                model.onPlay(model.uiState.value.playTarget!!)
                advanceUntilIdle()

                navigations shouldBe listOf(PlayRequest(ITEM_ID, RESUME_TICKS))
                coVerify(exactly = 0) { syncPlaySession.playForGroup(any(), any()) }
            }
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
    fun `playing a movie in a group replaces the group's queue at the resume position`() =
        runTest(dispatcher) {
            val resumable = movie.copy(userData = UserData(playbackPositionTicks = RESUME_TICKS, played = false))
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(resumable)
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            withNavigations(model) { navigations ->
                model.onPlay(model.uiState.value.playTarget!!)
                advanceUntilIdle()

                // No series lookup: web accepts a one-item movie queue as it is, and the episode
                // expansion must not leak into anything that is not an episode.
                coVerify(exactly = 1) { syncPlaySession.playForGroup(listOf(ITEM_ID), RESUME_TICKS) }
                coVerify(exactly = 0) { repository.getSeriesEpisodes(any()) }
                // Navigating here would open a local player the group knows nothing about, which
                // sits under "Waiting for group" for ever.
                navigations.shouldBeEmpty()
                model.uiState.value.userMessage shouldBe UserMessage.GroupPlayRequested
            }
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
            model.uiState.value.userMessage shouldBe UserMessage.GroupActionSent(GroupAction.ADD_TO_QUEUE)
        }

    @Test
    fun `a series page plays the episode its Play button resolves to, not the series`() =
        runTest(dispatcher) {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(series)
            coEvery { repository.getNextUpForSeries(ITEM_ID) } returns
                AppResult.Success(JellyfinItem(id = EPISODE_2, name = "Chestnut", type = ItemType.EPISODE))
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.uiState.value.groupTarget
                ?.id shouldBe EPISODE_2
            model.onPlay(model.uiState.value.playTarget!!)
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

            model.onPlay(model.uiState.value.playTarget!!)
            advanceUntilIdle()

            // The exact list web rebuilds locally and then indexes the server's playlist by.
            coVerify(exactly = 1) {
                syncPlaySession.playForGroup(listOf(EPISODE_2, EPISODE_3, EPISODE_4), RESUME_TICKS)
            }
        }

    @Test
    fun `an episode row's own play button goes to the group, expanded and from its own position`() =
        runTest {
            // The second entry point: an episode row is not what the header would have resolved to.
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(season)
            coEvery { repository.getEpisodes(SERIES_ID, ITEM_ID) } returns
                AppResult.Success(listOf(episode(EPISODE_1), episode(EPISODE_2)))
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns
                AppResult.Success(listOf(episode(EPISODE_1), episode(EPISODE_2), episode(EPISODE_3)))
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            withNavigations(model) { navigations ->
                model.onPlay(episode(EPISODE_2, RESUME_TICKS))
                advanceUntilIdle()

                coVerify(exactly = 1) {
                    syncPlaySession.playForGroup(listOf(EPISODE_2, EPISODE_3), RESUME_TICKS)
                }
                navigations.shouldBeEmpty()
            }
        }

    @Test
    fun `an episode the series listing cannot account for is sent on its own`() =
        runTest {
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(episode(EPISODE_2))
            // A failed lookup and an absent-from-the-listing one share the same fallback.
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns AppResult.Failure(AppError.Network())
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            model.onPlay(model.uiState.value.playTarget!!)
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

            model.onPlay(model.uiState.value.playTarget!!)
            advanceUntilIdle()

            coVerify(exactly = 1) { syncPlaySession.playForGroup(listOf(EPISODE_2), 0L) }
        }

    @Test
    fun `a page with nothing a group can play offers no group target`() =
        runTest(dispatcher) {
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

    @Test
    fun `in a group, something the group cannot play still opens here rather than nowhere`() =
        runTest(dispatcher) {
            // A folder resolves Play to itself, which no group queue can hold — sending it would be
            // refused server-side and swallow the tap.
            val folder = JellyfinItem(id = ITEM_ID, name = "Extras", type = ItemType.FOLDER)
            coEvery { repository.getItem(ITEM_ID) } returns AppResult.Success(folder)
            inAGroup()
            val model = viewModel()
            advanceUntilIdle()

            withNavigations(model) { navigations ->
                model.onPlay(model.uiState.value.playTarget!!)
                advanceUntilIdle()

                navigations shouldBe listOf(PlayRequest(ITEM_ID, 0L))
                coVerify(exactly = 0) { syncPlaySession.playForGroup(any(), any()) }
            }
        }

    /** The type and the series link are what drive the expansion. */
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

    /**
     * A plain list, not Turbine: most tests here assert the *absence* of an event, which an empty
     * list after `advanceUntilIdle()` states exactly. The collector must stay a **foreground**
     * coroutine — `advanceUntilIdle()` never resumes `backgroundScope`, so a collector launched
     * there would read empty whatever the ViewModel did.
     */
    private suspend fun TestScope.withNavigations(
        model: ItemDetailViewModel,
        block: suspend (List<PlayRequest>) -> Unit,
    ) {
        val seen = mutableListOf<PlayRequest>()
        val collector = launch { model.playRequests.collect(seen::add) }
        block(seen)
        collector.cancel()
    }

    private companion object {
        const val EPISODE_3 = "episode-3"
        const val EPISODE_4 = "episode-4"

        /** Jellyfin ticks — 90 seconds in. */
        const val RESUME_TICKS = 900_000_000L
    }
}
