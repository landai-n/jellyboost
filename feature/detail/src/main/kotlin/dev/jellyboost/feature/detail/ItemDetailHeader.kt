package dev.jellyboost.feature.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import dev.jellyboost.core.ui.theme.JellyfinGradients
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.popShadow
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The top of the detail screen: the backdrop, the title lockup drawn **on** it, the action row, and
 * the long-form text under them (2026 refresh, spec section 4c).
 *
 * The lockup moved onto the artwork in the refresh — eyebrow, title and metadata now sit in the
 * bottom-left of the banner instead of in a block below it, which is what the taller backdrop
 * (`ItemDetailScreen`'s fractions) exists to make room for.
 *
 * On a wide screen the same lockup lives in a *stage*: a 190×285 poster overlaps the bottom of the
 * backdrop and the facts column runs beside it, capped at [FACTS_MAX_WIDTH] so a paragraph never
 * spans a tablet. The two shapes share every piece below the lockup, so a change to the metadata
 * line or the action row lands on both.
 *
 * @param playTarget what the Play button will actually start (`ItemDetailUiState.playTarget`) — the
 *   label says so ("Play S1 · E10") when a series or season page resolves to an episode.
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
 * The wide stage: poster over the backdrop's bottom edge, facts beside it.
 *
 * The overlap is a top padding on the whole row rather than a negative offset, so the stage stays a
 * single measured box — a negative offset inside a `LazyColumn` item would draw outside the item's
 * bounds and be clipped away by the row above it.
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
                // `fill = false` is what lets [FACTS_MAX_WIDTH] bite: a filled weight hands the
                // child a *fixed* width, and `widthIn` enforces the incoming constraint over its
                // own, so the cap would silently do nothing on a 1200dp tablet.
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

/** The artwork itself, with the scrim it always had and — on wide — the refresh's accent halo. */
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
        Box(modifier = Modifier.fillMaxSize().background(JellyfinGradients.HeroHalo))
    }
}

/** The things the header can do, bundled so the composables stay under the parameter limit. */
data class DetailActionHandlers(
    val onPlay: () -> Unit,
    val onDownload: () -> Unit,
    val onToggleWatched: () -> Unit,
    val onToggleFavorite: () -> Unit,
    /**
     * The SyncPlay group this page is acting for, or `null` when there is no group — which is the
     * ordinary case, and why the field is nullable rather than a flag beside a lambda (M11 Phase 4).
     * Carried in this bundle so no composable between here and the buttons grows a parameter for a
     * feature it does not otherwise know about.
     *
     * Non-null also changes what [onPlay] *means*: in a group a play is the group's play
     * (DECISIONS.md, 2026-07-31), which is why the Play button reads its label from here.
     */
    val group: DetailGroupActions? = null,
)

/**
 * The active group, and the one callback its queue buttons share.
 *
 * [groupName] is here because the buttons name the group they act on: "Play for Film night" says
 * what a tap does in a way "Play" cannot, and on a detail page a user may well have forgotten which
 * group they joined — which matters more now that Play itself is the group's play.
 */
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

/**
 * Eyebrow, title and metadata — the block that reads as the page's headline, wherever it is drawn.
 *
 * @param expanded the wide/landscape size of the title (44sp rather than 34sp).
 */
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
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // The page's own heading, which is what makes TalkBack's heading-jump land somewhere
            // useful on a screen whose first stop is otherwise a backdrop (audit A11Y-10). The full
            // title is spoken whatever the two ellipsized lines had room for.
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
 * The series and season an episode belongs to, as tappable chips under the title lockup —
 * a shortcut past the season page an episode used to require (episode-detail-shortcuts,
 * DECISIONS.md).
 *
 * Lives in [TitleLockup] and nowhere else: that composable is shared by both the compact and the
 * wide hero, so one call site serves both layouts (the point of putting it here rather than in
 * [DetailHero] or [WideStage] directly).
 *
 * Either chip is skipped independently when its own target is missing — a series page's episode
 * may carry a `seasonId` the server has not resolved a [JellyfinItem.parentIndexNumber] for, or vice
 * versa — and the whole row draws nothing for a non-episode item, or an episode with neither target.
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
 * The refresh splits the old single metadata line into three kinds of element — the accent-starred
 * community rating, the outlined certificate badge, and plain muted text for the rest — but the
 * *data* is the pre-refresh line's, in the pre-refresh order: a fact that used to show still shows.
 *
 * The size entry reads from the device rather than the server once a local copy is what the user
 * actually has: [downloadedBytes] is only trusted while [downloadState] itself is
 * [DownloadState.Downloaded], so a season mid-download (whose aggregate state is not yet
 * `Downloaded`) keeps showing the server's figure rather than a partial sum, and a fully-downloaded
 * container — which has no download row, and so no bytes, of its own — falls back to it too.
 *
 * To a screen reader the row is **one** node. Read as drawn it was four or five disconnected
 * fragments — "8.6", "2016", "TV-MA" — none of which says what it is a number *of* (accessibility
 * audit 2026-08-05, A11Y-21). The merged sentence qualifies the two that need it in words, the way
 * the star glyph and the badge outline qualify them to the eye: "Rating 8.6, 2016, rated TV-MA,
 * 4 seasons".
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
 * The metadata row as one sentence, in the order the row draws it.
 *
 * A plain function, so the *order* is held still by a JVM test rather than by a device. The join
 * itself — blanks dropped so a server that answers `""` for a certificate cannot produce a dangling
 * "rated", duplicates collapsed, and a comma rather than the interpunct the row draws — is
 * `:core:ui`'s [describeParts], shared with the cards and the home hero (audit DUP-8).
 *
 * @param rating the community rating, already qualified ("Rating 8.6") and formatted.
 * @param certificate the age certificate, already qualified ("rated TV-MA").
 * @param facts the same plain-text facts the row's last element joins with [SEPARATOR] — season
 *   count or runtime, size, time left — each of which already says what it is.
 */
