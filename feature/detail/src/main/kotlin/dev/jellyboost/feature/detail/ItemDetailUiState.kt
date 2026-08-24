package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.common.selection.BatchReport
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.data.downloads.withDownloadState
import dev.jellyboost.data.downloads.withDownloadStates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** One state class for all three shapes; which rows appear follows from [item]'s type. */
data class ItemDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val item: JellyfinItem? = null,
    val seasons: List<JellyfinItem> = emptyList(),
    /** Season detail only. Drives batch-selection/download/play semantics; empty on episode pages. */
    val episodes: List<JellyfinItem> = emptyList(),
    val nextUp: JellyfinItem? = null,
    val nextEpisode: JellyfinItem? = null,
    /** Episode detail: the parent season's episodes. Deliberately separate from [episodes]. */
    val seasonEpisodes: List<JellyfinItem> = emptyList(),
    val similar: List<JellyfinItem> = emptyList(),
    /**
     * Separate from `JellyfinItem.downloadState` because it comes from a different Flow — Room's
     * download table, updating several times a second — while the item is fetched once. For a season
     * this is the *aggregate* of its episodes' states ([aggregateDownloadState]).
     */
    val downloadState: DownloadState = DownloadState.NotDownloaded,
    /**
     * `null` when nothing of [item] is on the device. Read only once [downloadState] is
     * [DownloadState.Downloaded] — a partly-downloaded container's SUM would read as a whole-item
     * size.
     */
    val downloadedBytes: Long? = null,
    /** Set only when the *item* could not be loaded — a related row failing is silent. */
    val errorMessage: UiText? = null,
    /** One-shot; cleared by `ItemDetailViewModel.consumeMessage`. */
    val userMessage: UserMessage? = null,
    val showDeleteConfirmation: Boolean = false,
) {
    val isLoaded: Boolean get() = !isLoading && item != null

    /**
     * A series or season is a container: Play must resolve to an episode. A season falls back to its
     * first unfinished episode, then its first — the same order jellyfin-web uses.
     */
    val playTarget: JellyfinItem?
        get() =
            when (item?.type) {
                null -> null
                ItemType.SERIES -> nextUp ?: episodes.firstOrNull()
                ItemType.SEASON -> episodes.firstOrNull { !it.userData.played } ?: episodes.firstOrNull()
                else -> item
            }

    /**
     * [playTarget] narrowed to what this app plays. The narrowing is here, not in `SyncPlaySession`,
     * whose contract speaks item ids and cannot know a type. Non-null also tells the header a tap on
     * Play will be a *group* play.
     */
    val groupTarget: JellyfinItem?
        get() = playTarget?.takeIf { it.type.isPlayable }

    /**
     * Series and seasons are folders with no file to send: the pipeline expands them, so the button
     * acts on [downloadTargets] rather than the item's own id.
     */
    val isDownloadContainer: Boolean get() = item?.type == ItemType.SERIES || item?.type == ItemType.SEASON

    /**
     * A season page that has not loaded its episodes yields **nothing**, which is correct: there is
     * nothing on the device this page knows to remove.
     */
    val downloadTargets: List<String>
        get() =
            when {
                item == null -> emptyList()
                isDownloadContainer -> episodes.map { it.id }
                else -> listOf(item.id)
            }
}

/**
 * Case order is load-bearing — it is what the button should offer next. The *Downloading* progress
 * is over the whole container (done episodes count 1, unstarted 0), so a season at 3 of 10 reads
 * ~30 %, not 100 % of whichever episode is moving. A partly-downloaded container with an empty queue
 * falls through to *NotDownloaded*, so the next tap enqueues what is missing.
 */
internal fun aggregateDownloadState(states: List<DownloadState>): DownloadState =
    when {
        states.isEmpty() -> DownloadState.NotDownloaded
        states.all { it is DownloadState.Downloaded } -> DownloadState.Downloaded
        states.any { it is DownloadState.Downloading } ->
            DownloadState.Downloading(progress = states.sumOf { it.fraction.toDouble() }.toFloat() / states.size)

        states.any { it is DownloadState.Queued || it is DownloadState.Paused } -> DownloadState.Queued
        states.any { it is DownloadState.Failed } -> DownloadState.Failed
        else -> DownloadState.NotDownloaded
    }

private val DownloadState.fraction: Float
    get() =
        when (this) {
            is DownloadState.Downloaded -> 1f
            is DownloadState.Downloading -> progress
            else -> 0f
        }

/**
 * Playing is deliberately *not* one of these: in a group the ordinary Play button already is the
 * group play. Each is a request to the server — nothing queues locally until the group's own update
 * comes back.
 */
