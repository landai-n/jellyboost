package dev.jellyboost.player.report

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.data.userdata.UserDataRepository
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.api.PlayerApi
import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.syncplay.SyncPlayStatusHolder
import io.kotest.matchers.nulls.shouldBeNull
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A local file reports only while the device is in a group *and* online; alone or offline it stays
 * silent (as [PlaybackReporterTest] pins). Being in a group must never make the reporter ask the
 * server to kill an encoder, since a file on disk started none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterSyncPlayTest {
    private val api = mockk<PlayerApi>(relaxed = true)
    private val userDataRepository = mockk<UserDataRepository>()
    private val connectionState = mockk<ConnectionStateProvider>()
    private val state = MutableStateFlow(ConnectionState.ONLINE)
    private val syncPlay = SyncPlayStatusHolder()

    @BeforeEach
    fun setUp() {
        every { api.deviceId } returns DEVICE_ID
        every { connectionState.state } returns state
        coEvery { userDataRepository.setPosition(any(), any()) } returns AppResult.Success(UserData())
        coEvery { userDataRepository.setPlayed(any(), any()) } returns AppResult.Success(UserData())
        syncPlay.setInGroup(true)
        syncPlay.setMintedPlaySessionId(MINTED_ID)
    }

    // ---- local + online + in a group reports ----------------------------------------------------

    @Test
    fun `a downloaded item watched with a group reports its start with the minted session`() =
        runTest {
            val info = slot<PlaybackStartInfo>()
            coEvery { api.reportPlaybackStart(capture(info)) } just Runs

            reporter().reportStart(
                PlayerFixtures.localSource(startPositionTicks = 600L, selectedAudioIndex = 2),
                PlaybackSnapshot(isPlaying = true),
            )

            info.captured.itemId shouldBe PlayerFixtures.ITEM_ID
            info.captured.playSessionId shouldBe MINTED_ID
            info.captured.positionTicks shouldBe 600L
            info.captured.audioStreamIndex shouldBe 2
            // Direct play by construction: the bytes are on this device.
            info.captured.playMethod shouldBe org.jellyfin.sdk.model.api.PlayMethod.DIRECT_PLAY
        }

    @Test
    fun `a downloaded item watched with a group reports its progress`() =
        runTest {
            val info = slot<PlaybackProgressInfo>()
            coEvery { api.reportPlaybackProgress(capture(info)) } just Runs

            reporter().reportProgress(
                PlayerFixtures.localSource(),
                PlaybackSnapshot(positionMs = 90_000L, isPlaying = true),
            )

            info.captured.positionTicks shouldBe 900_000_000L
            info.captured.playSessionId shouldBe MINTED_ID
            // Position is still written locally, so the download resumes correctly with or without a server.
            coVerify(exactly = 1) { userDataRepository.setPosition(any(), 900_000_000L) }
        }

    @Test
    fun `a downloaded item watched with a group reports its stop`() =
        runTest {
            val info = slot<PlaybackStopInfo>()
            coEvery { api.reportPlaybackStopped(capture(info)) } just Runs

            reporter().reportStop(PlayerFixtures.localSource(), PlaybackSnapshot(positionMs = 95_000L))

            info.captured.positionTicks shouldBe 950_000_000L
            info.captured.playSessionId shouldBe MINTED_ID
            coVerify(exactly = 1) { userDataRepository.setPosition(any(), 950_000_000L) }
        }

    @Test
    fun `the ticker reports a grouped download to the server as well as to Room`() =
        runTest {
            val reporter = reporter()
            val source = PlayerFixtures.localSource()

            val job =
                reporter.startReporting(
                    scope = this,
                    currentSource = { source },
                    snapshot = { PlaybackSnapshot(positionMs = 1_000L, isPlaying = true) },
                )

            advanceTimeBy(11.seconds)
            runCurrent()

            coVerify(exactly = 2) { api.reportPlaybackProgress(any()) }
            coVerify(exactly = 2) { userDataRepository.setPosition(any(), 10_000_000L) }

            job.cancel()
        }

    @Test
    fun `a failed mint still reports, with no session id at all`() =
        runTest {
            // The server keys the session on the authenticated device, so a report without an id still lands.
            syncPlay.setMintedPlaySessionId(null)
            val info = slot<PlaybackStartInfo>()
            coEvery { api.reportPlaybackStart(capture(info)) } just Runs

            reporter().reportStart(PlayerFixtures.localSource(), PlaybackSnapshot(isPlaying = true))

            info.captured.playSessionId.shouldBeNull()
            info.captured.itemId shouldBe PlayerFixtures.ITEM_ID
        }

    // ---- and the three cases that stay silent ---------------------------------------------------

    @Test
    fun `a grouped download reports nothing while the server is unreachable`() =
        runTest {
            state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE
            val reporter = reporter()
            val source = PlayerFixtures.localSource()

            reporter.reportStart(source, PlaybackSnapshot(isPlaying = true))
            reporter.reportProgress(source, PlaybackSnapshot(positionMs = 90_000L, isPlaying = true))
            reporter.reportStop(source, PlaybackSnapshot(positionMs = 95_000L))

            coVerify(exactly = 0) { api.reportPlaybackStart(any()) }
            coVerify(exactly = 0) { api.reportPlaybackProgress(any()) }
            coVerify(exactly = 0) { api.reportPlaybackStopped(any()) }
            // The group is unreachable too, and nothing is owed to the server until it's back (decision 10).
            coVerify(exactly = 1) { userDataRepository.setPosition(any(), 950_000_000L) }
        }

    @Test
    fun `a download played alone is as silent as it was before groups existed`() =
        runTest {
            syncPlay.setInGroup(false)
            val reporter = reporter()
            val source = PlayerFixtures.localSource()

            reporter.reportStart(source, PlaybackSnapshot(isPlaying = true))
            reporter.reportProgress(source, PlaybackSnapshot(positionMs = 90_000L, isPlaying = true))
            reporter.reportStop(source, PlaybackSnapshot(positionMs = 95_000L))

            coVerify(exactly = 0) { api.reportPlaybackStart(any()) }
            coVerify(exactly = 0) { api.reportPlaybackProgress(any()) }
            coVerify(exactly = 0) { api.reportPlaybackStopped(any()) }
        }

    @Test
    fun `a group never makes the reporter kill an encoder that cannot exist`() =
        runTest {
            val reporter = reporter()
            val source = PlayerFixtures.localSource()

            reporter.reportStop(source, PlaybackSnapshot(positionMs = 95_000L))
            reporter.stopTranscoding(source)

            // A file on disk is direct play by construction: there is no ffmpeg process to stop.
            coVerify(exactly = 0) { api.stopEncodingProcess(any(), any()) }
        }

    // ---- leaving the group mid-item -------------------------------------------------------------

    @Test
    fun `leaving a group mid-item closes the server session that was opened for it`() =
        runTest {
            // `inGroup` is already false by the time anything can observe the group ending — why
            // this path exists rather than an ordinary stop report.
            syncPlay.setInGroup(false)
            syncPlay.setMintedPlaySessionId(null)
            val info = slot<PlaybackStopInfo>()
            coEvery { api.reportPlaybackStopped(capture(info)) } just Runs

            reporter().reportGroupExitStop(
                PlayerFixtures.localSource(),
                PlaybackSnapshot(positionMs = 95_000L, isPlaying = true),
                playSessionId = MINTED_ID,
            )

            info.captured.playSessionId shouldBe MINTED_ID
            info.captured.positionTicks shouldBe 950_000_000L
            // Playback carries on solo, so nothing is written as "stopped" locally.
            coVerify(exactly = 0) { userDataRepository.setPosition(any(), any()) }
            coVerify(exactly = 0) { userDataRepository.setPlayed(any(), any()) }
        }

    @Test
    fun `a stream leaving a group keeps its own session and sends no closing stop`() =
        runTest {
            syncPlay.setInGroup(false)

            reporter().reportGroupExitStop(
                PlayerFixtures.remoteSource(),
                PlaybackSnapshot(positionMs = 95_000L, isPlaying = true),
                playSessionId = PlayerFixtures.PLAY_SESSION_ID,
            )

            // A stream's session was never the group's to close: it goes on reporting solo.
            coVerify(exactly = 0) { api.reportPlaybackStopped(any()) }
        }

    @Test
    fun `an offline group exit has nothing to send`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK

            reporter().reportGroupExitStop(
                PlayerFixtures.localSource(),
                PlaybackSnapshot(positionMs = 95_000L),
                playSessionId = MINTED_ID,
            )

            coVerify(exactly = 0) { api.reportPlaybackStopped(any()) }
        }

    // ---- the remote paths are untouched by any of it ---------------------------------------------

    @Test
    fun `a stream in a group reports on its own play session, not the minted one`() =
        runTest {
            val info = slot<PlaybackStartInfo>()
            coEvery { api.reportPlaybackStart(capture(info)) } just Runs

            reporter().reportStart(PlayerFixtures.remoteSource(), PlaybackSnapshot(isPlaying = true))

            info.captured.playSessionId shouldBe PlayerFixtures.PLAY_SESSION_ID
        }

    @Test
    fun `a transcode in a group still has its encoder killed`() =
        runTest {
            reporter().reportStop(
                PlayerFixtures.remoteSource(playMethod = PlayMethod.TRANSCODE),
                PlaybackSnapshot(positionMs = 1_000L),
            )

            coVerify(exactly = 1) {
                api.stopEncodingProcess(deviceId = DEVICE_ID, playSessionId = PlayerFixtures.PLAY_SESSION_ID)
            }
        }

    private fun kotlinx.coroutines.test.TestScope.reporter() =
        PlaybackReporter(
            api = api,
            userDataRepository = userDataRepository,
            connectionState = connectionState,
            detachedScope = this,
            syncPlay = syncPlay,
        )

    private companion object {
        const val DEVICE_ID = "device-1"

        /** What one `PlaybackInfo` POST bought for a file that is already on disk. */
        const val MINTED_ID = "minted-session-1"
    }
}
