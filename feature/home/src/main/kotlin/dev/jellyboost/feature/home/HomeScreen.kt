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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.LibraryCard
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.component.libraryIcon
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The home screen: the app's landing destination, mirroring jellyfin-web's row order so a
 * side-by-side comparison shows the same sections, items and ordering (the M2 definition of done).
 *
 * Since the 2026 refresh the first *Continue watching* item is promoted out of its row into the
 * full-bleed [HomeHero] at the top of the list, and — on a compact layout — the *My Media* row
 * becomes a row of quick-access chips (DECISIONS.md 2026-08-01, spec section 4a). Both are
 * presentations of rows the screen already had: no item and no destination is added or lost.
 *
 * The [HomeViewModel] is passed in rather than resolved here so that `:app` owns the
 * `hiltViewModel()` call together with the rest of the navigation graph wiring — see
 * `HomeRoute` in `:app`.
 */
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
 * Everywhere the home screen can send the user, bundled so that the row builders below take one
 * parameter instead of four.
 *
 * @param onItemClick open an item's detail page — what tapping any card has always done, and what
 *   the hero's *Details* button does.
 * @param onPlay start playback of [JellyfinItem.id] at the given position in Jellyfin ticks. Same
 *   contract as the detail and downloads screens': `:app` turns it into a `Routes.Player`
 *   navigation. The hero's Resume pill is its only caller.
 * @param onLibraryClick open a library's grid — the *See all* action and the quick-access chips.
 * @param onOpenDownloads switch to the Downloads tab, behind the quick-access row's *Offline* chip.
 */
data class HomeActions(
    val onItemClick: (JellyfinItem) -> Unit,
    val onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    val onLibraryClick: (LibraryView) -> Unit,
    val onOpenDownloads: () -> Unit,
)

/**
 * Stateless home rendering — everything the screen draws is a pure function of [state], which
 * keeps it previewable and testable without a ViewModel.
 */
@Composable
fun HomeContent(
    state: HomeUiState,
    onRetry: () -> Unit,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> LoadingState(modifier = modifier)

        state.errorMessage != null ->
            ErrorState(message = state.errorMessage, modifier = modifier, onRetry = onRetry)

        state.isEmpty ->
            EmptyState(
                message = stringResource(R.string.home_empty_message),
                modifier = modifier,
                actionLabel = stringResource(R.string.home_empty_refresh),
                onAction = onRetry,
            )

        else -> HomeRows(state = state, actions = actions, modifier = modifier)
    }
}

/**
 * The scrolling column: the hero, then the user's rows in the order the server gives them.
 *
 * ### How the hero and the app's chrome share the top of the window
 * `AppScaffold` reserves no space and publishes how much of the window its floating chrome covers as
 * `LocalAppChromePadding`; a top-level screen adds that to its `contentPadding` so its first and last
 * rows come to rest in the clear. **Home is the designated exception at the top**: the hero is meant
 * to run full-bleed under the status bar and under the compact action cluster, which is exactly what
 * the mocks show, so when there is a hero this list consumes only the chrome's *bottom* padding and
 * starts the hero at y=0. With no resume items — no hero — the top padding is consumed as usual, so
 * the first row still clears the top nav (wide) or the action cluster (compact).
 *
 * The hero's own copy is bottom-left (compact) or left (wide) precisely because the top-right of
 * that band belongs to `AppActionCluster`'s Cast/SyncPlay/overflow buttons, which are drawn over it.
 */
