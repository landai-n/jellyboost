package dev.jellyboost.feature.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.Person
import dev.jellyboost.core.common.selection.ItemSelection
import dev.jellyboost.core.common.selection.SelectionIntent
import dev.jellyboost.core.common.syncplay.SyncPlayGroupHandle
import dev.jellyboost.core.ui.component.ConfirmDialog
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.component.JellyboostSnackbarHost
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.SelectionAppBar
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.component.batchOutcomeText
import dev.jellyboost.core.ui.component.rememberOneShotSnackbar
import dev.jellyboost.core.ui.text.resolve
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * @param onPlay fires only for a **solo** play, at a position in Jellyfin ticks. In a SyncPlay group
 *   `ItemDetailViewModel.onPlay` sends the group a queue instead and the player is opened by the
 *   group's answer, so this never fires.
 */
@Composable
fun ItemDetailScreen(
    viewModel: ItemDetailViewModel,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onNavigateToItemId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectionState = viewModel.selection.collectAsStateWithLifecycle()
    // Must stay separate from [uiState]: the group changes a few times a session, the state several
    // times a second while a download runs.
    val activeGroup by viewModel.activeGroup.collectAsStateWithLifecycle()
    // Only *whether* the mode is on may be read in this scope. Reading the set here would recompose
    // the page — and re-create the episode list's content lambda — on every toggle.
    val isSelecting by remember(selectionState) { derivedStateOf { selectionState.value.isActive } }
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = viewModel::consumeMessage,
        ) { userMessageText(it) }

    val currentOnPlay by rememberUpdatedState(onPlay)
    LaunchedEffect(viewModel) {
        viewModel.playRequests.collect { request ->
            currentOnPlay(request.itemId, request.startPositionTicks)
        }
    }

    // Enabled only while selecting, so Back keeps popping this destination at every other moment.
    BackHandler(enabled = isSelecting) { viewModel.onSelection(SelectionIntent.Clear) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layout = detailLayoutFor(maxWidth = maxWidth, maxHeight = maxHeight)

        ItemDetailContent(
            state = state,
            onRetry = viewModel::refresh,
            onItemClick = onItemClick,
            onPlay = viewModel::onPlay,
            actions = detailActions(state = state, activeGroup = activeGroup, viewModel = viewModel),
            selection = selectionState,
            onSelection = viewModel::onSelection,
            onNavigateToItemId = onNavigateToItemId,
        )

        DetailTopOverlay(
            isSelecting = isSelecting,
            selection = selectionState,
            favorite = state.item?.userData?.isFavorite,
            showFavorite = state.item != null && layout != DetailLayout.WIDE,
            onSelection = viewModel::onSelection,
            onBack = onBack,
            onHome = onHome,
            onToggleFavorite = viewModel::toggleFavorite,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        JellyboostSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (state.showDeleteConfirmation) {
            DeleteDownloadDialog(
                onDismiss = viewModel::dismissDeleteConfirmation,
                onConfirm = viewModel::confirmDeleteDownload,
            )
        }
    }
}

/**
 * [DetailActionHandlers.group] non-null is exactly "a tap on Play is a group play" — there is a
 * group *and* something a group can play. The header labels itself from that.
 */
private fun detailActions(
    state: ItemDetailUiState,
    activeGroup: SyncPlayGroupHandle?,
    viewModel: ItemDetailViewModel,
): DetailActionHandlers =
    DetailActionHandlers(
        onPlay = { state.playTarget?.let(viewModel::onPlay) },
        onDownload = viewModel::onDownloadClick,
        onToggleWatched = viewModel::toggleWatched,
        onToggleFavorite = viewModel::toggleFavorite,
        group =
            activeGroup
                ?.takeIf { state.groupTarget != null }
                ?.let { DetailGroupActions(it.name, viewModel::onGroupAction) },
    )

/**
 * A pushed destination gets no inset from `AppScaffold`, and the status-bar inset must live on this
 * overlay rather than the surrounding `Box` so the backdrop still draws edge-to-edge behind it.
 */
