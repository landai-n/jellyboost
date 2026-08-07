package dev.jellyboost.player.syncplay.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.player.syncplay.SyncPlayController
import dev.jellyboost.player.syncplay.SyncPlayLaunchRequest
import dev.jellyboost.player.syncplay.SyncPlayMessage
import dev.jellyboost.player.syncplay.SyncPlayState
import dev.jellyboost.player.syncplay.api.SyncPlayApi
import dev.jellyboost.player.syncplay.model.SyncPlayGroupSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import timber.log.Timber
import java.net.HttpURLConnection
import java.util.UUID
import javax.inject.Inject

/**
 * The dedicated SyncPlay section (M11 Phase 5): every joinable group on the server, and the one
 * this device may already be in.
 *
 * Membership itself is never this class's business — [SyncPlayController] owns the socket, the
 * join handshake and the group's own state, and this ViewModel only *reads* [SyncPlayController.state]
 * and forwards the three membership intents (docs/notes/syncplay-m11-plan.md, "Phase 5"). The one
 * thing genuinely local here is the group **list**, because the plan's design decision 3 makes that
 * a plain polled `GET /SyncPlay/List` rather than anything the websocket carries — the socket is
 * connected only while already in a group.
 *
 * ### Polling
 * [SyncPlayApi.getGroups] is polled every [POLL_INTERVAL_MS] while this screen is visible — the
 * upstream flow lives inside `uiState`'s [SharingStarted.WhileSubscribed], the same "stop when
 * nobody is collecting" idiom `DownloadsViewModel` uses for its Room projection, and
 * `collectAsStateWithLifecycle` in the screen is what makes "visible" track the lifecycle. A 403
 * (`SyncPlay is disabled for your account`) is terminal: the poll loop parks itself in
 * [awaitCancellation] rather than looping again, so a disabled account costs exactly one request.
 * Any other failure is transient and the loop tries again at the next tick, or immediately if
 * [retry] is called — the same conflated-request idiom `ConnectionStateProvider` uses for its own
 * debounced probe.
 */
