package dev.jellyfinnative.player.resolve

import dev.jellyfinnative.core.common.AppError
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.network.ConnectionState
import dev.jellyfinnative.core.network.connectivity.ConnectionStateProvider
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.LocalPlaybackMediaSource
import dev.jellyfinnative.player.model.RemotePlaybackMediaSource
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    fun `a server failure is surfaced unchanged`() =
        runTest {
            coEvery { remote.resolve(any()) } returns AppResult.Failure(AppError.Server(statusCode = 500))

            resolver
                .resolve(request)
                .shouldBeInstanceOf<AppResult.Failure>()
                .error
                .shouldBeInstanceOf<AppError.Server>()
        }
}
