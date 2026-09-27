package dev.jellyboost.player.ui

import androidx.lifecycle.SavedStateHandle
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.player.cast.CastMetadataHolder
import dev.jellyboost.player.cast.CastSessionCoordinator
import dev.jellyboost.player.cast.CastSessionListener
import dev.jellyboost.player.cast.CastSessionMonitor
import dev.jellyboost.player.cast.CastStatusHolder
import dev.jellyboost.player.fallback.DecoderFallbackHandler
import dev.jellyboost.player.resolve.PlaybackResolveRequest
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlaybackSessionController
import dev.jellyboost.player.session.RoutingPlayerHandle
import io.mockk.coEvery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import javax.inject.Provider

/**
 * The cast half of the ViewModel fixture: a real [RoutingPlayerHandle] and [CastSessionCoordinator] over
 * fakes, so "exactly one stop report per source" is verified as a system property, not two
 * independently-mocked halves. Shared by [PlayerViewModelCastTest] and [PlayerViewModelCastReattachTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class PlayerViewModelCastFixture : PlayerViewModelFixture() {
    protected val local get() = playerHandle

    protected val castHandle = FakePlayerHandle()

    protected val routing = RoutingPlayerHandle(local, Provider { castHandle })

    protected val castStatus = CastStatusHolder()

    /** What the receiver would be loaded with; the handle reads it at `prepare`. */
    protected val castMetadata = CastMetadataHolder()

    /** Captures the coordinator's listener, so a test can be the Cast framework. */
    private val monitor =
        object : CastSessionMonitor {
            var listener: CastSessionListener? = null

            override fun start(listener: CastSessionListener) {
                this.listener = listener
            }
        }

    protected val coordinator by lazy {
        CastSessionCoordinator(
            monitor = monitor,
            routing = routing,
            reporter = reporter,
            status = castStatus,
            detachedScope = CoroutineScope(dispatcher),
            mainDispatcher = dispatcher,
        ).also { it.start() }
    }

    protected val framework get() = requireNotNull(monitor.listener) { "The coordinator never started watching" }

    /** Own builder, not the fixture's: the handle and the session controller both need the routing one. */
    protected fun castViewModel(savedStateHandle: SavedStateHandle = navArgs()): PlayerViewModel =
        PlayerViewModel(
            repository = repository,
            sessionController =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    ioDispatcher = UnconfinedTestDispatcher(),
                    playerHandle = routing,
                    reporter = reporter,
                    // Must be the same holder the coordinator writes: the controller re-checks it at
                    // prepare time, and a private never-casting default would re-resolve every open.
                    castStatus = castStatus,
                ),
            playerHandle = routing,
            reporter = reporter,
            fallback = DecoderFallbackHandler(),
            trickplayResolver = trickplayResolver,
            segmentLoader = segmentLoader,
            upNextResolver = upNextResolver,
            preferences = preferences,
            assSubtitles = assSubtitles,
            pipController = pipController,
            connectionState = connectionState,
            syncPlayController = syncPlayController,
            syncPlayLocalSession = syncPlayLocalSession,
            savedStateHandle = savedStateHandle,
            castStatus = castStatus,
            castMetadata = castMetadata,
            castCoordinator = coordinator,
        )

    protected fun recordResolves(): List<PlaybackResolveRequest> {
        val requests = mutableListOf<PlaybackResolveRequest>()
        coEvery { resolver.resolve(capture(requests)) } returns AppResult.Success(source)
        return requests
    }
}
