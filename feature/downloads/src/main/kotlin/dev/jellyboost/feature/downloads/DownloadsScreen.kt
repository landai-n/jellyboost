package dev.jellyboost.feature.downloads

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.formatDurationSeconds
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.PillSnackbar
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.mSurface
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.StorageUsage

/**
 * The Downloads screen (docs/PLAN.md, "Screens" → Downloads): a *Downloaded* tab grouped by show or
 * film with sizes and delete, a *Queue* tab with progress, speed and pause/resume/cancel/reorder,
 * and a storage header.
 *
 * Room-only by construction — it never touches the network, so it is the one screen that behaves
 * identically online and offline.
 *
 * The Wi-Fi-only toggle lives on this screen rather than in the app overflow menu that holds
 * *Offline mode*: it is a download setting, this is the download screen, and the effect of flipping
 * it (the queue stopping or starting) is visible right underneath (DECISIONS.md 2026-07-28, "M7:
 * the Wi-Fi-only toggle lives in the Downloads top bar"). Since M9 this screen has no top bar of
 * its own — `:app`'s chrome carries the navigation for every top-level destination — so the toggle
 * sits next to the storage header at the top of the content instead.
 *
 * The `Scaffold` that remains is here for the snackbar alone, hence `contentWindowInsets =
 * WindowInsets(0)`: the app's chrome floats over this screen rather than shrinking it, and how much
 * of the window it covers arrives through `LocalAppChromePadding` instead — consumed by the outer
 * column, by both tabs' lists and by the snackbar host.
 *
 * @param onPlay play was requested for a finished download, at its resume position in Jellyfin
 *   ticks — the caller pushes `Routes.Player`, the same destination `:feature:detail`'s Play
 *   button navigates to. Reused rather than invented afresh: a completed download always resolves
 *   locally (`PlaybackSourceResolver`), so the player needs nothing from this screen but the id and
 *   the start position.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.userMessage?.let { downloadsMessageText(it) }

    // Remembered, not rebuilt per composition. These bundles are `equals`-compared by Compose to
    // decide whether a row can skip, and a fresh instance every time is never equal to the last —
    // so a queue that writes progress two to six times a second recomposed *every* visible row on
    // every write, however little that row changed (audit PERF-05).
    val actions = remember(viewModel) { downloadsActions(viewModel) }
    val bulk = remember(viewModel) { queueBulkActions(viewModel) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            // Clear of the floating navigation pill, which this screen's own frame knows nothing
            // about — the pill is drawn by `:app` over the top of this whole `Scaffold`. Restyled
            // as `PillSnackbar` (2026 refresh) — the same glass pill `AppScaffold`'s own snackbar
            // host draws, so a message from this screen and one from anywhere else in the app look
            // identical.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalAppChromePadding.current.calculateBottomPadding()),
            ) { data -> PillSnackbar(snackbarData = data) }
        },
    ) { innerPadding ->
        DownloadsContent(
            state = state,
            actions = actions,
            bulk = bulk,
            onPlay = onPlay,
            onWifiOnlyChange = viewModel::setWifiOnly,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** The four row actions, bundled so the list composables stay under the parameter limit. */
data class DownloadsActions(
    val onPause: (DownloadItem) -> Unit,
    val onResume: (DownloadItem) -> Unit,
    val onDelete: (DownloadItem) -> Unit,
    val onMoveUp: (DownloadItem) -> Unit,
    val onMoveDown: (DownloadItem) -> Unit,
    val onSelectTab: (DownloadsTab) -> Unit,
)

/**
 * The queue-wide actions, kept apart from [DownloadsActions] rather than swelling it: these take no
 * row, they belong to one tab, and the *Downloaded* half of the screen has no use for them.
 */
data class QueueBulkActions(
    val onPauseAll: () -> Unit,
    val onResumeAll: () -> Unit,
    val onCancelAll: () -> Unit,
    val onConfirmCancelAll: () -> Unit,
    val onDismissCancelAll: () -> Unit,
)

/** Binds the row actions to the ViewModel; a function so the screen stays one expression. */
private fun downloadsActions(viewModel: DownloadsViewModel) =
    DownloadsActions(
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onDelete = viewModel::delete,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onSelectTab = viewModel::selectTab,
    )

