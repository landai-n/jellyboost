package dev.jellyboost.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.formatDurationSeconds
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.JellyfinAsyncImage
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.SizeCertainty

/** Artwork corner radius for every row on this screen — the "m-surface card" language's own radius. */
private val ROW_ART_RADIUS = 8.dp

/** [QueueRow]'s compact-layout artwork size (2026 refresh, spec "4d Downloads"). */
private val ROW_ART_WIDTH_COMPACT = 64.dp
private val ROW_ART_HEIGHT_COMPACT = 38.dp

/** [QueueRow]'s wide-layout artwork size, also used uniformly by [DownloadedRow]. */
private val ROW_ART_WIDTH_WIDE = 76.dp
private val ROW_ART_HEIGHT_WIDE = 44.dp

/** [QueueRow]'s compact-layout trailing action circle diameter. */
private val ACTION_CIRCLE_SIZE_COMPACT = 32.dp

/** [QueueRow]'s wide-layout trailing action circle diameter, also used by [DownloadedRow]. */
private val ACTION_CIRCLE_SIZE_WIDE = 34.dp

/** Half the "m-surface card" list's 10dp inter-card gap — applied top and bottom of every row. */
private val ROW_GAP_HALF = 5.dp

/** Track alpha behind a queue row's 3dp progress bar — the app's standing "inset progress" alpha. */
private const val QUEUE_TRACK_ALPHA = 0.22f

private val QueueTitleCompact = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)
private val QueueTitleWide = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500)
private val QueueStatusCompact = TextStyle(fontSize = 11.sp)
private val QueueStatusWide = TextStyle(fontSize = 12.sp)

/** Shared "card text" title/subtitle styles (spec, "Shared visual language" → Card text). */
private val CardTitle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500, lineHeight = 18.sp)
private val CardSubtitle = TextStyle(fontSize = 12.sp)

/**
 * The restyled 2026 "m-surface" card fill shared by every list row and stat panel on this screen —
 * `Panels` (`m-panel`) without glass translucency: a solid [surfaceColor] fill, set apart from the
 * page by the same white@6% hairline glass surfaces use rather than by blur. Mirrors
 * `:feature:detail`'s `EpisodeRow.kt` `episodeCard` precedent (spec, "Panels (`m-panel`)"), at
 * [Dimens.CardCornerRadius] rather than [Dimens.PanelRadius] — the 4d spec states 12dp explicitly for
 * this screen's cards.
 */
internal fun Modifier.mSurface(
    surfaceColor: Color,
    radius: Dp = Dimens.CardCornerRadius,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(color = surfaceColor, shape = shape)
        .border(width = GlassDefaults.HairlineWidth, color = GlassDefaults.PanelHairline, shape = shape)
}

/**
 * One finished download: artwork, title, size on disk, delete — an "m-surface card" (2026 refresh).
 *
 * @param onPlay the row itself is the play target — tapping anywhere on it starts playback of
 *   [item] from its resume position, the same as the detail page's Play button (see
 *   [DownloadItem.playbackStartTicks]). The *Downloaded* tab has no batch-selection mode to
 *   conflict with (unlike `:feature:detail`'s episode rows), so the row's own click can mean Play
 *   unconditionally.
 * @param onDelete the trailing icon button; nested inside the row's clickable area but its own
 *   independent target — Compose resolves the tap to whichever target is hit first, so pressing
 *   the icon never also fires [onPlay].
 */
@Composable
internal fun DownloadedRow(
    item: DownloadItem,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    inSeriesGroup: Boolean = false,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = ROW_GAP_HALF)
                .mSurface(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onPlay)
                .padding(Dimens.SpaceMedium),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowArtwork(item = item, width = ROW_ART_WIDTH_WIDE, height = ROW_ART_HEIGHT_WIDE)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.rowTitle(inSeriesGroup = inSeriesGroup),
                style = CardTitle,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(formatBytes(item.bytesOnDisk), item.transcodedMarker()).joinToString(" · "),
                style = CardSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        GlassIconButton(
            icon = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.downloads_action_delete),
            onClick = onDelete,
            size = ACTION_CIRCLE_SIZE_WIDE,
        )
    }
}

