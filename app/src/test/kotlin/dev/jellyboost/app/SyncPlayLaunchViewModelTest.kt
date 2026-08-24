package dev.jellyboost.app

import app.cash.turbine.test
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayLaunchRequest
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncPlayLaunchViewModelTest {
    private val requests = MutableSharedFlow<SyncPlayLaunchRequest>()
    private val controller = mockk<SyncPlayController> { every { launchRequests } returns requests }

    @Test
    @DisplayName("the controller's launch requests are passed straight through")
    fun passesLaunchRequestsThrough() =
        runTest {
            SyncPlayLaunchViewModel(controller).launchRequests.test {
                requests.emit(REQUEST)
                awaitItem() shouldBe REQUEST
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        val REQUEST =
            SyncPlayLaunchRequest(
                itemId = UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
                startPositionTicks = 1_234_567L,
            )
    }
}
