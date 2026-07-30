package dev.jellyfinnative.player.ui

import androidx.lifecycle.SavedStateHandle
import dev.jellyfinnative.core.common.AppResult
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.JellyfinItem
import dev.jellyfinnative.core.common.model.MediaSegmentKind
import dev.jellyfinnative.core.common.model.SegmentSkipMode
import dev.jellyfinnative.core.datastore.AppPreferences
import dev.jellyfinnative.data.JellyfinRepository
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.fallback.DecoderFallbackHandler
import dev.jellyfinnative.player.model.PlaybackMediaItemSpec
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.model.TrickplayTiles
import dev.jellyfinnative.player.pip.PipController
import dev.jellyfinnative.player.report.PlaybackReporter
import dev.jellyfinnative.player.resolve.ExoMediaSourceFactory
import dev.jellyfinnative.player.resolve.PlaybackSourceResolver
import dev.jellyfinnative.player.segments.MediaSegment
import dev.jellyfinnative.player.segments.MediaSegmentLoader
import dev.jellyfinnative.player.session.FakePlayerHandle
import dev.jellyfinnative.player.session.PlaybackSessionController
import dev.jellyfinnative.player.trickplay.TrickplayResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * The collaborators a [PlayerViewModel] needs, and the two builders that assemble one.
 *
 * A base class rather than a helper object because every test reaches the same doubles by name and
 * overrides one or two of them; splitting it out is what keeps the test class itself down to the
 * behaviour it pins, now that the ViewModel's own collaborators are tested next door
 * ([PlaybackSessionController], [PlayerSessionStore], [PlaybackPositionTracker]).
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
    protected val pipController = PipController()

    /** The M9 preferences at their defaults; individual tests override what they exercise. */
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

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.getItem(any()) } returns
            AppResult.Success(JellyfinItem(id = "x", name = "Arrival", type = ItemType.MOVIE))
        coEvery { resolver.resolve(any()) } returns AppResult.Success(source)
        every { mediaSourceFactory.create(any()) } returns spec
        every { reporter.startReporting(any(), any(), any()) } returns Job()
        coEvery { trickplayResolver.resolve(any(), any()) } returns null
        coEvery { segmentLoader.load(any()) } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
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
            preferences = preferences,
            pipController = pipController,
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