@Composable
private fun DetailTopOverlay(
    isSelecting: Boolean,
    selection: State<ItemSelection>,
    favorite: Boolean?,
    showFavorite: Boolean,
    onSelection: (SelectionIntent) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isSelecting) {
        SelectionOverlay(selection = selection, onIntent = onSelection, modifier = modifier)
    } else {
        OverlayNav(
            favorite = favorite,
            showFavorite = showFavorite,
            onBack = onBack,
            onHome = onHome,
            onToggleFavorite = onToggleFavorite,
            modifier = modifier,
        )
    }
}

/**
 * Deliberately not `:core:ui`'s `ScreenHeader`: no title, and Home at the *end*. It still shares the
 * `action_back`/`action_home` labels and `ChromeFill`.
 *
 * @param showFavorite `false` on the wide layout, whose action row hosts the heart, and before the
 *   item has loaded.
 */
@Composable
private fun OverlayNav(
    favorite: Boolean?,
    showFavorite: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(DetailEdgePadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ChromeFill, not the default Fill: these float over bright movie artwork.
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(CoreUiR.string.action_back),
            onClick = onBack,
            surfaceTint = GlassDefaults.ChromeFill,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (showFavorite) {
            val isFavorite = favorite == true
            GlassIconButton(
                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription =
                    stringResource(
                        if (isFavorite) R.string.detail_remove_favorite else R.string.detail_add_favorite,
                    ),
                onClick = onToggleFavorite,
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else GlassIconTint,
                surfaceTint = GlassDefaults.ChromeFill,
            )
        }
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(CoreUiR.string.action_home),
            onClick = onHome,
            surfaceTint = GlassDefaults.ChromeFill,
        )
    }
}

/** Its own composable so that reading the *count* recomposes this and nothing else. */
@Composable
private fun SelectionOverlay(
    selection: State<ItemSelection>,
    onIntent: (SelectionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectionAppBar(
        count = selection.value.count,
        onIntent = onIntent,
        modifier = modifier,
        // Safe here, unlike the paged library grid: an episode list is fetched whole, so "all" is a
        // set the user can see and count (docs/features/batch-selection.md).
        showSelectAll = true,
    )
}

@Composable
private fun DeleteDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.detail_delete_download_dialog_title),
        text = stringResource(R.string.detail_delete_download_dialog_message),
        confirmLabel = stringResource(R.string.detail_delete_download_dialog_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * @param onPlay hands over the *item*, not an id and a position: what a play means depends on
 *   whether there is a SyncPlay group, and that is the ViewModel's answer to give.
 */
@Composable
fun ItemDetailContent(
    state: ItemDetailUiState,
    onRetry: () -> Unit,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (JellyfinItem) -> Unit,
    actions: DetailActionHandlers,
    modifier: Modifier = Modifier,
    selection: State<ItemSelection> = remember { mutableStateOf(ItemSelection()) },
    onSelection: (SelectionIntent) -> Unit = {},
    onNavigateToItemId: (String) -> Unit = {},
) {
    val detail = state.item
    when {
        state.isLoading -> LoadingState(modifier = modifier)

        state.errorMessage != null ->
            ErrorState(
                message = state.errorMessage.resolve(),
                modifier = modifier,
                onRetry = onRetry,
                announce = LiveRegionMode.Assertive,
            )

        detail == null ->
            EmptyState(
                message = stringResource(R.string.detail_empty),
                modifier = modifier,
                announce = LiveRegionMode.Polite,
            )

        else ->
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                DetailSections(
                    state = state,
                    detail = detail,
                    layout = detailLayoutFor(maxWidth = maxWidth, maxHeight = maxHeight),
                    backdropHeight = backdropHeight(maxWidth = maxWidth, maxHeight = maxHeight),
                    onItemClick = onItemClick,
                    onPlay = onPlay,
                    actions = actions,
                    selection = selection,
                    onSelection = onSelection,
                    onNavigateToItemId = onNavigateToItemId,
                )
            }
    }
}

