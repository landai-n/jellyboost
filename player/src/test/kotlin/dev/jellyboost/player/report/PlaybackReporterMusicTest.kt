package dev.jellyboost.player.report

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.data.userdata.UserDataRepository
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.api.PlayerApi
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The music half of [PlaybackReporter].
 *
 * The video path's own tests are untouched and still pass unchanged — the point of adding a
 * parallel entry point rather than widening `PlaybackMediaSource`. What is pinned here is the
 * vocabulary a *queue* has and a film does not (repeat and shuffle reach the wire), the
 * completed-track rule (full runtime, marked played), and the two silences: a downloaded track and
 * an offline session tell the server nothing while still writing the position locally.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterMusicTest {
    private val api = mockk<PlayerApi>(relaxed = true)
    private val userDataRepository = mockk<UserDataRepository>()
    private val connectionState = mockk<ConnectionStateProvider>()
    private val state = MutableStateFlow(ConnectionState.ONLINE)

    @BeforeEach
    fun setUp() {
        every { api.deviceId } returns "device-1"
        every { connectionState.state } returns state
        coEvery { userDataRepository.setPosition(any(), any()) } returns AppResult.Success(UserData())
        coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
    }

    @Test
    fun `a start report carries the queue's repeat and shuffle modes`() =
        runTest {
            val info = slot<PlaybackStartInfo>()
            coEvery { api.reportPlaybackStart(capture(info)) } just Runs

            reporter().reportMusicStart(
                target = target(),
                positionTicks = 0L,
                isPaused = false,
                repeatMode = RepeatMode.REPEAT_ALL,
                playbackOrder = PlaybackOrder.SHUFFLE,
            )

            info.captured.itemId shouldBe ITEM_ID
            info.captured.playSessionId shouldBe PLAY_SESSION_ID
            info.captured.mediaSourceId shouldBe ITEM_ID.toString()
            info.captured.repeatMode shouldBe RepeatMode.REPEAT_ALL
            info.captured.playbackOrder shouldBe PlaybackOrder.SHUFFLE
            info.captured.isPaused shouldBe false
        }

    @Test
    fun `a progress report writes the position locally as well as to the server`() =
        runTest {
            val info = slot<PlaybackProgressInfo>()
            coEvery { api.reportPlaybackProgress(capture(info)) } just Runs

            reporter().reportMusicProgress(
                target = target(),
                positionTicks = 450_000_000L,
                isPaused = true,
                repeatMode = RepeatMode.REPEAT_ONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )

            info.captured.positionTicks shouldBe 450_000_000L
            info.captured.isPaused shouldBe true
            info.captured.repeatMode shouldBe RepeatMode.REPEAT_ONE
            coVerify(exactly = 1) { userDataRepository.setPosition(ITEM_ID.toString(), 450_000_000L) }
        }

    @Test
    fun `a track played to the end stops at its full runtime and is marked played`() =
        runTest {
            val info = slot<PlaybackStopInfo>()
            coEvery { api.reportPlaybackStopped(capture(info)) } just Runs

            reporter().reportMusicStop(target(), positionTicks = 1_000L, hasEnded = true)

            info.captured.positionTicks shouldBe RUN_TIME_TICKS
            coVerify(exactly = 1) { userDataRepository.setPlayed(ITEM_ID.toString(), played = true) }
            coVerify(exactly = 0) { userDataRepository.setPosition(any(), any()) }
        }

    @Test
    fun `a track skipped away from keeps its position and is not marked played`() =
        runTest {
            reporter().reportMusicStop(target(), positionTicks = 300_000_000L, hasEnded = false)

            coVerify(exactly = 1) { userDataRepository.setPosition(ITEM_ID.toString(), 300_000_000L) }
            coVerify(exactly = 0) { userDataRepository.setPlayed(any(), any()) }
        }

    @Test
    fun `a transcoded track has its encoder killed on stop`() =
        runTest {
            reporter().reportMusicStop(
                target(playMethod = PlayMethod.TRANSCODE),
                positionTicks = 0L,
                hasEnded = false,
            )

            coVerify(exactly = 1) {
                api.stopEncodingProcess(deviceId = "device-1", playSessionId = PLAY_SESSION_ID)
            }
        }

    @Test
    fun `a direct-played track has no encoder to kill`() =
        runTest {
            reporter().reportMusicStop(target(), positionTicks = 0L, hasEnded = false)

            coVerify(exactly = 0) { api.stopEncodingProcess(any(), any()) }
        }

    @Test
    fun `a downloaded track tells the server nothing and still writes the position`() =
        runTest {
            val target = target(playSessionId = null)

            reporter().reportMusicStart(target, 0L, false, RepeatMode.REPEAT_NONE, PlaybackOrder.DEFAULT)
            reporter().reportMusicProgress(target, 900L, false, RepeatMode.REPEAT_NONE, PlaybackOrder.DEFAULT)
            reporter().reportMusicStop(target, 900L, hasEnded = false)

            coVerify(exactly = 0) { api.reportPlaybackStart(any()) }
            coVerify(exactly = 0) { api.reportPlaybackProgress(any()) }
            coVerify(exactly = 0) { api.reportPlaybackStopped(any()) }
            coVerify(exactly = 2) { userDataRepository.setPosition(ITEM_ID.toString(), 900L) }
        }

    @Test
    fun `an offline session writes the position locally and sends nothing`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            reporter().reportMusicProgress(target(), 900L, false, RepeatMode.REPEAT_NONE, PlaybackOrder.DEFAULT)

            coVerify(exactly = 0) { api.reportPlaybackProgress(any()) }
            coVerify(exactly = 1) { userDataRepository.setPosition(ITEM_ID.toString(), 900L) }
        }

    private fun kotlinx.coroutines.test.TestScope.reporter() =
        PlaybackReporter(
            api = api,
            userDataRepository = userDataRepository,
            connectionState = connectionState,
            detachedScope = this,
        )

    private fun target(
        playMethod: PlayMethod = PlayMethod.DIRECT_PLAY,
        playSessionId: String? = PLAY_SESSION_ID,
    ) = MusicReportTarget(
        itemId = ITEM_ID,
        mediaSourceId = ITEM_ID.toString(),
        playMethod = playMethod,
        playSessionId = playSessionId,
        runTimeTicks = RUN_TIME_TICKS,
    )

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        const val PLAY_SESSION_ID = "music-session-1"
        const val RUN_TIME_TICKS = 2_400_000_000L
    }
}
