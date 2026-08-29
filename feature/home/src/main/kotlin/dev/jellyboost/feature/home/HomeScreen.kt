// The per-section `LazyListScope` extensions are not `@Composable`, so the rule's Composable
// exemption cannot reach them. Suppressed here rather than raising the global `thresholdInFiles`.
@file:Suppress("TooManyFunctions")

package dev.jellyboost.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.CollectionKind
import dev.jellyboost.core.common.model.HomeSectionType
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.component.AlbumCard
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.LIBRARY_CARD_CONTENT_TYPE
import dev.jellyboost.core.ui.component.LibraryCard
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.component.POSTER_CARD_CONTENT_TYPE
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.THUMB_CARD_CONTENT_TYPE
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.component.libraryIcon
import dev.jellyboost.core.ui.text.episodeNumberLabel
import dev.jellyboost.core.ui.text.resolve
import dev.jellyboost.core.ui.theme.ChromeBackdrop
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.core.ui.theme.LocalChromeBackdrop
import dev.jellyboost.core.ui.theme.OverMedia
import dev.jellyboost.core.ui.theme.pageInk
import dev.jellyboost.core.ui.R as CoreUiR

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onRetry = viewModel::refresh,
        actions = actions,
        modifier = modifier,
    )
}

/**
 * @param onPlay start position is in Jellyfin ticks; `:app` turns this into a `Routes.Player` nav.
 * @param onPlayTrack starts the music queue in place rather than navigating — not an [onPlay] overload.
 */
data class HomeActions(
    val onItemClick: (JellyfinItem) -> Unit,
    val onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    val onLibraryClick: (LibraryView) -> Unit,
    val onOpenDownloads: () -> Unit,
    val onPlayTrack: (JellyfinItem) -> Unit,
)

@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState(modifier = modifier)

        // Both states replace what the reader was on, so they must announce or the swap is silent.
        state.errorMessage != null ->
            ErrorState(
                message = state.errorMessage.resolve(),
                modifier = modifier,
                onRetry = onRetry,
                announce = LiveRegionMode.Assertive,
            )

        state.isEmpty ->
            EmptyState(
                message = stringResource(R.string.home_empty_message),
                modifier = modifier,
                actionLabel = stringResource(R.string.home_empty_refresh),
                onAction = onRetry,
                announce = LiveRegionMode.Polite,
            )

        else -> HomeRows(state = state, actions = actions, modifier = modifier)
    }
}

/**
 * Home is the designated exception to `LocalAppChromePadding`: with a hero it consumes only the
 * chrome's *bottom* padding, so the banner runs full-bleed under the status bar and action cluster.
 * With no hero the top padding is consumed as usual.
 */
@Composable
private fun HomeRows(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    // One `BoxWithConstraints` for the whole screen, not per card: one subcomposition, not hundreds.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cardWidth = homeThumbCardWidth(maxWidth)
        val wide = isWideHome(maxWidth = maxWidth, maxHeight = maxHeight)
        val hero = state.resume.firstOrNull()
        // Must stay remembered: dropping inline in the lazy builder hands `MediaRow` a fresh,
        // never-equal list every recomposition, defeating skipping on the most-patched row.
        val resumeAfterHero = remember(state.resume) { state.resume.drop(1) }
        val chrome = LocalAppChromePadding.current
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val listState = rememberLazyListState()
        val heroHeight = heroHeight(wide = wide, viewportHeight = maxHeight, fontScale = fontScale)
        ChromeOverHeroEffect(
            chromeBackdrop = LocalChromeBackdrop.current,
            listState = listState,
            heroHeight = if (hero == null) 0.dp else heroHeight,
            chrome = chrome,
            density = density,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    top = if (hero != null) 0.dp else Dimens.SpaceLarge + chrome.calculateTopPadding(),
                    bottom = Dimens.SpaceLarge + chrome.calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
        ) {
            heroRow(
                hero = hero,
                wide = wide,
                height = heroHeight,
                actions = actions,
            )

            sectionRows(
                state = state,
                actions = actions,
                wide = wide,
                cardWidth = cardWidth,
                resumeAfterHero = resumeAfterHero,
            )
        }
    }
}