internal fun metaRowDescription(
    rating: String?,
    year: String?,
    certificate: String?,
    facts: List<String>,
): String = describeParts(listOf(rating, year, certificate) + facts)

/** The starred community rating — the one part of the metadata line the refresh draws in colour. */
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

/** The resume bar, kept from the pre-refresh header — a page for a half-watched item says so. */
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
        trackColor = Color.White.copy(alpha = PROGRESS_TRACK_ALPHA),
        drawStopIndicator = {},
    )
}

/** Tagline, overview, credits and genres — everything under the action row, in both layouts. */
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
                    // Not a filter yet — genre filtering lives on the library grid (M3). The inert
                    // chip is what keeps it looking like the filter it will be without claiming to
                    // be a disabled one: a genre here is a label, and a screen reader is told so
                    // (accessibility audit 2026-08-05, A11Y-14).
                    InfoPillChip(text = genre)
                }
            }
        }
    }
}

/**
 * The action row.
 *
 * Compact keeps one worded button — the white Play pill, which stretches — beside 44dp glass
 * circles for download and mark-watched; the favourite heart moved up to the overlay nav
 * (`ItemDetailScreen`), which is what freed the room. Wide has the width for a labelled Download
 * ghost pill, and keeps favourite and watched as glass circles beside it.
 */
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
            // A plain Row, not a FlowRow: the Play pill takes the width the two circles leave, and
            // a weighted child in a wrapping row is what would decide to wrap them onto a line of
            // their own instead.
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
 * What the Play button says.
 *
 * In a group there is one Play button and it plays for the group — there is no second "Play for
 * group" button beside it, and no solo escape hatch on this page (DECISIONS.md, 2026-07-31): while
 * a group is joined, everything this page starts is started for everyone in it. What must *not*
 * happen is the meaning changing silently, so the label carries the group's name and the group icon
 * replaces the play triangle.
 *
 * Outside a group the refresh names the *episode* a container page will start — "Play S1 · E10",
 * from [playTarget] — which is the one thing "Play" on a series page never said. An episode's own
 * page keeps the bare word: its title lockup is already the episode.
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
 * The overview clamped to [COMPACT_OVERVIEW_MAX_LINES] on a phone, expanding (and collapsing
 * again) on tap. A paragraph that already fits is not made tappable — a ripple on inert text
 * would promise interaction it doesn't have.
 *
 * Non-visually the affordance used to be invisible: a clickable paragraph with no state and no
 * label, announced as five ellipsized lines that could be activated for reasons unknown
 * (accessibility audit 2026-08-05, A11Y-12). It now says which of the two states it is in, and
 * names what a tap does — TalkBack reads the click label in place of its own "double tap to
 * activate", so "Read full overview" is the whole of the promise.
 *
 * ### Why both flags are saveable, and why the measurement is written once
 * `expanded` and `overflowing` together decide whether the paragraph is tappable at all, so they
 * have to survive the same events or the control comes back in a state that cannot be described:
 * `expanded` was `rememberSaveable` and `overflowing` a plain `remember`, which meant a collapsed
 * paragraph restored after process death was briefly inert — clickable only once a layout pass had
 * re-measured it (audit 2026-08-08, UI-17). One saveable [OverviewState] holds both.
 *
 * The write from `onTextLayout` is also **conditional on the value changing**. `onTextLayout` runs
 * inside layout, and this `Text` sits under `animateContentSize`, so an unconditional
 * `overflowing = …` would queue a recomposition on every frame of the expand animation — the
 * measurement is settled after the first meaningful pass and re-asserting it is what turns a
 * one-shot into a per-frame loop. It is guarded today by the `if (!expanded)`; making the write
 * itself idempotent is what keeps it guarded after the next edit.
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
            // Only while collapsed (an expanded paragraph never overflows), and only when the
            // answer actually moves — see this composable's KDoc.
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

/**
 * Both halves of [ExpandableOverview]'s state, in one saveable object.
 *
 * Saveable rather than remembered: the pair is what makes the paragraph tappable, and restoring one
 * without the other brings the control back in a state its own semantics cannot describe. Kept as
 * one value rather than two `rememberSaveable`s so a future edit cannot re-introduce the asymmetry.
 */
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

