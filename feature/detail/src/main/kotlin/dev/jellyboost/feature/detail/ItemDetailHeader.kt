package dev.jellyboost.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.PersonKind
import dev.jellyboost.core.ui.component.ActionPillChip
import dev.jellyboost.core.ui.component.BackdropHeader
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.GlassIconTint
import dev.jellyboost.core.ui.component.InfoPillChip
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.component.MPillBadge
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.component.describeParts
import dev.jellyboost.core.ui.component.formatRatingBadge
import dev.jellyboost.core.ui.text.episodeNumberLabel
import dev.jellyboost.core.ui.text.subtitleLine
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.heroHalo
import dev.jellyboost.core.ui.theme.pageInk
import dev.jellyboost.core.ui.theme.popShadow
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The lockup is drawn *on* the artwork, which is what `ItemDetailScreen`'s taller backdrop fractions
 * exist to make room for.
 *
 * @param playTarget what Play will actually start — the label says so ("Play S1 · E10") when a
 *   series or season page resolves to an episode.
 */
@Composable
internal fun DetailHero(
    item: JellyfinItem,
    playTarget: JellyfinItem?,
    layout: DetailLayout,
    backdropHeight: Dp,
    downloadState: DownloadState,
    actions: DetailActionHandlers,
    onNavigateToItemId: (String) -> Unit,
    modifier: Modifier = Modifier,
    downloadedBytes: Long? = null,
) {
    if (layout == DetailLayout.WIDE) {
        WideStage(
            item = item,
            playTarget = playTarget,
            backdropHeight = backdropHeight,
            downloadState = downloadState,
            actions = actions,
            onNavigateToItemId = onNavigateToItemId,
            downloadedBytes = downloadedBytes,
            modifier = modifier,
        )
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().height(backdropHeight)) {
                DetailBackdrop(item = item, height = backdropHeight, halo = false)
                TitleLockup(
                    item = item,
                    downloadState = downloadState,
                    downloadedBytes = downloadedBytes,
                    expanded = false,
                    onNavigateToItemId = onNavigateToItemId,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(DetailEdgePadding),
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DetailEdgePadding)
                        .padding(top = Dimens.SpaceLarge),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
            ) {
                ProgressLine(item = item)
                DetailActions(
                    item = item,
                    playTarget = playTarget,
                    downloadState = downloadState,
                    actions = actions,
                    isWide = false,
                )
                DetailBody(item = item, clampOverview = layout.clampsOverview)
            }
        }
    }
}

/**
 * The poster's overlap must stay a top padding, never a negative offset: inside a `LazyColumn` item
 * a negative offset draws outside the item's bounds and is clipped away by the row above.
 */
@Composable
private fun WideStage(
    item: JellyfinItem,
    playTarget: JellyfinItem?,
    backdropHeight: Dp,
    downloadState: DownloadState,
    actions: DetailActionHandlers,
    onNavigateToItemId: (String) -> Unit,
    modifier: Modifier = Modifier,
    downloadedBytes: Long? = null,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(backdropHeight)) {
            DetailBackdrop(item = item, height = backdropHeight, halo = true)
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.SpaceExtraLarge)
                    .padding(top = (backdropHeight - POSTER_OVERLAP).coerceAtLeast(0.dp)),
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraLarge),
        ) {
            DetailPoster(item = item)

            Column(
                // `fill = false` is what lets [FACTS_MAX_WIDTH] bite: a filled weight hands a fixed
                // width, and `widthIn` obeys the incoming constraint over its own.
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = FACTS_MAX_WIDTH)
                        .padding(top = FACTS_TOP_PADDING),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
            ) {
                TitleLockup(
                    item = item,
                    downloadState = downloadState,
                    downloadedBytes = downloadedBytes,
                    expanded = true,
                    onNavigateToItemId = onNavigateToItemId,
                )
                ProgressLine(item = item)
                DetailActions(
                    item = item,
                    playTarget = playTarget,
                    downloadState = downloadState,
                    actions = actions,
                    isWide = true,
                )
                DetailBody(item = item, clampOverview = DetailLayout.WIDE.clampsOverview)
            }
        }
    }
}