@Composable
private fun DetailSections(
    state: ItemDetailUiState,
    detail: JellyfinItem,
    layout: DetailLayout,
    backdropHeight: Dp,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (JellyfinItem) -> Unit,
    actions: DetailActionHandlers,
    selection: State<ItemSelection>,
    onSelection: (SelectionIntent) -> Unit,
    onNavigateToItemId: (String) -> Unit,
) {
    val handlers =
        EpisodeHandlers(
            onItemClick = onItemClick,
            onPlay = onPlay,
            selection = selection,
            onSelection = onSelection,
        )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Dimens.SpaceExtraLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        // Backdrop and lockup must stay one node: the title sits *over* the artwork, and two lazy
        // items cannot overlap.
        item(key = SECTION_HERO, contentType = DetailContentType.SECTION) {
            DetailHero(
                item = detail,
                playTarget = state.playTarget,
                layout = layout,
                backdropHeight = backdropHeight,
                downloadState = state.downloadState,
                actions = actions,
                onNavigateToItemId = onNavigateToItemId,
                downloadedBytes = state.downloadedBytes,
            )
        }

        state.nextUp?.let { next ->
            item(key = SECTION_NEXT_UP, contentType = DetailContentType.SECTION) {
                MediaRow(
                    title = stringResource(R.string.detail_section_next_up),
                    items = listOf(next),
                    key = JellyfinItem::id,
                ) { episode -> ThumbCard(item = episode, onClick = { onItemClick(episode) }) }
            }
        }

        if (state.seasons.isNotEmpty()) {
            item(key = SECTION_SEASONS, contentType = DetailContentType.SECTION) {
                MediaRow(
                    title = stringResource(R.string.detail_section_seasons),
                    items = state.seasons,
                    key = JellyfinItem::id,
                ) { season -> PosterCard(item = season, onClick = { onItemClick(season) }) }
            }
        }

        episodeOriginRows(
            state = state,
            detail = detail,
            onItemClick = onItemClick,
            onNavigateToItemId = onNavigateToItemId,
        )

        episodeSection(episodes = state.episodes, layout = layout, handlers = handlers)

        relatedSections(people = detail.people, similar = state.similar, onItemClick = onItemClick)
    }
}

/**
 * Draws nothing on non-episode item types: only `fetchRelated`'s episode branch populates
 * [ItemDetailUiState.nextEpisode] / [ItemDetailUiState.seasonEpisodes], so both stay empty.
 */
private fun LazyListScope.episodeOriginRows(
    state: ItemDetailUiState,
    detail: JellyfinItem,
    onItemClick: (JellyfinItem) -> Unit,
    onNavigateToItemId: (String) -> Unit,
) {
    state.nextEpisode?.let { next ->
        item(key = SECTION_NEXT_EPISODE, contentType = DetailContentType.SECTION) {
            MediaRow(
                title = stringResource(R.string.detail_section_next_episode),
                items = listOf(next),
                key = JellyfinItem::id,
            ) { episode -> ThumbCard(item = episode, onClick = { onItemClick(episode) }) }
        }
    }

    val siblings = seasonSiblings(state.seasonEpisodes, detail.id)
    if (siblings.isNotEmpty()) {
        item(key = SECTION_SEASON_SIBLINGS, contentType = DetailContentType.SECTION) {
            val seasonNumber = detail.parentIndexNumber
            MediaRow(
                title =
                    if (seasonNumber != null) {
                        stringResource(R.string.detail_section_more_from_season, seasonNumber)
                    } else {
                        stringResource(R.string.detail_section_more_from_season_unnumbered)
                    },
                items = siblings,
                key = JellyfinItem::id,
                onSeeAll = detail.seasonId?.let { seasonId -> { onNavigateToItemId(seasonId) } },
            ) { sibling -> ThumbCard(item = sibling, onClick = { onItemClick(sibling) }) }
        }
    }
}

private fun LazyListScope.relatedSections(
    people: List<Person>,
    similar: List<JellyfinItem>,
    onItemClick: (JellyfinItem) -> Unit,
) {
    if (people.isNotEmpty()) {
        item(key = SECTION_CAST, contentType = DetailContentType.SECTION) {
            CastRail(people = people)
        }
    }

    if (similar.isNotEmpty()) {
        item(key = SECTION_SIMILAR, contentType = DetailContentType.SECTION) {
            MediaRow(
                title = stringResource(R.string.detail_section_similar),
                items = similar,
                key = JellyfinItem::id,
            ) { related -> PosterCard(item = related, onClick = { onItemClick(related) }) }
        }
    }
}

