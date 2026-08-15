package dev.jellyboost.player.ui

import androidx.media3.common.PlaybackException
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.model.PlaybackQuality
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the quality picker does now that Auto resolves to a *measured* cap (DECISIONS.md, 2026-08-15).
 *
 * Its own class rather than more of [PlayerViewModelTest] — which the addition tipped over detekt's
 * `LargeClass` threshold — and its own subject besides: every test here turns on the one thing a
 * bitrate cannot say, which is whether the user chose it. `AutoBitrateDetector` is not in the loop,
 * because the ViewModel never waits on the measurement: it sends the flag and the resolver, mocked
 * here, is what fills the number in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerAutoQualityTest : PlayerViewModelFixture() {
    /**
     * An Auto session whose measured cap landed exactly on Medium's rung.
     *
     * The awkward case the flag exists for: by its number alone this stream is indistinguishable
     * from one the user hand-picked.
     */
    private val measuredAuto = source.copy(maxStreamingBitrate = MEDIUM_BITRATE, autoBitrate = true)

    @Test
    fun `a stream measured onto a rung still reads as Auto in the picker`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(measuredAuto)

            val model = viewModel()
            advanceUntilIdle()

            // 8 Mbps is Medium's number, but nobody picked Medium — the flag, not the bitrate, is
            // what the chip is derived from.
            model.uiState.value.quality shouldBe PlaybackQuality.AUTO
        }

    @Test
    fun `picking the rung a measurement happened to land on is still a real change`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(measuredAuto)
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(source.copy(maxStreamingBitrate = MEDIUM_BITRATE))

            model.selectQuality(PlaybackQuality.MEDIUM)
            advanceUntilIdle()

            // Comparing bitrates would have swallowed this tap; comparing picker entries does not.
            requests.last().maxStreamingBitrate shouldBe MEDIUM_BITRATE
            requests.last().autoBitrate shouldBe false
        }

    @Test
    fun `going back to Auto hands the cap back to the measurement`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns
                AppResult.Success(source.copy(maxStreamingBitrate = MEDIUM_BITRATE))
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(measuredAuto)

            model.selectQuality(PlaybackQuality.AUTO)
            advanceUntilIdle()

            // No cap and the flag set: the ViewModel never blocks a tap on a measurement, the
            // resolver fills the number in.
            requests.last().maxStreamingBitrate.shouldBeNull()
            requests.last().autoBitrate shouldBe true
        }

    @Test
    fun `tapping Auto while already on Auto changes nothing`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns AppResult.Success(measuredAuto)
            val model = viewModel()
            advanceUntilIdle()

            model.selectQuality(PlaybackQuality.AUTO)
            advanceUntilIdle()

            // A measured cap must not make Auto look like a change away from itself.
            coVerify(exactly = 1) { resolver.resolve(any()) }
        }

    @Test
    fun `the fallback ladder's rung is a manual pick, not another measurement`() =
        runTest(dispatcher) {
            coEvery { resolver.resolve(any()) } returns
                AppResult.Success(measuredAuto.copy(playMethod = PlayMethod.TRANSCODE))
            val model = viewModel()
            advanceUntilIdle()

            val requests = mutableListOf<PlaybackResolveRequest>()
            coEvery { resolver.resolve(capture(requests)) } returns
                AppResult.Success(
                    source.copy(playMethod = PlayMethod.TRANSCODE, maxStreamingBitrate = LOW_BITRATE),
                )

            playerHandle.emit(PlayerEvent.Error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, "stalled"))
            advanceUntilIdle()

            // Leaving the flag on would have the resolver overwrite the rung with the very
            // measurement that just failed to play.
            requests.last().maxStreamingBitrate shouldBe LOW_BITRATE
            requests.last().autoBitrate shouldBe false
            model.uiState.value.quality shouldBe PlaybackQuality.LOW
        }

    private companion object {
        /** `PlaybackQuality.MEDIUM`'s rung, named because it is a coincidence here, not a choice. */
        const val MEDIUM_BITRATE = 8_000_000

        /** `PlaybackQuality.LOW`'s rung — the step the ladder takes down from [MEDIUM_BITRATE]. */
        const val LOW_BITRATE = 3_000_000
    }
}