/** Binds the queue-wide actions to the ViewModel. */
private fun queueBulkActions(viewModel: DownloadsViewModel) =
    QueueBulkActions(
        onPauseAll = viewModel::pauseAll,
        onResumeAll = viewModel::resumeAll,
        onCancelAll = viewModel::requestCancelAll,
        onConfirmCancelAll = viewModel::confirmCancelAll,
        onDismissCancelAll = viewModel::dismissCancelAll,
    )

/**
 * Stateless rendering — a pure function of [state], so it previews without a ViewModel.
 *
 * `wide` (2026 refresh) is decided once here, in the one `BoxWithConstraints` the whole screen
 * needs, as [queueRowCompact]'s complement — no second breakpoint invented for it (spec "4d
 * Downloads"). It picks the storage card vs. the three-panel tablet summary, the tab row's
 * flex-fill vs. content-hug segments, where the bulk-action pills live, and — handed down as
 * `compact = !wide` — [QueueRow]'s own two-tier/one-tier split, so every one of those decisions
 * agrees about which width class the screen is in.
 */
@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    actions: DownloadsActions,
    bulk: QueueBulkActions,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The chrome's TOP padding goes on the outer content: the header, storage/summary panel and tab
    // row never scroll, so they are exactly what would sit under the top nav (or under the compact
    // layout's floating action cluster) otherwise. The BOTTOM half stays with the two tabs' lists, so
    // their rows still scroll under the floating nav pill.
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().padding(top = LocalAppChromePadding.current.calculateTopPadding()),
    ) {
        val wide = !queueRowCompact(maxWidth)
        val downloadedBytes = state.downloaded.sumOf { it.bytesOnDisk }

        Column(modifier = Modifier.fillMaxSize()) {
            DownloadsHeader(wide = wide)

            if (wide) {
                WideSummary(
                    storage = state.storage,
                    downloadedBytes = downloadedBytes,
                    queue = state.queue,
                    queueStats = state.queueStats,
                    wifiOnly = state.wifiOnly,
                    onWifiOnlyChange = onWifiOnlyChange,
                )
            } else {
                StorageCard(
                    usage = state.storage,
                    downloadedBytes = downloadedBytes,
                    wifiOnly = state.wifiOnly,
                    onWifiOnlyChange = onWifiOnlyChange,
                )
            }

            val showInlineBulkActions = wide && state.selectedTab == DownloadsTab.QUEUE && state.queue.isNotEmpty()
            DownloadsTabRow(
                selectedTab = state.selectedTab,
                onSelectTab = actions.onSelectTab,
                wide = wide,
                trailing =
                    if (showInlineBulkActions) {
                        { QueueActionsBar(state = state, bulk = bulk, horizontalPadding = 0.dp) }
                    } else {
                        null
                    },
            )

            when {
                state.isLoading -> LoadingState()

                // Ahead of the tabs: the projection both of them read is the thing that failed, so
                // neither has anything trustworthy to draw.
                state.loadFailed -> EmptyState(message = stringResource(R.string.downloads_load_failed))

                state.selectedTab == DownloadsTab.DOWNLOADED ->
                    DownloadedTab(groups = state.downloaded, onDelete = actions.onDelete, onPlay = onPlay)

                else -> QueueTab(state = state, actions = actions, bulk = bulk, wide = wide)
            }
        }
    }
}

/** The screen's own title row — a top-level tab, so unlike a pushed screen it draws no back button. */
@Composable
private fun DownloadsHeader(
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.PanelPadding, vertical = Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.downloads_screen_title),
            style = if (wide) JellyfinTypeExtras.ScreenTitleLarge else JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DownloadedTab(
    groups: List<DownloadGroup>,
    onDelete: (DownloadItem) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyState(message = stringResource(R.string.downloads_empty_downloaded))
        return
    }

    // Which delete the user has asked for but not yet confirmed. Local to the list on purpose: it
    // is a question the screen is asking, not something the ViewModel or Room knows about.
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                top = Dimens.SpaceSmall,
                bottom = Dimens.SpaceSmall + LocalAppChromePadding.current.calculateBottomPadding(),
            ),
    ) {
        groups.forEach { group ->
            // A film's heading would only repeat the title of the single row under it, so a lone
            // film group draws no header. A series always gets one, and so does the shared Movies
            // group once one exists — otherwise a film row right after a series' last episode reads
            // as one more row of that series (the bug docs/POLISH.md's "Downloads page duplicate
            // movie header" entry did not cover, since it only ever looked at a film on its own).
            if (group.isSeries || group.isMoviesSection) {
                item(
                    key = "header-${if (group.isMoviesSection) "movies-section" else group.title}",
                    contentType = DownloadsContentType.HEADER,
                ) {
                    GroupHeader(group = group)
                }
            }
            items(
                items = group.items,
                key = { it.itemId },
                contentType = { DownloadsContentType.ROW },
            ) { item ->
                DownloadedRow(
                    item = item,
                    onDelete = { pendingDelete = item },
                    onPlay = { onPlay(item.itemId, item.playbackStartTicks) },
                    inSeriesGroup = group.isSeries,
                )
            }
        }
    }

    pendingDelete?.let { item ->
        DeleteDownloadDialog(
            item = item,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(item)
            },
        )
    }
}

