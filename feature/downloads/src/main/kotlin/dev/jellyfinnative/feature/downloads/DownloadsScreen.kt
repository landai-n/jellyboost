package dev.jellyfinnative.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
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
 * The Wi-Fi-only toggle lives in this screen's top bar rather than in the home overflow menu that
 * holds *Offline mode*: it is a download setting, this is the download screen, and the effect of
 * flipping it (the queue stopping or starting) is visible right underneath (DECISIONS.md
 * 2026-07-28, "M7: the Wi-Fi-only toggle lives in the Downloads top bar").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.userMessage?.let { stringResource(it.textRes()) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.downloads_title)) },
                actions = {
                    WifiOnlyToggle(enabled = state.wifiOnly, onChange = viewModel::setWifiOnly)
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        DownloadsContent(
            state = state,
            actions = downloadsActions(viewModel),
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

/** Stateless rendering — a pure function of [state], so it previews without a ViewModel. */
@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    actions: DownloadsActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        StorageHeader(usage = state.storage, downloadedBytes = state.downloaded.sumOf { it.bytesOnDisk })

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

            else -> QueueTab(state = state, actions = actions)
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Dimens.SpaceSmall),
    ) {
        groups.forEach { group ->
            item(key = "header-${group.title}") {
                GroupHeader(group = group)
            }
            items(items = group.items, key = { it.itemId }) { item ->
                DownloadedRow(item = item, onDelete = { onDelete(item) })
            }
        }
    }
}

@Composable
private fun QueueTab(
    state: DownloadsUiState,
    actions: DownloadsActions,
) {
    if (state.queue.isEmpty()) {
        EmptyState(message = stringResource(R.string.downloads_empty_queue))
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Dimens.SpaceSmall),
    ) {
        items(items = state.queue, key = { it.itemId }) { item ->
            QueueRow(
                item = item,
                speedBytesPerSecond = state.speeds[item.itemId],
                actions = actions,
            )
        }
    }
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
            text = group.title,
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
        Text(
            text =
                stringResource(
                    R.string.downloads_storage_summary,
                    formatBytes(maxOf(usage.usedBytes, downloadedBytes)),
                    formatBytes(usage.availableBytes),
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        modifier = Modifier.padding(end = Dimens.SpaceSmall),
    ) {
        Text(
            text = stringResource(R.string.downloads_wifi_only),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

private fun DownloadsTab.titleRes(): Int =
    when (this) {
        DownloadsTab.DOWNLOADED -> R.string.downloads_tab_downloaded
        DownloadsTab.QUEUE -> R.string.downloads_tab_queue
    }

private fun DownloadsMessage.textRes(): Int =
    when (this) {
        DownloadsMessage.DeleteFailed -> R.string.downloads_message_delete_failed
        DownloadsMessage.ActionFailed -> R.string.downloads_message_action_failed
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
        )
    }
}