/**
 * One pending download: progress, speed, and the four queue actions — an "m-surface card" (2026
 * refresh).
 *
 * @param progress the fraction to draw, which is **not** `item.progress`: it comes through
 *   [DownloadProgressRatchet] so the bar can never run backwards while the projection behind its
 *   denominator settles.
 * @param compact below the `COMPACT_MAX_WIDTH` breakpoint (`DownloadsScreen.kt`), a single row of
 *   artwork, weighted text column and up to four action buttons leaves the title under ~90dp — a
 *   device-verified defect that crushed titles to ~4 characters ("Hous…") on a 360dp phone. Compact
 *   switches to two tiers: artwork+text get the full row width, and the actions move to their own
 *   end-aligned row below rather than shrinking to fit; wide keeps them trailing on one row, with
 *   title and status sharing a baseline instead of stacking (spec "4d Downloads"). Decided once at
 *   the screen level (`DownloadsScreen.kt`'s `BoxWithConstraints`), not per row.
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
            .padding(horizontal = Dimens.ScreenPadding, vertical = ROW_GAP_HALF)
            .mSurface(MaterialTheme.colorScheme.surface)
            .padding(Dimens.SpaceMedium)

    if (compact) {
        Column(
            modifier = cardModifier,
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RowArtwork(item = item, width = ROW_ART_WIDTH_COMPACT, height = ROW_ART_HEIGHT_COMPACT)
                QueueRowText(
                    item = item,
                    progress = progress,
                    speedBytesPerSecond = speedBytesPerSecond,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
            QueueRowActions(
                item = item,
                actions = actions,
                size = ACTION_CIRCLE_SIZE_COMPACT,
                modifier = Modifier.align(Alignment.End),
            )
        }
    } else {
        Row(
            modifier = cardModifier,
            horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowArtwork(item = item, width = ROW_ART_WIDTH_WIDE, height = ROW_ART_HEIGHT_WIDE)
            QueueRowText(
                item = item,
                progress = progress,
                speedBytesPerSecond = speedBytesPerSecond,
                compact = false,
                modifier = Modifier.weight(1f),
            )
            QueueRowActions(item = item, actions = actions, size = ACTION_CIRCLE_SIZE_WIDE)
        }
    }
}

/**
 * The title, progress bar and status line shared by both [QueueRow] layouts.
 *
 * @param compact stacks title, track and status on three lines (spec "4d Downloads", COMPACT). Wide
 *   instead shares one baseline row between title and status, with the track on its own line below —
 *   there is room for both on a tablet width, which the phone width the compact layout answers does
 *   not have.
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        if (compact) {
            Text(
                text = item.rowTitle(),
                style = QueueTitleCompact,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QueueTrack(progress = progress, fillColor = trackFillColor)
            Text(
                text = statusText,
                style = QueueStatusCompact,
                color = statusColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = item.rowTitle(),
                    style = QueueTitleWide,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = statusText,
                    style = QueueStatusWide,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QueueTrack(progress = progress, fillColor = trackFillColor)
        }
    }
}

/**
 * A queue row's 3dp progress track (spec, "Shared visual language" → INSET progress geometry).
 *
 * @param fillColor [MaterialTheme.colorScheme.error] for a failed row, so the bar reads as stopped
 *   partway rather than as still making progress — [MaterialTheme.colorScheme.primary] otherwise.
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
        trackColor = Color.White.copy(alpha = QUEUE_TRACK_ALPHA),
        drawStopIndicator = {},
    )
}

@Composable
private fun QueueRowActions(
    item: DownloadItem,
    actions: DownloadsActions,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall)) {
        GlassIconButton(
            icon = Icons.Filled.ArrowUpward,
            contentDescription = stringResource(R.string.downloads_action_move_up),
            onClick = { actions.onMoveUp(item) },
            size = size,
        )
        GlassIconButton(
            icon = Icons.Filled.ArrowDownward,
            contentDescription = stringResource(R.string.downloads_action_move_down),
            onClick = { actions.onMoveDown(item) },
            size = size,
        )

        // Paused and failed items both offer "resume": retrying a failure is the same operation,
        // and for an original download the partial file means it costs only the bytes that are
        // missing. The two predicates are shared with the queue's *Resume all* / *Pause all*
        // (DownloadsUiState.kt) so a row and the bulk button can never disagree about it. Tinted
        // primary — the one action circle on the row worth reaching for (spec "4d Downloads").
        if (item.isResumeTarget) {
            GlassIconButton(
                icon = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.downloads_action_resume),
                onClick = { actions.onResume(item) },
                size = size,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (item.isPauseTarget) {
            // A transcode cannot be resumed — the server ignores `Range` on a file it is still
            // producing — so pausing one would silently discard everything it has downloaded. See
            // [DownloadItem.isPausable]; *Cancel* remains, and says what it actually does.
            GlassIconButton(
                icon = Icons.Filled.Pause,
                contentDescription = stringResource(R.string.downloads_action_pause),
                onClick = { actions.onPause(item) },
                size = size,
            )
        }

        GlassIconButton(
            icon = Icons.Filled.Delete,
            contentDescription = stringResource(R.string.downloads_action_cancel),
            onClick = { actions.onDelete(item) },
            size = size,
        )
    }
}

@Composable
private fun RowArtwork(
    item: DownloadItem,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    JellyfinAsyncImage(
        url = item.item?.primaryImageUrl,
        contentDescription = null,
        modifier =
            modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(ROW_ART_RADIUS)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * `Westworld · Chestnut` for an episode, the plain title otherwise.
 *
 * @param inSeriesGroup `true` when the row is drawn under its series' own group header (the
 *   *Downloaded* tab's series groups) — the header already names the series, so repeating it on
 *   every row underneath ("Pyjamasques" header over "Pyjamasques · Bibou et le ballon-lune" rows)
 *   was the M9 device walk's other title-duplication bug (docs/POLISH.md). Standalone rows — the
 *   queue tab, and films, which are never grouped — keep the full form.
 */