/**
 * Confirms a delete from the *Downloaded* tab.
 *
 * Only here, and deliberately not on the queue tab's *Cancel*: this button destroys a finished
 * transfer — a film that may have taken an hour of a metered connection — and its icon sits one
 * row away from the next item's. Cancelling something still downloading costs the bytes not yet
 * spent, and is undone by pressing Download again.
 */
@Composable
private fun DeleteDownloadDialog(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.downloads_delete_dialog_title, item.rowTitle())) },
        text = { Text(text = stringResource(R.string.downloads_delete_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.downloads_delete_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.downloads_delete_dialog_cancel))
            }
        },
    )
}

@Composable
private fun QueueTab(
    state: DownloadsUiState,
    actions: DownloadsActions,
    bulk: QueueBulkActions,
    wide: Boolean,
) {
    if (state.queue.isEmpty()) {
        // No bar either: with nothing queued there is nothing for any of the three to act on, and a
        // row of dead buttons over an empty state says less than the empty state alone.
        EmptyState(message = stringResource(R.string.downloads_empty_queue))
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // On wide layouts the same bar already sits inline in DownloadsTabRow, to the right of the
        // segmented control (spec "4d Downloads") — drawing it a second time here would duplicate
        // every button.
        if (!wide) {
            QueueActionsBar(state = state, bulk = bulk, modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    top = Dimens.SpaceSmall,
                    bottom = Dimens.SpaceSmall + LocalAppChromePadding.current.calculateBottomPadding(),
                ),
        ) {
            items(items = state.queue, key = { it.itemId }) { item ->
                QueueRow(
                    item = item,
                    // The ratcheted fraction, falling back to the row's own only for an item the
                    // ratchet has not seen yet (the very first frame after an enqueue).
                    progress = state.progress[item.itemId] ?: item.progress,
                    speedBytesPerSecond = state.speeds[item.itemId],
                    actions = actions,
                    compact = !wide,
                )
            }
        }
    }

    if (state.showCancelAllConfirmation) {
        CancelAllDialog(
            count = state.queue.size,
            onDismiss = bulk.onDismissCancelAll,
            onConfirm = bulk.onConfirmCancelAll,
        )
    }
}

/**
 * Below this width, [QueueRow]'s single-row layout — a 48dp thumbnail, a weighted text column, and
 * up to four 48dp `QueueRowActions` buttons (≈192dp) inside `Dimens.ScreenPadding` — leaves the
 * title under ~90dp: a device-verified defect that crushed a queue row's title to ~4 characters
 * ("Hous…") on a 360dp phone. `QueueRow(compact = true)` moves the actions to their own row below
 * the title/progress instead, so every action keeps its full 48dp touch target rather than
 * shrinking to fit. Tablet widths (≥600dp) are always well above this, so their layout is
 * unaffected.
 *
 * Also the screen's one and only wide/compact breakpoint (2026 refresh) — [DownloadsContent] reads
 * its complement, `!queueRowCompact(maxWidth)`, to decide the storage card vs. the tablet stat
 * summary and the tab row's shape, per spec "4d Downloads": "do NOT invent a new breakpoint."
 */
private val COMPACT_MAX_WIDTH = 480.dp

/** Extracted so the breakpoint is unit-testable without a Compose test harness. */
internal fun queueRowCompact(maxWidth: Dp): Boolean = maxWidth < COMPACT_MAX_WIDTH