@Composable
private fun DetailBackdrop(
    item: JellyfinItem,
    height: Dp,
    halo: Boolean,
) {
    BackdropHeader(
        imageUrl = item.backdropImageUrl ?: item.thumbImageUrl ?: item.primaryImageUrl,
        height = height,
    )
    if (halo) {
        Box(modifier = Modifier.fillMaxSize().heroHalo())
    }
}

data class DetailActionHandlers(
    val onPlay: () -> Unit,
    val onDownload: () -> Unit,
    val onToggleWatched: () -> Unit,
    val onToggleFavorite: () -> Unit,
    /** Non-null changes what [onPlay] *means*: in a group a play is the group's play. */
    val group: DetailGroupActions? = null,
)

/** [groupName] is carried because the buttons name the group they act on ("Play for Film night"). */
data class DetailGroupActions(
    val groupName: String,
    val onAction: (GroupAction) -> Unit,
)

@Composable
private fun DetailPoster(
    item: JellyfinItem,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(Dimens.PanelRadius)
    JellyfinAsyncImage(
        url = item.primaryImageUrl,
        contentDescription = null,
        modifier =
            modifier
                .width(Dimens.DetailPosterWidth)
                .height(Dimens.DetailPosterHeight)
                .popShadow(shape)
                .clip(shape),
        contentScale = ContentScale.Crop,
    )
}

/** @param expanded the wide/landscape size of the title (44sp rather than 34sp). */
@Composable
private fun TitleLockup(
    item: JellyfinItem,
    downloadState: DownloadState,
    downloadedBytes: Long?,
    expanded: Boolean,
    onNavigateToItemId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = TEXT_MAX_WIDTH),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        item.typeEyebrow()?.let { eyebrow ->
            Text(
                text = eyebrow.drawn,
                style = JellyfinTypeExtras.Eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = eyebrow.spoken },
            )
        }

        val title = item.displayTitle
        Text(
            text = title,
            style = if (expanded) JellyfinTypeExtras.HeroTitleExpanded else JellyfinTypeExtras.HeroTitleCompact,
            // `HomeHero.HeroTitle`'s rule: the scrim has faded to the page colour by this row, so
            // this is page ink, not the over-artwork white the rating badge above still uses.
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // The page's only heading — TalkBack's heading-jump has nowhere else to land. The
            // contentDescription speaks the full title whatever the two lines had room for.
            modifier =
                Modifier.semantics {
                    heading()
                    contentDescription = title
                },
        )

        item.subtitleLine()?.let { subtitle ->
            Text(
                text = subtitle,
                style = SubtitleStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        EpisodeOriginChips(item = item, onNavigateToItemId = onNavigateToItemId)

        MetaRow(item = item, downloadState = downloadState, downloadedBytes = downloadedBytes)
    }
}

/**
 * Called from [TitleLockup] only, which both heroes share — putting it in [DetailHero] or
 * [WideStage] would need two call sites. Either chip drops out independently: the server may give a
 * `seasonId` with no [JellyfinItem.parentIndexNumber], or the reverse.
 */
@Composable
internal fun EpisodeOriginChips(
    item: JellyfinItem,
    onNavigateToItemId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (item.type != ItemType.EPISODE) return

    val seriesId = item.seriesId
    val seriesName = item.seriesName
    val seasonId = item.seasonId
    val seasonNumber = item.parentIndexNumber
    val showSeries = seriesId != null && seriesName != null
    val showSeason = seasonId != null && seasonNumber != null
    if (!showSeries && !showSeason) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        if (seriesId != null && seriesName != null) {
            val description = stringResource(R.string.detail_go_to_series, seriesName)
            ActionPillChip(
                text = seriesName,
                onClick = { onNavigateToItemId(seriesId) },
                modifier =
                    Modifier
                        .widthIn(max = OriginChipMaxWidth)
                        .semantics(mergeDescendants = true) { contentDescription = description },
            )
        }
        if (seasonId != null && seasonNumber != null) {
            val label = stringResource(R.string.detail_go_to_season, seasonNumber)
            val description = stringResource(R.string.detail_go_to_season_description, seasonNumber)
            ActionPillChip(
                text = label,
                onClick = { onNavigateToItemId(seasonId) },
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
            )
        }
    }
}

