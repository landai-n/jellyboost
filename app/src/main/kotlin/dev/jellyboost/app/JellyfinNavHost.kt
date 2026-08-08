package dev.jellyboost.app

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import dev.jellyboost.core.common.Routes
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.core.ui.theme.LocalHazeState
import dev.jellyboost.feature.auth.LoginScreen
import dev.jellyboost.feature.auth.ServerSetupScreen
import dev.jellyboost.feature.detail.ItemDetailScreen
import dev.jellyboost.feature.downloads.DownloadsScreen
import dev.jellyboost.feature.home.HomeActions
import dev.jellyboost.feature.home.HomeScreen
import dev.jellyboost.feature.library.LibraryGridScreen
import dev.jellyboost.feature.library.libraries.LibrariesScreen
import dev.jellyboost.feature.music.AlbumDetailScreen
import dev.jellyboost.feature.music.ArtistDetailScreen
import dev.jellyboost.feature.music.MusicLibraryScreen
import dev.jellyboost.feature.music.PlaylistDetailScreen
import dev.jellyboost.feature.music.nowplaying.NowPlayingScreen
import dev.jellyboost.feature.search.SearchScreen
import dev.jellyboost.feature.settings.SettingsScreen
import dev.jellyboost.player.syncplay.ui.SyncPlayGroupsScreen
import dev.jellyboost.player.ui.PlayerScreen
import timber.log.Timber

/**
 * How long a screen change takes, chrome included.
 *
 * One constant for the whole frame on purpose: [AppScaffold] animates the combined app bar and the
 * bottom inset over exactly this span, so a page that fades in while the bar expands stays in step
 * with it. It also replaces `NavHost`'s ~700ms default fade, which was slow enough that the app felt
 * unresponsive to a tab tap.
 */
internal const val NAV_TRANSITION_MILLIS = 300

/**
 * The app's navigation graph.
 *
 * `:app` resolves every `@HiltViewModel` via `hiltViewModel()` here and hands it to the feature
 * screen, which keeps the feature modules themselves free of a dependency on `:core:network`'s
 * Hilt component (docs/PLAN.md, "Project skeleton").
 *
 * @param startsSignedIn whether the session existed when the graph was first built.
 * @param sessionState live session; a flip to [SessionState.LoggedOut] from outside the auth
 *   flow (the Settings screen's sign-out today, a 401-driven logout later) pushes the user back to
 *   server setup.
 * @param modifier applied to the [NavHost] itself — [AppScaffold] uses it to reserve space for the
 *   combined app bar and the system navigation bar on the four top-level destinations, animating
 *   both over [NAV_TRANSITION_MILLIS] so the frame moves with the transitions below.
 */
