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
 * The group **list** must be polled: the websocket is connected only while already in a group, so
 * `GET /SyncPlay/List` is the only source. [SyncPlayController] owns membership; this only reads its state.
 *
 * A 403 (SyncPlay disabled for the account) is terminal — the loop parks in [awaitCancellation]; every other
 * failure is transient and retried at the next tick.
 */
@HiltViewModel
internal class SyncPlayGroupsViewModel
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

        internal val uiState: StateFlow<SyncPlayGroupsUiState> =
            combine(pollGroups(), controller.state, local) { poll, controllerState, localState ->
                poll.toUiState(membership = controllerState.toMembership(), userMessage = localState.userMessage)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = SyncPlayGroupsUiState(),
            )

        init {
            // Deliberately outside the poll's `WhileSubscribed` sharing: membership can change while nobody is
            // looking, and those messages must not be lost to a poll that has not restarted yet.
            viewModelScope.launch {
                controller.messages.collect { message -> local.update { it.copy(userMessage = message) } }
            }
        }

        /** A blank name (all whitespace) is ignored. */
        internal fun createGroup(name: String) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            controller.createGroup(trimmed)
        }

        internal fun join(group: SyncPlayGroupSummary) {
            controller.joinGroup(group)
        }

        internal fun leave() {
            controller.leaveGroup()
        }

        internal fun retry() {
            retryRequests.trySend(Unit)
        }

        internal fun consumeMessage() {
            local.update { it.copy(userMessage = null) }
        }

        private fun pollGroups(): Flow<SyncPlayGroupsPoll> =
            flow {
                while (true) {
                    val outcome = fetchGroups()
                    emit(outcome)
                    // A disabled account cannot become enabled without a fresh sign-in; parking beats a request
                    // every ten seconds for the rest of the screen's life.
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
            const val POLL_INTERVAL_MS = 10_000L

            /** Rotation grace period, matching `DownloadsViewModel`/`SyncPlayQueueViewModel`. */
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

private sealed interface SyncPlayGroupsPoll {
    data class Success(
        val groups: List<SyncPlayGroupSummary>,
    ) : SyncPlayGroupsPoll

    /** HTTP 403 — terminal for the poll loop. */
    data object Disabled : SyncPlayGroupsPoll

    /** Transient; the next tick tries again. */
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
        // Rejoining is a join in progress to this screen: same spinner, same refusal of a second group.
        SyncPlayState.Joining, is SyncPlayState.Rejoining -> SyncPlayGroupsMembership.Joining
        is SyncPlayState.InGroup ->
            SyncPlayGroupsMembership.InGroup(
                groupId = group.id,
                groupName = group.name,
                participants = group.participants,
                openPlayer =
                    queue?.playingEntry?.let { entry -> SyncPlayLaunchRequest(entry.itemId, queue.startPositionTicks) },
            )
    }

data class SyncPlayGroupsUiState(
    val isLoading: Boolean = true,
    /** Joinable groups, minus the one this device is already in — that one is [membership]'s. */
    val groups: List<SyncPlayGroupSummary> = emptyList(),
    /** HTTP 403 — permanent for this screen instance; the poll has stopped. */
    val disabled: Boolean = false,
    /** Transient; the next tick (or [SyncPlayGroupsViewModel.retry]) tries again. */
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

sealed interface SyncPlayGroupsMembership {
    data object None : SyncPlayGroupsMembership

    /** The create/join call is in flight; confirmation arrives on the websocket. */
    data object Joining : SyncPlayGroupsMembership

    data class InGroup(
        val groupId: UUID,
        val groupName: String,
        val participants: List<String>,
        /** Non-null only when the group has something playing. */
        val openPlayer: SyncPlayLaunchRequest?,
    ) : SyncPlayGroupsMembership
}