@Composable
private fun HomeRows(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    // One `BoxWithConstraints` for the whole screen — not per row or per card — matching the
    // pattern `LibrariesGrid` uses for the same problem (see its comment): a single subcomposition
    // buys the phone-vs-tablet branch in `homeThumbCardWidth` instead of one per thumb/library card.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cardWidth = homeThumbCardWidth(maxWidth)
        val wide = isWideHome(maxWidth = maxWidth, maxHeight = maxHeight)
        val hero = state.resume.firstOrNull()
        // The rest of *Continue watching* once the hero has taken the first card. Dropped once per
        // resume-list change rather than inline in the lazy builder, which re-runs on every
        // recomposition and handed `MediaRow` a fresh, never-equal list each time — defeating its
        // skipping on exactly the row `UserDataEventBus` patches most often.
        val resumeAfterHero = remember(state.resume) { state.resume.drop(1) }
        val chrome = LocalAppChromePadding.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    top = if (hero != null) 0.dp else Dimens.SpaceLarge + chrome.calculateTopPadding(),
                    bottom = Dimens.SpaceLarge + chrome.calculateBottomPadding(),
                ),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
        ) {
            if (hero != null) {
                item(key = SECTION_HERO, contentType = ROW_HERO) {
                    HomeHero(
                        item = hero,
                        wide = wide,
                        height = heroHeight(wide = wide, viewportHeight = maxHeight),
                        onResume = { actions.onPlay(hero.id, hero.userData.playbackPositionTicks) },
                        onDetails = { actions.onItemClick(hero) },
                        // The rows below a wide banner come to rest inside its faded bottom edge —
                        // the mocks' -48dp rail. The item reports itself that much shorter (plus the
                        // column's own gap, which still applies) rather than offsetting what follows
                        // it, which a lazy list has no way to express: an item can only be placed
                        // after the one before it ends.
                        modifier =
                            if (wide) {
                                Modifier.reportShorterBy(HeroRailOverlap + Dimens.SpaceExtraLarge)
                            } else {
                                Modifier
                            },
                    )
                }
            }

            // The order and the presence of every row is the user's, read from the server (see
            // `HomeLayoutRepository`); each row itself is unchanged. Sections this app has no row for
            // — audio/book resume, live TV — are skipped here rather than dropped upstream, so that
            // hiding one in jellyfin-web still moves the rows around it correctly.
            //
            // Empty sections are skipped entirely rather than emitted as zero-height items, which
            // would still consume the column's `spacedBy` gap and leave a visible hole.
            //
            // Every row declares its `contentType` — both here (a screenful of rows is itself a lazy
            // list) and inside `MediaRow` — so scrolling reuses nodes instead of composing new ones.
            var librariesDrawn = false
            state.sections.forEach { section ->
                when (section) {
                    // Both spellings of *My Media* are the same row for us, so a layout containing
                    // both draws it once — two items under one key would crash the lazy list.
                    HomeSectionType.SMALL_LIBRARY_TILES, HomeSectionType.LIBRARY_BUTTONS ->
                        if (!librariesDrawn) {
                            librariesDrawn = true
                            if (wide) {
                                librariesRow(state, actions, cardWidth)
                            } else {
                                quickAccessRow(state, actions)
                            }
                        }

                    // The hero *is* the first resume card; the row picks up where it leaves off.
                    // (`resumeAfterHero` of an empty list is empty, so the no-hero case is the
                    // same empty row it always was.)
                    HomeSectionType.RESUME ->
                        resumeRow(
                            items = resumeAfterHero,
                            actions = actions,
                            cardWidth = cardWidth,
                        )

                    HomeSectionType.NEXT_UP -> nextUpRow(state, actions, cardWidth)
                    HomeSectionType.LATEST_MEDIA -> latestRows(state, actions)

                    HomeSectionType.NONE,
                    HomeSectionType.ACTIVE_RECORDINGS,
                    HomeSectionType.RESUME_AUDIO,
                    HomeSectionType.RESUME_BOOK,
                    HomeSectionType.LIVE_TV,
                    -> Unit
                }
            }
        }
    }
}

/**
 * Makes a composable *lay out* [amount] shorter than it draws, so that whatever follows it in a
 * lazy list overlaps its bottom edge.
 *
 * Only ever applied to something whose bottom edge has already faded to the app background — the
 * wide [HomeHero]'s rail fade — so the overlapped band is background either way, and the row that
 * lands on it is drawn after (and therefore over) the banner.
 */
private fun Modifier.reportShorterBy(amount: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height - amount.roundToPx()).coerceAtLeast(0)
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

/** *My Media* as tiles — the wide layout's shape for the libraries row. */
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
            contentType = CARD_LIBRARY,
        ) { library ->
            LibraryCard(
                library = library,
                onClick = { actions.onLibraryClick(library) },
                width = cardWidth,
                // Absent for a library rebuilt from the offline cache, which stores no count, or one
                // whose count request failed; the tile then draws its name alone
                // (`LibraryView.itemCount`).
                subtitle =
                    library.itemCount?.let { count ->
                        pluralStringResource(CoreUiR.plurals.library_item_count, count, count)
                    },
            )
        }
    }
}