/**
 * `★ 8.6 · 2016 · TV-MA · 4 seasons`, skipping whatever the server does not know.
 *
 * [downloadedBytes] is trusted **only** while [downloadState] is [DownloadState.Downloaded], so a
 * season mid-download shows the server's figure rather than a partial sum.
 *
 * One merged node to a screen reader, and the description must qualify the bare numbers in words —
 * drawn, "8.6" and "TV-MA" say nothing about what they are numbers of.
 */
@Composable
private fun MetaRow(
    item: JellyfinItem,
    downloadState: DownloadState,
    modifier: Modifier = Modifier,
    downloadedBytes: Long? = null,
) {
    val facts = item.metaFacts(downloadState = downloadState, downloadedBytes = downloadedBytes)
    if (item.communityRating == null && item.officialRating == null && facts.isEmpty()) return

    val description =
        metaRowDescription(
            rating = item.communityRating?.let { stringResource(R.string.detail_meta_rating, formatRatingBadge(it)) },
            year = item.productionYear?.toString(),
            certificate = item.officialRating?.let { stringResource(R.string.detail_meta_rated, it) },
            facts = facts,
        )

    FlowRow(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(MetaGap),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        item.communityRating?.let { rating ->
            RatingFact(rating = rating, modifier = Modifier.align(Alignment.CenterVertically))
        }
        item.productionYear?.let { year ->
            MetaText(text = year.toString(), modifier = Modifier.align(Alignment.CenterVertically))
        }
        item.officialRating?.let { certificate ->
            MPillBadge(text = certificate, modifier = Modifier.align(Alignment.CenterVertically))
        }
        if (facts.isNotEmpty()) {
            MetaText(
                text = facts.joinToString(Separators.DOT),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * Extracted so the *order* is pinned by a JVM test. [describeParts] drops blanks, so a server that
 * answers `""` for a certificate cannot produce a dangling "rated".
 *
 * @param rating already qualified ("Rating 8.6") and formatted.
 * @param certificate already qualified ("rated TV-MA").
 */
internal fun metaRowDescription(
    rating: String?,
    year: String?,
    certificate: String?,
    facts: List<String>,
): String = describeParts(listOf(rating, year, certificate) + facts)

@Composable
private fun RatingFact(
    rating: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(RatingStarSize),
        )
        Text(text = formatRatingBadge(rating), style = RatingStyle, color = Color.White)
    }
}

@Composable
private fun MetaText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MetaStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun ProgressLine(
    item: JellyfinItem,
    modifier: Modifier = Modifier,
) {
    val progress = item.playbackProgress ?: return
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier.fillMaxWidth().height(Dimens.InsetProgressHeight),
        color = MaterialTheme.colorScheme.primary,
        trackColor = pageInk(darkAlpha = PROGRESS_TRACK_ALPHA, lightAlpha = PROGRESS_TRACK_ALPHA_LIGHT),
        drawStopIndicator = {},
    )
}

@Composable
private fun DetailBody(
    item: JellyfinItem,
    clampOverview: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        item.taglines.firstOrNull()?.let { tagline ->
            Text(
                text = tagline,
                style = TaglineStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH),
            )
        }

        item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            if (clampOverview) {
                ExpandableOverview(text = overview)
            } else {
                Text(
                    text = overview,
                    style = OverviewStyle,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH),
                )
            }
        }

        item.creditLine()?.let { credits ->
            Text(
                text = credits,
                style = CreditStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = TEXT_MAX_WIDTH),
            )
        }

        if (item.genres.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            ) {
                item.genres.forEach { genre ->
                    // An inert label, not a filter: genre filtering lives on the library grid.
                    InfoPillChip(text = genre)
                }
            }
        }
    }
}

