package dev.jellyfinnative.player.report

import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.UserData
import dev.jellyfinnative.data.userdata.UserDataRepository
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.api.PlayerApi
import dev.jellyfinnative.player.model.PlaybackSnapshot
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [PlaybackReporter], on a virtual clock.
 *
 * Three things here are invisible until they break in production and are therefore pinned hard:
 * the 5-second cadence, the local position write that happens *whatever* the server does, and the
 * `stopEncodingProcess` call without which a transcode outlives the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {
    private val api = mockk<PlayerApi>(relaxed = true)
    private val userDataRepository = mockk<UserDataRepository>()

    @BeforeEach
    fun setUp() {
        every { api.deviceId } returns DEVICE_ID
        coEvery { userDataRepository.setPosition(any(), any()) } returns AppResult.Success(UserData())
        coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
    }

    // ---- start --------------------------------------------------------------------------------

    @Test
    fun `reports the start with the play method and the resume position`() =
        runTest {
            val reporter = reporter()
            val info = slot<PlaybackStartInfo>()
            coEvery { api.reportPlaybackStart(capture(info)) } just Runs

            reporter.reportStart(
                PlayerFixtures.remoteSource(startPositionTicks = 600L, selectedAudioIndex = 2),
                PlaybackSnapshot(isPlaying = true),
            )

            info.captured.itemId shouldBe PlayerFixtures.ITEM_ID
            info.captured.playSessionId shouldBe PlayerFixtures.PLAY_SESSION_ID
            info.captured.positionTicks shouldBe 600L
            info.captured.audioStreamIndex shouldBe 2
            info.captured.isPaused shouldBe false
            info.captured.playMethod shouldBe org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY
        }

    @Test
    fun `reports a paused start when playback has not begun`() =
        runTest {
            val reporter = reporter()
            val info = slot<PlaybackStartInfo>()
            coEvery { api.reportPlaybackStart(capture(info)) } just Runs

            reporter.reportStart(PlayerFixtures.remoteSource(), PlaybackSnapshot(isPlaying = false))

            info.captured.isPaused shouldBe true
        }

    // ---- progress -----------------------------------------------------------------------------

    @Test
    fun `the ticker reports progress every five seconds`() =
        runTest {
            val reporter = reporter()
            val source = PlayerFixtures.remoteSource()

            val job =
                reporter.startReporting(
                    scope = this,
                    currentSource = { source },
                    snapshot = { PlaybackSnapshot(positionMs = 1_000L, isPlaying = true) },
                )

            advanceTimeBy(4.seconds)
            runCurrent()
            coVerify(exactly = 0) { api.reportPlaybackProgress(any()) }

            advanceTimeBy(2.seconds)
            runCurrent()
            coVerify(exactly = 1) { api.reportPlaybackProgress(any()) }

            advanceTimeBy(10.seconds)
            runCurrent()
            coVerify(exactly = 3) { api.reportPlaybackProgress(any()) }

            // The loop runs until playback stops; leaving it alive would hang the test scope.
            job.cancel()
        }

    @Test
    fun `a progress report converts the position into ticks`() =
        runTest {
            val reporter = reporter()
            val info = slot<PlaybackProgressInfo>()
            coEvery { api.reportPlaybackProgress(capture(info)) } just Runs

            reporter.reportProgress(
                PlayerFixtures.remoteSource(selectedSubtitleIndex = 3),
                PlaybackSnapshot(positionMs = 90_000L, isPlaying = true),
            )

            info.captured.positionTicks shouldBe 900_000_000L
            info.captured.subtitleStreamIndex shouldBe 3
            info.captured.isPaused shouldBe false
        }

    @Test
    fun `every progress report also writes the position locally`() =
        runTest {
            // This is what makes resume identical online and offline (docs/PLAN.md).
            reporter().reportProgress(
                PlayerFixtures.remoteSource(),
                PlaybackSnapshot(positionMs = 90_000L, isPlaying = true),
            )

            coVerify(exactly = 1) {
                userDataRepository.setPosition(PlayerFixtures.ITEM_ID.toString(), 900_000_000L)
            }
        }

    @Test
    fun `a failing server still gets the position written locally`() =
        runTest {
            coEvery { api.reportPlaybackProgress(any()) } throws IOException("offline")

            reporter().reportProgress(
                PlayerFixtures.remoteSource(),
                PlaybackSnapshot(positionMs = 90_000L, isPlaying = true),
            )

            // An unreachable server must not cost the user their place in the film.
            coVerify(exactly = 1) { userDataRepository.setPosition(any(), 900_000_000L) }
        }

    @Test
    fun `no progress is reported once the item has ended`() =
        runTest {
            reporter().reportProgress(
                PlayerFixtures.remoteSource(),
                PlaybackSnapshot(positionMs = 90_000L, hasEnded = true),
            )

            coVerify(exactly = 0) { api.reportPlaybackProgress(any()) }
            coVerify(exactly = 0) { userDataRepository.setPosition(any(), any()) }
        }

    // ---- stop ---------------------------------------------------------------------------------

    @Test
    fun `stopping mid-item reports and stores the current position`() =
        runTest {
            val info = slot<PlaybackStopInfo>()
            coEvery { api.reportPlaybackStopped(capture(info)) } just Runs

            reporter().reportStop(
                PlayerFixtures.remoteSource(),
                PlaybackSnapshot(positionMs = 90_000L),
            )

            info.captured.positionTicks shouldBe 900_000_000L
            info.captured.failed shouldBe false
            coVerify(exactly = 1) { userDataRepository.setPosition(any(), 900_000_000L) }
            coVerify(exactly = 0) { userDataRepository.setPlayed(any(), any()) }
        }

    @Test
    fun `finishing an item reports the full runtime and marks it watched`() =
        runTest {
            val info = slot<PlaybackStopInfo>()
            coEvery { api.reportPlaybackStopped(capture(info)) } just Runs

            reporter().reportStop(
                PlayerFixtures.remoteSource(),
                PlaybackSnapshot(positionMs = 71_999_000L, hasEnded = true),
            )

            info.captured.positionTicks shouldBe PlayerFixtures.RUN_TIME_TICKS
            // Through the repository, not a bare markPlayedItem: this also clears the local resume
            // position and publishes on the user-data event bus.
            coVerify(exactly = 1) {
                userDataRepository.setPlayed(PlayerFixtures.ITEM_ID.toString(), played = true)
            }
            coVerify(exactly = 0) { userDataRepository.setPosition(any(), any()) }
        }

    @Test
    fun `stopping a transcode kills the server-side encoder`() =
        runTest {
            reporter().reportStop(
                PlayerFixtures.remoteSource(playMethod = PlayMethod.TRANSCODE),
                PlaybackSnapshot(positionMs = 1_000L),
            )

            coVerify(exactly = 1) {
                api.stopEncodingProcess(
                    deviceId = DEVICE_ID,
                    playSessionId = PlayerFixtures.PLAY_SESSION_ID,
                )
            }
        }

    @Test
    fun `stopping a direct play has no encoder to kill`() =
        runTest {
            reporter().reportStop(
                PlayerFixtures.remoteSource(playMethod = PlayMethod.DIRECT_PLAY),
                PlaybackSnapshot(positionMs = 1_000L),
            )

            coVerify(exactly = 0) { api.stopEncodingProcess(any(), any()) }
        }

    @Test
    fun `a failing stop report still kills the encoder and writes the position`() =
        runTest {
            coEvery { api.reportPlaybackStopped(any()) } throws IOException("offline")

            reporter().reportStop(
                PlayerFixtures.remoteSource(playMethod = PlayMethod.TRANSCODE),
                PlaybackSnapshot(positionMs = 1_000L),
            )

            coVerify(exactly = 1) { api.stopEncodingProcess(any(), any()) }
            coVerify(exactly = 1) { userDataRepository.setPosition(any(), 10_000_000L) }
        }

    @Test
    fun `the detached stop report runs on the scope that outlives the screen`() =
        runTest {
            // The whole point: viewModelScope is already cancelled when this is called.
            val reporter = reporter()

            reporter.reportStopDetached(PlayerFixtures.remoteSource(), PlaybackSnapshot(positionMs = 1_000L))
            runCurrent()

            coVerify(exactly = 1) { api.reportPlaybackStopped(any()) }
        }

    @Test
    fun `nothing is reported when the device id is unknown`() =
        runTest {
            every { api.deviceId } returns null

            reporter().stopTranscoding(PlayerFixtures.remoteSource(playMethod = PlayMethod.TRANSCODE))

            coVerify(exactly = 0) { api.stopEncodingProcess(any(), any()) }
        }

    private fun kotlinx.coroutines.test.TestScope.reporter() =
        PlaybackReporter(api = api, userDataRepository = userDataRepository, detachedScope = this)

    private companion object {
        const val DEVICE_ID = "device-1"
    }
}
