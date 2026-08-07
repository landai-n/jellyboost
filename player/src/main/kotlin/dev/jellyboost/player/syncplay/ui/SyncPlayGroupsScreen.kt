package dev.jellyboost.player.syncplay.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.FieldLabel
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.JellyboostSnackbarHost
import dev.jellyboost.core.ui.component.JellyfinTextField
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.component.rememberOneShotSnackbar
import dev.jellyboost.core.ui.theme.Dimens
import dev.jellyboost.core.ui.theme.GlassDefaults
import dev.jellyboost.core.ui.theme.JellyfinTheme
import dev.jellyboost.core.ui.theme.JellyfinTypeExtras
import dev.jellyboost.core.ui.theme.mSurface
import dev.jellyboost.player.R
import dev.jellyboost.player.syncplay.SyncPlayLaunchRequest
import dev.jellyboost.player.syncplay.SyncPlayMessage
import dev.jellyboost.player.syncplay.model.SyncPlayGroupState
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import java.util.UUID
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * The dedicated SyncPlay section (M11 Phase 5, docs/notes/syncplay-m11-plan.md): every group this
 * account may join, the one it is already in pinned above them, and the three membership actions.
 *
 * A pushed destination like `SettingsScreen` and `LibraryGridScreen` — reached from the home top
 * bar's Groups action, not one of the four tabs — so it owns the same back-plus-home glass header
 * `LibraryGridScreen` established (2026 refresh, Phase 5 sweep) rather than the app's own chrome.
 *
 * Join, create and leave are never handled here: they go straight to [SyncPlayGroupsViewModel],
 * which forwards them to `SyncPlayController` — the only thing that owns the socket and the join
 * handshake (key decision 11 again: this screen only ever *asks*).
 *
 * @param viewModel passed in rather than resolved here so `:app` owns the `hiltViewModel()` call,
 *   the same convention every other pushed screen follows.
 * @param onOpenPlayer opens the full-screen player for the pinned group's current item — the same
 *   `(itemId, startPositionTicks)` shape the app NavHost's own launch-request collector uses, so a
 *   tap here and a `PlayQueueUpdate` arriving with nobody watching land on the exact same route.
 */
@Composable
fun SyncPlayGroupsScreen(
    viewModel: SyncPlayGroupsViewModel,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenPlayer: (itemId: String, startPositionTicks: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SyncPlayGroupsContent(
        state = state,
        onBack = onBack,
        onHome = onHome,
        onCreate = viewModel::createGroup,
        onJoin = viewModel::join,
        onLeave = viewModel::leave,
        onRetry = viewModel::retry,
        onOpenPlayer = { request -> onOpenPlayer(request.itemId.toString(), request.startPositionTicks) },
        onMessageShown = viewModel::consumeMessage,
        modifier = modifier,
    )
}

/** Stateless rendering — a pure function of [state], previewable without a ViewModel. */
@Composable
fun SyncPlayGroupsContent(
    state: SyncPlayGroupsUiState,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onCreate: (String) -> Unit,
    onJoin: (SyncPlayGroupSummary) -> Unit,
    onLeave: () -> Unit,
    onRetry: () -> Unit,
    onOpenPlayer: (SyncPlayLaunchRequest) -> Unit,
    onMessageShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf(false) }
    // The shared one-shot idiom, keyed on the message rather than on its copy (audit DUP-3/HYG-8):
    // two `SyncPlayMessage`s that happen to resolve to the same sentence are still two messages, and
    // the copy-keyed version this replaces would have shown neither the second one nor consumed it.
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = onMessageShown,
        ) { message -> stringResource(message.textRes()) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The header below carries its own status-bar padding, the same way `LibraryGridScreen`'s
        // does — nothing here reserves space for a `TopAppBar` any more.
        contentWindowInsets = WindowInsets(0),
        // The shared host, which is also the fix for this screen's missing inset: taking no window
        // insets at all (above) meant the `Scaffold` handed its snackbar slot none either, so the
        // pill sat under the gesture bar (audit DUP-3).
        snackbarHost = { JellyboostSnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SyncPlayGroupsHeader(
                onBack = onBack,
                onHome = onHome,
                onCreate = { showCreateDialog = true },
                // Disabled together with the disabled state: a create call against a server that
                // just answered 403 to the list would only fail the same way.
                createEnabled = !state.disabled,
            )

            Box(
                // Capped and centred like every other pushed screen's content: a full-bleed list on
                // the test tablet puts a row's name and its state hint a hand-span apart.
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
            ) {
                when {
                    state.isLoading -> LoadingState()
                    state.disabled -> EmptyState(message = stringResource(R.string.player_syncplay_groups_disabled))
                    // Full-screen only when there is nothing else worth keeping on screen: with a
                    // membership standing, the pinned card — this screen's only Leave affordance —
                    // must survive a single failed 10 s poll, which lands precisely in the flaky
                    // network the user wants to leave from (audit SP-05). The membership comes
                    // from the controller, not the poll, so it was never in doubt; the error shows
                    // inline in [GroupsList] instead.
                    state.transientError && state.membership == SyncPlayGroupsMembership.None ->
                        ErrorState(message = stringResource(R.string.player_syncplay_groups_error), onRetry = onRetry)

                    state.isEmpty -> EmptyState(message = stringResource(R.string.player_syncplay_groups_empty))

                    else ->
                        GroupsList(
                            state = state,
                            onJoin = onJoin,
                            onLeaveClick = { confirmingLeave = true },
                            onOpenPlayer = onOpenPlayer,
                            onRetry = onRetry,
                        )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                onCreate(name)
            },
        )
    }

    if (confirmingLeave) {
        AlertDialog(
            onDismissRequest = { confirmingLeave = false },
            modifier =
                Modifier.border(
                    width = GlassDefaults.HairlineWidth,
                    color = GlassDefaults.PanelHairline,
                    shape = MaterialTheme.shapes.extraLarge,
                ),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(text = stringResource(R.string.player_syncplay_leave_title)) },
            text = { Text(text = stringResource(R.string.player_syncplay_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingLeave = false
                        onLeave()
                    },
                ) { Text(text = stringResource(R.string.player_syncplay_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = false }) {
                    Text(text = stringResource(R.string.player_syncplay_cancel))
                }
            },
        )
    }
}