/** Compact has no favourite heart here: it lives in `ItemDetailScreen`'s overlay nav instead. */
@Composable
private fun DetailActions(
    item: JellyfinItem,
    playTarget: JellyfinItem?,
    downloadState: DownloadState,
    actions: DetailActionHandlers,
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    val watched = item.userData.played
    val label = playLabel(item = item, playTarget = playTarget, group = actions.group)
    val playIcon = if (actions.group == null) Icons.Filled.PlayArrow else Icons.Outlined.Groups

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        if (isWide) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            ) {
                PrimaryPillButton(text = label, onClick = actions.onPlay, leadingIcon = playIcon)
                DownloadButton(state = downloadState, onClick = actions.onDownload, labelled = true)
                FavoriteButton(
                    favorite = item.userData.isFavorite,
                    onClick = actions.onToggleFavorite,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                WatchedButton(
                    watched = watched,
                    onClick = actions.onToggleWatched,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        } else {
            // A plain Row, not a FlowRow: a weighted child in a wrapping row makes the circles wrap
            // onto a line of their own.
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryPillButton(
                    text = label,
                    onClick = actions.onPlay,
                    modifier = Modifier.weight(1f),
                    leadingIcon = playIcon,
                )
                DownloadButton(state = downloadState, onClick = actions.onDownload)
                WatchedButton(watched = watched, onClick = actions.onToggleWatched)
            }
        }

        actions.group?.let { group ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
            ) {
                GroupActionButtons(group = group)
            }
        }
    }
}

/**
 * In a group there is no solo escape hatch on this page, so the label must carry the group's name —
 * the meaning of Play changes and must not change silently.
 */
@Composable
private fun playLabel(
    item: JellyfinItem,
    playTarget: JellyfinItem?,
    group: DetailGroupActions?,
): String {
    val resume = item.userData.isResumable
    val episode =
        playTarget
            ?.takeIf { it.type == ItemType.EPISODE && it.id != item.id }
            ?.episodeNumberLabel()
    return when {
        group != null && resume -> stringResource(R.string.detail_group_resume, group.groupName)
        group != null -> stringResource(R.string.detail_group_play, group.groupName)
        episode != null && resume -> stringResource(R.string.detail_resume_target, episode)
        episode != null -> stringResource(R.string.detail_play_target, episode)
        resume -> stringResource(R.string.detail_resume)
        else -> stringResource(CoreUiR.string.action_play)
    }
}

/**
 * A paragraph that already fits must not become tappable, and the clickable one must keep its state
 * and click label — TalkBack reads the click label in place of "double tap to activate".
 *
 * `expanded` and `overflowing` must survive the same events, which is why one saveable
 * [OverviewState] holds both: a saveable `expanded` beside a `remember`ed `overflowing` leaves a
 * restored paragraph inert until a layout pass re-measures it.
 *
 * The `onTextLayout` write must stay **conditional on the value changing**: it runs inside layout
 * under `animateContentSize`, so an unconditional write recomposes on every frame of the animation.
 */
@Composable
private fun ExpandableOverview(
    text: String,
    modifier: Modifier = Modifier,
) {
    var state by rememberSaveable(stateSaver = OverviewState.Saver) { mutableStateOf(OverviewState()) }
    val expanded = state.expanded
    val overflowing = state.overflowing
    val toggleable = overflowing || expanded
    val expandedState =
        stringResource(if (expanded) R.string.detail_overview_expanded else R.string.detail_overview_collapsed)
    val clickLabel =
        stringResource(if (expanded) R.string.detail_overview_collapse else R.string.detail_overview_expand)
    Text(
        text = text,
        style = OverviewStyle,
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = if (expanded) Int.MAX_VALUE else COMPACT_OVERVIEW_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            // Idempotent by construction — see this composable's KDoc.
            if (!expanded && result.hasVisualOverflow != overflowing) {
                state = state.copy(overflowing = result.hasVisualOverflow)
            }
        },
        modifier =
            modifier
                .widthIn(max = TEXT_MAX_WIDTH)
                .animateContentSize()
                .then(
                    if (toggleable) {
                        Modifier
                            .semantics { stateDescription = expandedState }
                            .clickable(onClickLabel = clickLabel) {
                                state = state.copy(expanded = !expanded)
                            }
                    } else {
                        Modifier
                    },
                ),
    )
}

