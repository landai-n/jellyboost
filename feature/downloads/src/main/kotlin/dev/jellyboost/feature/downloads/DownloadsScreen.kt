package dev.jellyboost.feature.downloads

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.common.Separators
import dev.jellyboost.core.common.formatBytes
import dev.jellyboost.core.common.formatDurationSeconds
import dev.jellyboost.core.common.model.DownloadStatus
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.ui.component.ConfirmDialog
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.JellyboostSnackbarHost
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.rememberOneShotSnackbar
import dev.jellyboost.core.ui.theme.ChromeAwarePadding
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.LocalAppChromePadding
import dev.jellyboost.core.ui.theme.glassSurface
import dev.jellyboost.core.ui.theme.mSurface
import dev.jellyboost.core.ui.theme.pageInk
import dev.jellyboost.data.downloads.model.DownloadItem
import dev.jellyboost.data.downloads.model.DownloadKind
import dev.jellyboost.data.downloads.model.StorageUsage

/**
 * Room-only by construction — never touches the network, so it behaves identically online and
 * offline. `contentWindowInsets = WindowInsets(0)`: `:app`'s chrome floats over this screen rather
 * than shrinking it, and how much of the window it covers arrives via `LocalAppChromePadding`.
 *
 * @param onPlay start position is in Jellyfin ticks. The cached item is `null` once the item cache
 *   has been wiped, in which case the caller falls back to the video route.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onPlay: (itemId: String, startPositionTicks: Long, item: JellyfinItem?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = viewModel::consumeMessage,
        ) { downloadsMessageText(it) }

    // Must stay remembered: a fresh bundle is never `equals` to the last, so every visible row
    // would recompose on each of the queue's two-to-six progress writes per second.
    val actions = remember(viewModel) { downloadsActions(viewModel) }
    val bulk = remember(viewModel) { queueBulkActions(viewModel) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // The host's own policy clears the floating nav pill. Do not substitute the chrome's bottom
        // padding: on a wide window that is zero, which would put the snackbar under the gesture bar.
        snackbarHost = { JellyboostSnackbarHost(hostState = snackbarHostState) },
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

/**
 * Each action takes an item **id**, not the row: a `DownloadItem` parameter is unstable and rebuilt
 * on every progress tick, which would stop [QueueRowActions] from ever skipping.
 */
data class DownloadsActions(
    val onPause: (itemId: String) -> Unit,
    val onResume: (itemId: String) -> Unit,
    val onDelete: (itemId: String) -> Unit,
    val onMoveUp: (itemId: String) -> Unit,
    val onMoveDown: (itemId: String) -> Unit,
    val onSelectTab: (DownloadsTab) -> Unit,
    /** Takes a [DownloadGroup.key], which outlives the group it names. */
    val onToggleGroup: (key: String) -> Unit,
)

data class QueueBulkActions(
    val onPauseAll: () -> Unit,
    val onResumeAll: () -> Unit,
    val onCancelAll: () -> Unit,
    val onConfirmCancelAll: () -> Unit,
    val onDismissCancelAll: () -> Unit,
)

private fun downloadsActions(viewModel: DownloadsViewModel) =
    DownloadsActions(
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onDelete = viewModel::delete,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onSelectTab = viewModel::selectTab,
        onToggleGroup = viewModel::toggleGroup,
    )

private fun queueBulkActions(viewModel: DownloadsViewModel) =
    QueueBulkActions(
        onPauseAll = viewModel::pauseAll,
        onResumeAll = viewModel::resumeAll,
        onCancelAll = viewModel::requestCancelAll,
        onConfirmCancelAll = viewModel::confirmCancelAll,
        onDismissCancelAll = viewModel::dismissCancelAll,
    )

/**
 * Two *independent* layout decisions: `wide` (the style everything is drawn in) is width alone,
 * while [chromePinned] also needs height — fused into one, a landscape phone would pin chrome over
 * a list with no room left to scroll in.
 */
