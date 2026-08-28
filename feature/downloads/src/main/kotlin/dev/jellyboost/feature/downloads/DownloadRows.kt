package dev.jellyboost.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.formatDurationSeconds
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.mSurface
import dev.jellyboost.core.ui.theme.pageInk
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.SizeCertainty
import kotlin.math.roundToInt
import dev.jellyboost.core.ui.R as CoreUiR

private val ROW_ART_RADIUS = 8.dp

private val ROW_ART_WIDTH_COMPACT = 64.dp
private val ROW_ART_HEIGHT_COMPACT = 38.dp

private val ROW_ART_WIDTH_WIDE = 76.dp
private val ROW_ART_HEIGHT_WIDE = 44.dp

/** Half the list's 10dp inter-card gap — applied top *and* bottom of every row. */
private val ROW_GAP_HALF = 5.dp

/**
 * WCAG 1.4.11 asks 3:1 of the unfilled track: white@22% is 1.97:1 on `#101010`, white@40% is 3.82:1
 * there and 3.75:1 on a card's `#202020`.
 */
private const val QUEUE_TRACK_ALPHA = 0.40f

/** 0.44 of the light page's ink is 2.79:1 on a white card, under WCAG 1.4.11's 3:1; 0.48 is 3.13:1. */
private const val QUEUE_TRACK_ALPHA_LIGHT = 0.48f

private val QueueTitleCompact = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)
private val QueueTitleWide = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500)
private val QueueStatusCompact = TextStyle(fontSize = 11.sp)
private val QueueStatusWide = TextStyle(fontSize = 12.sp)

private val CardTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500, lineHeight = 18.sp)
private val CardSubtitle = TextStyle(fontSize = 12.sp)

/**
 * @param onPlay the whole row is the target, unconditionally — the *Downloaded* tab has no
 *   batch-selection mode for the click to conflict with, unlike `:feature:detail`'s episode rows.
 * @param compact must stay [QueueRow]'s own width class, or switching tabs shifts the text columns
 *   and row height out from under the user.
 * @param showArtwork `false` inside an album group, whose header already carries the one cover every
 *   track shares. A loose track has no header to carry it, so it keeps its own.
 */
@Composable
@Suppress("LongParameterList")
internal fun DownloadedRow(
    item: DownloadItem,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    inGroup: Boolean = false,
    compact: Boolean = false,
    showArtwork: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PanelPadding, vertical = ROW_GAP_HALF)
                .mSurface(MaterialTheme.colorScheme.surface)
                // The label and role are load-bearing: a bare `clickable` announces nothing at all.
                .clickable(
                    onClickLabel = stringResource(CoreUiR.string.action_play),
                    role = Role.Button,
                    onClick = onPlay,
                ).padding(Dimens.SpaceMedium),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArtwork) {
            RowArtwork(
                imageUrl = item.item?.primaryImageUrl,
                width = if (compact) ROW_ART_WIDTH_COMPACT else ROW_ART_WIDTH_WIDE,
                height = if (compact) ROW_ART_HEIGHT_COMPACT else ROW_ART_HEIGHT_WIDE,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.rowTitle(inGroup = inGroup),
                style = CardTitle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(formatBytes(item.bytesOnDisk), item.transcodedMarker())
                        .joinToString(Separators.DOT),
                style = CardSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GlassIconButton(
            icon = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.downloads_action_delete),
            onClick = onDelete,
            size = Dimens.PillHeightSmall,
        )
    }
}

/**
 * @param progress **not** `item.progress`: it comes through [DownloadProgressRatchet] so the bar
 *   cannot run backwards while the projection behind its denominator settles.
 * @param compact two tiers, because on one row artwork + weighted text + four action buttons leave
 *   the title under ~90dp — ~4 characters on a 360dp phone. Decided once at the screen level.
 */
@Composable
internal fun QueueRow(
    item: DownloadItem,
    progress: Float,
    speedBytesPerSecond: Long?,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cardModifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.PanelPadding, vertical = ROW_GAP_HALF)
            .mSurface(MaterialTheme.colorScheme.surface)
            .padding(Dimens.SpaceMedium)

    if (compact) {
        TwoTierQueueRow(
            item = item,
            progress = progress,
            speedBytesPerSecond = speedBytesPerSecond,
            actions = actions,
            modifier = cardModifier,
        )
    } else {
        OneTierQueueRow(
            item = item,
            progress = progress,
            speedBytesPerSecond = speedBytesPerSecond,
            actions = actions,
            modifier = cardModifier,
        )
    }
}