/**
 * The screen's header: the pushed-screen glass idiom `LibraryGridScreen` established (2026 refresh,
 * Phase 5 sweep) — back-then-home glass circles, the screen title, and a trailing glass *Create*
 * circle where the old `TopAppBar`'s `actions` slot used to sit.
 */
@Composable
private fun SyncPlayGroupsHeader(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onCreate: () -> Unit,
    createEnabled: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = HeaderPadding, vertical = Dimens.SpaceSmall),
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.player_syncplay_groups_back),
            onClick = onBack,
        )
        GlassIconButton(
            icon = Icons.Filled.Home,
            contentDescription = stringResource(R.string.player_syncplay_groups_home),
            onClick = onHome,
        )
        Text(
            text = stringResource(R.string.player_syncplay_groups_title),
            style = JellyfinTypeExtras.ScreenTitle,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = Dimens.SpaceExtraSmall),
        )
        GlassIconButton(
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.player_syncplay_groups_create),
            onClick = onCreate,
            enabled = createEnabled,
        )
    }
}

/** Side padding of the header — the same 20dp `LibraryGridScreen`'s header uses. */
private val HeaderPadding = 20.dp

@Composable
private fun GroupsList(
    state: SyncPlayGroupsUiState,
    onJoin: (SyncPlayGroupSummary) -> Unit,
    onLeaveClick: () -> Unit,
    onOpenPlayer: (SyncPlayLaunchRequest) -> Unit,
    onRetry: () -> Unit,
) {
    val membership = state.membership

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        if (state.transientError) {
            item(key = "poll-error") { PollErrorRow(onRetry = onRetry) }
        }

        when (membership) {
            is SyncPlayGroupsMembership.InGroup ->
                item(key = "active-group") {
                    ActiveGroupCard(membership = membership, onLeave = onLeaveClick, onOpenPlayer = onOpenPlayer)
                }

            SyncPlayGroupsMembership.Joining ->
                item(key = "joining") { JoiningRow() }

            SyncPlayGroupsMembership.None -> Unit
        }

        items(items = state.groups, key = { it.id }) { group ->
            GroupRow(
                group = group,
                // Joining or already in a group: this list is not interactive until that settles —
                // the protocol only tracks one membership per session (`SyncPlayController.state`).
                enabled = membership == SyncPlayGroupsMembership.None,
                onClick = { onJoin(group) },
            )
        }
    }
}