/**
 * *My Media* as quick-access chips — the compact layout's shape for the same row.
 *
 * A phone has no room for a shelf of 232dp tiles above the artwork the screen is actually about, so
 * the refresh turns the libraries into a single line of glass pills, with the Downloads tab on the
 * end: everything the tile row reached, one tap away, in a sixth of the height.
 */
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
 * One quick-access pill: a glyph and a name on a translucent capsule.
 *
 * The fill is `GlassDefaults.Fill` painted flat rather than `Modifier.glassSurface`, and
 * deliberately so: a real blur samples `AppScaffold`'s haze source, which is the very content this
 * chip scrolls *inside*, so an in-content glass surface would be blurring itself. The chrome floating
 * over the page is what the blur is for; in-content glass is the flat fill, which is also what
 * `PillChip` does.
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
                // A *minimum*, not a fixed height (`GlassBottomNav` records the same reasoning):
                // 38dp around a 13sp label has under 4dp of slack, so at accessibility font scales
                // a hard `height` clipped the chip's word. The row scrolls, so growing is free.
                .heightIn(min = QuickAccessChipHeight)
                .background(color = GlassDefaults.Fill, shape = CircleShape)
                .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                .clickable(onClick = onClick)
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
            contentType = CARD_THUMB,
        ) { item ->
            ThumbCard(
                item = item,
                onClick = { actions.onItemClick(item) },
                width = cardWidth,
                topStartBadge = item.episodeLabel,
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
            contentType = CARD_THUMB,
        ) { item ->
            // No time chip: nothing in *Next up* has been started, so there is no time left to show.
            ThumbCard(
                item = item,
                onClick = { actions.onItemClick(item) },
                width = cardWidth,
                topStartBadge = item.episodeLabel,
            )
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
            contentType = CARD_POSTER,
            onSeeAll = { actions.onLibraryClick(section.library) },
        ) { item ->
            // Posters keep the overlays they already had (progress, watched); the star rating badge
            // belongs to the library grid, where a wall of posters is all a user has to choose from.
            PosterCard(item = item, onClick = { actions.onItemClick(item) })
        }
    }
}

/**
 * Viewport width below which [homeThumbCardWidth] switches to [COMPACT_THUMB_WIDTH], and the width
 * half of [isWideHome].
 *
 * 600dp is the standard compact/medium width-class boundary — the same cutoff
 * `librariesMinCellWidth` in `LibrariesScreen.kt` uses — and comfortably below every width this
 * screen actually renders at on a tablet: the test tablet is 711dp in portrait and 1138dp in
 * landscape, so both stay on the [Dimens.ThumbWidth] (232dp) branch and the tablet render this
 * file was calibrated against is unchanged.
 *
 * It is deliberately *not* the chrome's own 560dp boundary (`TopNavMinWidth`): that number decides
 * which navigation bar the window gets, this one decides how much room a card has, and the home
 * screen already had this one. Between the two — a 560–600dp window — the wide nav bar floats over
 * the compact home layout, which is exactly the arrangement the hero is built for anyway: it ignores
 * the chrome's top padding at every width.
 */
private val COMPACT_MAX_WIDTH = 600.dp

/**
 * Viewport height below which the home screen stays on its compact shape however wide it is.
 *
 * The wide hero is a fixed-height banner with a copy block inset 104dp from the top; on a short
 * window there is no room for that above the first row, and the same reasoning (and the same guard)
 * as `ItemDetailScreen.isWideLayout` applies. A phone in landscape — ~360dp tall and well over
 * 600dp wide — is the shape this rules out; every tablet orientation clears it.
 */
private val WIDE_MIN_HEIGHT = 560.dp

/**
 * Fixed *Continue Watching* / *Next Up* / *My Media* card width once the viewport drops below
 * [COMPACT_MAX_WIDTH].
 *
 * At 360dp — the narrowest phone this app targets — [Dimens.ThumbWidth] (232dp) only fits 1.4
 * cards per row, which reads as zoomed-in next to jellyfin-web. 160dp is chosen so exactly two
 * full cards plus a peek of a third fit: `ScreenPadding` (16dp) + 160 + `SpaceMedium` gutter
 * (12dp) + 160 = 348dp, inside the 360dp viewport, leaving a 12dp sliver of the next card as the
 * scroll affordance a fixed-width `LazyRow` needs (unlike `LibrariesGrid`'s adaptive grid, these
 * rows don't reflow to fill leftover width).
 */
private val COMPACT_THUMB_WIDTH = 160.dp

