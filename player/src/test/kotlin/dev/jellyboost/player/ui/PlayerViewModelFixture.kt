package dev.jellyboost.player.ui

import androidx.lifecycle.SavedStateHandle
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.MediaSegmentKind
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.fallback.DecoderFallbackHandler
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.TrickplayTiles
import dev.jellyboost.player.pip.PipController
import dev.jellyboost.player.report.PlaybackReporter
import dev.jellyboost.player.resolve.ExoMediaSourceFactory
import dev.jellyboost.player.resolve.PlaybackSourceResolver
import dev.jellyboost.player.segments.MediaSegment
import dev.jellyboost.player.segments.MediaSegmentLoader
import dev.jellyboost.player.session.FakePlayerHandle
import dev.jellyboost.player.session.PlaybackSessionController
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayLocalSession
import dev.jellyboost.player.syncplay.SyncPlayMessage
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.trickplay.TrickplayResolver
import dev.jellyboost.player.upnext.UpNextResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The collaborators a [PlayerViewModel] needs, and the two builders that assemble one.
 *
 * A base class, not a helper object, so every test reaches the same doubles by name and
 * overrides only what it exercises.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class PlayerViewModelFixture {
    protected val dispatcher = StandardTestDispatcher()
    protected val repository = mockk<JellyfinRepository>()
    protected val resolver = mockk<PlaybackSourceResolver>()
    protected val mediaSourceFactory = mockk<ExoMediaSourceFactory>()
    protected val reporter = mockk<PlaybackReporter>(relaxed = true)
    protected val playerHandle = FakePlayerHandle()
    protected val trickplayResolver = mockk<TrickplayResolver>()
    protected val segmentLoader = mockk<MediaSegmentLoader>()

    /**
     * The up-next lookup; "which episode follows" is pinned in `UpNextResolverTest`, this only
     * records *when* it's asked. `null` by default, so every other test here has no successor.
     */
    protected val upNextResolver = mockk<UpNextResolver>()

    protected val pipController = PipController()

    /**
     * The app's online/offline verdict, writable so a test can drop the network mid-session.
     * Online by default — the everyday state under which a downloaded item's pickers offer the
     * full track list.
     */
    protected val connection = MutableStateFlow(ConnectionState.ONLINE)

    protected val connectionState =
        mockk<ConnectionStateProvider> {
            every { state } returns connection
        }

    /**
     * The group this session is in, writable so a test can put the player in one. `Idle` by
     * default, so every other test in this package is implicitly a solo test.
     */
    protected val syncPlayState = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)

    protected val syncPlayMessages =
        MutableSharedFlow<SyncPlayMessage>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The coordinator, mocked rather than assembled: a real one runs a ping loop and drift
     * monitor forever, which would hang every test's `advanceUntilIdle()`. Its own behaviour is
     * pinned in `SyncPlayControllerTest`.
     */
    protected val syncPlayController =
        mockk<SyncPlayController>(relaxed = true) {
            every { state } returns syncPlayState
            every { messages } returns syncPlayMessages
        }

    /**
     * The server-visible session of a downloaded item in a group; reconciliation itself is
     * pinned in `SyncPlayLocalSessionTest`.
     */
    protected val syncPlayLocalSession = mockk<SyncPlayLocalSession>(relaxed = true)

    /** The player preferences at their defaults; individual tests override what they exercise. */
    protected val preferences =
        mockk<AppPreferences> {
            every { introSkipMode } returns flowOf(SegmentSkipMode.SHOW_BUTTON)
            every { outroSkipMode } returns flowOf(SegmentSkipMode.SHOW_BUTTON)
            every { pipOnLeave } returns flowOf(true)
        }

    protected val source =
        PlayerFixtures.remoteSource(
            playMethod = PlayMethod.DIRECT_PLAY,
            startPositionTicks = RESUME_TICKS,
            audioTracks =
                listOf(
                    PlaybackTrack(index = 1, label = "English", language = "eng", codec = "ac3"),
                    PlaybackTrack(index = 2, label = "French", language = "fra", codec = "aac"),
                ),
            selectedAudioIndex = 1,
        )

    protected val spec =
        PlaybackMediaItemSpec(mediaId = PlayerFixtures.ITEM_ID.toString(), uri = "https://server/x")

    /** An intro from 30 s to 2 min — long enough to be worth a button. */
    protected val intro = MediaSegment(MediaSegmentKind.INTRO, startMs = 30_000L, endMs = 120_000L)

    protected val tiles =
        TrickplayTiles(
            thumbnailWidth = 320,
            thumbnailHeight = 180,
            columns = 10,
            rows = 10,
            thumbnailCount = 250,
            intervalMs = 10_000,
            tileUris = listOf("https://server/t.0.jpg"),
        )

    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension(dispatcher)

    @BeforeEach
    fun setUp() {
        coEvery { repository.getItem(any()) } returns
            AppResult.Success(JellyfinItem(id = "x", name = "Arrival", type = ItemType.MOVIE))
        coEvery { resolver.resolve(any()) } returns AppResult.Success(source)
        every { mediaSourceFactory.create(any()) } returns spec
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        coEvery { trickplayResolver.resolve(any(), any()) } returns null
        coEvery { segmentLoader.load(any()) } returns emptyList()
        coEvery { upNextResolver.resolve(any()) } returns null
    }

    protected fun viewModel(savedStateHandle: SavedStateHandle = navArgs()) =
        PlayerViewModel(
            repository = repository,
            sessionController =
                PlaybackSessionController(
                    resolver = resolver,
                    mediaSourceFactory = mediaSourceFactory,
                    playerHandle = playerHandle,
                    reporter = reporter,
                ),
            playerHandle = playerHandle,
            reporter = reporter,
            fallback = DecoderFallbackHandler(),
            trickplayResolver = trickplayResolver,
            segmentLoader = segmentLoader,
            upNextResolver = upNextResolver,
            preferences = preferences,
            pipController = pipController,
            connectionState = connectionState,
            syncPlayController = syncPlayController,
            syncPlayLocalSession = syncPlayLocalSession,
            savedStateHandle = savedStateHandle,
        )

    /**
     * The handle as the navigation library hands it over, plus whatever a restore would carry.
     *
     * `extra` empty is the fresh-tap case most tests exercise.
     */
    protected fun navArgs(vararg extra: Pair<String, Any>) =
        SavedStateHandle(
            mapOf(
                PlayerViewModel.ARG_ITEM_ID to PlayerFixtures.ITEM_ID.toString(),
                PlayerViewModel.ARG_MEDIA_SOURCE_ID to MEDIA_SOURCE_ID,
                PlayerViewModel.ARG_START_TICKS to RESUME_TICKS,
            ) + extra,
        )

    companion object {
        const val MEDIA_SOURCE_ID = "source-1"
        const val RESUME_TICKS = 12_000_000_000L

        /** 90 minutes in — an hour past [RESUME_TICKS], so a stale replay is unmistakable. */
        const val LIVE_TICKS = 54_000_000_000L
    }
}