/** Lines a compact overview shows before asking for a tap — about a third of a 360×800 screen. */
private const val COMPACT_OVERVIEW_MAX_LINES = 5

/**
 * The "mark watched/unwatched" toggle: a 44dp glass circle whose glyph goes accent when the item is
 * watched. The label it used to carry moved to the icon's `contentDescription`, so TalkBack reads
 * the same thing it always did.
 */
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

/** The favourite toggle, drawn here only on wide — compact hosts it in the overlay nav. */
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

/**
 * The two group *queue* actions, drawn only while a SyncPlay group is active (M11 Phase 4).
 *
 * They join the Play button — which is itself the group's play, see [playLabel] — because they are
 * the two things playing has no way to say: put this after what we are watching, or at the end.
 * Small ghost pills, so the page keeps exactly one primary action.
 */
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
 * The Download control: one button that is really four wearing one coat, its glyph saying what
 * tapping it does *now* — download, cancel, remove, retry.
 *
 * A transfer in flight keeps the determinate ring the cards' `DownloadBadge` draws, which is why
 * this is not simply a `GlassIconButton`: that case has no glyph at all, it has progress.
 *
 * @param labelled `true` on the wide layout, which has the room to say the word as well as draw the
 *   glyph, so the control becomes a ghost pill rather than a 44dp circle. The state machine is the
 *   same either way — which is the point. The wide layout used to draw a *static* `GhostPillButton`
 *   straight off `labelRes()`, so a tablet showed a frozen "Cancel" pill for the whole of a transfer
 *   the phone reported as a filling ring, and a finished download got none of the accent tint
 *   (audit 2026-08-08, UI-4). This function's KDoc claimed the ring for "the Download control" while
 *   only one of its two layouts had it.
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
            // The determinate ring and the completion tint the circular form has always drawn,
            // reported through the pill's leading slot instead of replacing the glyph wholesale.
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
                    // The one action row control that is not a `GlassIconButton`, and the only one
                    // that had no role: a progress ring you can tap is a button (ROLE-01).
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

/**
 * The label says what a tap *does*, not what the state is — the state is already the icon.
 *
 * Cancelling a queued, running or paused download and deleting a finished one are the same
 * operation with different words for it, which is why they share a handler.
 */
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

/** The facts that stay plain text in the metadata row, in the order the old single line used. */
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
 * `SERIES` / `MOVIE` — the lockup's eyebrow, or nothing for the shapes a user never opens.
 *
 * Returns the drawn text and the spoken one, which are deliberately not the same string — the same
 * split `TagPill` documents, and the pattern this eyebrow was missing both halves of (audit
 * 2026-08-08, UI-9):
 *
 * - **Uppercased in the *device's* locale**, not the JVM's root: `uppercase()` with no argument
 *   takes `Locale.getDefault()`, which on Android is the *system* locale rather than the one the
 *   resources resolved in, and in Turkish maps `i` to `İ`. The project's own rule
 *   (`PlayerControls.kt`, `config/lint/lint.xml`) says to pass the locale explicitly; the lint rule
 *   is not gateable, so this is the second place it has to be written down rather than checked.
 * - **Spoken in sentence case.** An all-caps word is a word to the eye and an initialism to a screen
 *   reader: TalkBack spelled the eyebrow out letter by letter, "S-E-R-I-E-S". The
 *   `contentDescription` carries the untransformed resource.
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

/** The eyebrow's two forms — see [typeEyebrow]. */
private data class TypeEyebrow(
    val drawn: String,
    val spoken: String,
)

/** `Directed by X · A, B, C` — the one-line version of the credits the cast rail draws in full. */
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

/** How many actors the credit line names before it stops. */
private const val TOP_BILLED = 4

/** Screen gutter of the lockup and of everything under it (the mocks' 20dp detail padding). */
internal val DetailEdgePadding = 20.dp

/** How far the wide stage's poster reaches up over the backdrop. */
private val POSTER_OVERLAP = 190.dp

/** Top inset of the wide facts column, which lands its eyebrow inside the backdrop. */
private val FACTS_TOP_PADDING = 96.dp

/** Width cap of the wide stage's facts column. */
private val FACTS_MAX_WIDTH = 660.dp

/** Long-form text stops growing here; a full-width paragraph on a tablet is unreadable. */
private val TEXT_MAX_WIDTH = 680.dp

/** Ellipsis point for the series origin chip — long titles stop here rather than stretching. */
private val OriginChipMaxWidth = 200.dp

private val MetaGap = 10.dp

private val RatingStarSize = 13.dp

private val DownloadRingWidth = 2.dp

/**
 * Track of the resume bar — the same white@40% the cards' inset progress uses.
 *
 * Raised from 22% by the 2026-08-05 accessibility audit: an unfilled track is what gives the filled
 * part a scale, so WCAG 1.4.11 asks 3:1 of it. 1.97:1 → 3.82:1 on `#101010`.
 */
private const val PROGRESS_TRACK_ALPHA = 0.40f

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
