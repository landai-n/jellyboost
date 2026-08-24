package dev.jellyboost.data.userdata

import app.cash.turbine.test
import dev.jellyboost.core.common.model.UserData
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class UserDataEventBusTest {
    private val bus = UserDataEventBus()

    @Test
    fun `delivers a change to every collector`() =
        runTest {
            bus.changes.test {
                bus.emit(UserDataChange("item-1", UserData(played = true)))

                val change = awaitItem()
                change.itemId shouldBe "item-1"
                change.userData.played shouldBe true
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `preserves the order changes were written in`() =
        runTest {
            bus.changes.test {
                bus.emit(UserDataChange("a", UserData(played = true)))
                bus.emit(UserDataChange("b", UserData(isFavorite = true)))

                awaitItem().itemId shouldBe "a"
                awaitItem().itemId shouldBe "b"
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `does not replay changes to a collector that arrives later`() =
        runTest {
            // A screen loading after a toggle reads the value from its own request; a replayed
            // stale change would fight it.
            bus.emit(UserDataChange("a", UserData(played = true)))

            bus.changes.test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `emitting with no collectors never suspends or throws`() =
        runTest {
            bus.emit(UserDataChange("a", UserData(played = true)))
            bus.emit(UserDataChange("b", UserData(played = true)))
        }
}