internal fun DownloadItem.rowTitle(inSeriesGroup: Boolean = false): String =
    if (inSeriesGroup) {
        title
    } else {
        listOfNotNull(seriesName?.takeIf { it.isNotBlank() }, title).joinToString(" · ")
    }

/** The second line under a queue row's progress bar. */
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
            ).joinToString(" · ")

        DownloadStatus.QUEUED ->
            listOfNotNull(
                stringResource(R.string.downloads_status_queued),
                // The expected size is already known at enqueue time (`DownloadEnqueuer.sizeEstimate`),
                // and an episode of a show already on the device may already carry a seeded
                // projection, so a row waiting its turn can show whichever of the two it has —
                // same rule the in-progress line above follows.
                displayTotalBytes.takeIf { it > 0L }?.let { expectedSizeText(it) },
                transcodedMarker(),
            ).joinToString(" · ")

        DownloadStatus.PAUSED -> stringResource(R.string.downloads_status_paused)
        DownloadStatus.ERROR ->
            errorMessage?.let { stringResource(R.string.downloads_status_failed_reason, it) }
                ?: stringResource(R.string.downloads_status_failed)

        DownloadStatus.DOWNLOADED, DownloadStatus.CANCELLED ->
            listOfNotNull(formatBytes(bytesOnDisk), transcodedMarker()).joinToString(" · ")
    }

/**
 * Whole seconds left at [speedBytesPerSecond], or `null` when there is nothing trustworthy to show.
 *
 * `null` covers: no speed yet (the tracker has not seen enough samples), a stalled transfer (speed
 * `<= 0`), a row already at or past its own total (`remaining <= 0` — the progress write and the
 * projection write can interleave, per [DownloadItem.displayTotalBytes]'s own doc), and an estimate
 * beyond [ETA_GUARD_SECONDS]: a very low instantaneous speed against a large remainder produces a
 * number in the days, which reads as broken rather than as an honest estimate and is better left
 * blank than shown.
 */
internal fun DownloadItem.etaSeconds(speedBytesPerSecond: Long?): Long? {
    val speed = speedBytesPerSecond?.takeIf { it > 0L } ?: return null
    val total = displayTotalBytes.takeIf { it > 0L } ?: return null
    val remaining = total - bytesDownloaded
    if (remaining <= 0L) return null

    val eta = (remaining + speed - 1) / speed
    return eta.takeIf { it <= ETA_GUARD_SECONDS }
}

/**
 * Above this, an ETA is guesswork rather than an estimate — see [DownloadItem.etaSeconds].
 *
 * `internal`, not `private`: [DownloadsUiState.queueStats] (`DownloadsUiState.kt`) applies the exact
 * same ceiling-division-plus-guard shape to an *aggregate* remainder, and reusing this constant is
 * what keeps a per-row ETA and the wide summary's aggregate one from ever disagreeing about where
 * "guesswork" starts.
 */
internal const val ETA_GUARD_SECONDS = 86_400L

/**
 * [etaSecondsValue] worded to match how well the total behind it is known — the same hedge
 * [expectedSizeText] applies to the size itself, since an ETA derived from a [SizeCertainty.CEILING]
 * total is exactly as approximate as that total is.
 */
@Composable
private fun DownloadItem.etaText(etaSecondsValue: Long): String =
    stringResource(
        if (sizeCertainty == SizeCertainty.CEILING) R.string.downloads_eta_approx else R.string.downloads_eta,
        formatDurationSeconds(etaSecondsValue),
    )

/**
 * `"Transcoded"` for a row that was re-encoded rather than downloaded as the original file, `null`
 * for one that was not — see `DownloadQuality.isTranscoded`. Appended as the last segment of every
 * status line that has one, so it never displaces the size, speed or ETA text ahead of it.
 */
@Composable
private fun DownloadItem.transcodedMarker(): String? =
    if (quality.isTranscoded) stringResource(R.string.downloads_transcoded_marker) else null

/**
 * [bytes] worded to match how well it is actually known — see [DownloadItem.sizeCertainty].
 *
 * *"552,4 MB"* when the number is the file's size, *"~301,2 MB"* when it is the app's projection,
 * *"up to 552,4 MB"* when it is only a bound. Stating a ceiling as exact is what read as wrong once
 * the real file landed at less than half of it (DECISIONS.md, 2026-07-29); stating a projection as
 * exact would be the same mistake with a better number behind it.
 */
@Composable
private fun DownloadItem.expectedSizeText(bytes: Long): String =
    when (sizeCertainty) {
        SizeCertainty.EXACT -> formatBytes(bytes)
        SizeCertainty.APPROXIMATE -> stringResource(R.string.downloads_size_approx, formatBytes(bytes))
        SizeCertainty.CEILING -> stringResource(R.string.downloads_size_capped, formatBytes(bytes))
    }