private fun LazyListScope.episodeSection(
    episodes: List<JellyfinItem>,
    layout: DetailLayout,
    handlers: EpisodeHandlers,
) {
    if (episodes.isEmpty()) return

    if (layout == DetailLayout.WIDE) {
        item(key = SECTION_EPISODES, contentType = DetailContentType.SECTION) {
            Column(modifier = Modifier.fillMaxWidth()) {
                DetailSectionTitle(text = stringResource(R.string.detail_section_episodes))
                Spacer(modifier = Modifier.height(Dimens.SpaceMedium))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = DetailEdgePadding),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
                ) {
                    items(
                        items = episodes,
                        key = JellyfinItem::id,
                        contentType = { DetailContentType.EPISODE },
                    ) { episode ->
                        SelectableEpisode(episode = episode, strip = true, handlers = handlers)
                    }
                }
            }
        }
        return
    }

    item(key = SECTION_EPISODES, contentType = DetailContentType.SECTION) {
        DetailSectionTitle(text = stringResource(R.string.detail_section_episodes))
    }
    items(
        items = episodes,
        key = JellyfinItem::id,
        contentType = { DetailContentType.EPISODE },
    ) { episode ->
        SelectableEpisode(episode = episode, strip = false, handlers = handlers)
    }
}

private data class EpisodeHandlers(
    val onItemClick: (JellyfinItem) -> Unit,
    val onPlay: (JellyfinItem) -> Unit,
    val selection: State<ItemSelection>,
    val onSelection: (SelectionIntent) -> Unit,
)

/** The derived flag must stay per row, or toggling one episode invalidates all forty of a season. */
@Composable
private fun SelectableEpisode(
    episode: JellyfinItem,
    strip: Boolean,
    handlers: EpisodeHandlers,
) {
    val id = episode.id
    val selection = handlers.selection
    val selected by
        remember(selection, id) {
            derivedStateOf {
                val current = selection.value
                if (current.isActive) id in current else null
            }
        }

    EpisodeRow(
        episode = episode,
        onClick = {
            if (selection.value.isActive) {
                handlers.onSelection(SelectionIntent.Toggle(id))
            } else {
                handlers.onItemClick(episode)
            }
        },
        onPlay = { handlers.onPlay(episode) },
        onLongClick = { handlers.onSelection(SelectionIntent.Toggle(id)) },
        selected = selected,
        strip = strip,
    )
}

/** Extracted, not inlined, so the exclusion of the on-screen episode is pinned by a JVM test. */
internal fun seasonSiblings(
    seasonEpisodes: List<JellyfinItem>,
    currentEpisodeId: String,
): List<JellyfinItem> = seasonEpisodes.filterNot { it.id == currentEpisodeId }

/** `SECTION` lumps every one-per-screen block together; `EPISODE` is the shape that repeats. */
private enum class DetailContentType {
    SECTION,
    EPISODE,
}

/** `heading()` is load-bearing: it is what lets a heading-jump skip a season's worth of rows. */
@Composable
internal fun DetailSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = JellyfinTypeExtras.SectionTitle,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier.padding(horizontal = DetailEdgePadding).semantics {
                heading()
                contentDescription = text
            },
    )
}

@Composable
private fun userMessageText(message: UserMessage): String =
    when (message) {
        UserMessage.DownloadQueued -> stringResource(R.string.detail_message_download_queued)
        UserMessage.DownloadFailed -> stringResource(R.string.detail_message_download_failed)
        UserMessage.DownloadDeleted -> stringResource(R.string.detail_message_download_deleted)
        UserMessage.DownloadDeleteFailed -> stringResource(R.string.detail_message_download_delete_failed)
        UserMessage.UserDataWriteFailed -> stringResource(R.string.detail_message_user_data_failed)
        is UserMessage.DownloadCancelledKeepingFinished ->
            pluralStringResource(
                R.plurals.detail_message_download_cancelled_kept,
                message.keptCount,
                message.keptCount,
            )

        is UserMessage.BatchFinished -> batchOutcomeText(message.report.action, message.report.outcome)

        UserMessage.GroupPlayRequested -> stringResource(R.string.detail_message_group_play)

        is UserMessage.GroupActionSent ->
            stringResource(
                when (message.action) {
                    GroupAction.PLAY_NEXT -> R.string.detail_message_group_play_next
                    GroupAction.ADD_TO_QUEUE -> R.string.detail_message_group_queued
                },
            )
    }