/**
 * Fixed width for Home's thumb-shaped cards (*My Media*, *Continue Watching*, *Next Up*) at a
 * viewport of [maxWidth].
 *
 * Pulled out of the composable so it's a plain, unit-testable function of the measured width —
 * see [COMPACT_THUMB_WIDTH] for why the compact value was chosen and [COMPACT_MAX_WIDTH] for why
 * the cutoff sits where it does. `Latest ...` poster rows are untouched by this: they use
 * `PosterCard`'s own fixed [Dimens.PosterWidth] (128dp) at every width.
 *
 * Width only, deliberately: this is how much room a card has, and that does not change because a
 * window is short. The *shape* of the screen — hero layout, tiles vs chips — is [isWideHome]'s
 * decision, and that one does look at the height.
 */
internal fun homeThumbCardWidth(maxWidth: Dp): Dp =
    if (maxWidth < COMPACT_MAX_WIDTH) COMPACT_THUMB_WIDTH else Dimens.ThumbWidth

/**
 * Whether the screen draws its wide shape: the landscape hero with the copy beside the artwork, and
 * *My Media* as library tiles rather than quick-access chips.
 *
 * Both halves matter — see [COMPACT_MAX_WIDTH] and [WIDE_MIN_HEIGHT].
 */
internal fun isWideHome(
    maxWidth: Dp,
    maxHeight: Dp,
): Boolean = maxWidth >= COMPACT_MAX_WIDTH && maxHeight >= WIDE_MIN_HEIGHT

/**
 * How tall the hero banner is in a [viewportHeight]-tall window.
 *
 * The mocks' 460dp (portrait) and 400dp (landscape) are what a phone and a tablet get. The ceiling
 * is the guard: at [HERO_MAX_VIEWPORT_FRACTION] of the window, a hero can never take more than
 * three fifths of the screen, so a small or split-screen window still shows what the screen is for —
 * the rows under it — instead of one enormous picture. A 640dp-tall phone lands at 384dp; the test
 * tablet and every ordinary phone are above the ceiling and get the mocks' figure exactly.
 */
internal fun heroHeight(
    wide: Boolean,
    viewportHeight: Dp,
): Dp =
    (if (wide) WIDE_HERO_HEIGHT else COMPACT_HERO_HEIGHT)
        .coerceAtMost(viewportHeight * HERO_MAX_VIEWPORT_FRACTION)

private val COMPACT_HERO_HEIGHT = 460.dp

private val WIDE_HERO_HEIGHT = 400.dp

/** Share of the window the hero may occupy at most — see [heroHeight]. */
private const val HERO_MAX_VIEWPORT_FRACTION = 0.6f

/** Height of a quick-access chip: taller than a filter chip, since it is a navigation target. */
private val QuickAccessChipHeight = 38.dp

private val QuickAccessChipPadding = 14.dp

private val QuickAccessChipIconSize = 16.dp

private val QuickAccessChipIconTint = Color.White.copy(alpha = 0.75f)

private val QuickAccessChipLabel =
    TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
    )

private const val SECTION_HERO = "section-hero"
private const val SECTION_MY_MEDIA = "section-my-media"
private const val SECTION_RESUME = "section-resume"
private const val SECTION_NEXT_UP = "section-next-up"

// Content types: rows of the same shape are interchangeable nodes, whatever section they belong to.
private const val ROW_HERO = "row-hero"
private const val ROW_LIBRARIES = "row-libraries"
private const val ROW_QUICK_ACCESS = "row-quick-access"
private const val ROW_THUMBS = "row-thumbs"
private const val ROW_POSTERS = "row-posters"
private const val CARD_LIBRARY = "card-library"
private const val CARD_THUMB = "card-thumb"
private const val CARD_POSTER = "card-poster"
private const val CHIP_QUICK_ACCESS = "chip-quick-access"
private const val CHIP_OFFLINE = "chip-offline"

private val PreviewMovies =
    LibraryView(id = "lib-movies", name = "Movies", collectionType = CollectionKind.MOVIES)

private val PreviewShows =
    LibraryView(id = "lib-shows", name = "Shows", collectionType = CollectionKind.TVSHOWS)

/** The *Continue watching* items: the first is the hero, the second is what the row is left with. */
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

/** The rows every preview below draws, with or without the *Continue watching* items. */
private fun previewState(withResume: Boolean): HomeUiState =
    HomeUiState(
        isLoading = false,
        libraries = listOf(PreviewMovies, PreviewShows),
        resume = if (withResume) PreviewResume else emptyList(),
        nextUp = PreviewNextUp,
        latest = PreviewLatest,
    )

private val PreviewActions =
    HomeActions(onItemClick = {}, onPlay = { _, _ -> }, onLibraryClick = {}, onOpenDownloads = {})

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
