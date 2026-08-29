package dev.jellyboost.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.jellyboost.core.common.Routes
import dev.jellyboost.core.common.music.MusicMessage
import dev.jellyboost.core.common.music.MusicPlaybackState
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.core.ui.component.JellyboostSnackbarHost
import dev.jellyboost.core.ui.theme.ChromeBackdrop
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.core.ui.theme.LocalChromeBackdrop
import dev.jellyboost.core.ui.theme.LocalHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app's outer frame: the [JellyfinNavHost] with the app's floating chrome drawn *over* it.
 *
 * The frame reserves no space for anything — every bar is a *sibling* of the nav host — so a
 * top-level screen must consume [LocalAppChromePadding] in the `contentPadding` of whatever it
 * scrolls, or its content comes to rest under the glass. [MiniPlayer] follows the queue rather than
 * the destination ([showsMiniPlayer]), so that padding's *bottom* is non-zero on pushed screens too.
 *
 * The bars are animated rather than switched because `isTopLevel` flips the instant `navigate()` is
 * called, a whole [NAV_TRANSITION_MILLIS] before the page cross-fade ends; the chrome is fed the last
 * *top-level* destination so the selected-tab pill does not blink off halfway through the fade.
 *
 * **This frame is the app's ground.** Nothing else paints `colorScheme.background`: screens draw
 * their own cards and chrome onto whatever is behind them, and until this fill existed that was the
 * window background, which `themes.xml` locks dark because it is painted before Compose or DataStore
 * exist. A light scheme therefore drew light-page ink onto a near-black window. The fill sits below
 * the `hazeSource` node exactly as the window did, so glass still samples page content and composites
 * it over `GlassDefaults.style`'s `backgroundColor` — the same role — when the page is empty there.
 * The player is the one screen that must not take it, and does not: `PlayerScreen` fills itself with
 * literal black for its letterbox.
 *
 * The chrome is a sibling of the nav host and cannot see what the page under it draws, so
 * [ChromeBackdrop] is published downward and written by the one screen that puts artwork under the
 * bars. It decides the band's ink and the cluster's glass — not the theme, which cannot answer a
 * question whose answer changes as a hero scrolls away.
 *
 * The nav host is the app's only `hazeSource` — every glass surface samples it through
 * [LocalHazeState]. The source is detached on the player: video is a `SurfaceView` composited by the
 * system, so its pixels never land in the recorded layer and a blur there would sample black.
 *
 * Overlapping siblings with no declared reading order are sorted geometrically, which interleaves the
 * chrome with the page, so each piece is its own traversal group with an index: top chrome
 * ([CHROME_TOP_INDEX]) → page ([PAGE_INDEX]) → bottom pill ([CHROME_BOTTOM_INDEX]). The scrim (no
 * semantics) and the snackbar (announced, not traversed to) are deliberately left out.
 */
