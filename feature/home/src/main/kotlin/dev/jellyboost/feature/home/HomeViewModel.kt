package dev.jellyboost.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.common.model.DownloadState
import dev.jellyboost.core.common.model.HomeSectionType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.LibraryView
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.core.ui.error.AppErrorCopy
import dev.jellyboost.core.ui.error.toUiText
import dev.jellyboost.core.ui.text.UiText
import dev.jellyboost.data.ConnectivityRefresher
import dev.jellyboost.data.JellyfinRepository
import dev.jellyboost.data.downloads.DownloadRepository
import dev.jellyboost.data.downloads.observeBadgeStates
import dev.jellyboost.data.homelayout.HomeLayoutRepository
import dev.jellyboost.data.userdata.UserDataEventBus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import dev.jellyboost.core.ui.R as CoreUiR

/**
 * State holder for the home screen.
 *
 * Resolves the user's row layout first (`HomeLayoutRepository`, which never fails), then loads the
 * libraries (every *Latest …* row is keyed off one) and fetches *Continue watching*, *Next up* and
 * every *Latest* row concurrently, so the screen is bound by the slowest single request rather
 * than by their sum. **Only rows the layout actually contains are fetched** — a user who hid
 * *Next up* in jellyfin-web costs no `getNextUp` call at all.
 *
 * Failure policy: only a failing `getUserViews` produces an error screen — without libraries there
 * is nothing to render. An individual row that fails is left empty, matching jellyfin-web, which
 * simply omits a section it could not load instead of blanking the page.
 *
 * `@HiltViewModel` makes this reachable from the `:app` Hilt graph via `hiltViewModel()` in the
 * NavHost; it requires the `org.jellyfin.sdk.api.client.ApiClient` binding `:core:network`
 * provides (see its `di/NetworkModule.kt`, `ApiClientModule`).
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val repository: JellyfinRepository,
        private val homeLayout: HomeLayoutRepository,
        private val userDataEvents: UserDataEventBus,
        private val downloads: DownloadRepository,
        private val connectivityRefresher: ConnectivityRefresher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HomeUiState())

        /** The single source of truth for [HomeScreen]. */
        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        /** Last download-state map seen, re-applied whenever a load replaces the rows. */
        private var downloadStates: Map<String, DownloadState> = emptyMap()

        /**
         * The newest user data this app itself published, per item, since the last full load.
         *
         * Re-applied on top of anything the membership refresh below fetches, so a read that
         * overtakes its own write cannot resurrect the state the user just changed. A full load
         * clears it: pull-to-refresh means "give me the server's answer".
         *
         * Only ever touched from `viewModelScope`, i.e. from the main dispatcher.
         */
        private val knownUserData = mutableMapOf<String, UserData>()

        /** One token per user-data change that may have moved an item in or out of a row. */
        private val membershipRefreshRequests =
            MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        init {
            load(isRefresh = false)
            observeUserDataChanges()
            observeMembershipRefreshRequests()
            observeDownloadStates()
            observeConnectivityChanges()
        }

        /**
         * Swaps the rows for the other source's the moment the connection changes (M9).
         *
         * Both directions matter. A home screen opened in airplane mode kept showing downloaded
         * media after the server came back; one opened online kept showing *its* rows — links to
         * media the app can no longer play — after the user pinned offline mode or lost the
         * network. `refresh()` is the same load either way: the repository picks the source.
         */
        private fun observeConnectivityChanges() {
            viewModelScope.launch {
                connectivityRefresher.connectivityChanged.collect { refresh() }
            }
        }

        /**
         * Keeps the `DownloadBadge` on every home card current (M7).
         *
         * One subscription for the whole screen, error-guarded so a collapse clears the badges
         * rather than freezing them — both rules, and why, live in [observeBadgeStates].
         */
        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads.observeBadgeStates(screen = "home").collect { states ->
                    downloadStates = states
                    _uiState.update { it.withDownloadStates(states) }
                }
            }
        }

        /**
         * Patches the loaded rows in place whenever user data changes anywhere in the app.
         *
         * The patch itself is never a refresh: M4's definition of done is that marking an item
         * watched on its detail page updates the home row **without a refetch** (docs/PLAN.md,
         * "Data layer"), and that still holds for everything a patch can express — the tick, the
         * favourite heart, the progress bar, and an item leaving *Continue watching* or *Next up*
         * because it is now played.
         *
         * What a patch cannot express is the rest of the membership question, and that is where
         * this screen was wrong: which episode takes a watched one's place in *Next up*, an item
         * coming back after being un-marked, and — the case with no matching card at all — *Mark
         * watched* on a series or season page, whose id no home row contains. Those changes queue
         * a [refreshMembershipRows] pass, debounced so that marking a whole season watched costs
         * one pair of requests rather than one per episode.
         */
        private fun observeUserDataChanges() {
            viewModelScope.launch {
                userDataEvents.changes.collect { change ->
                    val previous = knownUserData[change.itemId] ?: currentUserData(change.itemId)
                    knownUserData[change.itemId] = change.userData
                    _uiState.update { it.withUserData(change.itemId, change.userData) }
                    if (movesRowMembership(previous, change.userData)) {
                        membershipRefreshRequests.tryEmit(Unit)
                    }
                }
            }
        }

        /**
         * `true` when this change can have moved items in or out of *Continue watching* / *Next up*.
         *
         * Only `played` can: both rows are defined by what is unfinished. Position is deliberately
         * excluded even though it reorders *Continue watching* — `PlaybackReporter` writes one
         * every five seconds, so honouring it here would turn a debounce into a poll for the whole
         * length of a film. An item that reaches the end is marked played by the same reporter, and
         * that is the edge that matters.
         *
         * An item the screen has never seen ([previous] `null`) counts as a possible move: that is
         * precisely the series or season whose *Mark watched* changed the episodes in the rows.
         */
        private fun movesRowMembership(
            previous: UserData?,
            next: UserData,
        ): Boolean = previous == null || previous.played != next.played

        /** The user data the loaded rows currently show for [itemId], if any of them shows it. */
        private fun currentUserData(itemId: String): UserData? =
            with(_uiState.value) {
                (resume.asSequence() + nextUp.asSequence() + latest.asSequence().flatMap { it.items })
                    .firstOrNull { it.id == itemId }
                    ?.userData
            }

        /**
         * Re-fetches the two membership-sensitive rows after a burst of watched-state changes.
         *
         * Silent by design: no spinner, no `isRefreshing`, no error state. This runs because the
         * user toggled something somewhere else in the app, so a failure must leave the screen
         * exactly as it was rather than announce itself. *Latest* is not re-fetched — recently
         * added is not a function of what has been watched.
         */
        @OptIn(FlowPreview::class)
        private fun observeMembershipRefreshRequests() {
            viewModelScope.launch {
                membershipRefreshRequests
                    .debounce(MEMBERSHIP_REFRESH_DEBOUNCE_MS)
                    .collect { refreshMembershipRows() }
            }
        }

        private suspend fun refreshMembershipRows() {
            // Offline the rows are the downloads Room already answered with, and the local write
            // has nowhere to have been adopted: the instant patch above is the whole update.
            if (!connectivityRefresher.isOnline) return

            // A row the user's layout does not include is not on screen, so its membership is not
            // a question anyone is asking — a hidden *Next up* costs no request here either.
            val sections = _uiState.value.sections
            val wantsResume = HomeSectionType.RESUME in sections
            val wantsNextUp = HomeSectionType.NEXT_UP in sections
            if (!wantsResume && !wantsNextUp) return

            val (resume, nextUp) =
                coroutineScope {
                    val resumeCall = async { if (wantsResume) repository.getResumeItems() else null }
                    val nextUpCall = async { if (wantsNextUp) repository.getNextUp() else null }
                    resumeCall.await() to nextUpCall.await()
                }

            _uiState.update { state ->
                state
                    .copy(
                        // A row whose call failed keeps what it had; one flaky request must not
                        // empty a shelf the user is looking at.
                        resume = resume?.getOrNull()?.mergeLocalUserData(knownUserData) ?: state.resume,
                        nextUp = nextUp?.getOrNull()?.mergeLocalUserData(knownUserData) ?: state.nextUp,
                    ).withDownloadStates(downloadStates)
            }
        }

        /** Re-fetches every row; called by pull-to-refresh and by the error state's retry button. */
        fun refresh() {
            load(isRefresh = true)
        }

        private fun load(isRefresh: Boolean) {
            // A deliberate reload asks for the server's answer, so the local overrides that guard
            // the silent refresh below stop applying; anything still unsynced comes back on the bus
            // when `UserDataSyncer` resolves it.
            knownUserData.clear()
            viewModelScope.launch {
                _uiState.update {
                    it.copy(isLoading = !isRefresh, isRefreshing = isRefresh, errorMessage = null)
                }

                // Resolved on every full load — initial, pull-to-refresh and connectivity edge —
                // and never on a timer: changing Settings → Home in jellyfin-web and pulling to
                // refresh is the whole freshness story. This call cannot fail.
                val sections = homeLayout.getHomeSections()

                if (LIBRARY_BACKED_SECTIONS.none { it in sections }) {
                    // Nothing on this home screen is keyed off a library, so the libraries call —
                    // the only one that can produce an error screen — is not made at all.
                    emitRows(sections, libraries = emptyList())
                    return@launch
                }

                when (val views = repository.getUserViews()) {
                    is AppResult.Failure ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isRefreshing = false,
                                sections = sections,
                                errorMessage = views.error.toMessage(),
                            )
                        }

                    is AppResult.Success -> emitRows(sections, views.value)
                }
            }
        }

        private suspend fun emitRows(
            sections: List<HomeSectionType>,
            libraries: List<LibraryView>,
        ) {
            val rows = fetchRows(sections, libraries)
            _uiState.update {
                it
                    .copy(
                        isLoading = false,
                        isRefreshing = false,
                        sections = sections,
                        libraries = rows.libraries,
                        // Anything toggled *while* the fetch was in flight still wins: the load
                        // cleared the overrides before starting, so only those changes are left.
                        resume = rows.resume.mergeLocalUserData(knownUserData),
                        nextUp = rows.nextUp.mergeLocalUserData(knownUserData),
                        latest = rows.latest,
                        errorMessage = null,
                    ).withDownloadStates(downloadStates)
            }
        }

        private suspend fun fetchRows(
            sections: List<HomeSectionType>,
            libraries: List<LibraryView>,
        ): Rows =
            coroutineScope {
                val wantsLatest = HomeSectionType.LATEST_MEDIA in sections
                val resume =
                    async {
                        if (HomeSectionType.RESUME in sections) {
                            repository.getResumeItems().getOrNull().orEmpty()
                        } else {
                            emptyList()
                        }
                    }
                val nextUp =
                    async {
                        if (HomeSectionType.NEXT_UP in sections) {
                            repository.getNextUp().getOrNull().orEmpty()
                        } else {
                            emptyList()
                        }
                    }
                val latest =
                    if (wantsLatest) {
                        libraries
                            .map { library -> async { library to repository.getLatestMedia(library.id) } }
                            .awaitAll()
                    } else {
                        emptyList()
                    }

                Rows(
                    // A library whose *Latest* call succeeded and came back empty has nothing
                    // behind it — offline that is every library with no downloads in it, since
                    // `getUserViews` still answers from the full cached list. A library whose call
                    // *failed* is kept: one flaky request must not delete a library card.
                    //
                    // With *Latest* hidden there is nothing to filter by, and asking anyway would
                    // undo the saving: the cards then list every library the user can see, which
                    // offline includes ones with no downloads behind them.
                    libraries =
                        if (wantsLatest) {
                            latest.filterNot { (_, items) -> items.isKnownEmpty() }.map { it.first }
                        } else {
                            libraries
                        },
                    resume = resume.await(),
                    nextUp = nextUp.await(),
                    latest =
                        latest.mapNotNull { (library, result) ->
                            result
                                .getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { LatestSection(library, it) }
                        },
                )
            }

        /** `true` only when the server (or Room) answered, and answered with nothing. */
        private fun AppResult<List<JellyfinItem>>.isKnownEmpty(): Boolean = this is AppResult.Success && value.isEmpty()

        private data class Rows(
            val libraries: List<LibraryView>,
            val resume: List<JellyfinItem>,
            val nextUp: List<JellyfinItem>,
            val latest: List<LatestSection>,
        )

        private companion object {
            /**
             * The sections that need `getUserViews`: the libraries row draws them, and every
             * *Latest …* row is one request per library.
             */
            val LIBRARY_BACKED_SECTIONS =
                setOf(
                    HomeSectionType.SMALL_LIBRARY_TILES,
                    HomeSectionType.LIBRARY_BUTTONS,
                    HomeSectionType.LATEST_MEDIA,
                )

            /**
             * How long the membership refresh waits for the toggling to stop, in milliseconds.
             *
             * Two jobs. It collapses a burst — *Mark watched* on a season is one write per episode
             * — into a single pair of requests. And it gives the write itself time to reach the
             * server: `UserDataRepositoryImpl` publishes on the bus *before* it pushes, so a read
             * fired immediately would race its own write. `mergeLocalUserData` still covers the
             * case where it loses.
             */
            const val MEMBERSHIP_REFRESH_DEBOUNCE_MS = 1_500L
        }
    }

/**
 * What this screen calls the two branches it does not share.
 *
 * Home asks the server for the user's *views*, so a 404 here is a missing library, not a missing
 * title; an unclassified failure happened loading the home screen itself. Everything else comes
 * from `:core:ui`.
 */
internal val HomeErrorCopy =
    AppErrorCopy(
        unknown = R.string.home_error_unknown,
        notFound = CoreUiR.string.error_not_found_library,
    )

/** Turns the domain failure taxonomy into copy a user can act on. */
internal fun AppError.toMessage(): UiText = toUiText(HomeErrorCopy)
