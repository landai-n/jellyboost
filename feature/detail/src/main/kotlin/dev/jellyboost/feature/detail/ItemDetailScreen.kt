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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.selection.ItemSelection
import dev.jellyboost.core.common.selection.SelectionIntent
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.MediaRow
import dev.jellyboost.core.ui.component.PosterCard
import dev.jellyboost.core.ui.component.SelectionAppBar
import dev.jellyboost.core.ui.component.ThumbCard
import dev.jellyboost.core.ui.component.batchOutcomeText
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras

/**
 * The movie / series / season detail screen (docs/PLAN.md, "Screens" → ItemDetail).
 *
 * Like `HomeScreen`, the ViewModel is passed in rather than resolved here so that `:app` owns the
 * `hiltViewModel()` call together with the rest of the navigation graph.
 *
 * @param onItemClick a season, episode or related item was tapped — the caller pushes another
 *   `Routes.ItemDetail` for it.
 * @param onPlay a **solo** play was resolved for an item, at the given position in Jellyfin ticks —
 *   the caller pushes `Routes.Player`. Which item a Play tap means (a series plays its next-up
 *   episode) is resolved on this side, because only this screen knows the rows it loaded; *whether*
 *   it is a solo play at all is resolved by `ItemDetailViewModel.onPlay`, since in a SyncPlay group
 *   a play is sent to the group and the player is opened by the group's answer instead
 *   (DECISIONS.md, 2026-07-31).
 * @param onBack pops one entry — the plain back affordance.
 * @param onHome leaves the whole pushed chain at once and lands on the Home tab; see
 *   `AppScaffold.navigateHome`.
 */
@Composable
fun ItemDetailScreen(
    viewModel: ItemDetailViewModel,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectionState = viewModel.selection.collectAsStateWithLifecycle()
    // Collected separately from [uiState] on purpose: the group changes a handful of times a
    // session, the state several times a second while a download runs (M11 Phase 4).
    val activeGroup by viewModel.activeGroup.collectAsStateWithLifecycle()
    // Only *whether* the mode is on is read in this scope — it flips twice per selection. Reading
    // the set here would recompose the page (and re-create the episode list's content lambda) on
    // every toggle; the count is read inside [SelectionOverlay]'s own scope.
    val isSelecting by remember(selectionState) { derivedStateOf { selectionState.value.isActive } }
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.userMessage?.let { userMessageText(it) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    // A play tap resolves to a navigation only when it is a *solo* play; in a group the ViewModel
    // sends the group a queue instead and the player is opened by the group's answer, through
    // `SyncPlayController.launchRequests` (DECISIONS.md, 2026-07-31).
    val currentOnPlay by rememberUpdatedState(onPlay)
    LaunchedEffect(viewModel) {
        viewModel.playRequests.collect { request ->
            currentOnPlay(request.itemId, request.startPositionTicks)
        }
    }

    // Enabled only while the mode is on, so Back keeps popping this destination — and the overlaid
    // Back / Home buttons keep working — at every other moment.
    BackHandler(enabled = isSelecting) { viewModel.onSelection(SelectionIntent.Clear) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The overlay needs the same answer the content does, because the favourite heart is only
        // *up here* on the shapes whose action row has no room for it (spec section 4c).
        val isWide = isWideLayout(maxWidth = maxWidth, maxHeight = maxHeight)

        ItemDetailContent(
            state = state,
            onRetry = viewModel::refresh,
            onItemClick = onItemClick,
            onPlay = viewModel::onPlay,
            actions =
                DetailActionHandlers(
                    onPlay = { state.playTarget?.let(viewModel::onPlay) },
                    onDownload = viewModel::onDownloadClick,
                    onToggleWatched = viewModel::toggleWatched,
                    onToggleFavorite = viewModel::toggleFavorite,
                    // Set only when there is a group *and* something a group can play: a series
                    // page resolves to its next-up episode, a library folder to nothing
                    // (`ItemDetailUiState.groupTarget`). Non-null is therefore also exactly when a
                    // tap on Play is a group play, which is what the header labels itself from.
                    group =
                        activeGroup
                            ?.takeIf { state.groupTarget != null }
                            ?.let { DetailGroupActions(it.name, viewModel::onGroupAction) },
                ),
            selection = selectionState,
            onSelection = viewModel::onSelection,
        )

        // This screen is a pushed destination, so per `AppScaffold`'s inset contract it gets none
        // of its own — the inset has to live on the overlay rather than the surrounding `Box`,
        // since the backdrop behind it is meant to draw edge-to-edge under the status bar.
        //
        // Home sits beside Back because a detail chain is the one place in the app that gets deep:
        // series → season → episode → "More like this" → … with no app bar to escape through, so
        // the only way out used to be tapping Back once per hop. The favourite heart joins them on
        // compact, where the action row below keeps one worded button and two circles and has no
        // room for a third (spec section 4c); on wide it stays in that row instead.
        //
        // While episodes are selected the contextual bar takes this overlay's place rather than
        // sitting beside or below it. This screen has no top bar of its own, so the overlaid pair
        // *is* its bar, and a contextual bar's whole job is to replace one: the close (X) lands
        // exactly where Back was, and Home is deliberately gone for the duration — leaving the
        // screen mid-selection is what Back and X are for.
        if (isSelecting) {
            SelectionOverlay(
                selection = selectionState,
                onIntent = viewModel::onSelection,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        } else {
            OverlayNav(
                favorite = state.item?.userData?.isFavorite,
                showFavorite = state.item != null && !isWide,
                onBack = onBack,
                onHome = onHome,
                onToggleFavorite = viewModel::toggleFavorite,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
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
 * The floating navigation the detail screen wears instead of a top bar: glass circles over the
 * backdrop, Back at the start and the page's own affordances at the end.
 *
 * @param showFavorite `false` on the wide layout, whose action row hosts the heart, and before the
 *   item has loaded — there is nothing to favourite yet.
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
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.detail_back),
            onClick = onBack,
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
            )
        }
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.detail_home),
            onClick = onHome,
        )
    }
}