/**
 * Row order is the user's, from the server. Unsupported sections are skipped here rather than
 * dropped upstream, so hiding one in jellyfin-web still reorders its neighbours.
 */
private fun LazyListScope.sectionRows(
    state: HomeUiState,
    actions: HomeActions,
    wide: Boolean,
    cardWidth: Dp,
    resumeAfterHero: List<JellyfinItem>,
) {
    var librariesDrawn = false
    state.sections.forEach { section ->
        when (section) {
            // Both spellings of *My Media* are one row here — two items under one key would crash
            // the lazy list.
            HomeSectionType.SMALL_LIBRARY_TILES, HomeSectionType.LIBRARY_BUTTONS ->
                if (!librariesDrawn) {
                    librariesDrawn = true
                    myMediaRow(state, actions, wide, cardWidth)
                }

            HomeSectionType.RESUME ->
                resumeRow(
                    items = resumeAfterHero,
                    actions = actions,
                    cardWidth = cardWidth,
                )

            HomeSectionType.NEXT_UP -> nextUpRow(state, actions, cardWidth)
            HomeSectionType.LATEST_MEDIA -> latestRows(state, actions)
            HomeSectionType.RESUME_AUDIO -> resumeAudioRow(state, actions)

            HomeSectionType.NONE,
            HomeSectionType.ACTIVE_RECORDINGS,
            HomeSectionType.RESUME_BOOK,
            HomeSectionType.LIVE_TV,
            -> Unit
        }
    }
}

/**
 * Home is the only screen that draws full-bleed artwork under the app frame's chrome, and the chrome
 * is a sibling of the nav host, so this is the one direction the answer can travel. It is a *scroll*
 * question, not a destination one: the moment the hero's foot passes the chrome's, the ground under
 * the bars is page again and the glass must go back to following the scheme.
 *
 * `derivedStateOf` so the per-pixel scroll and the animating chrome inset move a boolean rather than
 * a recomposition, and `onDispose` so leaving Home — or Home losing its hero — cannot strand the
 * frame in the over-media state.
 */
@Composable
private fun ChromeOverHeroEffect(
    chromeBackdrop: ChromeBackdrop,
    listState: LazyListState,
    heroHeight: Dp,
    chrome: PaddingValues,
    density: Density,
) {
    val overMedia =
        remember(listState, heroHeight, chrome, density) {
            derivedStateOf {
                listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset <
                    with(density) {
                        (heroHeight - chrome.calculateTopPadding()).coerceAtLeast(0.dp).roundToPx()
                    }
            }
        }
    LaunchedEffect(chromeBackdrop, overMedia) {
        snapshotFlow { overMedia.value }.collect(chromeBackdrop::reportOverMedia)
    }
    DisposableEffect(chromeBackdrop) {
        onDispose { chromeBackdrop.reportOverMedia(false) }
    }
}

/**
 * Lays out [amount] shorter than it draws, so the next lazy item overlaps its bottom edge. Only safe
 * on content whose bottom band has already faded to the app background — which the hero's does only
 * where [OverMedia.artworkDissolvesIntoPage].
 */
private fun Modifier.reportShorterBy(amount: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height - amount.roundToPx()).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

private fun LazyListScope.heroRow(
    hero: JellyfinItem?,
    wide: Boolean,
    height: Dp,
    actions: HomeActions,
) {
    if (hero == null) return
    item(key = SECTION_HERO, contentType = ROW_HERO) {
        val overlaps = wide && OverMedia.artworkDissolvesIntoPage
        HomeHero(
            item = hero,
            wide = wide,
            height = height,
            onResume = { actions.onPlay(hero.id, hero.userData.playbackPositionTicks) },
            onDetails = { actions.onItemClick(hero) },
            // A lazy list cannot offset the following item, only shorten this one.
            modifier =
                if (overlaps) {
                    Modifier.reportShorterBy(HeroRailOverlap + Dimens.SpaceExtraLarge)
                } else {
                    Modifier
                },
        )
    }
}