/** One value rather than two `rememberSaveable`s, so an edit cannot re-introduce the asymmetry. */
private data class OverviewState(
    val expanded: Boolean = false,
    val overflowing: Boolean = false,
) {
    companion object {
        val Saver: Saver<OverviewState, List<Boolean>> =
            Saver(
                save = { listOf(it.expanded, it.overflowing) },
                restore = { OverviewState(expanded = it[0], overflowing = it[1]) },
            )
    }
}

/** About a third of a 360×800 screen. */
private const val COMPACT_OVERVIEW_MAX_LINES = 5

/** The icon's `contentDescription` is the only thing naming this action to TalkBack. */
@Composable
private fun WatchedButton(
    watched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassIconButton(
        icon = if (watched) Icons.Filled.Check else Icons.Outlined.CheckCircle,
        contentDescription =
            stringResource(if (watched) R.string.detail_mark_unwatched else R.string.detail_mark_watched),
        onClick = onClick,
        modifier = modifier,
        size = Dimens.PillHeight,
        tint = accent(watched),
    )
}

/** Wide only — compact hosts the heart in `ItemDetailScreen`'s overlay nav. */
@Composable
private fun FavoriteButton(
    favorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassIconButton(
        icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
        contentDescription =
            stringResource(if (favorite) R.string.detail_remove_favorite else R.string.detail_add_favorite),
        onClick = onClick,
        modifier = modifier,
        size = Dimens.PillHeight,
        tint = accent(favorite),
    )
}

/** Ghost pills, not primary: the page keeps exactly one primary action. */
@Composable
private fun GroupActionButtons(group: DetailGroupActions) {
    GhostPillButton(
        text = stringResource(R.string.detail_group_play_next),
        onClick = { group.onAction(GroupAction.PLAY_NEXT) },
        small = true,
        leadingIcon = Icons.AutoMirrored.Outlined.PlaylistPlay,
    )
    GhostPillButton(
        text = stringResource(R.string.detail_group_add_to_queue),
        onClick = { group.onAction(GroupAction.ADD_TO_QUEUE) },
        small = true,
        leadingIcon = Icons.AutoMirrored.Outlined.PlaylistAdd,
    )
}

/**
 * Four actions in one coat — download, cancel, remove, retry — and a transfer in flight draws a
 * determinate ring instead of a glyph, which is why this is not a plain `GlassIconButton`.
 *
 * @param labelled the wide form. Both forms must keep the same state machine: a static
 *   `GhostPillButton` off `labelRes()` would freeze at "Cancel" for a whole transfer.
 */
@Composable
private fun DownloadButton(
    state: DownloadState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    labelled: Boolean = false,
) {
    val label = stringResource(state.labelRes())

    if (labelled) {
        GhostPillButton(
            text = label,
            onClick = onClick,
            modifier = modifier,
            leadingIcon = state.icon(),
            progress = (state as? DownloadState.Downloading)?.progress,
            leadingIconTint =
                if (state is DownloadState.Downloaded || state is DownloadState.Downloading) {
                    MaterialTheme.colorScheme.primary
                } else {
                    null
                },
        )
        return
    }

    if (state is DownloadState.Downloading) {
        Box(
            modifier =
                modifier
                    .size(Dimens.PillHeight)
                    .glassSurface(CircleShape)
                    // The explicit role is load-bearing: a tappable progress ring is a button.
                    .clickable(role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.size(Dimens.BadgeSize),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = DownloadRingWidth,
            )
        }
    } else {
        GlassIconButton(
            icon = state.icon(),
            contentDescription = label,
            onClick = onClick,
            modifier = modifier,
            size = Dimens.PillHeight,
            tint = if (state is DownloadState.Downloaded) MaterialTheme.colorScheme.primary else GlassIconTint,
        )
    }
}