/**
 * The queue-wide actions.
 *
 * Compact: [QueueTab] draws this as its own full-width row above the list. Wide: [DownloadsContent]
 * draws it inline inside [DownloadsTabRow], trailing the segmented control, via [horizontalPadding]
 * `= 0.dp` — the row that hosts it already carries the screen's side margin.
 *
 * *Pause all* and *Resume all* are disabled, not hidden, when they have nothing to act on: a queue
 * of transcodes has nothing pausable in it, and a button that vanishes as the queue changes shape
 * under the finger is worse than one that visibly cannot be pressed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueActionsBar(
    state: DownloadsUiState,
    bulk: QueueBulkActions,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Dimens.PanelPadding,
) {
    FlowRow(
        modifier = modifier.padding(horizontal = horizontalPadding, vertical = Dimens.SpaceExtraSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        QueueBulkButton(
            icon = Icons.Filled.Pause,
            labelRes = R.string.downloads_action_pause_all,
            enabled = state.canPauseAll,
            onClick = bulk.onPauseAll,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QueueBulkButton(
            icon = Icons.Filled.PlayArrow,
            labelRes = R.string.downloads_action_resume_all,
            enabled = state.canResumeAll,
            onClick = bulk.onResumeAll,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QueueBulkButton(
            icon = Icons.Filled.Delete,
            labelRes = R.string.downloads_action_cancel_all,
            enabled = true,
            onClick = bulk.onCancelAll,
            // The one destructive action on the bar, coloured like one.
            contentColor = MaterialTheme.colorScheme.error,
        )
    }
}

/** A glass pill bulk-action button (spec "4d Downloads": "8×14dp pad, 12sp/500 icon 15dp"). */
@Composable
private fun QueueBulkButton(
    icon: ImageVector,
    @StringRes labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    contentColor: Color,
) {
    val resolvedColor = if (enabled) contentColor else contentColor.copy(alpha = BULK_BUTTON_DISABLED_ALPHA)
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .glassSurface(CircleShape)
                .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
                .padding(horizontal = BulkButtonHorizontalPadding, vertical = BulkButtonVerticalPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = resolvedColor,
            modifier = Modifier.size(BulkButtonIconSize),
        )
        Text(
            text = stringResource(labelRes),
            style = BulkButtonLabel,
            color = resolvedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Confirms emptying the queue.
 *
 * Confirmed although a single row's *Cancel* is not: one tap here can throw away every partly
 * transferred file on the device, and the button sits a few millimetres from *Pause all*. The copy
 * says out loud what the action does **not** touch — finished downloads are on the other tab and
 * are never in this list (`toQueue()`), and the season-cancel walk showed that is exactly the
 * question a user asks before pressing something called "cancel all" (DECISIONS.md, 2026-07-29).
 */
@Composable
private fun CancelAllDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = pluralStringResource(R.plurals.downloads_cancel_all_dialog_title, count, count)) },
        text = { Text(text = stringResource(R.string.downloads_cancel_all_dialog_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.downloads_cancel_all_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.downloads_cancel_all_dialog_dismiss))
            }
        },
    )
}

@Composable
private fun GroupHeader(
    group: DownloadGroup,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // The Movies group carries no title of its own (DownloadGroup.isMoviesSection) — its
            // heading is a string resource, resolved here, not baked into the ViewModel's state.
            text = if (group.isMoviesSection) stringResource(R.string.downloads_group_movies) else group.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = formatBytes(group.bytesOnDisk),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The compact storage summary — an "m-surface panel" (spec "4d Downloads"): the used/free figures,
 * a usage bar, and the Wi-Fi-only toggle. Replaced by [WideSummary] on a wide layout.
 */
@Composable
private fun StorageCard(
    usage: StorageUsage,
    downloadedBytes: Long,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = usage.usedBytes + usage.availableBytes
    val usedBytes = maxOf(usage.usedBytes, downloadedBytes)

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceSmall)
                .mSurface(MaterialTheme.colorScheme.surface)
                .padding(horizontal = Dimens.SpaceLarge, vertical = StatPanelVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = formatBytes(usedBytes), style = StatValue, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = stringResource(R.string.downloads_storage_free, formatBytes(usage.availableBytes)),
                style = StatCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UsageBar(fraction = usageFraction(usedBytes, total))
        Row(
            modifier =
                Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .toggleable(value = wifiOnly, onValueChange = onWifiOnlyChange, role = Role.Switch),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.downloads_wifi_only),
                style = WifiLabelCompact,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = wifiOnly, onCheckedChange = null)
        }
    }
}

