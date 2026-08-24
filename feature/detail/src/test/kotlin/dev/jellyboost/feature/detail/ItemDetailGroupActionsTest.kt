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
 * What the detail page offers, and sends, while a SyncPlay group is active.
 *
 * Its own class rather than more of [ItemDetailViewModelTest], which is at detekt's `LargeClass`
 * ceiling — the same split [ItemDetailSelectionTest] already makes for batch selection.
 *
 * The claim underneath every test here is that in a group **everything this page starts is started
 * for the group**: Play is a `SetNewQueue` and not a navigation, the two queue actions are
 * requests, nothing on this page changes locally, and the snackbar says only that the ask went out.
 * The control is the whole of the rest of this package, which runs with no group and must be
 * untouched by any of it — plus the solo tests below, which pin that a play with no group is still
 * a navigation.
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

                // The resume position travels with it, exactly as the header button sends it.
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

                // Exactly one id, and no series lookup: web accepts a one-item movie queue as it is,
                // and the episode expansion below must not leak into anything that is not an episode.
                coVerify(exactly = 1) { syncPlaySession.playForGroup(listOf(ITEM_ID), RESUME_TICKS) }
                coVerify(exactly = 0) { repository.getSeriesEpisodes(any()) }
                // The bug this fixes, in one assertion: navigating here opens a local player the
                // group knows nothing about, which is what sat under "Waiting for group" for ever.
                // The player is opened by the server's own `PlayQueueUpdate` instead.
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

            // From the chosen episode to the end of the series, in server order, and nothing before
            // it: that is the list web rebuilds locally and then indexes the server's playlist by.
            coVerify(exactly = 1) {
                syncPlaySession.playForGroup(listOf(EPISODE_2, EPISODE_3, EPISODE_4), RESUME_TICKS)
            }
        }

    @Test
    fun `an episode row's own play button goes to the group, expanded and from its own position`() =
        runTest {
            // A season page: the row a user taps is not what the header would have resolved to, and
            // it is the second entry point, and it must not navigate straight past the group.
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
            // The failure mode and the absent-from-the-listing mode are the same fallback: whatever
            // the lookup came back with, the target itself is still worth sending.
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

    @Test
    fun `in a group, something the group cannot play still opens here rather than nowhere`() =
        runTest(dispatcher) {
            // A folder page resolves its Play button to the folder itself, which no group queue can
            // hold. Sending it would be refused server-side and swallow the tap; playing it locally
            // is what the user asked for and costs the group nothing.
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

    /**
     * Runs [block] with every solo play this page resolves, oldest first — the list that must stay
     * **empty** in a group.
     *
     * A plain list rather than Turbine because most of these tests are about the *absence* of an
     * event, and "nothing was emitted by the time everything else had run" is exactly what an empty
     * list after `advanceUntilIdle()` states. The collector is a foreground coroutine, cancelled
     * when the block ends: `advanceUntilIdle()` runs the test's own work and not `backgroundScope`'s,
     * so a collector launched there would never be resumed and every assertion here would read an
     * empty list whatever the ViewModel did.
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

        /** A resume position in Jellyfin ticks — 90 seconds in. */
        const val RESUME_TICKS = 900_000_000L
    }
}