@Suppress(
    // Every block binds one chrome slot to one inset rule; extracting a slot moves its rule away from the five it
    // trades with.
    "LongMethod",
)
@Composable
internal fun AppScaffold(
    startsSignedIn: Boolean,
    sessionState: SessionState,
) {
    val navController: NavHostController = rememberNavController()
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    val isTopLevel = currentDestination.isTopLevel()
    val onPlayer = currentDestination?.hasRoute<Routes.Player>() == true
    val onNowPlaying = currentDestination?.hasRoute<Routes.NowPlaying>() == true

    // Written during composition, which is safe only because it is idempotent (the same value for the
    // same destination) and read below, after this line has run.
    var barDestination by remember { mutableStateOf<NavDestination?>(null) }
    if (isTopLevel) barDestination = currentDestination

    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()

    val syncPlayBadgeViewModel: SyncPlayBadgeViewModel = hiltViewModel()
    val activeSyncPlayGroup by syncPlayBadgeViewModel.activeGroup.collectAsStateWithLifecycle()

    // No NavBackStackEntry owns this destination-independent chrome, so this and the `hiltViewModel()`
    // calls in `MusicMessageEffect` and `JellyfinNavHost` all resolve the same Activity-scoped instance.
    val musicViewModel: MusicPlaybackViewModel = hiltViewModel()
    val musicState by musicViewModel.state.collectAsStateWithLifecycle()
    // Not gated on `isTopLevel`: playback starts on album/artist/playlist screens, all of them pushed.
    val showMiniPlayer = showsMiniPlayer(musicState, onPlayer, onNowPlaying)

    val snackbarHostState = remember { SnackbarHostState() }
    val showConnectionStatus =
        rememberConnectionStatusExplainer(
            state = connectionState,
            snackbarHostState = snackbarHostState,
            onRetry = connectionViewModel::refresh,
            onLeaveOfflineMode = { connectionViewModel.setForceOffline(false) },
        )

    MusicMessageEffect(snackbarHostState = snackbarHostState)

    // The network may well have changed while nothing was listening.
    LifecycleResumeEffect(Unit) {
        connectionViewModel.refresh()
        onPauseOrDispose { }
    }

    val hazeState = remember { HazeState() }
    val chromeBackdrop = remember { ChromeBackdrop() }

    val chrome = AppChromeState(connectionState = connectionState, hasActiveSyncPlayGroup = activeSyncPlayGroup != null)
    val chromeActions =
        remember(navController, connectionViewModel, showConnectionStatus) {
            AppChromeActions(
                onConnectionStatusClick = showConnectionStatus,
                onOpenSyncPlayGroups = { navController.navigate(Routes.SyncPlay) },
                onNavigateToSettings = { navController.navigate(Routes.Settings) },
                onSetForceOffline = connectionViewModel::setForceOffline,
            )
        }

    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                // Deliberately only the fill, not a `Surface`: a Surface would also provide
                // `LocalContentColor`, moving what an unstyled `Text` draws from black to
                // `onBackground` in both schemes — a separate change, and not one to make blind.
                .background(MaterialTheme.colorScheme.background),
    ) {
        val bottomNav = useBottomNav(maxWidth)
        val chromePadding =
            chromePadding(isTopLevel = isTopLevel, bottomNav = bottomNav, showMiniPlayer = showMiniPlayer)

        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            LocalAppChromePadding provides chromePadding,
            LocalChromeBackdrop provides chromeBackdrop,
        ) {
            JellyfinNavHost(
                startsSignedIn = startsSignedIn,
                sessionState = sessionState,
                navController = navController,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pageTraversal()
                        .then(if (onPlayer) Modifier else Modifier.hazeSource(hazeState)),
            )

            AnimatedVisibility(
                visible = isTopLevel,
                enter = fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopChromeScrim(
                    height = topChromeInset + if (bottomNav) ActionClusterHeight else TopNavHeight,
                    overMedia = chromeBackdrop.overMedia,
                )
            }

            AnimatedVisibility(
                visible = isTopLevel && !bottomNav,
                enter = slideInVertically { -it } + fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = slideOutVertically { -it } + fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier = Modifier.align(Alignment.TopCenter).topChromeTraversal(),
            ) {
                GlassTopNav(
                    currentDestination = barDestination,
                    chrome = chrome,
                    actions = chromeActions,
                    onSelectTab = navController::navigateToTab,
                )
            }

            AnimatedVisibility(
                visible = isTopLevel && bottomNav,
                enter = fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier = Modifier.align(Alignment.TopEnd).topChromeTraversal(),
            ) {
                AppActionCluster(
                    chrome = chrome,
                    actions = chromeActions,
                    overMedia = chromeBackdrop.overMedia,
                )
            }

            AnimatedVisibility(
                visible = isTopLevel && bottomNav,
                enter = slideInVertically { it } + fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = slideOutVertically { it } + fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = BottomNavMargin)
                        .bottomChromeTraversal(),
            ) {
                GlassBottomNav(
                    currentDestination = barDestination,
                    onSelectTab = navController::navigateToTab,
                )
            }

            AnimatedVisibility(
                visible = showMiniPlayer,
                enter = slideInVertically { it } + fadeIn(tween(NAV_TRANSITION_MILLIS)),
                exit = slideOutVertically { it } + fadeOut(tween(NAV_TRANSITION_MILLIS / CHROME_EXIT_DIVISOR)),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = Dimens.ScreenPadding)
                        .padding(bottom = miniPlayerBottomOffset(isTopLevel = isTopLevel, bottomNav = bottomNav)),
            ) {
                (musicState as? MusicPlaybackState.Active)?.let { active ->
                    DismissableMiniPlayer(
                        state = active,
                        onTogglePlayPause = musicViewModel::togglePlayPause,
                        onPrevious = musicViewModel::previous,
                        onNext = musicViewModel::next,
                        onClick = { navController.navigate(Routes.NowPlaying) },
                        onDismiss = musicViewModel::stop,
                    )
                }
            }

            JellyboostSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * [GlassTopNav] (wide) and [AppActionCluster] (compact) are never on screen together, so they share
 * one index. `internal`, with the two below it, so the instrumented suite can assert the three
 * indices without composing the whole signed-in app.
 */
