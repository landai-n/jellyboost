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
import androidx.navigation.NavGraphBuilder
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
import dev.jellyboost.feature.settings.LicenceScreen
import dev.jellyboost.feature.settings.SettingsAccountScreen
import dev.jellyboost.feature.settings.SettingsCategory
import dev.jellyboost.feature.settings.SettingsCategoryScreen
import dev.jellyboost.feature.settings.SettingsScreen
import dev.jellyboost.feature.settings.ThirdPartyLicencesScreen
import dev.jellyboost.player.syncplay.ui.SyncPlayGroupsScreen
import dev.jellyboost.player.ui.PlayerScreen
import timber.log.Timber

/**
 * One constant for the whole frame: [AppScaffold] animates the chrome and the insets it publishes
 * over exactly this span, so a page fading in stays in step with the bars. Also replaces `NavHost`'s
 * ~700ms default fade, which felt unresponsive to a tab tap.
 */
internal const val NAV_TRANSITION_MILLIS = 300

/**
 * The app's navigation graph. Every `@HiltViewModel` is resolved here and handed to the feature
 * screen, which keeps the feature modules free of a dependency on `:core:network`'s Hilt component.
 */
@Suppress(
    // The route list read top to bottom *is* the app's map; per-area hosts would hide whether it is complete.
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

    // Hoisted rather than resolved per destination: the queue behind it is a process-wide singleton.
    val music: MusicPlaybackViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = if (startsSignedIn) Routes.Home else Routes.ServerSetup,
        modifier = modifier,
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
                        onPlay = { item ->
                            when (playbackRouteFor(item.type)) {
                                PlaybackRoute.MUSIC_QUEUE -> music.playResumed(item)
                                PlaybackRoute.VIDEO_PLAYER ->
                                    navController.navigate(
                                        Routes.Player(
                                            itemId = item.id,
                                            startPositionTicks = item.userData.playbackPositionTicks,
                                        ),
                                    )
                            }
                        },
                        onLibraryClick = { library -> navController.navigateToLibrary(library) },
                        // A tab switch, not a push: back stack and all, exactly as the nav bar's button.
                        onOpenDownloads = { navController.navigateToTab(Routes.Downloads) },
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
                    // A wiped cache row leaves the kind unknowable, and only then does the video
                    // route act as the fallback.
                    val route = item?.let { playbackRouteFor(it.type) } ?: PlaybackRoute.VIDEO_PLAYER
                    if (item != null && route == PlaybackRoute.MUSIC_QUEUE) {
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
                onOpenCategory = { navController.navigate(it.route()) },
                onOpenAccount = { navController.navigate(Routes.SettingsAccount) },
                onOpenLicence = { navController.navigate(Routes.Licence) },
                onOpenThirdPartyLicences = { navController.navigate(Routes.ThirdPartyLicences) },
                appVersion = BuildConfig.VERSION_NAME,
            )
        }

        composable<Routes.SettingsAccount> {
            SettingsAccountScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                appVersion = BuildConfig.VERSION_NAME,
            )
        }

        // One destination per category rather than one carrying the category as an argument: each is
        // its own deep-linkable screen, and a route with no arguments cannot be navigated to with a
        // category that does not exist. A wide window never reaches these — there the category is
        // saveable state on `Routes.Settings` — but a pushed one survives the rotation into it.
        settingsCategory<Routes.SettingsPlayback>(navController, SettingsCategory.PLAYBACK)
        settingsCategory<Routes.SettingsDownloads>(navController, SettingsCategory.DOWNLOADS)
        settingsCategory<Routes.SettingsAppearance>(navController, SettingsCategory.APPEARANCE)
        settingsCategory<Routes.SettingsNetwork>(navController, SettingsCategory.NETWORK)
        settingsCategory<Routes.SettingsAbout>(navController, SettingsCategory.ABOUT)

        composable<Routes.Licence> {
            LicenceScreen(
                viewModel = hiltViewModel(),
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
            )
        }

        composable<Routes.ThirdPartyLicences> {
            ThirdPartyLicencesScreen(
                // The AboutLibraries Gradle plugin generates this from `:app`'s resolved graph, which
                // is the one that ships; `:feature:settings` cannot name it.
                librariesRawResId = R.raw.aboutlibraries,
                onBack = { navController.popBackStack() },
                onHome = { navController.navigateHome() },
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
                onNavigateToItemId = { id -> navController.navigate(Routes.ItemDetail(id)) },
            )
        }

        // Music
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
            // `AppScaffold` detaches the `hazeSource` here, and a Haze effect whose state has no
            // source draws *nothing* — every glass control would turn transparent. Nulling the local
            // routes them onto `glassSurface`'s flat-fill fallback instead.
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
 * Written against the live session rather than the sign-out button so it also covers logouts the UI
 * did not ask for, such as a rejected token. The auth flow is exempt: logged out is normal there.
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
 * Opens the player for whatever the group moved on to: membership survives leaving the player screen,
 * so a `PlayQueueUpdate` can arrive while nobody has one open. A global effect because no one
 * destination owns it.
 *
 * The destination check and `launchSingleTop` are two layers of the same guard: a launch request
 * arriving while the app already sits on the player must not stack a duplicate screen.
 */
@Composable
private fun SyncPlayLaunchEffect(navController: NavHostController) {
    val viewModel: SyncPlayLaunchViewModel = hiltViewModel()
    val currentEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(viewModel) {
        viewModel.launchRequests.collect { request ->
            // Consumed first, acted-on or ignored alike: the flow replays its last request to the
            // next collector, and a replay after either outcome would navigate twice.
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

/**
 * The compact settings path: one destination per category, each drawing the same page body a wide
 * window draws in its pane. Reified so the route stays type-safe while the five registrations below
 * stay one line each.
 */
private inline fun <reified T : Any> NavGraphBuilder.settingsCategory(
    navController: NavHostController,
    category: SettingsCategory,
) = composable<T> {
    SettingsCategoryScreen(
        viewModel = hiltViewModel(),
        category = category,
        onBack = { navController.popBackStack() },
        onOpenLicence = { navController.navigate(Routes.Licence) },
        onOpenThirdPartyLicences = { navController.navigate(Routes.ThirdPartyLicences) },
        appVersion = BuildConfig.VERSION_NAME,
    )
}

/** The hub's row-to-destination map; the only place a category becomes a route. */
private fun SettingsCategory.route(): Any =
    when (this) {
        SettingsCategory.PLAYBACK -> Routes.SettingsPlayback
        SettingsCategory.DOWNLOADS -> Routes.SettingsDownloads
        SettingsCategory.APPEARANCE -> Routes.SettingsAppearance
        SettingsCategory.NETWORK -> Routes.SettingsNetwork
        SettingsCategory.ABOUT -> Routes.SettingsAbout
    }

/** Navigates to [route] and drops everything behind it — used at both ends of the auth flow. */
private fun NavController.navigateClearingBackStack(route: Any) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

private fun NavController.navigateToLibrary(library: LibraryView) {
    if (library.collectionType == CollectionKind.MUSIC) {
        navigate(Routes.MusicLibrary(library.id, library.name))
    } else {
        navigate(Routes.LibraryGrid(library.id, library.name))
    }
}

/**
 * The one place an item click becomes a destination. A track with no `albumId` deliberately does
 * nothing rather than opening a broken album page.
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