@Composable
fun DownloadsContent(
    state: DownloadsUiState,
    actions: DownloadsActions,
    bulk: QueueBulkActions,
    onPlay: (itemId: String, startPositionTicks: Long, item: JellyfinItem?) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Chrome TOP padding here, BOTTOM on whichever list is drawn (listContentPadding).
    // Must stay a `PaddingValues` object resolved in the layout phase: the value animates every
    // frame of a navigation, and reading `calculateTopPadding()` in composition would cost this
    // `BoxWithConstraints` a whole subcomposition pass per transition frame.
    BoxWithConstraints(modifier = modifier.fillMaxSize().padding(chromeTopPadding())) {
        val wide = !queueRowCompact(maxWidth)

        // Keyed on the selected tab: that is what drops a pending confirmation on a tab switch.
        // The *id* is saved, not the row — `DownloadItem` is not `Parcelable`, and looking the row
        // up from on-screen state is what stops the dialog outliving the download it asks about.
        var pendingDeleteId by rememberSaveable(state.selectedTab) { mutableStateOf<String?>(null) }
        val pendingDelete =
            remember(pendingDeleteId, state.downloaded) {
                pendingDeleteId?.let { id -> state.downloaded.itemOrNull(id) }
            }

        if (chromePinned(maxWidth, maxHeight)) {
            PinnedChromeLayout(
                state = state,
                actions = actions,
                bulk = bulk,
                onPlay = onPlay,
                onWifiOnlyChange = onWifiOnlyChange,
                onRequestDelete = { pendingDeleteId = it },
            )
        } else {
            UnifiedScrollLayout(
                state = state,
                actions = actions,
                bulk = bulk,
                wide = wide,
                onPlay = onPlay,
                onWifiOnlyChange = onWifiOnlyChange,
                onRequestDelete = { pendingDeleteId = it },
            )
        }

        pendingDelete?.let { item ->
            DeleteDownloadDialog(
                item = item,
                onDismiss = { pendingDeleteId = null },
                onConfirm = {
                    pendingDeleteId = null
                    actions.onDelete(item.itemId)
                },
            )
        }

        if (state.showCancelAllConfirmation && state.queue.isNotEmpty()) {
            CancelAllDialog(
                count = state.queue.size,
                onDismiss = bulk.onDismissCancelAll,
                onConfirm = bulk.onConfirmCancelAll,
            )
        }
    }
}

/** [chromePinned] is only ever true where `wide` is, hence the hardcoded `wide = true` below. */
@Composable
private fun PinnedChromeLayout(
    state: DownloadsUiState,
    actions: DownloadsActions,
    bulk: QueueBulkActions,
    onPlay: (itemId: String, startPositionTicks: Long, item: JellyfinItem?) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onRequestDelete: (itemId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DownloadsChrome(
            chrome = state.chrome,
            onSelectTab = actions.onSelectTab,
            bulk = bulk,
            wide = true,
            onWifiOnlyChange = onWifiOnlyChange,
        )

        val body = state.body()
        when (body) {
            // **One** list for both tabs, branch inside its content. Two `LazyColumn`s in two
            // branches are two scroll states, so a tab switch would jump to the top here but not
            // under `UnifiedScrollLayout`, which has one list.
            DownloadsBody.DOWNLOADS, DownloadsBody.QUEUE ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding(top = Dimens.SpaceSmall),
                ) {
                    if (body == DownloadsBody.QUEUE) {
                        queueRows(state = state, actions = actions, bulk = bulk, wide = true)
                    } else {
                        downloadedRows(
                            sections = state.downloaded,
                            expandedGroups = state.expandedGroups,
                            showKindHeaders = state.showKindHeaders,
                            onToggleGroup = actions.onToggleGroup,
                            onDelete = onRequestDelete,
                            onPlay = onPlay,
                            compact = false,
                        )
                    }
                }

            else -> DownloadsStateView(body = body)
        }
    }
}

@Composable
private fun UnifiedScrollLayout(
    state: DownloadsUiState,
    actions: DownloadsActions,
    bulk: QueueBulkActions,
    wide: Boolean,
    onPlay: (itemId: String, startPositionTicks: Long, item: JellyfinItem?) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onRequestDelete: (itemId: String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // No top padding: the chrome is inside this list and brings its own, and the window's top
        // inset is already on the outer box.
        contentPadding = listContentPadding(top = 0.dp),
    ) {
        item(key = CHROME_ITEM_KEY, contentType = DownloadsContentType.CHROME) {
            DownloadsChrome(
                chrome = state.chrome,
                onSelectTab = actions.onSelectTab,
                bulk = bulk,
                wide = wide,
                onWifiOnlyChange = onWifiOnlyChange,
            )
        }

        when (val body = state.body()) {
            DownloadsBody.DOWNLOADS ->
                downloadedRows(
                    sections = state.downloaded,
                    expandedGroups = state.expandedGroups,
                    showKindHeaders = state.showKindHeaders,
                    onToggleGroup = actions.onToggleGroup,
                    onDelete = onRequestDelete,
                    onPlay = onPlay,
                    compact = !wide,
                )

            DownloadsBody.QUEUE -> queueRows(state = state, actions = actions, bulk = bulk, wide = wide)

            // An item, not a sibling of the list: on a landscape phone the chrome alone overflows
            // the window, so it must still scroll even with no rows under it.
            else ->
                item(key = STATE_ITEM_KEY, contentType = DownloadsContentType.STATE) {
                    DownloadsStateView(body = body)
                }
        }
    }
}

