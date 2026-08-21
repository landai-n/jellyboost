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
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.core.ui.theme.LocalHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The app's outer frame: the [JellyfinNavHost] with the app's floating chrome drawn *over* it, plus
 * the snackbar that explains the connection status.
 *
 * ### The inset contract (2026 refresh)
 * The frame is a plain `Box`, not a `Scaffold`, and it reserves no space for anything. Every piece
 * of chrome — [GlassBottomNav], [GlassTopNav], [AppActionCluster] — is a *sibling* of the nav host
 * that floats on top of it, so the page below always fills the whole window and its content scrolls
 * under the glass, which is the whole point of the refresh's material (DECISIONS.md 2026-08-01).
 *
 * - **Pushed destinations get no *navigation* chrome.** Settings, LibraryGrid, ItemDetail, the
 *   player and the auth flow each manage their own system-bar insets and no bar is drawn over them.
 *   The one exception is [MiniPlayer], which follows the queue rather than the destination — see
 *   [showsMiniPlayer] — and which therefore makes [LocalAppChromePadding]'s *bottom* non-zero on a
 *   pushed screen too. A pushed screen that scrolls under it consumes that bottom the same way a
 *   top-level one does (`:feature:music`'s four browse screens are the ones that do).
 * - **Top-level destinations consume [LocalAppChromePadding]** in the `contentPadding` of whatever
 *   they scroll. That is the one thing a top-level screen has to do, and it replaces the
 *   `innerPadding`/bottom-inset the old `Scaffold` used to hand down: padding that scrolls away,
 *   rather than a window that got shorter. See the composition local's own KDoc for the values.
 *
 * ### Which chrome a window gets
 * [useBottomNav] decides, from one [BoxWithConstraints] at this level (one subcomposition per
 * screen, the codebase's rule against per-item constraint reads). Below [TopNavMinWidth] the
 * navigation is the floating bottom pill and the app-wide actions float in the top-right corner as
 * the [AppActionCluster]; at and above it, [GlassTopNav] carries both in one row.
 *
 * ### Why the chrome is animated rather than switched
 * `isTopLevel` is read from the back stack, so it flips the instant `navigate()` is called — a good
 * half-second before the [JellyfinNavHost] cross-fade that follows it has finished. Drawing the
 * chrome with a bare `if` therefore made it disappear from under a screen that was still fading.
 * Both the bars and the padding they publish are consequently animated on the same clock the pages
 * use ([NAV_TRANSITION_MILLIS]) — the bars through `AnimatedVisibility`, the padding through
 * [animateDpAsState] so a list's `contentPadding` slides to zero instead of snapping.
 *
 * The chrome is fed the last *top-level* destination rather than the live one, because during the
 * exit animation the current destination is already the pushed screen and the selected-tab pill
 * would blink off halfway through the fade.
 *
 * ### One backdrop, one source
 * [HazeState] is created here and the nav host is the only `hazeSource` in the app: every glass
 * surface in the chrome samples the page underneath it through `LocalHazeState`. A second source —
 * inside a lazy item, say — would sample a node that scrolls, which is both wrong and expensive.
 *
 * ### The order a screen reader reads it in
 * Everything above is a *sibling* of the nav host drawn over it, which is precisely the arrangement
 * accessibility traversal has no way to guess: overlapping siblings with no reading order declared
 * are sorted geometrically, and chrome that covers the page from both ends came out interleaved with
 * — or after — the whole of the page's content (accessibility audit 2026-08-05, F9). Each piece
 * therefore declares itself a traversal group and says where it belongs:
 *
 * - **top chrome** ([GlassTopNav] on wide, [AppActionCluster] on compact) — [CHROME_TOP_INDEX],
 * - **the page** ([JellyfinNavHost]) — a group of its own at the default index,
 * - **the bottom pill** ([GlassBottomNav]) — [CHROME_BOTTOM_INDEX].
 *
 * so TalkBack reads top chrome → page → bottom nav, which is what the window looks like. Grouping is
 * what makes the indices bite: a `traversalIndex` orders a node against its *peers*, and without
 * `isTraversalGroup` the chrome's own buttons would be sorted against the page's rows individually
 * instead of moving as one block. The scrim and the snackbar host are deliberately left out — the
 * scrim is a gradient with no semantics at all, and the snackbar is announced when it appears rather
 * than traversed to.
 *
 * The one destination the source is *detached* on is the player. Recording the source is a
 * full-window offscreen layer capture on every frame, and on the player nothing useful can come of
 * it: the video is a `SurfaceView` whose pixels are composited by the system and never land in the
 * recorded layer, so a blur there samples black — which is why the player's own controls draw flat
 * dark glass instead of `glassSurface` ([JellyfinNavHost] nulls `LocalHazeState` for that subtree).
 * Detaching the source hands the capture cost back to the screen where GPU headroom matters most.
 */
@Suppress(
    // 106 lines of slot wiring, and the wiring is the content: every block binds one Scaffold slot to one inset rule,
    // and which slot owns which inset is exactly what the KDoc above spends its length establishing. Extracting a slot
    // moves its rule away from the other five it trades with.
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

    // What the chrome shows while it animates away: the destination it was last drawn for. Writing
    // it during composition is idempotent — the same value for the same destination — and it is only
    // ever read below, after this line has run.
    var barDestination by remember { mutableStateOf<NavDestination?>(null) }
    if (isTopLevel) barDestination = currentDestination

    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val connectionState by connectionViewModel.connectionState.collectAsStateWithLifecycle()

    val syncPlayBadgeViewModel: SyncPlayBadgeViewModel = hiltViewModel()
    val activeSyncPlayGroup by syncPlayBadgeViewModel.activeGroup.collectAsStateWithLifecycle()

    // Resolved at the scaffold's own level, independent of `MusicMessageEffect`'s and
    // `JellyfinNavHost`'s own `hiltViewModel()` calls below — all three return the same
    // Activity-scoped instance (no NavBackStackEntry owns this destination-independent chrome), the
    // same arrangement those two already rely on.
    val musicViewModel: MusicPlaybackViewModel = hiltViewModel()
    val musicState by musicViewModel.state.collectAsStateWithLifecycle()
    // Not gated on `isTopLevel`: the bar has to be visible where playback *starts*, which on this
    // app is an album, artist or playlist screen — all of them pushed. Restricting it to the tabs
    // meant the user tapped Play and saw nothing at all until they navigated back to one (device
    // walk, 2026-08-15). `showsMiniPlayer` already excludes the two destinations that would
    // duplicate it, and the clearance argument the restriction rested on is answered by
    // `chromePadding` below: its bottom already folds the bar in whatever the destination, so a
    // pushed screen consumes it exactly like a tab does.
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

    // Coming back to the app is the other moment the plan wants a reachability probe: the network
    // may well have changed while we were not listening (docs/PLAN.md, "Connectivity").
    LifecycleResumeEffect(Unit) {
        connectionViewModel.refresh()
        onPauseOrDispose { }
    }

    val hazeState = remember { HazeState() }

    // The six values both bars carry, as the two bundles they have always travelled as (audit
    // 2026-08-08, DUP-10). The callbacks are `remember`ed on the two things they close over, so a
    // connectivity change re-emits the *state* without also handing the bars four fresh lambdas.
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val bottomNav = useBottomNav(maxWidth)
        val chromePadding =
            chromePadding(isTopLevel = isTopLevel, bottomNav = bottomNav, showMiniPlayer = showMiniPlayer)

        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            LocalAppChromePadding provides chromePadding,
        ) {
            JellyfinNavHost(
                startsSignedIn = startsSignedIn,
                sessionState = sessionState,
                navController = navController,
                // No source on the player: nothing there samples it and the capture is pure
                // per-frame cost during playback — see "One backdrop, one source" above.
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
                AppActionCluster(chrome = chrome, actions = chromeActions)
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
 * What both pieces of top chrome declare: one block, read before the page under it.
 *
 * The two are never on screen together — [GlassTopNav] is the wide layout's, [AppActionCluster] the
 * compact one's — so they share an index rather than being ordered against each other.
 *
 * `internal`, with the two below it, so the instrumented suite can hold the three indices in the
 * right order without composing the whole signed-in app (`app/src/androidTest`).
 */
internal fun Modifier.topChromeTraversal(): Modifier =
    semantics {
        isTraversalGroup = true
        traversalIndex = CHROME_TOP_INDEX
    }

/** The page: one block of its own, read between the two pieces of chrome. */
internal fun Modifier.pageTraversal(): Modifier =
    semantics {
        isTraversalGroup = true
        traversalIndex = PAGE_INDEX
    }

/** The bottom pill: last, as it is drawn — it is the bottom of the window. */
internal fun Modifier.bottomChromeTraversal(): Modifier =
    semantics {
        isTraversalGroup = true
        traversalIndex = CHROME_BOTTOM_INDEX
    }

/** Before the page. */
internal const val CHROME_TOP_INDEX = -1f

/** Compose's own default, stated rather than left implicit so the three read as one scale. */
internal const val PAGE_INDEX = 0f

/** After the page — see [AppScaffold]'s "The order a screen reader reads it in". */
internal const val CHROME_BOTTOM_INDEX = 1f

/**
 * How much faster than the page cross-fade the chrome leaves.
 *
 * The bars are drawn for the whole of [NAV_TRANSITION_MILLIS] so that they do not vanish from under
 * a screen that is still fading (see [AppScaffold]'s KDoc) — but for that whole time the pushed
 * screen underneath is *also* fading in, with its own top-right buttons: the detail screen's overlay
 * nav, the library grid's sort, SyncPlay's create, the player's cast. Two sets of controls in the
 * same corner, both semi-transparent, read as one smeared pile. Halving the chrome's exit clears the
 * corner well before the incoming screen is legible, and the *padding* is deliberately left on the
 * full clock — that one is read by a list's `contentPadding`, where a faster collapse would make the
 * content jump under the fade.
 */
private const val CHROME_EXIT_DIVISOR = 2

/**
 * Shows the music queue's one-shot notices in the chrome's snackbar (M13 Phase 3).
 *
 * At the scaffold's level, alongside [LogoutRedirectEffect] and `SyncPlayLaunchEffect`, and for the
 * same reason: a refusal or an unplayable track is a fact about a `@Singleton` that no destination
 * owns — the album screen that asked for the queue is very often gone by the time the answer comes
 * back. The flow is hot and unreplayed, so a message emitted while the app was not composing is
 * simply dropped rather than surfacing later out of context.
 */
@Composable
private fun MusicMessageEffect(snackbarHostState: SnackbarHostState) {
    val viewModel: MusicPlaybackViewModel = hiltViewModel()

    // Resolved here rather than inside the collector because a collector runs outside
    // composition, where `stringResource` is not available — the same arrangement
    // [rememberConnectionStatusExplainer] documents. The parameterized ones are resolved as
    // their raw positional templates and formatted with the item name at collect time.
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
 * The band of darkened background the top chrome is read against.
 *
 * A sibling of the nav host, drawn over it and under the bars — deliberately *not* a background on
 * [GlassTopNav]'s row or on the cluster. Those pieces are glass, and glass samples the `hazeSource`
 * around the nav host; a scrim inside that source would be blurred into the very surfaces it is
 * meant to protect, and a scrim inside a `hazeEffect` would be sampling an effect rather than a
 * backdrop, which Haze does not do (`GlassTopNav`'s KDoc). Outside both, it is exactly what it looks
 * like: a gradient over the page, with the glass floating on top of it.
 */
@Composable
private fun TopChromeScrim(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .background(JellyfinGradients.TopChromeScrim),
    )
}

/**
 * How much of a top-level screen the chrome covers, as the animated value published through
 * [LocalAppChromePadding] — see that composition local for the contract itself.
 *
 * [MiniPlayer] (M13 Phase 4) folds into [bottomTarget][chromePadding] exactly like the floating
 * bottom pill does: [showMiniPlayer] adds [MiniPlayerHeight] plus [MiniPlayerGap] of clearance
 * whenever the bar is on screen, so a list's last row scrolls clear of it the same way it already
 * scrolls clear of [GlassBottomNav] — no top-level screen has anything else to do.
 *
 * That term is deliberately **not** gated on [isTopLevel], and it is the one thing that makes this
 * value non-zero on a pushed destination. It matches [miniPlayerBottomOffset] term for term: on a
 * pushed screen the bar docks [MiniPlayerGap] above the navigation-bar inset it consumes itself, so
 * `MiniPlayerHeight + MiniPlayerGap` is exactly how much of the window it covers *above* that inset
 * — which is the half a pushed screen does not already apply by hand (`LibraryGridScreen`'s own
 * KDoc records that convention). The four `:feature:music` browse screens add it to their list's
 * `contentPadding` on top of their own navigation-bar inset.
 *
 * Both ends animate over [NAV_TRANSITION_MILLIS] rather than switching, for the reason spelled out
 * in [AppScaffold]'s KDoc: the value is read by a list's `contentPadding`, and a screen fading out
 * while its padding snapped to zero jumped visibly under the fade.
 *
 * The compact top value is the status bar plus the floating [AppActionCluster] rather than zero.
 * The cluster overlaps content by design — the mocks' home hero runs full-bleed under it — but a
 * screen's *first, non-scrolling* row (the search field) would otherwise sit permanently under the
 * Cast and overflow buttons, so the frame keeps that band clear and lets the rest scroll under.
 *
 * Both variants add [Dimens.SpaceSmall] of clearance on top of the bar's own height. Reserving
 * *exactly* the bar meant a screen's first row came to rest touching the glass, so any rounding —
 * a shadow, a focus ring, a row whose own top padding was zero — read as an overlap.
 *
 * The animated values are **not read here**. This function used to destructure the two
 * `animateDpAsState`s and rebuild a `PaddingValues` per frame, which invalidated the whole
 * scaffold scope — the nav host, four `AnimatedVisibility` blocks and the snackbar host — on every
 * one of the transition's ~18 frames, and published a fresh object through the composition local
 * on each of them. Instead the two `State`s go into one stable [AnimatedChromePadding], whose
 * `calculate*` methods read them where `contentPadding` is actually consumed: inside a lazy
 * layout's measure pass, which is a snapshot-observing scope of its own. The animation then
 * invalidates layout, not composition.
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
 * How much of the bottom of the window the chrome covers — [chromePadding]'s `bottom`, split out as
 * a plain function of the four things it depends on so the arithmetic is unit-testable without a
 * device (the same reasoning [miniPlayerBottomOffset] and [useBottomNav] document).
 *
 * Two independent terms:
 * - the floating navigation pill, on a **top-level, compact** window only — its margin, its height,
 *   and the navigation-bar inset it floats above, none of which any screen applies itself;
 * - [MiniPlayer], **whatever the destination**, because the bar follows the queue rather than the
 *   back stack (see [showsMiniPlayer]).
 *
 * The mini-player's term is [MiniPlayerHeight] + [MiniPlayerGap] and deliberately carries no
 * navigation-bar inset of its own: it is the bar's extent *above* whatever is already at the bottom
 * edge, which is what [miniPlayerBottomOffset] docks it past. On a top-level compact window the
 * pill's term supplies the inset; on a pushed one the screen does, by the convention every pushed
 * screen in this app already follows (`LibraryGridScreen`), so the two never double-count it.
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
 * How far above whatever is already at the bottom edge [MiniPlayer] itself docks (M13 Phase 4).
 *
 * The same condition [chromePadding]'s `bottomTarget` gates its own contribution on: stacked above
 * the floating pill when one is showing ([BottomNavMargin] + [BottomNavHeight] plus [MiniPlayerGap]
 * of daylight between the two floating surfaces), flush against the window's bottom edge — just
 * [MiniPlayerGap] above the navigation-bar inset the bar's own `navigationBarsPadding()` already
 * consumes — everywhere else, which is the wide layout's "at the bottom edge" (spec wording).
 */
internal fun miniPlayerBottomOffset(
    isTopLevel: Boolean,
    bottomNav: Boolean,
): Dp = if (isTopLevel && bottomNav) BottomNavMargin + BottomNavHeight + MiniPlayerGap else MiniPlayerGap

/**
 * The [PaddingValues] published through [LocalAppChromePadding]: a stable identity whose top and
 * bottom are read from the two animated states at *use* — see [chromePadding] for why.
 */
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
 * Builds the action behind the chrome's offline status icon: a snackbar carrying the reason the app
 * is offline, and — for the two reasons the user can act on — the action that fixes it.
 *
 * The strings are resolved here rather than inside the click handler because a handler runs outside
 * composition, where `stringResource` is not available.
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
        // Repeated taps must replace the snackbar, not line up behind it: `showSnackbar` is a
        // mutex queue, so without the cancel each tap would append another Long-duration entry.
        // Cancelling the suspended call dismisses its snackbar (or drops it from the queue).
        pending.value?.cancel()
        pending.value =
            scope.launch {
                // `actionLabel` alone would default `duration` to Indefinite (M3's `showSnackbar`)
                // — the M9 device walk found the offline snackbar sitting over the last list row
                // for minutes. `Long` still leaves the action tappable, just not forever.
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