private fun LazyListScope.myMediaRow(
    state: HomeUiState,
    actions: HomeActions,
    wide: Boolean,
    cardWidth: Dp,
) {
    if (wide) {
        librariesRow(state, actions, cardWidth)
    } else {
        quickAccessRow(state, actions)
    }
}

private fun LazyListScope.librariesRow(
    state: HomeUiState,
    actions: HomeActions,
    cardWidth: Dp,
) {
    if (state.libraries.isEmpty()) return
    item(key = SECTION_MY_MEDIA, contentType = ROW_LIBRARIES) {
        MediaRow(
            title = stringResource(R.string.home_section_my_media),
            items = state.libraries,
            key = LibraryView::id,
            contentType = LIBRARY_CARD_CONTENT_TYPE,
        ) { library ->
            LibraryCard(
                library = library,
                onClick = { actions.onLibraryClick(library) },
                width = cardWidth,
                // Null for a library rebuilt from the offline cache, which stores no count.
                subtitle =
                    library.itemCount?.let { count ->
                        pluralStringResource(CoreUiR.plurals.library_item_count, count, count)
                    },
            )
        }
    }
}

private fun LazyListScope.quickAccessRow(
    state: HomeUiState,
    actions: HomeActions,
) {
    if (state.libraries.isEmpty()) return
    item(key = SECTION_MY_MEDIA, contentType = ROW_QUICK_ACCESS) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        ) {
            items(
                items = state.libraries,
                key = LibraryView::id,
                contentType = { CHIP_QUICK_ACCESS },
            ) { library ->
                QuickAccessChip(
                    icon = libraryIcon(library.collectionType),
                    label = library.name,
                    onClick = { actions.onLibraryClick(library) },
                )
            }
            item(key = CHIP_OFFLINE, contentType = CHIP_QUICK_ACCESS) {
                QuickAccessChip(
                    icon = Icons.Outlined.Download,
                    label = stringResource(R.string.home_quick_access_offline),
                    onClick = actions.onOpenDownloads,
                )
            }
        }
    }
}

/**
 * Flat `GlassDefaults.Fill`, never `Modifier.glassSurface`: a real blur samples `AppScaffold`'s haze
 * source, which is the content this chip scrolls inside — it would be blurring itself.
 */