@HiltViewModel
class SyncPlayGroupsViewModel
    @Inject
    internal constructor(
        private val api: SyncPlayApi,
        private val controller: SyncPlayController,
    ) : ViewModel() {
        private data class LocalState(
            val userMessage: SyncPlayMessage? = null,
        )

        private val local = MutableStateFlow(LocalState())

        /** Conflated: a tap on *Retry* collapses into whichever wait is already in progress. */
        private val retryRequests = Channel<Unit>(Channel.CONFLATED)

        /** The single source of truth for [SyncPlayGroupsScreen]. */
        internal val uiState: StateFlow<SyncPlayGroupsUiState> =
            combine(pollGroups(), controller.state, local) { poll, controllerState, localState ->
                poll.toUiState(membership = controllerState.toMembership(), userMessage = localState.userMessage)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SyncPlayGroupsUiState(),
            )

        init {
            // Independent of the poll/uiState sharing: membership can change (a group ending, a
            // connection loss) whether or not anyone is looking at this screen right now, and the
            // messages that go with it should not be lost to a poll that has not restarted yet.
            viewModelScope.launch {
                controller.messages.collect { message -> local.update { it.copy(userMessage = message) } }
            }
        }

        /** Creates a group named [name] and joins it. A blank name (all whitespace) is ignored. */
        internal fun createGroup(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            controller.createGroup(trimmed)
        }

        /** Joins [group] — one of the rows this screen is already showing. */
        internal fun join(group: SyncPlayGroupSummary) {
            controller.joinGroup(group)
        }

        /** Leaves whatever group this device is in. */
        internal fun leave() {
            controller.leaveGroup()
        }

        /** Asks the poll loop to try again now, instead of waiting out the rest of its 10 s tick. */
        internal fun retry() {
            retryRequests.trySend(Unit)
        }

        /** Clears the one-shot message once the snackbar has shown it. */
        internal fun consumeMessage() {
            local.update { it.copy(userMessage = null) }
        }

        private fun pollGroups(): Flow<SyncPlayGroupsPoll> =
            flow {
                while (true) {
                    val outcome = fetchGroups()
                    emit(outcome)
                    // A disabled account never becomes enabled without a fresh sign-in, so there is
                    // nothing a further request would learn — the loop parks here instead of
                    // spending a request every ten seconds for the rest of the screen's life.
                    if (outcome is SyncPlayGroupsPoll.Disabled) awaitCancellation()
                    waitForNextPoll()
                }
            }

        private suspend fun fetchGroups(): SyncPlayGroupsPoll =
            try {
                SyncPlayGroupsPoll.Success(api.getGroups())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: InvalidStatusException) {
                if (error.status == HttpURLConnection.HTTP_FORBIDDEN) {
                    Timber.i("SyncPlay is disabled for this account")
                    SyncPlayGroupsPoll.Disabled
                } else {
                    Timber.w(error, "Could not list SyncPlay groups")
                    SyncPlayGroupsPoll.Error
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                Timber.w(error, "Could not list SyncPlay groups")
                SyncPlayGroupsPoll.Error
            }

        private suspend fun waitForNextPoll() {
            withTimeoutOrNull(POLL_INTERVAL_MS) { retryRequests.receive() }
        }

        private companion object {
            /** How often the group list is re-fetched while this screen is visible. */
            const val POLL_INTERVAL_MS = 10_000L

            /** Same grace period `DownloadsViewModel`/`SyncPlayQueueViewModel` give a rotation. */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** One outcome of a single `getGroups` poll. */
private sealed interface SyncPlayGroupsPoll {
    data class Success(
        val groups: List<SyncPlayGroupSummary>,
    ) : SyncPlayGroupsPoll

    /** HTTP 403 — SyncPlay is disabled for this account; terminal for the poll loop. */
    data object Disabled : SyncPlayGroupsPoll

    /** Anything else — network blip, server hiccup; the next tick tries again. */
    data object Error : SyncPlayGroupsPoll
}

private fun SyncPlayGroupsPoll.toUiState(
    membership: SyncPlayGroupsMembership,
    userMessage: SyncPlayMessage?,
): SyncPlayGroupsUiState {
    val ownGroupId = (membership as? SyncPlayGroupsMembership.InGroup)?.groupId
    val groups = (this as? SyncPlayGroupsPoll.Success)?.groups.orEmpty().filterNot { it.id == ownGroupId }
    return SyncPlayGroupsUiState(
        isLoading = false,
        groups = groups,
        disabled = this is SyncPlayGroupsPoll.Disabled,
        transientError = this is SyncPlayGroupsPoll.Error,
        membership = membership,
        userMessage = userMessage,
    )
}

private fun SyncPlayState.toMembership(): SyncPlayGroupsMembership =
    when (this) {
        SyncPlayState.Idle -> SyncPlayGroupsMembership.None
        // Rejoining is a join in progress as far as the screen is concerned: the same spinner, and
        // the same refusal to offer a second group while one is being negotiated.
        SyncPlayState.Joining, is SyncPlayState.Rejoining -> SyncPlayGroupsMembership.Joining
        is SyncPlayState.InGroup ->
            SyncPlayGroupsMembership.InGroup(
                groupId = group.id,
                groupName = group.name,
                participants = group.participants,
                // Reuses the launch-request shape rather than inventing a second one: "somewhere to
                // open a player for" is exactly what a launch request already means, and the app
                // NavHost's own collector (M11 Phase 5) resolves the same way from the same state.
                openPlayer =
                    queue?.playingEntry?.let { entry -> SyncPlayLaunchRequest(entry.itemId, queue.startPositionTicks) },
            )
    }

/** The groups screen's state: what is joinable, and what this device is already doing. */
data class SyncPlayGroupsUiState(
    val isLoading: Boolean = true,
    /** Joinable groups, minus the one this device is already in (that one is [membership]'s, pinned). */
    val groups: List<SyncPlayGroupSummary> = emptyList(),
    /** HTTP 403 from the server — permanent for this screen instance; the poll has stopped. */
    val disabled: Boolean = false,
    /** A transient poll failure; the next tick (or [SyncPlayGroupsViewModel.retry]) tries again. */
    val transientError: Boolean = false,
    val membership: SyncPlayGroupsMembership = SyncPlayGroupsMembership.None,
    val userMessage: SyncPlayMessage? = null,
) {
    val isEmpty: Boolean
        get() =
            !isLoading &&
                !disabled &&
                !transientError &&
                groups.isEmpty() &&
                membership == SyncPlayGroupsMembership.None
}

/** Where this device stands with respect to a group, independent of what is joinable. */
sealed interface SyncPlayGroupsMembership {
    data object None : SyncPlayGroupsMembership

    /** The create/join call is in flight; confirmation arrives on the websocket. */
    data object Joining : SyncPlayGroupsMembership

    data class InGroup(
        val groupId: UUID,
        val groupName: String,
        val participants: List<String>,
        /** Non-null when the group has something playing that this screen can open a player for. */
        val openPlayer: SyncPlayLaunchRequest?,
    ) : SyncPlayGroupsMembership
}