/**
 * @param chrome exactly the numbers drawn here, and no more. The whole `DownloadsUiState` is
 *   unstable and rebuilt several times a second during a transfer: passing it would stop everything
 *   under this from skipping and re-sum every download's `bytesOnDisk` each time.
 */
@Composable
private fun DownloadsChrome(
    chrome: DownloadsChromeState,
    onSelectTab: (DownloadsTab) -> Unit,
    bulk: QueueBulkActions,
    wide: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        DownloadsHeader(wide = wide)

        if (wide) {
            WideSummary(
                storage = chrome.storage,
                queueStats = chrome.queueStats,
                queueProgress = chrome.queueProgress,
                wifiOnly = chrome.wifiOnly,
                onWifiOnlyChange = onWifiOnlyChange,
            )
        } else {
            StorageCard(
                storage = chrome.storage,
                wifiOnly = chrome.wifiOnly,
                onWifiOnlyChange = onWifiOnlyChange,
            )
        }

        val showInlineBulkActions = wide && chrome.selectedTab == DownloadsTab.QUEUE && chrome.hasQueue
        DownloadsTabRow(
            selectedTab = chrome.selectedTab,
            onSelectTab = onSelectTab,
            wide = wide,
            trailing =
                if (showInlineBulkActions) {
                    {
                        QueueActionsBar(
                            canPauseAll = chrome.canPauseAll,
                            canResumeAll = chrome.canResumeAll,
                            bulk = bulk,
                            horizontalPadding = 0.dp,
                        )
                    }
                } else {
                    null
                },
        )
    }
}

private enum class DownloadsBody {
    LOADING,
    LOAD_FAILED,
    NO_DOWNLOADS,
    DOWNLOADS,
    NO_QUEUE,
    QUEUE,
}

private fun DownloadsUiState.body(): DownloadsBody =
    when {
        isLoading -> DownloadsBody.LOADING

        // Must stay ahead of the tab branches: the projection both read is what failed.
        loadFailed -> DownloadsBody.LOAD_FAILED

        selectedTab == DownloadsTab.DOWNLOADED ->
            if (downloaded.isEmpty()) DownloadsBody.NO_DOWNLOADS else DownloadsBody.DOWNLOADS

        queue.isEmpty() -> DownloadsBody.NO_QUEUE

        else -> DownloadsBody.QUEUE
    }

@Composable
private fun DownloadsStateView(
    body: DownloadsBody,
    modifier: Modifier = Modifier,
) {
    when (body) {
        DownloadsBody.LOADING -> LoadingState(modifier = modifier)
        DownloadsBody.LOAD_FAILED ->
            EmptyState(message = stringResource(R.string.downloads_load_failed), modifier = modifier)

        DownloadsBody.NO_DOWNLOADS ->
            EmptyState(message = stringResource(R.string.downloads_empty_downloaded), modifier = modifier)

        DownloadsBody.NO_QUEUE ->
            EmptyState(message = stringResource(R.string.downloads_empty_queue), modifier = modifier)

        DownloadsBody.DOWNLOADS, DownloadsBody.QUEUE -> Unit
    }
}

/** Bottom clears `:app`'s floating nav pill, which floats over this screen rather than shrinking it. */
@Composable
private fun listContentPadding(top: Dp): PaddingValues {
    val chrome = LocalAppChromePadding.current
    return remember(chrome, top) {
        ChromeAwarePadding(chrome = chrome, top = top, bottom = Dimens.SpaceSmall, takeChromeBottom = true)
    }
}

@Composable
private fun chromeTopPadding(): PaddingValues {
    val chrome = LocalAppChromePadding.current
    return remember(chrome) { ChromeAwarePadding(chrome = chrome, takeChromeTop = true) }
}

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
            // A heading, so TalkBack's heading-jump has somewhere to land on this screen at all.
            modifier = Modifier.semantics { heading() },
            style = if (wide) JellyfinTypeExtras.ScreenTitleLarge else JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * @param onDelete *asks* for a delete rather than performing one: a `LazyListScope` extension is not
 *   a composition, so the confirmation state it feeds lives in [DownloadsContent].
 */
@Suppress("LongParameterList")
private fun LazyListScope.downloadedRows(
    sections: List<DownloadSection>,
    expandedGroups: Set<String>,
    showKindHeaders: Boolean,
    onToggleGroup: (key: String) -> Unit,
    onDelete: (itemId: String) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long, item: JellyfinItem?) -> Unit,
    compact: Boolean,
) {
    sections.forEach { section ->
        if (showKindHeaders) {
            item(key = "kind-${section.kind.name}", contentType = DownloadsContentType.KIND_HEADER) {
                KindHeader(kind = section.kind)
            }
        }
        section.groups.forEach { group ->
            downloadedGroup(
                kind = section.kind,
                group = group,
                expanded = group.key in expandedGroups,
                onToggleGroup = onToggleGroup,
                onDelete = onDelete,
                onPlay = onPlay,
                compact = compact,
            )
        }
    }
}