private const val SECTION_HERO = "section-hero"
private const val SECTION_NEXT_UP = "section-next-up"
private const val SECTION_SEASONS = "section-seasons"
private const val SECTION_NEXT_EPISODE = "section-next-episode"
private const val SECTION_SEASON_SIBLINGS = "section-season-siblings"
private const val SECTION_EPISODES = "section-episodes"
private const val SECTION_CAST = "section-cast"
private const val SECTION_SIMILAR = "section-similar"

/**
 * One enum, not two booleans off two breakpoints (720dp header, 480dp overview clamp): that pair
 * left the 480–720dp band stacked *and* unclamped, and made `isWide && compact` representable and
 * undefined.
 *
 * @see clampsOverview
 */
internal enum class DetailLayout {
    COMPACT,

    /** Too wide for the compact treatment, too small for the stage: stacked header, still clamped. */
    MEDIUM,

    WIDE,
}

/**
 * [DetailLayout.WIDE] needs width *and* height: a phone in landscape (800×360dp) clears
 * [WIDE_BREAKPOINT] but is far too short for a side-by-side header over a fixed-height banner — the
 * pair left the header in ~30dp. Wide-but-short lands in [DetailLayout.MEDIUM], not COMPACT.
 */
internal fun detailLayoutFor(
    maxWidth: Dp,
    maxHeight: Dp,
): DetailLayout =
    when {
        maxWidth >= WIDE_BREAKPOINT && maxHeight >= WIDE_MIN_HEIGHT -> DetailLayout.WIDE
        maxWidth < COMPACT_MAX_WIDTH -> DetailLayout.COMPACT
        else -> DetailLayout.MEDIUM
    }

/** [DetailLayout.MEDIUM] clamps too: keyed on compact *width* alone that band got the full paragraph. */
internal val DetailLayout.clampsOverview: Boolean
    get() = this != DetailLayout.WIDE

/**
 * Portrait takes a share of viewport *height*, floored at the width-derived value and capped by
 * [MAX_BACKDROP_HEIGHT]: a tablet in portrait clears [WIDE_BREAKPOINT] on a screen twice as tall,
 * which stranded the poster at the top over dead space. Landscape keeps the fixed height unless the
 * viewport is also short (phone landscape only), where 360dp would eat ~90% of the screen.
 */
internal fun backdropHeight(
    maxWidth: Dp,
    maxHeight: Dp,
): Dp {
    val fixed = if (maxWidth >= WIDE_BREAKPOINT) WIDE_BACKDROP_HEIGHT else NARROW_BACKDROP_HEIGHT
    val isPortrait = maxHeight > maxWidth
    val portraitFraction =
        if (maxWidth < COMPACT_MAX_WIDTH) COMPACT_PORTRAIT_BACKDROP_FRACTION else PORTRAIT_BACKDROP_FRACTION
    return when {
        isPortrait -> (maxHeight * portraitFraction).coerceIn(fixed, MAX_BACKDROP_HEIGHT)
        maxHeight < WIDE_MIN_HEIGHT -> maxHeight * COMPACT_LANDSCAPE_BACKDROP_FRACTION
        else -> fixed
    }
}

private val WIDE_BREAKPOINT = 720.dp

/** Phone landscape is ~330–410dp tall; every tablet orientation clears this. */
private val WIDE_MIN_HEIGHT = 480.dp

/** Also the floor the poster overlaps into. */
private val WIDE_BACKDROP_HEIGHT = 360.dp

/** The title lockup is drawn *on* the artwork, so a shorter banner has nothing to say it on. */
private val NARROW_BACKDROP_HEIGHT = 320.dp

private const val PORTRAIT_BACKDROP_FRACTION = 0.46f

/**
 * Both portrait fractions are generous because the banner carries the eyebrow, title and metadata
 * row rather than leaving them below it — 416dp of hero on a 360×800 phone, most of it text.
 */
private const val COMPACT_PORTRAIT_BACKDROP_FRACTION = 0.52f

private const val COMPACT_LANDSCAPE_BACKDROP_FRACTION = 0.5f

/** Ceiling for the proportional portrait banner, so the header below it stays in view. */
private val MAX_BACKDROP_HEIGHT = 560.dp

private val COMPACT_MAX_WIDTH = 480.dp
