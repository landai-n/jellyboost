package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.RemotePlaybackMediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PlaybackSourceResolver] — where a downloaded item beats the server.
 *
 * This is the milestone's differentiator expressed as three branches, and each is a decision a user
 * would notice: a downloaded film must not stream, an item with no local copy must still stream,
 * and neither of those may end in a spinner that never resolves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSourceResolverTest {
    private val local = mockk<LocalPlaybackResolver>()
    private val remote = mockk<PlaybackInfoResolver>()
    private val connectionState = mockk<ConnectionStateProvider>()
    private val state = MutableStateFlow(ConnectionState.ONLINE)

    private val resolver = PlaybackSourceResolver(local, remote, connectionState)

    private val request = PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID)

    @BeforeEach
    fun setUp() {
        every { connectionState.state } returns state
        every { connectionState.reportFailure() } just runs
        coEvery { local.resolve(any()) } returns null
        coEvery { remote.resolve(any()) } returns AppResult.Success(PlayerFixtures.remoteSource())
    }

    @Test
    fun `a downloaded item plays locally even with the server right there`() =
        runTest {
            coEvery { local.resolve(any()) } returns PlayerFixtures.localSource()

            val result = resolver.resolve(request)

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            result.value.shouldBeInstanceOf<LocalPlaybackMediaSource>()
            // The differentiator: a film the user deliberately put on the device is never streamed.
            coVerify(exactly = 0) { remote.resolve(any()) }
        }

    @Test
    fun `a downloaded item plays locally with no network at all`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            coEvery { local.resolve(any()) } returns PlayerFixtures.localSource()

            resolver
                .resolve(request)
                .shouldBeInstanceOf<AppResult.Success<*>>()
                .value
                .shouldBeInstanceOf<LocalPlaybackMediaSource>()
        }

    @Test
    fun `an item that was never downloaded is negotiated with the server`() =
        runTest {
            val result = resolver.resolve(request)

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            result.value.shouldBeInstanceOf<RemotePlaybackMediaSource>()
            coVerify(exactly = 1) { remote.resolve(request) }
        }

    @Test
    fun `an item with no local copy fails immediately when offline rather than hanging`() =
        runTest {
            state.value = ConnectionState.OFFLINE_SERVER_UNREACHABLE

            val result = resolver.resolve(request)

            // A PlaybackInfo POST into a dead network would sit on the SDK's socket timeout behind
            // a spinner with no cancel.
            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Network>()
            coVerify(exactly = 0) { remote.resolve(any()) }
        }

    @Test
    fun `forced offline mode is treated like any other offline state`() =
        runTest {
            state.value = ConnectionState.OFFLINE_FORCED

            resolver.resolve(request).shouldBeInstanceOf<AppResult.Failure>()
        }

    @Test
    fun `a forced transcode skips the local copy, because those are the bytes that just failed`() =
        runTest {
            coEvery { local.resolve(any()) } returns PlayerFixtures.localSource()

            val result = resolver.resolve(request.copy(enableDirectPlay = false))

            result.shouldBeInstanceOf<AppResult.Success<*>>()
            result.value.shouldBeInstanceOf<RemotePlaybackMediaSource>()
            coVerify(exactly = 0) { local.resolve(any()) }
        }

    @Test
    fun `a forced transcode with no server to ask surfaces the error instead of looping`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            coEvery { local.resolve(any()) } returns PlayerFixtures.localSource()

            resolver.resolve(request.copy(enableDirectPlay = false)).shouldBeInstanceOf<AppResult.Failure>()
        }

    @Test
    fun `a track only the server has skips the download and streams the item`() =
        runTest {
            coEvery { local.resolve(any()) } returns PlayerFixtures.localSource()

            val result = resolver.resolve(request.copy(forceRemote = true, audioStreamIndex = 5))

            // Rule 1 would hand back the same file and the same tracks, which is the loop the flag
            // exists to break — the requested index has to reach the server.
            result.shouldBeInstanceOf<AppResult.Success<*>>()
            result.value.shouldBeInstanceOf<RemotePlaybackMediaSource>()
            coVerify(exactly = 0) { local.resolve(any()) }
            coVerify(exactly = 1) { remote.resolve(match { it.audioStreamIndex == 5 }) }
        }

    @Test
    fun `forcing the server with no server to reach fails rather than quietly playing the file`() =
        runTest {
            state.value = ConnectionState.OFFLINE_NO_NETWORK
            coEvery { local.resolve(any()) } returns PlayerFixtures.localSource()

            // Succeeding here would return the file *without* the track that was asked for, and the
            // player would have restarted for nothing. The caller decides what to do with this.
            resolver.resolve(request.copy(forceRemote = true)).shouldBeInstanceOf<AppResult.Failure>()
        }

    @Test
    fun `a server failure is surfaced unchanged`() =
        runTest {
            coEvery { remote.resolve(any()) } returns AppResult.Failure(AppError.Server(statusCode = 500))

            resolver
                .resolve(request)
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<AppError.Server>()
        }

    // ---- the call ceiling ----------------------------------------------------------------------

    @Test
    fun `a negotiation that never answers is cut off at the ceiling instead of hanging`() =
        runTest {
            coEvery { remote.resolve(any()) } coAnswers {
                // A server that died since the last browse call still reads as online: nothing has
                // probed it since, so Play goes out and rides the SDK's own socket timeout.
                delay(SOCKET_TIMEOUT_MS)
                AppResult.Success(PlayerFixtures.remoteSource())
            }

            val start = testScheduler.currentTime
            val result = resolver.resolve(request)

            result.shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<AppError.Network>()
            (testScheduler.currentTime - start) shouldBe PlaybackSourceResolver.RESOLVE_TIMEOUT_MS
            // Demoting the server is what stops the next tap paying the same ceiling again.
            verify(exactly = 1) { connectionState.reportFailure() }
        }

    @Test
    fun `a negotiation that answers in time is left alone`() =
        runTest {
            coEvery { remote.resolve(any()) } coAnswers {
                delay(PlaybackSourceResolver.RESOLVE_TIMEOUT_MS - 1)
                AppResult.Success(PlayerFixtures.remoteSource())
            }

            resolver.resolve(request).shouldBeInstanceOf<AppResult.Success<*>>()

            verify(exactly = 0) { connectionState.reportFailure() }
        }

    private companion object {
        /** The SDK's own default socket timeout — the hang the ceiling exists to rule out. */
        const val SOCKET_TIMEOUT_MS = 30_000L
    }
}