@Composable
private fun QuickAccessChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                // Minimum, never a fixed height: 38dp around a 13sp label clips at large font scales.
                .heightIn(min = QuickAccessChipHeight)
                .background(color = GlassDefaults.Fill, shape = CircleShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = QuickAccessChipPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = QuickAccessChipIconTint,
            modifier = Modifier.size(QuickAccessChipIconSize),
        )
        Text(
            text = label,
            style = QuickAccessChipLabel,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun LazyListScope.resumeRow(
    items: List<JellyfinItem>,
    actions: HomeActions,
    cardWidth: Dp,
) {
    if (items.isEmpty()) return
    item(key = SECTION_RESUME, contentType = ROW_THUMBS) {
        MediaRow(
            title = stringResource(R.string.home_section_continue_watching),
            items = items,
            key = JellyfinItem::id,
            contentType = THUMB_CARD_CONTENT_TYPE,
        ) { item ->
            ThumbCard(
                item = item,
                onClick = { actions.onItemClick(item) },
                width = cardWidth,
                topStartBadge = item.episodeNumberLabel(),
                timeChipText = item.remainingMinutes?.let { stringResource(R.string.home_time_left_short, it) },
            )
        }
    }
}

private fun LazyListScope.nextUpRow(
    state: HomeUiState,
    actions: HomeActions,
    cardWidth: Dp,
) {
    if (state.nextUp.isEmpty()) return
    item(key = SECTION_NEXT_UP, contentType = ROW_THUMBS) {
        MediaRow(
            title = stringResource(R.string.home_section_next_up),
            items = state.nextUp,
            key = JellyfinItem::id,
            contentType = THUMB_CARD_CONTENT_TYPE,
        ) { item ->
            // No time chip: nothing in *Next up* has been started.
            ThumbCard(
                item = item,
                onClick = { actions.onItemClick(item) },
                width = cardWidth,
                topStartBadge = item.episodeNumberLabel(),
            )
        }
    }
}

/** A tap resumes the track rather than opening a detail page — there is no `ItemDetail` for tracks. */
private fun LazyListScope.resumeAudioRow(
    state: HomeUiState,
    actions: HomeActions,
) {
    if (state.resumeAudio.isEmpty()) return
    item(key = SECTION_RESUME_AUDIO, contentType = ROW_ALBUMS) {
        MediaRow(
            title = stringResource(R.string.home_section_continue_listening),
            items = state.resumeAudio,
            key = JellyfinItem::id,
            contentType = CARD_ALBUM,
        ) { item ->
            AlbumCard(item = item, onClick = { actions.onPlayTrack(item) })
        }
    }
}

private fun LazyListScope.latestRows(
    state: HomeUiState,
    actions: HomeActions,
) {
    items(
        items = state.latest,
        key = { it.library.id },
        contentType = { ROW_POSTERS },
    ) { section ->
        MediaRow(
            title = stringResource(R.string.home_section_latest, section.library.name),
            items = section.items,
            key = JellyfinItem::id,
            contentType = POSTER_CARD_CONTENT_TYPE,
            onSeeAll = { actions.onLibraryClick(section.library) },
        ) { item ->
            PosterCard(item = item, onClick = { actions.onItemClick(item) })
        }
    }
}

/** The standard compact/medium width-class boundary — deliberately not the chrome's own 560dp. */
private val COMPACT_MAX_WIDTH = 600.dp

/** Rules out landscape phones: the wide hero's 104dp copy inset leaves no room above the first row. */
private val WIDE_MIN_HEIGHT = 560.dp

/**
 * Sized so two full cards plus a peek fit the narrowest 360dp phone: 16 + 160 + 12 + 160 = 348dp.
 * These rows don't reflow, so the 12dp sliver is the only scroll affordance.
 */
private val COMPACT_THUMB_WIDTH = 160.dp

internal fun homeThumbCardWidth(maxWidth: Dp): Dp =
    if (maxWidth < COMPACT_MAX_WIDTH) COMPACT_THUMB_WIDTH else Dimens.ThumbWidth

internal fun isWideHome(
    maxWidth: Dp,
    maxHeight: Dp,
): Boolean = maxWidth >= COMPACT_MAX_WIDTH && maxHeight >= WIDE_MIN_HEIGHT

/**
 * The base heights are the mocks' figures; the viewport-fraction ceiling keeps a small or
 * split-screen window from being one enormous picture. Both grow with the font scale — a 2.0×
 * device asking a 460dp banner to hold 615dp of lockup would clip the hero's own buttons.
 */
internal fun heroHeight(
    wide: Boolean,
    viewportHeight: Dp,
    fontScale: Float = 1f,
): Dp {
    val base = if (wide) WIDE_HERO_HEIGHT else COMPACT_HERO_HEIGHT
    val lockupText = if (wide) WideLockupText else CompactLockupText
    val growth = textGrowth(fontScale)
    // The ceiling relaxes with the scale: at 2.0× the rows below are twice as tall too, so holding
    // the same fraction would buy a glimpse of one row at the price of a clipped play button.
    val fraction =
        HERO_MAX_VIEWPORT_FRACTION +
            (HERO_MAX_VIEWPORT_FRACTION_LARGE - HERO_MAX_VIEWPORT_FRACTION) * growth.coerceAtMost(1f)
    return (base + lockupText * growth).coerceAtMost(viewportHeight * fraction)
}

private val COMPACT_HERO_HEIGHT = 460.dp

private val WIDE_HERO_HEIGHT = 400.dp

private const val HERO_MAX_VIEWPORT_FRACTION = 0.6f

private const val HERO_MAX_VIEWPORT_FRACTION_LARGE = 0.75f

private val QuickAccessChipHeight = 38.dp

private val QuickAccessChipPadding = 14.dp

private val QuickAccessChipIconSize = 16.dp

private val QuickAccessChipIconTint: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.75f)

