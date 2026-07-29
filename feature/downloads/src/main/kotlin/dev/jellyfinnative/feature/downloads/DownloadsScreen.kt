package dev.jellyfinnative.feature.downloads

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyfinnative.core.common.model.DownloadStatus
import dev.jellyfinnative.core.ui.component.EmptyState
import dev.jellyfinnative.core.ui.component.LoadingState
import dev.jellyfinnative.core.ui.theme.Dimens
import dev.jellyfinnative.core.ui.theme.JellyfinTheme
import dev.jellyfinnative.data.downloads.model.DownloadItem
import dev.jellyfinnative.data.downloads.model.StorageUsage

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
 * its own — `:app`'s combined `AppTopBar` carries the navigation for every top-level destination —
 * so the toggle sits next to the storage header at the top of the content instead.
 *
 * The `Scaffold` that remains is here for the snackbar alone, hence `contentWindowInsets =
 * WindowInsets(0)`: the frame above already reserved both the app bar and the system navigation
 * bar, and a second helping of the same insets would pad the list twice.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.userMessage?.let { downloadsMessageText(it) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        DownloadsContent(
            state = state,
            actions = downloadsActions(viewModel),
            bulk = queueBulkActions(viewModel),
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

/** Stateless rendering — a pure function of [state], so it previews without a ViewModel. */
@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    actions: DownloadsActions,
    bulk: QueueBulkActions,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        StorageHeader(
            usage = state.storage,
            downloadedBytes = state.downloaded.sumOf { it.bytesOnDisk },
            wifiOnly = state.wifiOnly,
            onWifiOnlyChange = onWifiOnlyChange,
        )

        PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
            DownloadsTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { actions.onSelectTab(tab) },
                    text = { Text(text = stringResource(tab.titleRes())) },
                )
            }
        }

        when {
            state.isLoading -> LoadingState()

            state.selectedTab == DownloadsTab.DOWNLOADED ->
                DownloadedTab(groups = state.downloaded, onDelete = actions.onDelete)

            else -> QueueTab(state = state, actions = actions, bulk = bulk)
        }
    }
}

@Composable
private fun DownloadedTab(
    groups: List<DownloadGroup>,
    onDelete: (DownloadItem) -> Unit,
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
        contentPadding = PaddingValues(vertical = Dimens.SpaceSmall),
    ) {
        groups.forEach { group ->
            // A film's heading would only repeat the title of the single row under it, so a lone
            // film group draws no header. A series always gets one, and so does the shared Movies
            // group once one exists — otherwise a film row right after a series' last episode reads
            // as one more row of that series (the bug docs/POLISH.md's "Downloads page duplicate
            // movie header" entry did not cover, since it only ever looked at a film on its own).
            if (group.isSeries || group.isMoviesSection) {
                item(key = "header-${if (group.isMoviesSection) "movies-section" else group.title}") {
                    GroupHeader(group = group)
                }
            }
            items(items = group.items, key = { it.itemId }) { item ->
                DownloadedRow(
                    item = item,
                    onDelete = { pendingDelete = item },
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
) {
    if (state.queue.isEmpty()) {
        // No bar either: with nothing queued there is nothing for any of the three to act on, and a
        // row of dead buttons over an empty state says less than the empty state alone.
        EmptyState(message = stringResource(R.string.downloads_empty_queue))
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        QueueActionsBar(state = state, bulk = bulk)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Dimens.SpaceSmall),
        ) {
            items(items = state.queue, key = { it.itemId }) { item ->
                QueueRow(
                    item = item,
                    // The ratcheted fraction, falling back to the row's own only for an item the
                    // ratchet has not seen yet (the very first frame after an enqueue).
                    progress = state.progress[item.itemId] ?: item.progress,
                    speedBytesPerSecond = state.speeds[item.itemId],
                    actions = actions,
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
 * The queue-wide actions, above the list.
 *
 * Here rather than in the app's top bar: that bar is shared by every top-level destination and
 * carries navigation, while these three are about one tab of one screen and have to disappear with
 * it. Labels rather than bare icons — *Cancel all* empties the queue, and an unlabelled bin at the
 * top of a list is not a thing to leave to guesswork. A [FlowRow] because on a narrow phone three
 * labelled buttons wrap rather than clip (the same idiom the detail header's action row uses).
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
) {
    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceSmall, vertical = Dimens.SpaceExtraSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceExtraSmall),
    ) {
        QueueBulkButton(
            icon = Icons.Filled.Pause,
            labelRes = R.string.downloads_action_pause_all,
            enabled = state.canPauseAll,
            onClick = bulk.onPauseAll,
        )
        QueueBulkButton(
            icon = Icons.Filled.PlayArrow,
            labelRes = R.string.downloads_action_resume_all,
            enabled = state.canResumeAll,
            onClick = bulk.onResumeAll,
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

@Composable
private fun QueueBulkButton(
    icon: ImageVector,
    @StringRes labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Text(text = stringResource(labelRes), modifier = Modifier.padding(start = Dimens.SpaceSmall))
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

@Composable
private fun StorageHeader(
    usage: StorageUsage,
    downloadedBytes: Long,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = usage.usedBytes + usage.availableBytes

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceMedium),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    stringResource(
                        R.string.downloads_storage_summary,
                        formatBytes(maxOf(usage.usedBytes, downloadedBytes)),
                        formatBytes(usage.availableBytes),
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            )
            WifiOnlyToggle(enabled = wifiOnly, onChange = onWifiOnlyChange)
        }
        LinearProgressIndicator(
            progress = { if (total <= 0L) 0f else (usage.usedBytes.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun WifiOnlyToggle(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The label and the switch are one control, but they must not touch: the same gap the
        // settings rows put between a label and its switch (`SettingsRows.SettingsSwitchRow`).
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        modifier =
            Modifier
                .defaultMinSize(minHeight = 48.dp)
                .toggleable(value = enabled, onValueChange = onChange, role = Role.Switch)
                .padding(end = Dimens.SpaceSmall),
    ) {
        Text(
            text = stringResource(R.string.downloads_wifi_only),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = enabled, onCheckedChange = null)
    }
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

@Preview(name = "Downloads — queue", showBackground = true, backgroundColor = 0xFF101010)
@Composable
private fun DownloadsPreview() {
    val queued =
        DownloadItem(
            itemId = "1",
            title = "Chestnut",
            seriesName = "Westworld",
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
            onWifiOnlyChange = {},
        )
    }
}