private fun DownloadState.icon() =
    when (this) {
        is DownloadState.NotDownloaded -> Icons.Outlined.Download
        is DownloadState.Downloaded -> Icons.Filled.DownloadDone
        is DownloadState.Failed -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.Downloading
    }

/** The label says what a tap *does*, not what the state is — the state is already the icon. */
private fun DownloadState.labelRes(): Int =
    when (this) {
        is DownloadState.NotDownloaded -> R.string.detail_download
        is DownloadState.Queued, is DownloadState.Downloading, is DownloadState.Paused ->
            CoreUiR.string.action_cancel

        is DownloadState.Downloaded -> R.string.detail_download_remove
        is DownloadState.Failed -> CoreUiR.string.state_retry
    }

@Composable
private fun accent(active: Boolean) = if (active) MaterialTheme.colorScheme.primary else GlassIconTint

@Composable
private fun JellyfinItem.metaFacts(
    downloadState: DownloadState,
    downloadedBytes: Long?,
): List<String> =
    buildList {
        val children = childCountLabel()
        if (children != null) {
            add(children)
        } else {
            runtimeMinutes?.let { add(stringResource(R.string.detail_runtime_minutes, it)) }
        }
        if (downloadState is DownloadState.Downloaded && downloadedBytes != null && downloadedBytes > 0) {
            add(stringResource(R.string.detail_size_on_device, formatBytes(downloadedBytes)))
        } else {
            sizeBytes?.let { add(formatBytes(it)) }
        }
        remainingMinutes?.let { add(stringResource(R.string.detail_remaining_minutes, it)) }
    }

@Composable
private fun JellyfinItem.childCountLabel(): String? {
    val count = childCount ?: return null
    return when (type) {
        ItemType.SERIES -> pluralStringResource(R.plurals.detail_season_count, count, count)
        ItemType.SEASON -> pluralStringResource(R.plurals.detail_episode_count, count, count)
        else -> null
    }
}

/**
 * Drawn and spoken are deliberately different strings, as in `TagPill`: uppercase in the *device's*
 * locale (bare `uppercase()` takes the system locale, not the resources' one, and maps `i` to `İ` in
 * Turkish), and spoken in sentence case, or TalkBack spells the eyebrow out "S-E-R-I-E-S".
 */
@Composable
private fun JellyfinItem.typeEyebrow(): TypeEyebrow? {
    val label =
        when (type) {
            ItemType.MOVIE -> R.string.detail_type_movie
            ItemType.SERIES -> R.string.detail_type_series
            ItemType.SEASON -> R.string.detail_type_season
            ItemType.EPISODE -> R.string.detail_type_episode
            else -> return null
        }
    val spoken = stringResource(label)
    val locale = LocalConfiguration.current.locales[0]
    return TypeEyebrow(drawn = spoken.uppercase(locale), spoken = spoken)
}

private data class TypeEyebrow(
    val drawn: String,
    val spoken: String,
)

@Composable
private fun JellyfinItem.creditLine(): String? {
    val director = people.firstOrNull { it.kind == PersonKind.DIRECTOR }?.name
    val cast =
        people
            .filter { it.kind == PersonKind.ACTOR || it.kind == PersonKind.GUEST_STAR }
            .take(TOP_BILLED)
            .map { it.name }
    val parts =
        buildList {
            director?.let { add(stringResource(R.string.detail_directed_by, it)) }
            if (cast.isNotEmpty()) add(cast.joinToString(", "))
        }
    return parts.joinToString(Separators.DOT).ifBlank { null }
}

private const val TOP_BILLED = 4

