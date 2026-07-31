package dev.jellyboost.player.syncplay

import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayQueueMode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Unit tests for [ControllerSyncPlaySession] — the seam `:feature:*` modules reach SyncPlay through.
 *
 * Two things are worth pinning and neither is about behaviour inside the controller: that the group
 * a feature sees is *the* group and not a copy that can drift from it, and that a browse-surface
 * verb maps onto the queue intent it claims to. The controller is mocked precisely so a failure here
 * can only mean the translation is wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSyncPlaySessionTest {
    private val itemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val nextItemId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

    @Test
    fun `there is no group until the controller is in one`() =
        runTest {
            val fixture = fixture()

            fixture.session.activeGroup.value
                .shouldBeNull()
        }

    @Test
    fun `the active group mirrors the controller, name and heads counted`() =
        runTest {
            val fixture = fixture()

            fixture.state.value =
                SyncPlayState.InGroup(
                    group = group(participants = listOf("casey", "alex")),
                    queue = null,
                    groupState = SyncPlayGroupState.Idle,
                    phase = SyncPlayPhase.Waiting,
                )
            runCurrent()

            fixture.session.activeGroup.value shouldBe
                SyncPlayGroupHandle(
                    id = group().id.toString(),
                    name = "Film night",
                    participantCount = 2,
                )
        }

    @Test
    fun `leaving the group takes the handle with it`() =
        runTest {
            val fixture = fixture()
            fixture.state.value = SyncPlayState.InGroup(group(), null, SyncPlayGroupState.Paused, SyncPlayPhase.Paused)
            runCurrent()

            fixture.state.value = SyncPlayState.Idle
            runCurrent()

            fixture.session.activeGroup.value
                .shouldBeNull()
        }

    @Test
    fun `playing for the group replaces its queue, starting where the caller said`() =
        runTest {
            val fixture = fixture()

            fixture.session.playForGroup(listOf(itemId.toString()), startPositionTicks = 900_000_000L)

            verify {
                fixture.controller.setNewQueue(
                    itemIds = listOf(itemId),
                    playingItemPosition = 0,
                    startPositionTicks = 900_000_000L,
                )
            }
        }

    @Test
    fun `a queue of several items keeps its order and starts at the first`() =
        runTest {
            val fixture = fixture()

            fixture.session.playForGroup(listOf(itemId.toString(), nextItemId.toString()))

            verify {
                fixture.controller.setNewQueue(
                    itemIds = listOf(itemId, nextItemId),
                    playingItemPosition = 0,
                    startPositionTicks = 0L,
                )
            }
        }

    @Test
    fun `play next inserts after the playing item, and plain queueing appends`() =
        runTest {
            val fixture = fixture()

            fixture.session.addToGroupQueue(itemId.toString(), next = true)
            fixture.session.addToGroupQueue(itemId.toString(), next = false)

            verify { fixture.controller.addToQueue(listOf(itemId), SyncPlayQueueMode.QueueNext) }
            verify { fixture.controller.addToQueue(listOf(itemId), SyncPlayQueueMode.Queue) }
        }

    @Test
    fun `an id that is not an item id reaches the protocol as nothing at all`() =
        runTest {
            val fixture = fixture()

            fixture.session.playForGroup(listOf("item-1"))
            fixture.session.addToGroupQueue("item-1", next = true)

            verify(exactly = 0) { fixture.controller.setNewQueue(any(), any(), any()) }
            verify(exactly = 0) { fixture.controller.addToQueue(any(), any()) }
        }

    @Test
    fun `one malformed id in a queue sends none of it, rather than a queue off by one`() =
        runTest {
            val fixture = fixture()

            fixture.session.playForGroup(listOf(itemId.toString(), "item-2", nextItemId.toString()))

            // Dropping just the bad entry would leave a shorter playlist than the caller counted on,
            // which is exactly the index mismatch this queue shape exists to avoid.
            verify(exactly = 0) { fixture.controller.setNewQueue(any(), any(), any()) }
        }

    @Test
    fun `an empty queue is nothing to play`() =
        runTest {
            val fixture = fixture()

            fixture.session.playForGroup(emptyList())

            verify(exactly = 0) { fixture.controller.setNewQueue(any(), any(), any()) }
        }

    private class Fixture(
        val session: ControllerSyncPlaySession,
        val controller: SyncPlayController,
        val state: MutableStateFlow<SyncPlayState>,
    )

    private fun TestScope.fixture(): Fixture {
        val state = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)
        val controller =
            mockk<SyncPlayController>(relaxed = true) {
                every { this@mockk.state } returns state
            }
        val session = ControllerSyncPlaySession(controller, backgroundScope)
        runCurrent()
        return Fixture(session, controller, state)
    }
}