/** A folded collapsible group emits its header and nothing else. */
@Suppress("LongParameterList")
private fun LazyListScope.downloadedGroup(
    kind: DownloadKind,
    group: DownloadGroup,
    expanded: Boolean,
    onToggleGroup: (key: String) -> Unit,
    onDelete: (itemId: String) -> Unit,
    onPlay: (itemId: String, startPositionTicks: Long, item: JellyfinItem?) -> Unit,
    compact: Boolean,
) {
    if (group.isCollapsible) {
        item(key = "header-${group.key}", contentType = DownloadsContentType.HEADER) {
            GroupHeader(
                title = group.title,
                subtitle = group.subtitle,
                artworkUrl = group.artworkUrl,
                kind = kind,
                itemCount = group.itemCount,
                bytesOnDisk = group.bytesOnDisk,
                expanded = expanded,
                onToggle = { onToggleGroup(group.key) },
            )
        }
        if (!expanded) return
    }
    // An album's tracks all share the header's one cover, so repeating it down the list says nothing.
    val showArtwork = !(group.isCollapsible && kind == DownloadKind.MUSIC)
    // Two node shapes, so two content types: one pool holding both would hand a recycled artless row
    // to a row that needs an image node, and Lazy layout would rebuild it from scratch anyway.
    val rowContentType =
        if (showArtwork) DownloadsContentType.DOWNLOADED_ROW else DownloadsContentType.DOWNLOADED_ROW_ARTLESS
    items(
        items = group.items,
        key = { it.itemId },
        contentType = { rowContentType },
    ) { item ->
        DownloadedRow(
            item = item,
            onDelete = { onDelete(item.itemId) },
            onPlay = { onPlay(item.itemId, item.playbackStartTicks, item.item) },
            inGroup = group.isCollapsible,
            compact = compact,
            showArtwork = showArtwork,
        )
    }
}

/**
 * Deliberately not mirrored on the queue tab's *Cancel*: this destroys a finished transfer, whereas
 * cancelling costs only the bytes not yet spent and is undone by pressing Download again.
 */