/** The mocks' 20dp detail padding. */
internal val DetailEdgePadding = 20.dp

private val POSTER_OVERLAP = 190.dp

/** Lands the wide facts column's eyebrow inside the backdrop. */
private val FACTS_TOP_PADDING = 96.dp

private val FACTS_MAX_WIDTH = 660.dp

/** A full-width paragraph on a tablet is unreadable. */
private val TEXT_MAX_WIDTH = 680.dp

private val OriginChipMaxWidth = 200.dp

private val MetaGap = 10.dp

private val RatingStarSize = 13.dp

private val DownloadRingWidth = 2.dp

/**
 * WCAG 1.4.11 asks 3:1 of the unfilled track: white@22% is 1.97:1 on `#101010`, white@40% is 3.82:1.
 */
private const val PROGRESS_TRACK_ALPHA = 0.40f

/** 0.40 of black is 2.82:1 on `#F6F7F8`, under WCAG 1.4.11's 3:1; 0.44 is 3.21:1. */
private const val PROGRESS_TRACK_ALPHA_LIGHT = 0.44f

private val RatingStyle =
    TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.W600,
    )

private val MetaStyle =
    TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

private val SubtitleStyle =
    TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.W500,
        lineHeight = 18.sp,
    )

private val TaglineStyle =
    TextStyle(
        fontSize = 13.sp,
        fontStyle = FontStyle.Italic,
        lineHeight = 18.sp,
    )

private val OverviewStyle =
    TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
    )

private val CreditStyle =
    TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.01.em,
    )

@Preview(name = "DetailHero", showBackground = true, backgroundColor = 0xFF101010, widthDp = 420, heightDp = 900)
@Composable
private fun DetailHeroPreview() {
    JellyfinTheme {
        DetailHero(
            item = previewSeries,
            playTarget = previewEpisodeTarget,
            layout = DetailLayout.MEDIUM,
            backdropHeight = 416.dp,
            downloadState = DownloadState.NotDownloaded,
            actions = previewActionHandlers,
            onNavigateToItemId = {},
        )
    }
}

@Preview(
    name = "DetailHero — compact",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 360,
    heightDp = 900,
)
@Composable
private fun DetailHeroCompactPreview() {
    JellyfinTheme {
        DetailHero(
            item = previewSeries,
            playTarget = previewEpisodeTarget,
            layout = DetailLayout.COMPACT,
            backdropHeight = 416.dp,
            downloadState = DownloadState.Downloaded,
            actions = previewActionHandlers,
            onNavigateToItemId = {},
        )
    }
}

@Preview(
    name = "DetailHero — wide",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 1000,
    heightDp = 700,
)
@Composable
private fun DetailHeroWidePreview() {
    JellyfinTheme {
        DetailHero(
            item = previewSeries,
            playTarget = previewEpisodeTarget,
            layout = DetailLayout.WIDE,
            backdropHeight = 360.dp,
            downloadState = DownloadState.NotDownloaded,
            actions = previewActionHandlers,
            onNavigateToItemId = {},
        )
    }
}

private val previewSeries =
    JellyfinItem(
        id = "1",
        name = "Westworld",
        type = ItemType.SERIES,
        productionYear = 2016,
        communityRating = 8.6f,
        officialRating = "TV-MA",
        childCount = 4,
        genres = listOf("Sci-Fi", "Drama", "Western"),
        taglines = listOf("These violent delights have violent ends."),
        overview =
            "A dark odyssey about the dawn of artificial consciousness and the evolution of sin, set " +
                "at the intersection of the near future and the reimagined past.",
    )

private val previewEpisodeTarget =
    JellyfinItem(
        id = "2",
        name = "The Original",
        type = ItemType.EPISODE,
        indexNumber = 1,
        parentIndexNumber = 1,
    )

private val previewActionHandlers =
    DetailActionHandlers(
        onPlay = {},
        onDownload = {},
        onToggleWatched = {},
        onToggleFavorite = {},
    )