/**
 * The contextual bar, in a composable of its own so that reading the *count* recomposes this and
 * nothing else — `ItemDetailScreen` only ever reads whether the mode is on.
 */
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
        // Unlike the paged library grid, an episode list is fetched whole: "all" is a set the user
        // can see and count (docs/features/batch-selection.md).
        showSelectAll = true,
    )
}

/**
 * Confirms removing a download from this screen's Download button (docs/POLISH.md — deleting a
 * downloaded file from the detail screen used to happen with no confirmation at all).
 *
 * Precedent: `SignOutDialog` in `:feature:settings`.
 */
@Composable
private fun DeleteDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.detail_delete_download_dialog_title)) },
        text = { Text(text = stringResource(R.string.detail_delete_download_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.detail_delete_download_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.detail_delete_download_dialog_cancel))
            }
        },
    )
}

/**
 * Stateless detail rendering — a pure function of [state], so it previews without a ViewModel.
 *
 * @param onPlay an episode row's play button was tapped. It hands over the *item*, not an id and a
 *   position: what a play means depends on whether there is a group, and that is the ViewModel's
 *   answer to give (DECISIONS.md, 2026-07-31).
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
) {
    val detail = state.item
    when {
        state.isLoading -> LoadingState(modifier = modifier)

        state.errorMessage != null ->
            ErrorState(message = state.errorMessage, modifier = modifier, onRetry = onRetry)

        detail == null ->
            EmptyState(message = stringResource(R.string.detail_empty), modifier = modifier)

        else ->
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                DetailSections(
                    state = state,
                    detail = detail,
                    isWide = isWideLayout(maxWidth = maxWidth, maxHeight = maxHeight),
                    backdropHeight = backdropHeight(maxWidth = maxWidth, maxHeight = maxHeight),
                    compact = maxWidth < COMPACT_MAX_WIDTH,
                    onItemClick = onItemClick,
                    onPlay = onPlay,
                    actions = actions,
                    selection = selection,
                    onSelection = onSelection,
                )
            }
    }
}

@Composable
private fun DetailSections(
    state: ItemDetailUiState,
    detail: JellyfinItem,
    isWide: Boolean,
    backdropHeight: Dp,
    compact: Boolean,
    onItemClick: (JellyfinItem) -> Unit,
    onPlay: (JellyfinItem) -> Unit,
    actions: DetailActionHandlers,
    selection: State<ItemSelection>,
    onSelection: (SelectionIntent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Dimens.SpaceExtraLarge),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
    ) {
        // The backdrop and the lockup drawn on it are one node: the refresh puts the title *over*
        // the artwork, and two lazy items cannot overlap.
        item(key = SECTION_HERO, contentType = DetailContentType.SECTION) {
            DetailHero(
                item = detail,
                playTarget = state.playTarget,
                isWide = isWide,
                backdropHeight = backdropHeight,
                downloadState = state.downloadState,
                actions = actions,
                downloadedBytes = state.downloadedBytes,
                compact = compact,
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

        episodeSection(
            episodes = state.episodes,
            isWide = isWide,
            handlers =
                EpisodeHandlers(
                    onItemClick = onItemClick,
                    onPlay = onPlay,
                    selection = selection,
                    onSelection = onSelection,
                ),
        )

        if (detail.people.isNotEmpty()) {
            item(key = SECTION_CAST, contentType = DetailContentType.SECTION) {
                CastRail(people = detail.people)
            }
        }

        if (state.similar.isNotEmpty()) {
            item(key = SECTION_SIMILAR, contentType = DetailContentType.SECTION) {
                MediaRow(
                    title = stringResource(R.string.detail_section_similar),
                    items = state.similar,
                    key = JellyfinItem::id,
                ) { related -> PosterCard(item = related, onClick = { onItemClick(related) }) }
            }
        }
    }
}

/**
 * The season's episodes: a column of full-width cards on a phone, a horizontal strip of tiles on a
 * wide layout (spec section 4c).
 *
 * The wide shape is one lazy node holding a `LazyRow`, not one node per episode, because a row that
 * scrolls sideways is a single item of the column it sits in — the same shape every `MediaRow` on
 * this screen already has.
 */