@Composable
private fun DeleteDownloadDialog(
    item: DownloadItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.downloads_delete_dialog_title, item.rowTitle()),
        text = stringResource(R.string.downloads_delete_dialog_message),
        confirmLabel = stringResource(R.string.downloads_delete_dialog_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The bulk-action bar is emitted here **only** when compact — wide draws it inline in
 * [DownloadsTabRow], so an unguarded copy here would duplicate every button.
 */
private fun LazyListScope.queueRows(
    state: DownloadsUiState,
    actions: DownloadsActions,
    bulk: QueueBulkActions,
    wide: Boolean,
) {
    if (!wide) {
        item(key = QUEUE_ACTIONS_ITEM_KEY, contentType = DownloadsContentType.QUEUE_ACTIONS) {
            QueueActionsBar(
                canPauseAll = state.canPauseAll,
                canResumeAll = state.canResumeAll,
                bulk = bulk,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    state.queueSections.forEach { section ->
        if (state.showQueueKindHeaders) {
            item(key = "queue-kind-${section.kind.name}", contentType = DownloadsContentType.KIND_HEADER) {
                KindHeader(kind = section.kind)
            }
        }
        items(
            items = section.items,
            key = { it.itemId },
            contentType = { DownloadsContentType.QUEUE_ROW },
        ) { item ->
            QueueRow(
                item = item,
                // The ratcheted fraction; the row's own is a fallback only for an item the ratchet
                // has not seen yet (the first frame after an enqueue).
                progress = state.progress[item.itemId] ?: item.progress,
                speedBytesPerSecond = state.speeds[item.itemId],
                actions = actions,
                compact = !wide,
            )
        }
    }
}

/**
 * Below this, [QueueRow]'s one-row layout — 48dp thumbnail + up to four 48dp action buttons (≈192dp)
 * inside `Dimens.ScreenPadding` — leaves under ~90dp for the title, ~4 characters on a 360dp phone.
 *
 * The screen's *only* wide/compact breakpoint: [DownloadsContent] reads its complement rather than
 * inventing a second one.
 */
private val COMPACT_MAX_WIDTH = 480.dp

/** `internal` so the breakpoint is testable without a Compose harness. */
internal fun queueRowCompact(maxWidth: Dp): Boolean = maxWidth < COMPACT_MAX_WIDTH

/**
 * The wide chrome (title, three stat panels, tab row) measures ~260–300dp; a landscape phone is
 * ~360–400dp tall but wide enough that [queueRowCompact] answers `false`, so pinning there would
 * leave the queue unreachable — nothing on the screen would scroll.
 *
 * Deliberately the same figure as [COMPACT_MAX_WIDTH] on the other axis, so the screen carries one
 * number rather than two.
 */
private val PINNED_CHROME_MIN_HEIGHT = 480.dp

internal fun chromePinned(
    maxWidth: Dp,
    maxHeight: Dp,
): Boolean = !queueRowCompact(maxWidth) && maxHeight >= PINNED_CHROME_MIN_HEIGHT

/**
 * [horizontalPadding] is `0.dp` when drawn inline in [DownloadsTabRow] — that row already carries
 * the screen's side margin. *Pause/Resume all* are disabled rather than hidden, so the buttons do
 * not vanish under the finger as the queue changes shape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QueueActionsBar(
    canPauseAll: Boolean,
    canResumeAll: Boolean,
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
            enabled = canPauseAll,
            onClick = bulk.onPauseAll,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QueueBulkButton(
            icon = Icons.Filled.PlayArrow,
            labelRes = R.string.downloads_action_resume_all,
            enabled = canResumeAll,
            onClick = bulk.onResumeAll,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QueueBulkButton(
            icon = Icons.Filled.Delete,
            labelRes = R.string.downloads_action_cancel_all,
            enabled = true,
            onClick = bulk.onCancelAll,
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
    contentColor: Color,
) {
    val resolvedColor = if (enabled) contentColor else BulkButtonDisabledContent
    Row(
        modifier =
            Modifier
                .clip(CircleShape)
                .glassSurface(CircleShape)
                .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
                .defaultMinSize(minHeight = Dimens.PillHeightSmall)
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
 * The message copy promises that finished downloads are untouched — true because `toQueue()` never
 * puts them in this list. Keep the two in step.
 */
@Composable
private fun CancelAllDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = pluralStringResource(R.plurals.downloads_cancel_all_dialog_title, count, count),
        text = stringResource(R.string.downloads_cancel_all_dialog_message),
        confirmLabel = stringResource(R.string.downloads_cancel_all_dialog_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        // Not the shared "Cancel": beside "Cancel all" that would be a coin toss. "Keep" is not.
        dismissLabel = stringResource(R.string.downloads_cancel_all_dialog_dismiss),
    )
}

/**
 * Uppercased in the UI locale and spoken sentence-case for the reasons [StatEyebrow] states; the
 * section label is only ever drawn when more than one kind is on the tab.
 */
@Composable
private fun KindHeader(
    kind: DownloadKind,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(kind.sectionTitleRes())
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = label.uppercase(locale),
        style = JellyfinTypeExtras.Eyebrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = Dimens.PanelPadding,
                    end = Dimens.PanelPadding,
                    top = Dimens.SpaceLarge,
                    bottom = Dimens.SpaceExtraSmall,
                ).semantics {
                    heading()
                    contentDescription = label
                },
    )
}

/**
 * `internal` for the instrumented suite, which is the only gate that can see Compose semantics.
 *
 * [heightIn], never a fixed height: the row must grow with the font scale rather than clip, and its
 * minimum is Material's touch target — the whole header is the toggle.
 *
 * @param kind decides the count's wording; only a collapsible group draws a header, so this is
 *   never [DownloadKind.MOVIE].
 * @param subtitle the album's artist or the series' season(s), drawn under the title. `null` keeps
 *   the header one line.
 * @param artworkUrl the album cover or the season poster, decorative: the merged row already speaks
 *   the title, the subtitle and the count, and a second description would repeat one of them. Drawn
 *   at one square size for both, so a portrait poster is centre-cropped rather than given the
 *   header a second geometry.
 */
@Composable
@Suppress("LongParameterList")
internal fun GroupHeader(
    title: String,
    kind: DownloadKind,
    itemCount: Int,
    bytesOnDisk: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    artworkUrl: String? = null,
) {
    val expandedState =
        stringResource(if (expanded) R.string.downloads_group_expanded else R.string.downloads_group_collapsed)
    val clickLabel =
        stringResource(if (expanded) R.string.downloads_group_collapse else R.string.downloads_group_expand)
    val chevronTurn by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_EXPANDED_DEGREES else 0f,
        label = "downloadGroupChevron",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                // One spoken node, and a heading so TalkBack can jump group to group.
                .semantics(mergeDescendants = true) {
                    heading()
                    stateDescription = expandedState
                }.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
                .padding(horizontal = Dimens.PanelPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            // The state is spoken by the row's own stateDescription; a second node would repeat it.
            contentDescription = null,
            modifier = Modifier.size(GroupChevronSize).graphicsLayer { rotationZ = chevronTurn },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (artworkUrl != null) {
            RowArtwork(imageUrl = artworkUrl, width = GroupArtworkSize, height = GroupArtworkSize)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text =
                listOf(kind.itemCountText(itemCount), formatBytes(bytesOnDisk))
                    .joinToString(Separators.DOT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@StringRes
private fun DownloadKind.sectionTitleRes(): Int =
    when (this) {
        DownloadKind.MOVIE -> R.string.downloads_section_movies
        DownloadKind.SERIES -> R.string.downloads_section_series
        DownloadKind.MUSIC -> R.string.downloads_section_music
    }

@Composable
private fun DownloadKind.itemCountText(count: Int): String =
    pluralStringResource(
        if (this == DownloadKind.MUSIC) {
            R.plurals.downloads_group_track_count
        } else {
            R.plurals.downloads_group_episode_count
        },
        count,
        count,
    )

/** Replaced by [WideSummary] on a wide layout; keep the two in step. */
@Composable
private fun StorageCard(
    storage: StorageSummary,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PanelPadding, vertical = Dimens.SpaceSmall)
                .mSurface(MaterialTheme.colorScheme.surface)
                .padding(horizontal = Dimens.SpaceLarge, vertical = StatPanelVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(StatPanelInnerGap),
    ) {
        StatEyebrow(text = stringResource(R.string.downloads_stat_on_device))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = formatBytes(storage.usedBytes),
                style = StatValue,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.downloads_storage_free, formatBytes(storage.availableBytes)),
                style = StatCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UsageBar(
            fraction = usageFraction(storage.usedBytes, storage.totalBytes),
            label = stringResource(R.string.downloads_usage_storage_label),
        )
        WifiOnlyToggle(wifiOnly = wifiOnly, onWifiOnlyChange = onWifiOnlyChange)
    }
}

/**
 * Shared by the compact [StorageCard] and the wide [NetworkStatPanel]. The label is `onBackground`,
 * not `onSurfaceVariant`: white at 70 % on the `#202020` m-surface is the lower-contrast answer.
 * The whole row is the toggle's target, [Dimens.MinTouchTarget] tall.
 */
@Composable
private fun WifiOnlyToggle(
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Dimens.MinTouchTarget)
                .toggleable(value = wifiOnly, onValueChange = onWifiOnlyChange, role = Role.Switch),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.downloads_wifi_only),
            style = StatSwitchLabel,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = wifiOnly, onCheckedChange = null)
    }
}

