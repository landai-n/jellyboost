package dev.jellyboost.data.downloads

import app.cash.turbine.test
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the shared download-badge helpers every list screen now sources (audit DUP-2).
 *
 * The two properties worth pinning are the two the four hand-written copies existed to provide:
 * a collapsing flow degrades to "nothing is downloaded" instead of freezing the badges
 * (audit STAB-10), and a patch that changes nothing returns the *same* list instance so an
 * unaffected row can skip recomposition entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadBadgesTest {
    @Test
    @DisplayName("the badge flow passes states through unchanged while the source is healthy")
    fun passesStatesThrough() =
        runTest {
            val states = mapOf("a" to DownloadState.Downloaded, "b" to DownloadState.Queued)
            val repository =
                mockk<DownloadRepository> {
                    every { observeStates() } returns flowOf(states)
                }

            repository.observeBadgeStates(screen = "test").test {
                awaitItem() shouldBe states
                awaitComplete()
            }
        }

    @Test
    @DisplayName("a collapsing source degrades to an empty map rather than freezing the badges")
    fun degradesToEmptyOnError() =
        runTest {
            val repository =
                mockk<DownloadRepository> {
                    every { observeStates() } returns
                        flow {
                            emit(mapOf("a" to DownloadState.Downloaded))
                            error("Room went away")
                        }
                }

            repository.observeBadgeStates(screen = "test").test {
                awaitItem() shouldBe mapOf("a" to DownloadState.Downloaded)
                // Not the last good value again, and not an exception reaching the collector: the
                // marks the user can no longer trust are cleared, and the screen keeps working.
                awaitItem() shouldBe emptyMap()
                awaitComplete()
            }
        }

    @Test
    @DisplayName("an item the map does not mention is not downloaded")
    fun absentIdMeansNotDownloaded() {
        val item = episode(id = "a", state = DownloadState.Downloaded)

        item.withDownloadState(emptyMap()).downloadState shouldBe DownloadState.NotDownloaded
    }

    @Test
    @DisplayName("an item whose state is unchanged is returned as the same instance")
    fun unchangedItemKeepsItsIdentity() {
        val item = episode(id = "a", state = DownloadState.Downloaded)

        val patched = item.withDownloadState(mapOf("a" to DownloadState.Downloaded))

        (patched === item) shouldBe true
    }

    @Test
    @DisplayName("a list nothing changed in is returned as the same list instance")
    fun unchangedListKeepsItsIdentity() {
        val items =
            listOf(
                episode(id = "a", state = DownloadState.Downloaded),
                episode(id = "b", state = DownloadState.NotDownloaded),
            )

        val patched = items.withDownloadStates(mapOf("a" to DownloadState.Downloaded))

        (patched === items) shouldBe true
    }

    @Test
    @DisplayName("one changed item makes a new list, and the untouched items keep their identity")
    fun changedListKeepsUntouchedItemIdentity() {
        val untouched = episode(id = "a", state = DownloadState.Downloaded)
        val items = listOf(untouched, episode(id = "b", state = DownloadState.NotDownloaded))

        val patched =
            items.withDownloadStates(
                mapOf("a" to DownloadState.Downloaded, "b" to DownloadState.Queued),
            )

        (patched === items) shouldBe false
        (patched[0] === untouched) shouldBe true
        patched[1].downloadState shouldBe DownloadState.Queued
    }

    @Test
    @DisplayName("an empty list is returned as itself")
    fun emptyListKeepsItsIdentity() {
        val items = emptyList<JellyfinItem>()

        (items.withDownloadStates(mapOf("a" to DownloadState.Queued)) === items) shouldBe true
    }

    private fun episode(
        id: String,
        state: DownloadState,
    ) = JellyfinItem(id = id, name = "Episode $id", type = ItemType.EPISODE, downloadState = state)
}