/**
 * The wide-layout replacement for [StorageCard] (spec "4d Downloads"): three equal-weight
 * "m-surface" stat panels — storage, the queue's own numbers, and the network toggle — rather than
 * one strip. Introduced alongside [DownloadsUiState.queueStats] as this screen's one small pure
 * derivation beyond a restyle (DECISIONS.md 2026-08-01, "Downloads restyle: a wide-layout queue
 * summary"; also pre-approved as a "convenience display" in STATUS.md's design-refresh entry).
 *
 * The queue panel's own progress bar is *not* one of [QueueStats]' fields — it is derived here, the
 * same way [StorageCard]'s bar is, from bytes [queue] already carries (`bytesDownloaded` against
 * `bytesDownloaded + queueStats.remainingBytes`), so [QueueStats] stays exactly the fields the task
 * asked for rather than growing a field only this one bar needs.
 */
@Composable
private fun WideSummary(
    storage: StorageUsage,
    downloadedBytes: Long,
    queue: List<DownloadItem>,
    queueStats: QueueStats,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val queueDownloaded = queue.sumOf { it.bytesDownloaded }
    val queueTotal = queueDownloaded + queueStats.remainingBytes

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.PanelPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
    ) {
        OnDeviceStatPanel(storage = storage, downloadedBytes = downloadedBytes, modifier = Modifier.weight(1f))
        QueueStatPanel(
            stats = queueStats,
            progress = usageFraction(queueDownloaded, queueTotal),
            modifier = Modifier.weight(1f),
        )
        NetworkStatPanel(wifiOnly = wifiOnly, onWifiOnlyChange = onWifiOnlyChange, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun OnDeviceStatPanel(
    storage: StorageUsage,
    downloadedBytes: Long,
    modifier: Modifier = Modifier,
) {
    val total = storage.usedBytes + storage.availableBytes
    val usedBytes = maxOf(storage.usedBytes, downloadedBytes)

    StatPanel(modifier = modifier) {
        StatEyebrow(text = stringResource(R.string.downloads_stat_on_device))
        Text(text = formatBytes(usedBytes), style = StatValue, color = MaterialTheme.colorScheme.onBackground)
        UsageBar(fraction = usageFraction(usedBytes, total))
        Text(
            text =
                stringResource(
                    R.string.downloads_stat_free_of,
                    formatBytes(storage.availableBytes),
                    formatBytes(total),
                ),
            style = StatCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QueueStatPanel(
    stats: QueueStats,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    StatPanel(modifier = modifier) {
        StatEyebrow(text = stringResource(R.string.downloads_stat_queue))
        Text(
            text =
                pluralStringResource(
                    R.plurals.downloads_stat_queue_items,
                    stats.itemCount,
                    stats.itemCount,
                    formatBytes(stats.remainingBytes),
                ),
            style = StatValueSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        UsageBar(fraction = progress)
        // Hidden while idle: a speed/ETA line reading "0 B/s" would look like a stall rather than
        // the truth, which is that nothing is asking the network for anything right now.
        if (!stats.isIdle) {
            val etaSeconds = stats.etaSeconds
            Text(
                text =
                    listOfNotNull(
                        stringResource(R.string.downloads_speed, formatBytes(stats.bytesPerSecond)),
                        etaSeconds?.let {
                            stringResource(R.string.downloads_stat_eta_about, formatDurationSeconds(it))
                        },
                    ).joinToString(" · "),
                style = StatCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetworkStatPanel(
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    StatPanel(modifier = modifier) {
        StatEyebrow(text = stringResource(R.string.downloads_stat_network))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .toggleable(value = wifiOnly, onValueChange = onWifiOnlyChange, role = Role.Switch),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.downloads_wifi_only),
                style = StatSwitchLabel,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Switch(checked = wifiOnly, onCheckedChange = null)
        }
        // Shown only while the toggle is on — the moment cellular would actually pause anything.
        if (wifiOnly) {
            Text(
                text = stringResource(R.string.downloads_stat_network_helper),
                style = StatCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The "m-surface" wrapper every wide stat panel shares. */
@Composable
private fun StatPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .mSurface(MaterialTheme.colorScheme.surface)
                .padding(horizontal = Dimens.SpaceLarge, vertical = StatPanelVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(StatPanelInnerGap),
        content = content,
    )
}

@Composable
private fun StatEyebrow(text: String) {
    Text(
        text = text.uppercase(),
        style = JellyfinTypeExtras.Eyebrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `used / total`, guarded against a not-yet-known total — shared by every bar on this screen. */
private fun usageFraction(
    used: Long,
    total: Long,
): Float = if (total <= 0L) 0f else (used.toFloat() / total).coerceIn(0f, 1f)

/**
 * The 6dp usage bar every stat panel on this screen draws (spec "4d Downloads": "track white@12%,
 * primary fill, 3dp radius"). Hand-rolled rather than a stock `LinearProgressIndicator`, the same
 * reasoning `core/ui`'s `MediaCardArtwork.InsetProgressBar` states for its own bar: at this height
 * and radius nothing the stock component provides (stop indicator, gap, stroke-cap rounding)
 * survives being configured away.
 */
@Composable
private fun UsageBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(UsageBarRadius)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(UsageBarHeight)
                .clip(shape)
                .background(UsageBarTrackColor),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color = MaterialTheme.colorScheme.primary, shape = shape),
        )
    }
}

/**
 * The Downloaded/Queue glass segmented control (spec "4d Downloads"): a pill container with 4dp
 * inner padding, each segment a smaller pill that goes solid white when selected — the same shape
 * `:app`'s `GlassTopNav` uses for its own tab bar, rebuilt here rather than shared across a
 * `:feature` → `:app` dependency neither module has.
 *
 * @param wide compact flex-fills the container's segments across the full row width (20dp side
 *   margins); wide instead lets the segments hug their own label width and, when [trailing] is
 *   given, draws it after them on the same row.
 */
@Composable
private fun DownloadsTabRow(
    selectedTab: DownloadsTab,
    onSelectTab: (DownloadsTab) -> Unit,
    wide: Boolean,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.PanelPadding, vertical = Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                (if (wide) Modifier else Modifier.weight(1f))
                    .clip(CircleShape)
                    .background(color = GlassDefaults.Fill, shape = CircleShape)
                    .border(GlassDefaults.HairlineWidth, GlassDefaults.Hairline, CircleShape)
                    .padding(SegmentedTabBarPadding),
            horizontalArrangement = Arrangement.spacedBy(SegmentedTabGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DownloadsTab.entries.forEach { tab ->
                SegmentedTab(
                    selected = tab == selectedTab,
                    label = stringResource(tab.titleRes()),
                    onClick = { onSelectTab(tab) },
                    wide = wide,
                    modifier = if (wide) Modifier else Modifier.weight(1f),
                )
            }
        }

        if (wide && trailing != null) {
            Spacer(modifier = Modifier.width(Dimens.SpaceMedium))
            trailing()
        }
    }
}

@Composable
private fun SegmentedTab(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) SegmentedSelectedContent else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier =
            modifier
                .height(Dimens.PillHeightSmall)
                .clip(CircleShape)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                .background(color = if (selected) Color.White else Color.Transparent, shape = CircleShape)
                .padding(
                    horizontal = if (wide) SegmentedTabHorizontalPaddingWide else SegmentedTabHorizontalPaddingCompact,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = SegmentedTabLabel,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The two node shapes the *Downloaded* tab's `LazyColumn` draws (audit PERF-08).
 *
 * Without a `contentType`, Compose's default (every item shares one type) means scrolling a header
 * into a slot the last recycled node held a row in — or the reverse — cannot reuse the composition
 * at all; it has to throw it away and start over. The queue tab's list is left alone: it draws one
 * row shape only, so it already gets this for free.
 */
private enum class DownloadsContentType {
    HEADER,
    ROW,
}

private fun DownloadsTab.titleRes(): Int =
    when (this) {
        DownloadsTab.DOWNLOADED -> R.string.downloads_tab_downloaded
        DownloadsTab.QUEUE -> R.string.downloads_tab_queue
    }

/** Renders a one-shot [message] as snackbar copy (precedent: `:feature:detail`'s `userMessageText`). */
@Composable
private fun downloadsMessageText(message: DownloadsMessage): String =
    when (message) {
        DownloadsMessage.DeleteFailed -> stringResource(R.string.downloads_message_delete_failed)
        DownloadsMessage.ActionFailed -> stringResource(R.string.downloads_message_action_failed)
        is DownloadsMessage.PausedKeepingTranscodes ->
            pluralStringResource(
                R.plurals.downloads_message_paused_keeping_transcodes,
                message.transcodingCount,
                message.pausedCount,
                message.transcodingCount,
            )
    }

// ---- 2026 refresh geometry — local to this screen, not shared `Dimens` tokens -------------------

private val StatPanelVerticalPadding = 18.dp
private val StatPanelInnerGap = 6.dp
private val UsageBarHeight = 6.dp
private val UsageBarRadius = 3.dp
private val UsageBarTrackColor = Color.White.copy(alpha = 0.12f)
private val SegmentedTabBarPadding = 4.dp
private val SegmentedTabGap = 2.dp
private val SegmentedTabHorizontalPaddingCompact = 12.dp
private val SegmentedTabHorizontalPaddingWide = 20.dp
private val SegmentedSelectedContent = Color(0xFF101010)
private val SegmentedTabLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)
private val BulkButtonHorizontalPadding = 14.dp
private val BulkButtonVerticalPadding = 8.dp
private val BulkButtonIconSize = 15.dp
private val BulkButtonLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W500)
private const val BULK_BUTTON_DISABLED_ALPHA = 0.35f
private val StatValue = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W600)
private val StatValueSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600)
private val StatSwitchLabel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600)
private val StatCaption = TextStyle(fontSize = 12.sp)
private val WifiLabelCompact = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)

@Preview(name = "Downloads — queue", showBackground = true, backgroundColor = 0xFF101010, widthDp = 390)
@Composable
private fun DownloadsPreview() {
    QueuePreview()
}

/**
 * The compact two-tier layout ([queueRowCompact]) at the exact width the device walk found it
 * broken on. `widthDp = 360` puts this right at a common phone's shortest dimension — below
 * `COMPACT_MAX_WIDTH`, so [QueueRow] renders its compact variant here.
 */
@Preview(
    name = "Downloads — queue (compact, 360dp)",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 360,
    heightDp = 800,
)
@Composable
private fun DownloadsPreviewCompact() {
    QueuePreview()
}

/**
 * The wide layout (2026 refresh): the three-panel [WideSummary] in place of [StorageCard], the
 * content-hug segmented tabs with the bulk-action pills inline, and [QueueRow]'s one-tier form.
 * `widthDp = 900` is well past `COMPACT_MAX_WIDTH`, so [queueRowCompact] answers `false` here.
 */
@Preview(
    name = "Downloads — queue (wide, 900dp)",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 900,
    heightDp = 700,
)
@Composable
private fun DownloadsPreviewWide() {
    QueuePreview()
}

/**
 * Shared by every queue preview above. The title is long enough to visibly truncate at 360dp
 * before the compact fix, and to show it fixed after — a short one like the tab bar's own
 * "Chestnut" example would not exercise the defect either way.
 */
@Composable
private fun QueuePreview() {
    val queued =
        DownloadItem(
            itemId = "1",
            title = "The Bicameral Mind",
            seriesName = "Westworld: Season One",
            status = DownloadStatus.DOWNLOADING,
            bytesDownloaded = 640_000_000L,
            bytesTotal = 2_100_000_000L,
            bytesOnDisk = 640_000_000L,
            queuePosition = 0,
        )

    JellyfinTheme {
        DownloadsContent(
            state =
                DownloadsUiState(
                    selectedTab = DownloadsTab.QUEUE,
                    queue = listOf(queued),
                    speeds = mapOf("1" to 8_400_000L),
                    storage = StorageUsage(usedBytes = 640_000_000L, availableBytes = 40_000_000_000L),
                    isLoading = false,
                ),
            actions =
                DownloadsActions(
                    onPause = {},
                    onResume = {},
                    onDelete = {},
                    onMoveUp = {},
                    onMoveDown = {},
                    onSelectTab = {},
                ),
            bulk =
                QueueBulkActions(
                    onPauseAll = {},
                    onResumeAll = {},
                    onCancelAll = {},
                    onConfirmCancelAll = {},
                    onDismissCancelAll = {},
                ),
            onPlay = { _, _ -> },
            onWifiOnlyChange = {},
        )
    }
}