/**
 * The group this device is in, pinned above the browsable list.
 *
 * *Open player* only appears once the group actually has something playing
 * ([SyncPlayGroupsMembership.InGroup.openPlayer] non-null) — a group that has only just been
 * created or joined has nothing to open yet, and there is no resume logic to invent here: the
 * button's target is exactly the launch request the app NavHost's own collector would otherwise
 * have acted on.
 */
@Composable
private fun ActiveGroupCard(
    membership: SyncPlayGroupsMembership.InGroup,
    onLeave: () -> Unit,
    onOpenPlayer: (SyncPlayLaunchRequest) -> Unit,
) {
    // An "m-surface" panel (2026 refresh, Phase 5 sweep) — the container language `:feature:downloads`
    // established for cards that sit inside another screen rather than over a backdrop image.
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .mSurface(MaterialTheme.colorScheme.surface)
                .padding(Dimens.PanelPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall),
    ) {
        Text(
            text = stringResource(R.string.player_syncplay_groups_active_label).uppercase(),
            style = JellyfinTypeExtras.Eyebrow,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = membership.groupName,
            style = JellyfinTypeExtras.SectionTitle,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text =
                pluralStringResource(
                    R.plurals.player_syncplay_participants,
                    membership.participants.size,
                    membership.participants.size,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceSmall)) {
            membership.openPlayer?.let { request ->
                PrimaryPillButton(
                    text = stringResource(R.string.player_syncplay_groups_open_player),
                    onClick = { onOpenPlayer(request) },
                    small = true,
                )
            }
            GhostPillButton(
                text = stringResource(R.string.player_syncplay_leave),
                onClick = onLeave,
                small = true,
            )
        }
    }
}

/**
 * A transient poll failure shown inline, above whatever the screen still knows for certain.
 *
 * Only rendered while a membership (or a join in flight) keeps [GroupsList] on screen — with
 * nothing else to show, the full-screen [ErrorState] still takes over. The list of *joinable*
 * groups is genuinely unknown during the failure, and the empty list under this row says so.
 */
@Composable
private fun PollErrorRow(onRetry: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .mSurface(MaterialTheme.colorScheme.surface)
                .padding(Dimens.PanelPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        Text(
            text = stringResource(R.string.player_syncplay_groups_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        GhostPillButton(
            text = stringResource(CoreUiR.string.state_retry),
            onClick = onRetry,
            small = true,
        )
    }
}

@Composable
private fun JoiningRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Dimens.SpaceLarge),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceMedium),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(Dimens.SpaceLarge),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = SPINNER_STROKE,
            trackColor = Color.White.copy(alpha = SPINNER_TRACK_ALPHA),
        )
        Text(text = stringResource(R.string.player_syncplay_groups_joining))
    }
}

@Composable
private fun GroupRow(
    group: SyncPlayGroupSummary,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .mSurface(MaterialTheme.colorScheme.surface)
                // Role, so the row announces as something that can be pressed rather than as three
                // fragments of text that happen to react to a tap (audit A11Y-ROLE-01).
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(Dimens.PanelPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = group.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = participantsSummary(group.participants),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The list DTO carries no playing-item title (`GroupInfoDto` has none) — its own
        // Idle/Waiting/Paused/Playing state is the closest hint this row can give for free.
        Text(
            text = stringResource(group.state.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** [CircularProgressIndicator] geometry per the shared "inline hint" spinner (spec, "Spinner"). */
private val SPINNER_STROKE = 2.dp
private const val SPINNER_TRACK_ALPHA = 0.14f

/**
 * Turns [participants] into the row's secondary text (B6).
 *
 * Follows `SyncPlayGroupSheet`, which already lists everyone in the group this device is in — the
 * browsable list has no room for one row per name, so it joins them instead and falls back to
 * "+N more" past [MAX_PARTICIPANT_NAMES], the same shape jellyfin-web's group picker uses. An empty
 * list falls back to the plural count rather than an empty line — the server has never actually sent
 * one, but a blank row would look broken if it ever did.
 */
@Composable
private fun participantsSummary(participants: List<String>): String {
    if (participants.isEmpty()) return pluralStringResource(R.plurals.player_syncplay_participants, 0, 0)
    val shown = participants.take(MAX_PARTICIPANT_NAMES)
    val hiddenCount = participants.size - shown.size
    val names = shown.joinToString(", ")
    return if (hiddenCount > 0) {
        stringResource(R.string.player_syncplay_groups_participants_more, names, hiddenCount)
    } else {
        names
    }
}

/** Names shown before the row switches to "+N more" — fits [GroupRow]'s width on the test tablet. */
private const val MAX_PARTICIPANT_NAMES = 3

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier.border(
                width = GlassDefaults.HairlineWidth,
                color = GlassDefaults.PanelHairline,
                shape = MaterialTheme.shapes.extraLarge,
            ),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(text = stringResource(R.string.player_syncplay_groups_create_title)) },
        text = {
            JellyfinTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.player_syncplay_groups_create_hint)) },
                // The field's name, not just its hint: a placeholder is gone the moment the user
                // types, and this field then announced as a bare edit box holding whatever it
                // holds. The dialog's own title is a separate node and does not name it
                // (accessibility audit 2026-08-05, CR-2). No error state exists here — the confirm
                // button is simply disabled until the name is non-blank — so no `errorMessage`.
                // No caption: the name is spoken, never drawn — the placeholder already draws it.
                label = FieldLabel(text = stringResource(R.string.player_syncplay_groups_create_hint)),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(R.string.player_syncplay_groups_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.player_syncplay_cancel)) }
        },
    )
}