@Composable
private fun TwoTierQueueRow(
    item: DownloadItem,
    progress: Float,
    speedBytesPerSecond: Long?,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowArtwork(
                imageUrl = item.item?.primaryImageUrl,
                width = ROW_ART_WIDTH_COMPACT,
                height = ROW_ART_HEIGHT_COMPACT,
            )
            QueueRowText(
                item = item,
                progress = progress,
                speedBytesPerSecond = speedBytesPerSecond,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
        QueueRowActions(
            itemId = item.itemId,
            isResumeTarget = item.isResumeTarget,
            isPauseTarget = item.isPauseTarget,
            actions = actions,
            size = Dimens.PillHeightSmall,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun OneTierQueueRow(
    item: DownloadItem,
    progress: Float,
    speedBytesPerSecond: Long?,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowArtwork(
            imageUrl = item.item?.primaryImageUrl,
            width = ROW_ART_WIDTH_WIDE,
            height = ROW_ART_HEIGHT_WIDE,
        )
        QueueRowText(
            item = item,
            progress = progress,
            speedBytesPerSecond = speedBytesPerSecond,
            compact = false,
            modifier = Modifier.weight(1f),
        )
        QueueRowActions(
            itemId = item.itemId,
            isResumeTarget = item.isResumeTarget,
            isPauseTarget = item.isPauseTarget,
            actions = actions,
            size = Dimens.PillHeightSmall,
        )
    }
}

/**
 * `clearAndSetSemantics`, not a merge: the visible title is `maxLines = 1` and the description has
 * to carry the whole one, and it takes the progress bar's bare "45 percent" node out in the same
 * stroke — untied to a row, that number means nothing in a queue of five.
 *
 * The four action buttons must stay **siblings** of this column, not descendants, so each keeps its
 * own stop and label.
 *
 * The status shares no line with the title at either width: the size·speed·ETA string is long enough
 * to starve the title down to a few characters on a portrait tablet, which is wide enough to take
 * the non-compact treatment. Only the type scale still differs by [compact].
 */
@Composable
private fun QueueRowText(
    item: DownloadItem,
    progress: Float,
    speedBytesPerSecond: Long?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val failed = item.status == DownloadStatus.ERROR
    val statusColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val trackFillColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val statusText = item.statusLine(speedBytesPerSecond)
    val description =
        stringResource(
            R.string.downloads_queue_row_description,
            item.rowTitle(),
            percentOf(progress),
            statusText,
        )

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        Text(
            text = item.rowTitle(),
            style = if (compact) QueueTitleCompact else QueueTitleWide,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        QueueTrack(progress = progress, fillColor = trackFillColor)
        Text(
            text = statusText,
            style = if (compact) QueueStatusCompact else QueueStatusWide,
            color = statusColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * @param fillColor [MaterialTheme.colorScheme.error] for a failed row, so the bar reads as stopped
 *   partway rather than as still making progress.
 */
@Composable
private fun QueueTrack(
    progress: Float,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier.fillMaxWidth().height(Dimens.InsetProgressHeight),
        color = fillColor,
        trackColor = pageInk(darkAlpha = QUEUE_TRACK_ALPHA, lightAlpha = QUEUE_TRACK_ALPHA_LIGHT),
        drawStopIndicator = {},
    )
}

/**
 * Takes the id and two predicates, never the row: a `DownloadItem` is unstable and rebuilt two to
 * six times a second, which would recompose four icon buttons on every progress write.
 *
 * Both predicates are `DownloadsUiState`'s, shared with *Resume all* / *Pause all*, so a row and the
 * bulk button can never disagree. A transcode is never a pause target: the server ignores `Range` on
 * a file it is still producing, so pausing one discards everything downloaded so far.
 */
@Composable
private fun QueueRowActions(
    itemId: String,
    isResumeTarget: Boolean,
    isPauseTarget: Boolean,
    actions: DownloadsActions,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall)) {
        GlassIconButton(
            icon = Icons.Filled.ArrowUpward,
            contentDescription = stringResource(R.string.downloads_action_move_up),
            onClick = { actions.onMoveUp(itemId) },
            size = size,
        )
        GlassIconButton(
            icon = Icons.Filled.ArrowDownward,
            contentDescription = stringResource(R.string.downloads_action_move_down),
            onClick = { actions.onMoveDown(itemId) },
            size = size,
        )

        if (isResumeTarget) {
            GlassIconButton(
                icon = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.downloads_action_resume),
                onClick = { actions.onResume(itemId) },
                size = size,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (isPauseTarget) {
            GlassIconButton(
                icon = Icons.Filled.Pause,
                contentDescription = stringResource(R.string.downloads_action_pause),
                onClick = { actions.onPause(itemId) },
                size = size,
            )
        }

        GlassIconButton(
            icon = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.downloads_action_cancel),
            onClick = { actions.onDelete(itemId) },
            size = size,
        )
    }
}

/** Takes the URL, not the row: a `DownloadItem` would recompose it on every progress write. */
@Composable
internal fun RowArtwork(
    imageUrl: String?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    JellyfinAsyncImage(
        url = imageUrl,
        contentDescription = null,
        modifier =
            modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(ROW_ART_RADIUS)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * Clamped, because the fraction reaching a row is the *ratcheted* one, computed against a projected
 * total that can briefly exceed 1 — "101 percent" reads as a bug in the number. `internal` so the
 * rounding is checkable without a Compose harness.
 */
internal fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * PERCENT_SCALE).roundToInt()

private const val PERCENT_SCALE = 100

/**
 * @param inGroup the group header already names the series or album, so the row drops it. Standalone
 *   rows — the queue tab, and films, which are never grouped — keep the full `Group · Title` form.
 */
internal fun DownloadItem.rowTitle(inGroup: Boolean = false): String =
    if (inGroup) title else listOfNotNull(groupTitle, title).joinToString(Separators.DOT)

@Composable
private fun DownloadItem.statusLine(speedBytesPerSecond: Long?): String =
    when (status) {
        DownloadStatus.DOWNLOADING ->
            listOfNotNull(
                stringResource(
                    when (sizeCertainty) {
                        SizeCertainty.EXACT -> R.string.downloads_progress_of
                        SizeCertainty.APPROXIMATE -> R.string.downloads_progress_of_approx
                        SizeCertainty.CEILING -> R.string.downloads_progress_of_capped
                    },
                    formatBytes(bytesDownloaded),
                    formatBytes(displayTotalBytes),
                ),
                speedBytesPerSecond
                    ?.takeIf { it > 0L }
                    ?.let { stringResource(R.string.downloads_speed, formatBytes(it)) },
                etaSeconds(speedBytesPerSecond)?.let { etaText(it) },
                transcodedMarker(),
            ).joinToString(Separators.DOT)

        DownloadStatus.QUEUED ->
            listOfNotNull(
                stringResource(R.string.downloads_status_queued),
                // A size is known at enqueue time (`DownloadEnqueuer.sizeEstimate`), so even a row
                // waiting its turn has one to show.
                displayTotalBytes.takeIf { it > 0L }?.let { expectedSizeText(it) },
                transcodedMarker(),
            ).joinToString(Separators.DOT)

        DownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        DownloadStatus.ERROR ->
            errorMessage?.let { stringResource(R.string.downloads_status_failed_reason, it) }
                ?: stringResource(R.string.downloads_status_failed)

        DownloadStatus.DOWNLOADED, DownloadStatus.CANCELLED ->
            listOfNotNull(formatBytes(bytesOnDisk), transcodedMarker()).joinToString(Separators.DOT)
    }

/**
 * `null` covers: no speed yet, a stalled transfer, a row at or past its own total (the progress and
 * projection writes can interleave — see [DownloadItem.displayTotalBytes]), and anything beyond
 * [ETA_GUARD_SECONDS], where a low instantaneous speed yields a number in the days.
 */
@Suppress("ReturnCount") // Four `?: return null` elvis guards; the KDoc above enumerates exactly what each one covers.
internal fun DownloadItem.etaSeconds(speedBytesPerSecond: Long?): Long? {
    val speed = speedBytesPerSecond?.takeIf { it > 0L } ?: return null
    val total = displayTotalBytes.takeIf { it > 0L } ?: return null
    val remaining = total - bytesDownloaded
    if (remaining <= 0L) return null

    val eta = (remaining + speed - 1) / speed
    return eta.takeIf { it <= ETA_GUARD_SECONDS }
}

/**
 * `internal`, not `private`: [DownloadsUiState.queueStats] applies the same guard to an *aggregate*
 * remainder, and sharing the constant is what keeps the two from disagreeing.
 */
internal const val ETA_GUARD_SECONDS = 86_400L

/**
 * Hedged to match [expectedSizeText]: an ETA off a [SizeCertainty.CEILING] total is exactly as
 * approximate as that total is.
 */
@Composable
private fun DownloadItem.etaText(etaSecondsValue: Long): String =
    stringResource(
        if (sizeCertainty == SizeCertainty.CEILING) R.string.downloads_eta_approx else R.string.downloads_eta,
        formatDurationSeconds(etaSecondsValue),
    )

/** Always the **last** segment of a status line, so it never displaces the size, speed or ETA. */
@Composable
private fun DownloadItem.transcodedMarker(): String? =
    if (quality.isTranscoded) stringResource(R.string.downloads_transcoded_marker) else null

/**
 * The hedge is load-bearing: *"552,4 MB"* exact, *"~301,2 MB"* projected, *"up to 552,4 MB"* a mere
 * bound. Stating a ceiling as exact read as wrong once the real file landed at half of it.
 */
@Composable
private fun DownloadItem.expectedSizeText(bytes: Long): String =
    when (sizeCertainty) {
        SizeCertainty.EXACT -> formatBytes(bytes)
        SizeCertainty.APPROXIMATE -> stringResource(R.string.downloads_size_approx, formatBytes(bytes))
        SizeCertainty.CEILING -> stringResource(R.string.downloads_size_capped, formatBytes(bytes))
    }