internal fun Modifier.topChromeTraversal(): Modifier =
    semantics {
        isTraversalGroup = true
        traversalIndex = CHROME_TOP_INDEX
    }

internal fun Modifier.pageTraversal(): Modifier =
    semantics {
        isTraversalGroup = true
        traversalIndex = PAGE_INDEX
    }

internal fun Modifier.bottomChromeTraversal(): Modifier =
    semantics {
        isTraversalGroup = true
        traversalIndex = CHROME_BOTTOM_INDEX
    }

internal const val CHROME_TOP_INDEX = -1f

internal const val PAGE_INDEX = 0f

internal const val CHROME_BOTTOM_INDEX = 1f

/**
 * The chrome leaves at half the page's clock: for the whole transition the incoming pushed screen is
 * fading its *own* top-right buttons in, and two semi-transparent sets in one corner read as a smear.
 * The padding stays on the full clock — a faster collapse makes a list's content jump under the fade.
 */
private const val CHROME_EXIT_DIVISOR = 2

/**
 * Shows the music queue's one-shot notices in the chrome's snackbar, at the scaffold's level because
 * the screen that asked for the queue is very often gone by the time the answer comes back. The flow
 * is hot and unreplayed: a message emitted while the app is not composing is dropped rather than
 * surfacing later out of context.
 */
@Composable
private fun MusicMessageEffect(snackbarHostState: SnackbarHostState) {
    val viewModel: MusicPlaybackViewModel = hiltViewModel()

    // A collector runs outside composition, where `stringResource` is not available; the
    // parameterized ones are resolved as raw positional templates and formatted at collect time.
    val refusedInGroup = stringResource(R.string.music_refused_in_group)
    val queueUnavailable = stringResource(R.string.music_queue_unavailable)
    val trackUnavailable = stringResource(R.string.music_track_unavailable)
    val playbackFailed = stringResource(R.string.music_playback_failed)
    val radioFailed = stringResource(R.string.music_radio_failed)

    LaunchedEffect(viewModel, refusedInGroup, queueUnavailable, trackUnavailable, playbackFailed, radioFailed) {
        viewModel.messages.collect { message ->
            val text =
                when (message) {
                    MusicMessage.RefusedInSyncPlayGroup -> refusedInGroup
                    is MusicMessage.TrackUnavailable -> trackUnavailable.format(message.itemName)
                    MusicMessage.QueueUnavailable -> queueUnavailable
                    is MusicMessage.PlaybackFailed -> playbackFailed.format(message.itemName)
                    is MusicMessage.RadioFailed -> radioFailed.format(message.itemName)
                }
            snackbarHostState.showSnackbar(message = text, duration = SnackbarDuration.Short)
        }
    }
}

/**
 * The band of darkened background the top chrome is read against — a sibling of the nav host, and
 * deliberately not a background on the bars themselves: inside the `hazeSource` it would be blurred
 * into the very surfaces it protects, and inside a `hazeEffect` it would be sampling an effect
 * rather than a backdrop, which Haze does not do.
 *
 * @param overMedia the band has landed on a screen's full-bleed artwork rather than on its page, so
 *   it takes the pinned dark ink: darkening the page's own colour over a picture is a white haze on
 *   the light side, which is the reading the saved canvas rejected.
 */
@Composable
private fun TopChromeScrim(
    height: Dp,
    overMedia: Boolean,
    modifier: Modifier = Modifier,
) {
    val brush =
        if (overMedia) JellyfinGradients.OverMediaTopChromeScrim else JellyfinGradients.TopChromeScrim
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(brush),
    )
}

/**
 * How much of a screen the chrome covers, published through [LocalAppChromePadding].
 *
 * The two animated values are **not read here**: rebuilding a `PaddingValues` per frame would
 * recompose the whole scaffold scope on every frame of the transition. They go into one stable
 * [AnimatedChromePadding] that reads them where `contentPadding` is consumed — inside a lazy
 * layout's measure pass — so the animation invalidates layout rather than composition.
 *
 * The compact top value keeps the status bar plus the floating [AppActionCluster] clear rather than
 * being zero: a screen's first *non-scrolling* row would otherwise sit permanently under the
 * cluster's buttons. [Dimens.SpaceSmall] is added on top of the bar's own height because reserving
 * exactly the bar left the first row touching the glass.
 */
