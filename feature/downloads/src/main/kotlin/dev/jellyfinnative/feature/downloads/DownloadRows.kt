package dev.jellyfinnative.feature.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jellyfinnative.core.common.formatBytes
import dev.jellyfinnative.core.common.formatDurationSeconds
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.ui.component.JellyfinAsyncImage
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.SizeCertainty

/** Artwork size for a list row — a small poster, not a card. */
private val THUMB_SIZE = 48.dp

/**
 * One finished download: artwork, title, size on disk, delete.
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
                .clickable(onClick = onPlay)
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowArtwork(item = item)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.rowTitle(inSeriesGroup = inSeriesGroup),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(formatBytes(item.bytesOnDisk), item.transcodedMarker()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.downloads_action_delete),
            )
        }
    }
}

/**
 * One pending download: progress, speed, and the four queue actions.
 *
 * @param progress the fraction to draw, which is **not** `item.progress`: it comes through
 *   [DownloadProgressRatchet] so the bar can never run backwards while the projection behind its
 *   denominator settles.
 */
@Composable
internal fun QueueRow(
    item: DownloadItem,
    progress: Float,
    speedBytesPerSecond: Long?,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowArtwork(item = item)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
        ) {
            Text(
                text = item.rowTitle(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                drawStopIndicator = {},
            )
            Text(
                text = item.statusLine(speedBytesPerSecond),
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (item.status == DownloadStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        QueueRowActions(item = item, actions = actions)
    }
}

@Composable
private fun QueueRowActions(
    item: DownloadItem,
    actions: DownloadsActions,
) {
    Row {
        IconButton(onClick = { actions.onMoveUp(item) }) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = stringResource(R.string.downloads_action_move_up),
            )
        }
        IconButton(onClick = { actions.onMoveDown(item) }) {
            Icon(
                imageVector = Icons.Filled.ArrowDownward,
                contentDescription = stringResource(R.string.downloads_action_move_down),
            )
        }

        // Paused and failed items both offer "resume": retrying a failure is the same operation,
        // and for an original download the partial file means it costs only the bytes that are
        // missing. The two predicates are shared with the queue's *Resume all* / *Pause all*
        // (DownloadsUiState.kt) so a row and the bulk button can never disagree about it.
        if (item.isResumeTarget) {
            IconButton(onClick = { actions.onResume(item) }) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.downloads_action_resume),
                )
            }
        } else if (item.isPauseTarget) {
            // A transcode cannot be resumed — the server ignores `Range` on a file it is still
            // producing — so pausing one would silently discard everything it has downloaded. See
            // [DownloadItem.isPausable]; *Cancel* remains, and says what it actually does.
            IconButton(onClick = { actions.onPause(item) }) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = stringResource(R.string.downloads_action_pause),
                )
            }
        }

        IconButton(onClick = { actions.onDelete(item) }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.downloads_action_cancel),
            )
        }
    }
}

@Composable
private fun RowArtwork(
    item: DownloadItem,
    modifier: Modifier = Modifier,
) {
    JellyfinAsyncImage(
        url = item.item?.primaryImageUrl,
        contentDescription = null,
        modifier =
            modifier
                .size(THUMB_SIZE)
                .clip(RoundedCornerShape(Dimens.CardCornerRadius)),
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

/** Above this, an ETA is guesswork rather than an estimate — see [DownloadItem.etaSeconds]. */
private const val ETA_GUARD_SECONDS = 86_400L

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
