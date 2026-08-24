package dev.jellyboost.player.syncplay.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jellyboost.core.ui.component.EmptyState
import dev.jellyboost.core.ui.component.ErrorState
import dev.jellyboost.core.ui.component.FieldLabel
import dev.jellyboost.core.ui.component.GhostPillButton
import dev.jellyboost.core.ui.component.GlassIconButton
import dev.jellyboost.core.ui.component.JellyboostAlertDialog
import dev.jellyboost.core.ui.component.JellyboostSnackbarHost
import dev.jellyboost.core.ui.component.JellyfinTextField
import dev.jellyboost.core.ui.component.LoadingState
import dev.jellyboost.core.ui.component.PrimaryPillButton
import dev.jellyboost.core.ui.component.ScreenHeader
import dev.jellyboost.core.ui.component.ScreenHeaderTitle
import dev.jellyboost.core.ui.component.rememberOneShotSnackbar
import dev.jellyboost.core.ui.theme.Dimens
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
 * The ViewModel is resolved here, not by the caller: [SyncPlayGroupsViewModel] is `internal`, so
 * `:app` can name the destination and nothing else.
 *
 * @param onOpenPlayer takes the same `(itemId, startPositionTicks)` shape the app NavHost's
 *   launch-request collector uses, so a tap here and a `PlayQueueUpdate` land on the same route.
 */
@Composable
fun SyncPlayGroupsScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenPlayer: (itemId: String, startPositionTicks: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    SyncPlayGroupsScreen(
        viewModel = hiltViewModel(),
        onBack = onBack,
        onHome = onHome,
        onOpenPlayer = onOpenPlayer,
        modifier = modifier,
    )
}

/** Separate from the public overload so tests can supply a ViewModel. */
@Composable
internal fun SyncPlayGroupsScreen(
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

@Composable
internal fun SyncPlayGroupsContent(
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
    // Keyed on the message, not its resolved copy: two messages with the same sentence are still two.
    val snackbarHostState =
        rememberOneShotSnackbar(
            message = state.userMessage,
            onShown = onMessageShown,
        ) { message -> stringResource(message.textRes()) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The header carries its own status-bar padding.
        contentWindowInsets = WindowInsets(0),
        // This host carries the inset itself: with `WindowInsets(0)` above, the Scaffold's snackbar
        // slot has none and the pill would sit under the gesture bar.
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
                // A create against a server that just answered 403 to the list fails the same way.
                createEnabled = !state.disabled,
            )

            Box(
                // Capped: full-bleed on a tablet puts a row's name and its state hint a hand-span apart.
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxSize(),
            ) {
                GroupsBody(
                    state = state,
                    onJoin = onJoin,
                    onLeaveClick = { confirmingLeave = true },
                    onOpenPlayer = onOpenPlayer,
                    onRetry = onRetry,
                )
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
        LeaveGroupDialog(
            onDismiss = { confirmingLeave = false },
            onConfirm = {
                confirmingLeave = false
                onLeave()
            },
        )
    }
}

@Composable
private fun GroupsBody(
    state: SyncPlayGroupsUiState,
    onJoin: (SyncPlayGroupSummary) -> Unit,
    onLeaveClick: () -> Unit,
    onOpenPlayer: (SyncPlayLaunchRequest) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.isLoading -> LoadingState()
        state.disabled -> EmptyState(message = stringResource(R.string.player_syncplay_groups_disabled))
        // Never full-screen while a membership stands: the pinned card is the only Leave affordance
        // and must survive a failed poll. Membership comes from the controller, not the poll.
        state.transientError && state.membership == SyncPlayGroupsMembership.None ->
            ErrorState(message = stringResource(R.string.player_syncplay_groups_error), onRetry = onRetry)

        state.isEmpty -> EmptyState(message = stringResource(R.string.player_syncplay_groups_empty))

        else ->
            GroupsList(
                state = state,
                onJoin = onJoin,
                onLeaveClick = onLeaveClick,
                onOpenPlayer = onOpenPlayer,
                onRetry = onRetry,
            )
    }
}

@Composable
private fun SyncPlayGroupsHeader(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onCreate: () -> Unit,
    createEnabled: Boolean,
) {
    ScreenHeader(
        onBack = onBack,
        onHome = onHome,
        trailing = {
            GlassIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.player_syncplay_groups_create),
                onClick = onCreate,
                enabled = createEnabled,
            )
        },
    ) {
        ScreenHeaderTitle(text = stringResource(R.string.player_syncplay_groups_title))
    }
}

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
                // The protocol tracks one membership per session, so no joining while one is settling.
                enabled = membership == SyncPlayGroupsMembership.None,
                onClick = { onJoin(group) },
            )
        }
    }
}

/**
 * *Open player* appears only once the group has something playing: its target is exactly the launch
 * request the app NavHost's collector would otherwise have acted on — invent no resume logic here.
 */
@Composable
private fun ActiveGroupCard(
    membership: SyncPlayGroupsMembership.InGroup,
    onLeave: () -> Unit,
    onOpenPlayer: (SyncPlayLaunchRequest) -> Unit,
) {
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
                // Role, so the row announces as pressable rather than as three fragments of text.
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
        // `GroupInfoDto` carries no playing-item title, so the group state is the only hint available.
        Text(
            text = stringResource(group.state.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SPINNER_STROKE = 2.dp
private const val SPINNER_TRACK_ALPHA = 0.14f

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

/** Names shown before the row switches to "+N more" — fits [GroupRow]'s width. */
private const val MAX_PARTICIPANT_NAMES = 3

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    JellyboostAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(text = stringResource(R.string.player_syncplay_groups_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(CoreUiR.string.action_cancel)) }
        },
        title = { Text(text = stringResource(R.string.player_syncplay_groups_create_title)) },
        text = {
            JellyfinTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.player_syncplay_groups_create_hint)) },
                // A label, not just the placeholder: the placeholder vanishes on the first keystroke
                // and the dialog title is a separate node, leaving the field unnamed to a screen reader.
                label = FieldLabel(text = stringResource(R.string.player_syncplay_groups_create_hint)),
                modifier = Modifier.fillMaxWidth(),
            )
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

/** Same cap `SettingsContent` and the SyncPlay sheets use. */
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