private val QuickAccessChipLabel =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
    )

private const val SECTION_HERO = "section-hero"
private const val SECTION_MY_MEDIA = "section-my-media"
private const val SECTION_RESUME = "section-resume"
private const val SECTION_NEXT_UP = "section-next-up"
private const val SECTION_RESUME_AUDIO = "section-resume-audio"

private const val ROW_HERO = "row-hero"
private const val ROW_LIBRARIES = "row-libraries"
private const val ROW_QUICK_ACCESS = "row-quick-access"
private const val ROW_THUMBS = "row-thumbs"
private const val ROW_POSTERS = "row-posters"
private const val ROW_ALBUMS = "row-albums"
private const val CARD_ALBUM = "card-album"
private const val CHIP_QUICK_ACCESS = "chip-quick-access"
private const val CHIP_OFFLINE = "chip-offline"

private val PreviewMovies =
    LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES)

private val PreviewShows =
    LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS)

private val PreviewResume =
    listOf(
        JellyfinItem(
            id = "e1",
            name = "The Bicameral Mind",
            type = ItemType.EPISODE,
            overview =
                "Ford unveils his new narrative while Maeve makes her escape and " +
                    "Dolores finally reaches the centre of the maze.",
            officialRating = "TV-MA",
            seriesName = "Westworld",
            indexNumber = 10,
            parentIndexNumber = 1,
            runTimeTicks = 54_000_000_000L,
            userData = UserData(playbackPositionTicks = 20_000_000_000L),
        ),
        JellyfinItem(
            id = "e3",
            name = "Trace Decay",
            type = ItemType.EPISODE,
            seriesName = "Westworld",
            indexNumber = 8,
            parentIndexNumber = 1,
            runTimeTicks = 60_000_000_000L,
            userData = UserData(playbackPositionTicks = 12_000_000_000L),
        ),
    )

private val PreviewNextUp =
    listOf(
        JellyfinItem(
            id = "e2",
            name = "Journey Into Night",
            type = ItemType.EPISODE,
            seriesName = "Westworld",
            indexNumber = 1,
            parentIndexNumber = 2,
        ),
    )

private val PreviewLatest =
    listOf(
        LatestSection(
            library = PreviewMovies,
            items =
                listOf(
                    JellyfinItem(id = "m1", name = "Dune", type = ItemType.MOVIE, productionYear = 2021),
                    JellyfinItem(id = "m2", name = "Arrival", type = ItemType.MOVIE, productionYear = 2016),
                ),
        ),
    )

private fun previewState(withResume: Boolean): HomeUiState =
    HomeUiState(
        isLoading = false,
        libraries = listOf(PreviewMovies, PreviewShows),
        resume = if (withResume) PreviewResume else emptyList(),
        nextUp = PreviewNextUp,
        latest = PreviewLatest,
    )

private val PreviewActions =
    HomeActions(
        onItemClick = {},
        onPlay = { _, _ -> },
        onLibraryClick = {},
        onOpenDownloads = {},
        onPlayTrack = {},
    )

@Preview(name = "Home — hero", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 900)
@Composable
private fun HomeContentPreview() {
    JellyfinTheme {
        HomeContent(state = previewState(withResume = true), onRetry = {}, actions = PreviewActions)
    }
}

@Preview(name = "Home — no hero", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 900)
@Composable
private fun HomeContentNoHeroPreview() {
    JellyfinTheme {
        HomeContent(state = previewState(withResume = false), onRetry = {}, actions = PreviewActions)
    }
}

@Preview(
    name = "Home — hero (wide)",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 960,
    heightDp = 720,
)
@Composable
private fun HomeContentWidePreview() {
    JellyfinTheme {
        HomeContent(state = previewState(withResume = true), onRetry = {}, actions = PreviewActions)
    }
}

@Preview(
    name = "Home — no hero (wide)",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 960,
    heightDp = 720,
)
@Composable
private fun HomeContentWideNoHeroPreview() {
    JellyfinTheme {
        HomeContent(state = previewState(withResume = false), onRetry = {}, actions = PreviewActions)
    }
}