private fun LazyListScope.episodeSection(
    episodes: List<JellyfinItem>,
    isWide: Boolean,
    handlers: EpisodeHandlers,
) {
    if (episodes.isEmpty()) return

    if (isWide) {
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

/**
 * Everything one episode card is wired to, bundled so the section function and the card stay under
 * the parameter limit — the same shape [DetailActionHandlers] takes for the hero's buttons.
 */
private data class EpisodeHandlers(
    val onItemClick: (JellyfinItem) -> Unit,
    val onPlay: (JellyfinItem) -> Unit,
    val selection: State<ItemSelection>,
    val onSelection: (SelectionIntent) -> Unit,
)

/**
 * One episode card, wired to the selection mode.
 *
 * The derived flag is per row on purpose, so toggling one episode invalidates one card rather than
 * the forty a season can hold — the same idiom `LibraryGridScreen` uses.
 */
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

/**
 * The two node shapes this screen's `LazyColumn` draws (audit PERF-08).
 *
 * `SECTION` covers the hero, and every `MediaRow`/`SectionTitle` block: each is structurally
 * different, but none of them repeats — there is exactly one of each per screen — so there is
 * nothing to gain from telling them apart further, and one shared type keeps a header slot from
 * being compared against a `MediaRow` slot as if reuse between them were ever on the table.
 * `EPISODE` is the one node shape that *does* repeat, up to a season's worth of times, and is the
 * one this exists for: without it, a `LazyColumn` with no `contentType` at all defaults every node
 * to the same type regardless of shape, so scrolling a section into a slot the recycler last held an
 * episode row in (or the reverse) could not reuse the composition — it had to be thrown away and
 * rebuilt from scratch.
 */
private enum class DetailContentType {
    SECTION,
    EPISODE,
}

/** A section heading on this screen, in the refresh's row-title type (spec, "Sections"). */
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
        modifier = modifier.padding(horizontal = DetailEdgePadding),
    )
}

/** Renders a one-shot [message] as snackbar copy (precedent: `:feature:auth`'s `authErrorText`). */
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
private const val SECTION_EPISODES = "section-episodes"
private const val SECTION_CAST = "section-cast"
private const val SECTION_SIMILAR = "section-similar"