/**
 * The wide-layout replacement for [StorageCard]; keep the two in step.
 *
 * [queueProgress] must stay derived on [DownloadsChromeState], not inline here: computed inline it
 * would re-sum the whole queue on every recomposition of a panel that recomposes several times a
 * second.
 */
@Composable
private fun WideSummary(
    storage: StorageSummary,
    queueStats: QueueStats,
    queueProgress: Float,
    wifiOnly: Boolean,
    onWifiOnlyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PanelPadding, vertical = Dimens.SpaceSmall)
                // Equal heights: the network panel grows a helper line while Wi-Fi-only is on.
                .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceLarge),
    ) {
        OnDeviceStatPanel(
            storage = storage,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        QueueStatPanel(
            stats = queueStats,
            progress = queueProgress,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        NetworkStatPanel(
            wifiOnly = wifiOnly,
            onWifiOnlyChange = onWifiOnlyChange,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun OnDeviceStatPanel(
    storage: StorageSummary,
    modifier: Modifier = Modifier,
) {
    StatPanel(modifier = modifier) {
        StatEyebrow(text = stringResource(R.string.downloads_stat_on_device))
        Text(
            text = formatBytes(storage.usedBytes),
            style = StatValue,
            color = MaterialTheme.colorScheme.onBackground,
        )
        UsageBar(
            fraction = usageFraction(storage.usedBytes, storage.totalBytes),
            label = stringResource(R.string.downloads_usage_storage_label),
        )
        Text(
            text =
                stringResource(
                    R.string.downloads_stat_free_of,
                    formatBytes(storage.availableBytes),
                    formatBytes(storage.totalBytes),
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
        UsageBar(fraction = progress, label = stringResource(R.string.downloads_usage_queue_label))
        // Hidden while idle: "0 B/s" reads as a stall rather than as nothing being asked for.
        if (!stats.isIdle) {
            val etaSeconds = stats.etaSeconds
            Text(
                text =
                    listOfNotNull(
                        stringResource(R.string.downloads_speed, formatBytes(stats.bytesPerSecond)),
                        etaSeconds?.let {
                            stringResource(R.string.downloads_stat_eta_about, formatDurationSeconds(it))
                        },
                    ).joinToString(Separators.DOT),
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
        WifiOnlyToggle(wifiOnly = wifiOnly, onWifiOnlyChange = onWifiOnlyChange)
        if (wifiOnly) {
            Text(
                text = stringResource(R.string.downloads_stat_network_helper),
                style = StatCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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

/**
 * Uppercasing stops here (as in `:player`'s `TagPill`), on both counts: the locale must be
 * `LocalConfiguration`'s, not the JVM default lint cannot see through — Turkish "TITLE" vs "TİTLE";
 * and the contentDescription keeps the sentence-case form, since some TTS engines spell out caps.
 */
@Composable
private fun StatEyebrow(text: String) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        text = text.uppercase(locale),
        style = JellyfinTypeExtras.Eyebrow,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { contentDescription = text },
    )
}

/**
 * Hand-rolled `Box`es carry no semantics, so the explicit [ProgressBarRangeInfo] is what makes a
 * screen reader say a percentage at all — and [label] is mandatory, since a wide layout draws three
 * of these and "23 percent" of *what* is the whole question.
 */
@Composable
private fun UsageBar(
    fraction: Float,
    label: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(UsageBarRadius)
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(UsageBarHeight)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(current = clamped, range = 0f..1f)
                }.clip(shape)
                .background(UsageBarTrackColor),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(color = MaterialTheme.colorScheme.primary, shape = shape),
        )
    }
}

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
                // Minimum, never a fixed `height` (see `GlassBottomNav`): 36dp around a 13sp label
                // leaves under 4dp of slack, and large font scales clipped the tab's own word.
                .heightIn(min = Dimens.PillHeightSmall)
                .clip(CircleShape)
                .selectable(selected = selected, onClick = onClick, role = Role.Tab)
                // The brand pill's rule (JellyfinButtons): the page's ink filled, the page on it.
                .background(
                    color = if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent,
                    shape = CircleShape,
                ).padding(
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

private enum class DownloadsContentType {
    CHROME,
    KIND_HEADER,
    HEADER,
    DOWNLOADED_ROW,
    DOWNLOADED_ROW_ARTLESS,
    QUEUE_ACTIONS,
    QUEUE_ROW,
    STATE,
}

/**
 * Must stay stable across a tab switch within one [UnifiedScrollLayout] list — the chrome above all,
 * which is the same item before and after and would otherwise be rebuilt and scrolled to the top.
 */
private const val CHROME_ITEM_KEY = "downloads-chrome"
private const val QUEUE_ACTIONS_ITEM_KEY = "downloads-queue-actions"
private const val STATE_ITEM_KEY = "downloads-state"

private fun DownloadsTab.titleRes(): Int =
    when (this) {
        DownloadsTab.DOWNLOADED -> R.string.downloads_tab_downloaded
        DownloadsTab.QUEUE -> R.string.downloads_tab_queue
    }

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

private val GroupChevronSize = 20.dp

/** Square, unlike the 16:9 row art: an album cover is square and would otherwise be cropped. */
private val GroupArtworkSize = 44.dp

/** Points down folded, up unfolded. */
private const val CHEVRON_EXPANDED_DEGREES = 180f
private val StatPanelVerticalPadding = 18.dp
private val StatPanelInnerGap = 6.dp
private val UsageBarHeight = 6.dp
private val UsageBarRadius = 3.dp
private val UsageBarTrackColor: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.12f)
private val SegmentedTabBarPadding = 4.dp
private val SegmentedTabGap = 2.dp
private val SegmentedTabHorizontalPaddingCompact = 12.dp
private val SegmentedTabHorizontalPaddingWide = 20.dp
private val SegmentedSelectedContent: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background
private val SegmentedTabLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.W500)
private val BulkButtonHorizontalPadding = 14.dp
private val BulkButtonVerticalPadding = 8.dp
private val BulkButtonIconSize = 15.dp
private val BulkButtonLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.W500)

/**
 * 0.48, not 0.35: the label is text with a 4.5:1 obligation — 3.20:1 at 0.35, 4.78:1 at 0.48 on
 * `#202020`. Dark ink is not white's mirror, so the light side runs at 0.65: 0.48 of it is 3.09:1
 * on the bar's light glass well, 5.23:1 at 0.65. The same pair `JellyfinButtons`' disabled pill
 * content uses — the page's ink rather than the enabled label's own colour, which is what lets it
 * carry a per-scheme alpha at all.
 */
private val BulkButtonDisabledContent: Color
    @Composable @ReadOnlyComposable
    get() = pageInk(darkAlpha = 0.48f, lightAlpha = 0.65f)
private val StatValue = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W600)
private val StatValueSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600)
private val StatSwitchLabel = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600)
private val StatCaption = TextStyle(fontSize = 12.sp)

@Preview(name = "Downloads — queue", showBackground = true, backgroundColor = 0xFF101010, widthDp = 390)
@Composable
private fun DownloadsPreview() {
    QueuePreview()
}

@Preview(
    name = "Downloads — queue (phone portrait, 360×740)",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 360,
    heightDp = 740,
)
@Composable
private fun DownloadsPreviewCompact() {
    QueuePreview()
}

/** Wide enough for the tablet treatment but under `PINNED_CHROME_MIN_HEIGHT`: unpinned chrome. */
@Preview(
    name = "Downloads — queue (phone landscape, 800×360)",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 800,
    heightDp = 360,
)
@Composable
private fun DownloadsPreviewPhoneLandscape() {
    QueuePreview()
}

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

@Preview(
    name = "Downloads — downloaded (three kinds, one group open)",
    showBackground = true,
    backgroundColor = 0xFF101010,
    widthDp = 390,
    heightDp = 740,
)
@Composable
private fun DownloadsDownloadedPreview() {
    JellyfinTheme {
        DownloadsContent(
            state =
                DownloadsUiState(
                    downloaded = downloadedPreviewSections(),
                    // "Westworld" open, "Rumours" folded: the two states must be visible at once.
                    expandedGroups = setOf("SERIES:Westworld"),
                    storage = StorageUsage(usedBytes = 9_400_000_000L, availableBytes = 40_000_000_000L),
                    isLoading = false,
                ),
            actions = previewActions(),
            bulk = previewBulkActions(),
            onPlay = { _, _, _ -> },
            onWifiOnlyChange = {},
        )
    }
}

private fun downloadedPreviewSections(): List<DownloadSection> =
    listOf(
        finishedPreviewRow(id = "1", title = "Dune", type = ItemType.MOVIE, onDisk = 6_100_000_000L),
        // Both episodes of one season, each with its own still: the header's poster and the rows'
        // stills must be visibly different images, which is the whole reason series rows keep theirs.
        finishedPreviewRow(
            id = "2",
            title = "The Bicameral Mind",
            type = ItemType.EPISODE,
            seriesName = "Westworld",
            seasonName = "Season 1",
            seasonNumber = 1,
            imageUrl = "https://example.invalid/bicameral-mind.jpg",
            seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg",
            onDisk = 2_100_000_000L,
        ),
        finishedPreviewRow(
            id = "3",
            title = "Chestnut",
            type = ItemType.EPISODE,
            seriesName = "Westworld",
            seasonName = "Season 1",
            seasonNumber = 1,
            imageUrl = "https://example.invalid/chestnut.jpg",
            seasonArtworkUrl = "https://example.invalid/westworld-s1.jpg",
            onDisk = 1_900_000_000L,
        ),
        finishedPreviewRow(
            id = "4",
            title = "Dreams",
            type = ItemType.AUDIO,
            albumName = "Rumours",
            artistName = "Fleetwood Mac",
            imageUrl = "https://example.invalid/rumours.jpg",
            onDisk = 24_000_000L,
        ),
        finishedPreviewRow(
            id = "5",
            title = "Go Your Own Way",
            type = ItemType.AUDIO,
            albumName = "Rumours",
            artistName = "Fleetwood Mac",
            imageUrl = "https://example.invalid/rumours.jpg",
            onDisk = 21_000_000L,
        ),
    ).toSections()

@Suppress("LongParameterList")
private fun finishedPreviewRow(
    id: String,
    title: String,
    type: ItemType,
    onDisk: Long,
    seriesName: String? = null,
    albumName: String? = null,
    artistName: String? = null,
    imageUrl: String? = null,
    seasonName: String? = null,
    seasonNumber: Int? = null,
    seasonArtworkUrl: String? = null,
) = DownloadItem(
    itemId = id,
    title = title,
    seriesName = seriesName,
    status = DownloadStatus.DOWNLOADED,
    bytesDownloaded = onDisk,
    bytesTotal = onDisk,
    bytesOnDisk = onDisk,
    queuePosition = 0,
    itemType = type,
    albumName = albumName,
    artistName = artistName,
    item =
        if (imageUrl == null && seasonName == null) {
            null
        } else {
            JellyfinItem(
                id = id,
                name = title,
                type = type,
                primaryImageUrl = imageUrl,
                seasonName = seasonName,
                parentIndexNumber = seasonNumber,
            )
        },
    seasonArtworkUrl = seasonArtworkUrl,
)

private fun previewActions() =
    DownloadsActions(
        onPause = {},
        onResume = {},
        onDelete = {},
        onMoveUp = {},
        onMoveDown = {},
        onSelectTab = {},
        onToggleGroup = {},
    )

private fun previewBulkActions() =
    QueueBulkActions(
        onPauseAll = {},
        onResumeAll = {},
        onCancelAll = {},
        onConfirmCancelAll = {},
        onDismissCancelAll = {},
    )

/** Keep the fixture title long: a short one does not truncate at 360dp, so it shows nothing. */
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
            actions = previewActions(),
            bulk = previewBulkActions(),
            onPlay = { _, _, _ -> },
            onWifiOnlyChange = {},
        )
    }
}
