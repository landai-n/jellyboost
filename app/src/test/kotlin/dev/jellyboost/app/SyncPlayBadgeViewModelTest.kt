package dev.jellyboost.app

import app.cash.turbine.test
import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.core.common.syncplay.SyncPlaySession
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class SyncPlayBadgeViewModelTest {
    private val groupFlow = MutableStateFlow<SyncPlayGroupHandle?>(null)
    private val session = mockk<SyncPlaySession> { every { activeGroup } returns groupFlow }

    @Test
    @DisplayName("the session's active group is passed straight through — joined, then lost")
    fun passesActiveGroupThrough() =
        runTest {
            SyncPlayBadgeViewModel(session).activeGroup.test {
                awaitItem() shouldBe null

                groupFlow.value = GROUP
                awaitItem() shouldBe GROUP

                groupFlow.value = null
                awaitItem() shouldBe null
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        val GROUP =
            SyncPlayGroupHandle(
                id = "00000000-0000-0000-0000-0000000000a1",
                name = "Film night",
                participantCount = 2,
            )
    }
}