@Composable
private fun chromePadding(
    isTopLevel: Boolean,
    bottomNav: Boolean,
    showMiniPlayer: Boolean,
): PaddingValues {
    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarInset = topChromeInset

    val topTarget =
        when {
            !isTopLevel -> 0.dp
            bottomNav -> statusBarInset + ActionClusterHeight + Dimens.SpaceSmall
            else -> statusBarInset + TopNavHeight + Dimens.SpaceSmall
        }
    val bottomTarget =
        chromeBottomTarget(
            isTopLevel = isTopLevel,
            bottomNav = bottomNav,
            showMiniPlayer = showMiniPlayer,
            navigationBarInset = navigationBarInset,
        )

    val top =
        animateDpAsState(
            targetValue = topTarget,
            animationSpec = tween(NAV_TRANSITION_MILLIS),
            label = "chromeTopPadding",
        )
    val bottom =
        animateDpAsState(
            targetValue = bottomTarget,
            animationSpec = tween(NAV_TRANSITION_MILLIS),
            label = "chromeBottomPadding",
        )

    return remember { AnimatedChromePadding(top = top, bottom = bottom) }
}

/**
 * [chromePadding]'s `bottom`, as a plain function so the arithmetic is testable without a device.
 *
 * The mini-player's term carries no navigation-bar inset of its own — it is the bar's extent *above*
 * whatever is already at the bottom edge, which is what [miniPlayerBottomOffset] docks it past. On a
 * top-level compact window the pill's term supplies that inset; on a pushed one the screen does
 * (`LibraryGridScreen`'s convention), so the two never double-count it.
 */
internal fun chromeBottomTarget(
    isTopLevel: Boolean,
    bottomNav: Boolean,
    showMiniPlayer: Boolean,
    navigationBarInset: Dp,
): Dp =
    (if (isTopLevel && bottomNav) navigationBarInset + BottomNavMargin + BottomNavHeight else 0.dp) +
        (if (showMiniPlayer) MiniPlayerHeight + MiniPlayerGap else 0.dp)

/**
 * How far above whatever is already at the bottom edge [MiniPlayer] docks — the mirror of
 * [chromeBottomTarget]'s mini-player term, gated on the same condition.
 */
internal fun miniPlayerBottomOffset(
    isTopLevel: Boolean,
    bottomNav: Boolean,
): Dp = if (isTopLevel && bottomNav) BottomNavMargin + BottomNavHeight + MiniPlayerGap else MiniPlayerGap

/** A stable identity whose top and bottom are read from the animated states at *use* — see [chromePadding]. */
@Stable
internal class AnimatedChromePadding(
    private val top: State<Dp>,
    private val bottom: State<Dp>,
) : PaddingValues {
    override fun calculateTopPadding(): Dp = top.value

    override fun calculateBottomPadding(): Dp = bottom.value

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}

/**
 * The action behind the chrome's offline status icon. The strings are resolved here because a click
 * handler runs outside composition, where `stringResource` is not available.
 */
@Composable
private fun rememberConnectionStatusExplainer(
    state: ConnectionState,
    snackbarHostState: SnackbarHostState,
    onRetry: () -> Unit,
    onLeaveOfflineMode: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val status = state.toStatus()
    val message = stringResource((status ?: ConnectionStatus.NO_NETWORK).messageRes)
    val actionLabel = status?.actionLabelRes?.let { stringResource(it) }
    val pending = remember { mutableStateOf<Job?>(null) }

    return {
        val action =
            when (status) {
                ConnectionStatus.SERVER_UNREACHABLE -> onRetry
                ConnectionStatus.FORCED -> onLeaveOfflineMode
                else -> null
            }
        // `showSnackbar` is a mutex queue: without the cancel, each repeated tap appends another
        // entry instead of replacing the one on screen.
        pending.value?.cancel()
        pending.value =
            scope.launch {
                // An `actionLabel` alone would default `duration` to Indefinite.
                val result =
                    snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = actionLabel,
                        duration = SnackbarDuration.Long,
                    )
                if (result == SnackbarResult.ActionPerformed) action?.invoke()
            }
    }
}
