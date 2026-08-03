package dev.jellyboost.player.ui

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What happens to a session when a *re*-negotiation goes wrong (audit PC-01/PC-05).
 *
 * A failed initial open is honestly the end — there was never anything playing. A failed reopen is
 * not: the resolve fails before `prepare`, so the player is still sitting on the source that was
 * playing a second earlier, and these tests pin the two rules that follow. Its terms are retried
 * once instead of tearing the screen down to an error whose only action is leaving; and two
 * re-negotiations never run concurrently, because interleaved resolve → prepare → publish
 * sequences can leave `source` describing a stream the player is not decoding.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerReopenRecoveryTest : PlayerViewModelFixture() {
    @Test
    fun `a failed re-negotiation retries the terms that were playing instead of erroring out`() =
        runTest(dispatcher) {
            playerHandle.snapshot = PlaybackSnapshot(positionMs = 60_000L, isPlaying = true)
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } coAnswers {
                if (requests.last().maxStreamingBitrate == PlaybackQuality.LOW.maxStreamingBitrate) {
                    AppResult.Failure(AppError.Network())
                } else {
                    AppResult.Success(source)
                }
            }

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // A session that was playing a second earlier is not torn down over a resolve the
            // server fumbled: its own terms are asked once more, from the live position, and the
            // user learns the change was lost rather than applied.
            model.uiState.value.errorMessage
                .shouldBeNull()
            model.uiState.value.userMessage shouldBe PlayerMessage.ChangeReverted
            requests.last().maxStreamingBitrate shouldBe source.maxStreamingBitrate
            requests.last().startPositionTicks shouldBe 600_000_000L
        }

    @Test
    fun `a re-negotiation whose recovery also fails ends in the error state`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            coEvery { resolver.resolve(any()) } returns AppResult.Failure(AppError.Network())

            model.selectQuality(PlaybackQuality.LOW)
            advanceUntilIdle()

            // One retry, not a loop: the initial open, the failed change, one spent recovery.
            coVerify(exactly = 3) { resolver.resolve(any()) }
            model.uiState.value.errorMessage
                .shouldNotBeNull()
        }

    @Test
    fun `a second re-negotiation cancels the one still resolving`() =
        runTest(dispatcher) {
            val model = viewModel()
            advanceUntilIdle()

            var firstCancelled = false
            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } coAnswers {
                if (requests.last().maxStreamingBitrate == PlaybackQuality.LOW.maxStreamingBitrate) {
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        firstCancelled = true
                        throw cancelled
                    }
                } else {
                    AppResult.Success(
                        source.copy(maxStreamingBitrate = PlaybackQuality.MEDIUM.maxStreamingBitrate),
                    )
                }
            }

            model.selectQuality(PlaybackQuality.LOW)
            runCurrent()
            model.selectQuality(PlaybackQuality.MEDIUM)
            advanceUntilIdle()

            // Two in-flight resolve → prepare → publish sequences would race `source` and prepare
            // in either order; the superseded one is cancelled where it suspends instead.
            firstCancelled shouldBe true
            model.uiState.value.quality shouldBe PlaybackQuality.MEDIUM
        }
}