@Suppress(
    // A flat table: one `composable<Route>` entry per destination, each a handful of lines binding a screen to its
    // arguments. The route list read top to bottom *is* the app's map, and splitting it into per-area hosts would hide
    // the one property worth checking at a glance — that it is complete.
    "LongMethod",
)
@Composable
internal fun JellyfinNavHost(
    startsSignedIn: Boolean,
    sessionState: SessionState,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    LogoutRedirectEffect(navController = navController, sessionState = sessionState)
    SyncPlayLaunchEffect(navController = navController)

    // Hoisted to the graph's level rather than resolved per destination: three music screens
    // share these two verbs, and the queue behind them is one process-wide singleton anyway.
    val music: MusicPlaybackViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = if (startsSignedIn) Routes.Home else Routes.ServerSetup,
        modifier = modifier,
        // A plain cross-fade, but on the frame's clock rather than the default's — see
        // [NAV_TRANSITION_MILLIS]. Push and pop look the same because the chrome they animate
        // alongside has no direction either.
        enterTransition = { fadeIn(tween(NAV_TRANSITION_MILLIS)) },
        exitTransition = { fadeOut(tween(NAV_TRANSITION_MILLIS)) },
        popEnterTransition = { fadeIn(tween(NAV_TRANSITION_MILLIS)) },
        popExitTransition = { fadeOut(tween(NAV_TRANSITION_MILLIS)) },
    ) {
        composable<Routes.ServerSetup> {
            ServerSetupScreen(
                onNavigateToLogin = { navController.navigate(Routes.Login) },
            )
        }

        composable<Routes.Login> {
            LoginScreen(
                onLoggedIn = { navController.navigateClearingBackStack(Routes.Home) },
                onBackToServerSetup = { navController.popBackStack() },
            )
        }

        composable<Routes.Home> {
            HomeScreen(
                viewModel = hiltViewModel(),
                actions =
                    HomeActions(
                        onItemClick = { item -> navController.navigateToItem(item) },
                        onPlay = { itemId, startPositionTicks ->
                            navController.navigate(
                                Routes.Player(itemId = itemId, startPositionTicks = startPositionTicks),
                            )
                        },
                        onLibraryClick = { library -> navController.navigateToLibrary(library) },
                        // A tab switch, not a push: the Downloads chip lands on the Downloads tab
                        // exactly as its button in the nav bar does, back stack and all.
                        onOpenDownloads = { navController.navigateToTab(Routes.Downloads) },
                        // Continue Listening (M13 Phase 4): starts the music queue at the saved
                        // position, rather than navigating anywhere — the mini-player is where the
                        // result shows up.
                        onPlayTrack = music::playResumed,
                    ),
            )
        }

        composable<Routes.Libraries> {
            LibrariesScreen(
                viewModel = hiltViewModel(),
                onLibraryClick = { library -> navController.navigateToLibrary(library) },
            )
        }

        composable<Routes.Search> {
            SearchScreen(
                viewModel = hiltViewModel(),
                onItemClick = { item -> navController.navigateToItem(item) },
            )
        }

        composable<Routes.Downloads> {
            DownloadsScreen(
                viewModel = hiltViewModel(),
                onPlay = { itemId, startPositionTicks, item ->
                    // A downloaded track goes to the music queue, not the immersive video screen
                    // (M13): Routes.Player cannot play audio, and the queue is what gives the tap
                    // its album context, mini-player and notification. Only when the cached item
                    // was wiped (type unknowable) does the video route remain the fallback.
                    if (item != null && item.type == ItemType.AUDIO) {
                        music.playDownloadedAudio(item, startPositionTicks)
                    } else {
                        navController.navigate(
                            Routes.Player(itemId = itemId, startPositionTicks = startPositionTicks),
                        )
                    }
                },
            )
        }

        composable<Routes.Settings> {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
                appVersion = BuildConfig.VERSION_NAME,
            )
        }

        composable<Routes.LibraryGrid> {
            LibraryGridScreen(
                viewModel = hiltViewModel(),
                onItemClick = { item -> navController.navigateToItem(item) },
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.ItemDetail> {
            ItemDetailScreen(
                viewModel = hiltViewModel(),
                onItemClick = { item -> navController.navigateToItem(item) },
                onPlay = { itemId, startPositionTicks ->
                    navController.navigate(
                        Routes.Player(itemId = itemId, startPositionTicks = startPositionTicks),
                    )
                },
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        // M13 Phase 2 — music
        composable<Routes.MusicLibrary> {
            MusicLibraryScreen(
                viewModel = hiltViewModel(),
                onAlbumClick = { item -> navController.navigateToItem(item) },
                onArtistClick = { item -> navController.navigateToItem(item) },
                onPlaylistClick = { item -> navController.navigateToItem(item) },
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.AlbumDetail> {
            AlbumDetailScreen(
                viewModel = hiltViewModel(),
                onArtistClick = { item -> navController.navigateToItem(item) },
                onPlay = music::play,
                onShuffle = music::shuffle,
                onStartRadio = music::startRadio,
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.ArtistDetail> {
            ArtistDetailScreen(
                viewModel = hiltViewModel(),
                onAlbumClick = { item -> navController.navigateToItem(item) },
                onTrackClick = music::play,
                onStartRadio = music::startRadio,
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.PlaylistDetail> {
            PlaylistDetailScreen(
                viewModel = hiltViewModel(),
                onTrackClick = music::play,
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.NowPlaying> {
            NowPlayingScreen(
                viewModel = hiltViewModel(),
                onArtistClick = { item -> navController.navigateToItem(item) },
                onStartRadio = music::startRadio,
                onBack = { navController.popBackStack() },
            )
        }

        composable<Routes.Player> {
            // No backdrop on the player, by construction. `AppScaffold` detaches the `hazeSource`
            // here (the video is a SurfaceView whose pixels never reach the recorded layer, so a
            // blur would sample black at a per-frame capture cost), and a Haze effect whose state
            // has no source draws *nothing* — every glass control would turn transparent. Nulling
            // the local instead routes the player's glass onto `glassSurface`'s documented
            // fallback: a flat fill of whatever tint the control asked for.
            CompositionLocalProvider(LocalHazeState provides null) {
                PlayerScreen(onBack = { navController.popBackStack() })
            }
        }

        composable<Routes.SyncPlay> {
            SyncPlayGroupsScreen(
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
                onOpenPlayer = { itemId, startPositionTicks ->
                    navController.navigate(
                        Routes.Player(itemId = itemId, startPositionTicks = startPositionTicks),
                    )
                },
            )
        }
    }
}

/**
 * Sends the user back to server setup whenever the session disappears while they are on a
 * signed-in destination.
 *
 * Written against the live session rather than hooked onto the sign-out button so that it also
 * covers logouts the UI did not ask for — a rejected token at M6, for instance. Destinations
 * inside the auth flow are exempt: being logged out there is the normal state of affairs.
 */
@Composable
private fun LogoutRedirectEffect(
    navController: NavHostController,
    sessionState: SessionState,
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val destination = currentEntry?.destination
    val inAuthFlow =
        destination?.hasRoute<Routes.ServerSetup>() == true || destination?.hasRoute<Routes.Login>() == true

    LaunchedEffect(sessionState, destination) {
        if (sessionState is SessionState.LoggedOut && destination != null && !inAuthFlow) {
            navController.navigateClearingBackStack(Routes.ServerSetup)
        }
    }
}

/**
 * Collects `SyncPlayController.launchRequests` and opens the player for whatever the group moved
 * on to — the other half of M11 key decision 5: membership survives leaving the player screen, so
 * a `PlayQueueUpdate` can arrive while nobody has one open, and this is what catches up.
 *
 * A global effect at the NavHost's own level, the same way [LogoutRedirectEffect] is: neither is
 * owned by any one destination, both react to state a `@Singleton` holds regardless of what is on
 * screen.
 *
 * ### Duplicate-navigation guard
 * The controller itself never emits a launch request while a player is attached
 * (`SyncPlayController.reconcile`) — the ordinary case where the group and this device already
 * agree is a reload in place, not a nav event. This still guards the current destination before
 * navigating, for the rarer case of a launch request arriving just as the app is already sitting on
 * the player (a race between the effect resubscribing and the destination changing): a second
 * `Routes.Player` push for it would stack a duplicate screen rather than the harmless no-op the
 * controller intended. `launchSingleTop` on the navigation itself is the second layer — even a
 * request that slips past the check collapses onto the existing entry instead of stacking a new one.
 */
@Composable
private fun SyncPlayLaunchEffect(navController: NavHostController) {
    val viewModel: SyncPlayLaunchViewModel = hiltViewModel()
    val currentEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(viewModel) {
        viewModel.launchRequests.collect { request ->
            // Consumed first, acted-on or ignored alike: the flow replays its last request to
            // whichever collector comes next (audit SP-12), and both outcomes below are this
            // effect *handling* it — a replay after either would navigate twice.
            viewModel.consume()
            if (currentEntry?.destination?.hasRoute<Routes.Player>() == true) {
                Timber.d("Ignoring a SyncPlay launch request while already on the player")
                return@collect
            }
            navController.navigate(
                Routes.Player(itemId = request.itemId.toString(), startPositionTicks = request.startPositionTicks),
                navOptions { launchSingleTop = true },
            )
        }
    }
}

/** Navigates to [route] and drops everything behind it — used at both ends of the auth flow. */
private fun NavController.navigateClearingBackStack(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * A library tile was tapped — the one fork between the movie/TV library grid and M13's music
 * library screen (docs/notes/music-m13-plan.md, Phase 2 spec item 6).
 */
private fun NavController.navigateToLibrary(library: LibraryView) {
    if (library.collectionType == CollectionKind.MUSIC) {
        navigate(Routes.MusicLibrary(library.id, library.name))
    } else {
        navigate(Routes.LibraryGrid(library.id, library.name))
    }
}

/**
 * The one place an item click becomes a destination, for every screen that can now hand this a
 * music item as easily as a movie or a series (Home, the library grid, item detail's own related
 * rows, search) — M13 Phase 2 spec item 6: "item clicks branch on type: MUSIC_ALBUM → AlbumDetail,
 * MUSIC_ARTIST → ArtistDetail, PLAYLIST → PlaylistDetail, AUDIO → its album's detail if albumId
 * present else no-op for now."
 *
 * A track with no `albumId` at all (a lone upload, a malformed server response) does nothing
 * rather than opening a broken album page — there is nowhere useful for a tap on it to go before
 * M13 Phase 3 gives a track its own now-playing destination.
 */
private fun NavController.navigateToItem(item: JellyfinItem) {
    when (item.type) {
        ItemType.MUSIC_ALBUM -> navigate(Routes.AlbumDetail(item.id))
        ItemType.MUSIC_ARTIST -> navigate(Routes.ArtistDetail(item.id))
        ItemType.PLAYLIST -> navigate(Routes.PlaylistDetail(item.id))
        ItemType.AUDIO -> item.albumId?.let { albumId -> navigate(Routes.AlbumDetail(albumId)) }
        else -> navigate(Routes.ItemDetail(item.id))
    }
}