enum class GroupAction {
    PLAY_NEXT,
    ADD_TO_QUEUE,
}

/**
 * A one-shot event, never a field on [ItemDetailUiState]: a state flag saying "navigate" would fire
 * again on every recomposition that re-read it.
 */
data class PlayRequest(
    val itemId: String,
    val startPositionTicks: Long,
)

fun playbackStartTicks(item: JellyfinItem): Long =
    if (item.userData.isResumable) item.userData.playbackPositionTicks else 0L

/** A type, not a string, so the ViewModel stays free of resources and the copy lives in strings.xml. */
sealed interface UserMessage {
    data object DownloadQueued : UserMessage

    data object DownloadFailed : UserMessage

    data object DownloadDeleted : UserMessage

    data object DownloadDeleteFailed : UserMessage

    /**
     * A container cancel left [keptCount] finished episodes on the device. Worth saying: the button
     * then simply offers *Download* again, so nothing else tells the user they survived.
     */
    data class DownloadCancelledKeepingFinished(
        val keptCount: Int,
    ) : UserMessage

    /** The toggle could not even be written locally. */
    data object UserDataWriteFailed : UserMessage

    /**
     * Worth saying precisely because nothing visible happens here: the queue changes on the server
     * and the result arrives later as a `PlayQueueUpdate`.
     */
    data class GroupActionSent(
        val action: GroupAction,
    ) : UserMessage

    /**
     * The one tap a user expects to open a player immediately. The screen deliberately stays put and
     * the player opens when the server's `PlayQueueUpdate` returns; unsaid, the gap reads as a dead
     * button.
     */
    data object GroupPlayRequested : UserMessage

    /** Counts travel resource-free; `batchOutcomeText` — shared with the library grid — words them. */
    data class BatchFinished(
        val report: BatchReport,
    ) : UserMessage
}

/**
 * A `null` [success] means silence on success — what the watched / favourite toggles want, since the
 * local write is already visible on the page.
 */
internal fun MutableStateFlow<ItemDetailUiState>.report(
    result: AppResult<*>,
    failure: UserMessage,
    success: UserMessage? = null,
) {
    val message = if (result is AppResult.Success) success else failure
    message?.let { next -> update { it.copy(userMessage = next) } }
}

/**
 * Every list holding the id must be patched, or the same item shows two states on one page. Fed by
 * `UserDataEventBus`, so a toggle is optimistic — no re-fetch and no "pending" flag.
 */
internal fun ItemDetailUiState.withUserData(
    itemId: String,
    userData: UserData,
): ItemDetailUiState =
    copy(
        item = item?.patch(itemId, userData),
        seasons = seasons.patch(itemId, userData),
        episodes = episodes.patch(itemId, userData),
        nextUp = nextUp?.patch(itemId, userData),
        nextEpisode = nextEpisode?.patch(itemId, userData),
        seasonEpisodes = seasonEpisodes.patch(itemId, userData),
        similar = similar.patch(itemId, userData),
    )

/**
 * Patching the items is what makes `:core:ui`'s cards draw their badges — the detail screen knows
 * nothing about badges itself.
 */
internal fun ItemDetailUiState.withDownloadStates(states: Map<String, DownloadState>): ItemDetailUiState =
    copy(
        downloadState = resolveDownloadState(states),
        item = item?.withDownloadState(states),
        seasons = seasons.withDownloadStates(states),
        episodes = episodes.withDownloadStates(states),
        nextUp = nextUp?.withDownloadState(states),
        nextEpisode = nextEpisode?.withDownloadState(states),
        seasonEpisodes = seasonEpisodes.withDownloadStates(states),
        similar = similar.withDownloadStates(states),
    )

/**
 * A season has no download row of its own, so its state is its episodes', aggregated. Everything
 * else — including a season page whose episodes have not arrived yet — reads its own row.
 */
private fun ItemDetailUiState.resolveDownloadState(states: Map<String, DownloadState>): DownloadState {
    val current = item ?: return DownloadState.NotDownloaded

    if (isDownloadContainer && episodes.isNotEmpty()) {
        return aggregateDownloadState(episodes.map { states[it.id] ?: DownloadState.NotDownloaded })
    }
    return states[current.id] ?: DownloadState.NotDownloaded
}

private fun JellyfinItem.patch(
    itemId: String,
    userData: UserData,
): JellyfinItem = if (id == itemId) copy(userData = userData) else this

private fun List<JellyfinItem>.patch(
    itemId: String,
    userData: UserData,
): List<JellyfinItem> = if (none { it.id == itemId }) this else map { it.patch(itemId, userData) }