/**
 * Whether the header lays out side by side (poster beside facts) rather than stacked.
 *
 * Width alone used to decide this, but a phone in landscape (e.g. 800×360dp) clears
 * [WIDE_BREAKPOINT] on width while being far too short to afford a side-by-side header on top of
 * a fixed-height banner — the pair together left the header crammed into ~30dp of remaining
 * height. [WIDE_MIN_HEIGHT] rules that shape out: every tablet orientation clears it, phone
 * landscape never does.
 */
internal fun isWideLayout(
    maxWidth: Dp,
    maxHeight: Dp,
): Boolean = maxWidth >= WIDE_BREAKPOINT && maxHeight >= WIDE_MIN_HEIGHT

/**
 * How tall the backdrop banner is for a viewport of [maxWidth] × [maxHeight].
 *
 * In **portrait** the banner is a share of the viewport *height* rather than a fixed number of dp:
 * a tablet in portrait is wide enough to take the [WIDE_BACKDROP_HEIGHT] branch on a screen twice
 * as tall, which left the poster stranded at the top with dead space below it (docs/POLISH.md).
 * The width-derived value stays as the floor — a proportional banner never gets *shorter* than the
 * fixed one — and [MAX_BACKDROP_HEIGHT] keeps it from pushing the facts and Play button off-screen
 * on a very tall device.
 *
 * In **landscape** vertical space is what is scarce, so the width-based height is used unchanged —
 * *unless* the viewport is also short ([WIDE_MIN_HEIGHT]), which only phone landscape is: there the
 * fixed [WIDE_BACKDROP_HEIGHT] would eat ~90% of the screen, so the banner instead takes a share of
 * the (scarce) height, the same way the portrait branch does.
 *
 * Portrait itself further splits on width: below [COMPACT_MAX_WIDTH] the share is
 * [COMPACT_PORTRAIT_BACKDROP_FRACTION] rather than [PORTRAIT_BACKDROP_FRACTION] — see that
 * constant's doc for why. This function keeps its (maxWidth, maxHeight) signature; the split lives
 * entirely inside it.
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

/** Above this width the header lays out side by side — roughly a tablet in portrait. */
private val WIDE_BREAKPOINT = 720.dp

/**
 * Below this viewport height, the side-by-side header and the fixed-height banner are both off the
 * table regardless of width — phone landscape is ~330-410dp tall, and every tablet orientation
 * clears it.
 */
private val WIDE_MIN_HEIGHT = 480.dp

/**
 * The banner on a wide layout, which is also the floor the poster overlaps into (2026 refresh: 320
 * → 360dp, DECISIONS.md 2026-08-01 "card metrics and radii leave the jellyfin-web footprint").
 */
private val WIDE_BACKDROP_HEIGHT = 360.dp

/**
 * The floor under a narrow layout's proportional banner (220 → 320dp in the refresh): the title
 * lockup is drawn *on* the artwork now, so a banner shorter than this has nothing to say it on.
 */
private val NARROW_BACKDROP_HEIGHT = 320.dp

/** Share of the viewport height the banner claims in portrait. */
private const val PORTRAIT_BACKDROP_FRACTION = 0.46f

/**
 * Share of the viewport height the banner claims in portrait on a compact-width ([COMPACT_MAX_WIDTH])
 * phone screen, instead of [PORTRAIT_BACKDROP_FRACTION].
 *
 * The refresh takes both fractions up (0.32 → 0.52, 0.40 → 0.46) because the banner now carries the
 * eyebrow, the title and the metadata row that used to sit under it — on a 360×800 phone that is
 * 416dp of hero, and the text it holds is the reason for the height rather than dead art.
 */
private const val COMPACT_PORTRAIT_BACKDROP_FRACTION = 0.52f

/** Share of the viewport height the banner claims in a short (phone) landscape. */
private const val COMPACT_LANDSCAPE_BACKDROP_FRACTION = 0.5f

/** Ceiling for the proportional portrait banner, so the header below it stays in view. */
private val MAX_BACKDROP_HEIGHT = 560.dp

/**
 * Below this width a portrait viewport is a phone, and takes the taller
 * [COMPACT_PORTRAIT_BACKDROP_FRACTION] share of its (short) height; it is also where the overview
 * collapses to a tappable five lines rather than running in full.
 */
private val COMPACT_MAX_WIDTH = 480.dp