private fun SyncPlayGroupState.labelRes(): Int =
    when (this) {
        SyncPlayGroupState.Idle -> R.string.player_syncplay_state_idle
        SyncPlayGroupState.Waiting -> R.string.player_syncplay_state_waiting
        SyncPlayGroupState.Paused -> R.string.player_syncplay_state_paused
        SyncPlayGroupState.Playing -> R.string.player_syncplay_state_playing
    }

private fun SyncPlayMessage.textRes(): Int =
    when (this) {
        SyncPlayMessage.ConnectionLost -> R.string.player_message_syncplay_connection_lost
        SyncPlayMessage.Rejoined -> R.string.player_message_syncplay_rejoined
        SyncPlayMessage.JoinFailed -> R.string.player_message_syncplay_join_failed
        SyncPlayMessage.GroupEnded -> R.string.player_message_syncplay_group_ended
        SyncPlayMessage.RemovedFromGroup -> R.string.player_message_syncplay_removed
        SyncPlayMessage.LibraryAccessDenied -> R.string.player_message_syncplay_library_denied
        SyncPlayMessage.ItemUnavailable -> R.string.player_message_syncplay_item_unavailable
    }

/** Same cap `SettingsContent`/the SyncPlay sheets use — readable, reachable one-handed on a tablet. */
private val ContentMaxWidth: Dp = 640.dp

@Preview(name = "Groups — browsable", showBackground = true, heightDp = 900)
@Composable
private fun SyncPlayGroupsContentPreview() {
    JellyfinTheme {
        SyncPlayGroupsContent(
            state =
                SyncPlayGroupsUiState(
                    isLoading = false,
                    groups =
                        listOf(
                            previewGroup("Film night", listOf("casey", "alex"), SyncPlayGroupState.Playing),
                            previewGroup("Rewatch", listOf("sam"), SyncPlayGroupState.Idle),
                        ),
                ),
            onBack = {},
            onHome = {},
            onCreate = {},
            onJoin = {},
            onLeave = {},
            onRetry = {},
            onOpenPlayer = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "Groups — in a group", showBackground = true, heightDp = 900)
@Composable
private fun SyncPlayGroupsContentInGroupPreview() {
    JellyfinTheme {
        SyncPlayGroupsContent(
            state =
                SyncPlayGroupsUiState(
                    isLoading = false,
                    groups = listOf(previewGroup("Rewatch", listOf("sam"), SyncPlayGroupState.Idle)),
                    membership =
                        SyncPlayGroupsMembership.InGroup(
                            groupId = UUID.randomUUID(),
                            groupName = "Film night",
                            participants = listOf("casey", "alex"),
                            openPlayer = SyncPlayLaunchRequest(UUID.randomUUID(), 0L),
                        ),
                ),
            onBack = {},
            onHome = {},
            onCreate = {},
            onJoin = {},
            onLeave = {},
            onRetry = {},
            onOpenPlayer = {},
            onMessageShown = {},
        )
    }
}

private fun previewGroup(
    name: String,
    participants: List<String>,
    state: SyncPlayGroupState,
) = SyncPlayGroupSummary(
    id = UUID.randomUUID(),
    name = name,
    participants = participants,
    state = state,
    lastUpdatedAt = java.time.Instant.parse("2026-07-30T18:00:00Z"),
)
