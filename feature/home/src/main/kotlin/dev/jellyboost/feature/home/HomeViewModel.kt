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
import dev.jellyboost.data.reloadOnChange
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
 * Failure policy: only a failing `getUserViews` produces an error screen — without libraries there
 * is nothing to render. An individual row that fails is left empty, matching jellyfin-web.
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

        val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

        /** Last download-state map seen, re-applied whenever a load replaces the rows. */
        private var downloadStates: Map<String, DownloadState> = emptyMap()

        /**
         * The newest user data this app itself published, per item, since the last full load.
         * Re-applied over anything the membership refresh fetches, so a read that overtakes its own
         * write cannot resurrect what the user just changed. Only touched from `viewModelScope`.
         */
        private val knownUserData = mutableMapOf<String, UserData>()

        private val membershipRefreshRequests =
            MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        init {
            load(isRefresh = false)
            observeUserDataChanges()
            observeMembershipRefreshRequests()
            observeDownloadStates()
            observeConnectivityChanges()
        }

        private fun observeConnectivityChanges() {
            connectivityRefresher.reloadOnChange(viewModelScope) { refresh() }
        }

        private fun observeDownloadStates() {
            viewModelScope.launch {
                downloads.observeBadgeStates(screen = "home").collect { states ->
                    downloadStates = states
                    _uiState.update { it.withDownloadStates(states) }
                }
            }
        }

        /**
         * A patch is never a refetch. What a patch cannot express — which episode replaces a watched
         * one in *Next up*, an item returning after being un-marked, *Mark watched* on a series
         * whose id no row contains — queues a debounced [refreshMembershipRows] pass instead.
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
         * Position is deliberately excluded even though it reorders *Continue watching*:
         * `PlaybackReporter` writes one every five seconds, which would turn the debounce into a
         * poll for the length of a film. A [previous] of `null` counts as a possible move — that is
         * the series or season whose *Mark watched* changed the episodes in the rows.
         */
        private fun movesRowMembership(
            previous: UserData?,
            next: UserData,
        ): Boolean = previous == null || previous.played != next.played

        private fun currentUserData(itemId: String): UserData? =
            with(_uiState.value) {
                (
                    resume.asSequence() + nextUp.asSequence() + resumeAudio.asSequence() +
                        latest.asSequence().flatMap { it.items }
                ).firstOrNull { it.id == itemId }
                    ?.userData
            }

        /**
         * Silent by design: no spinner, no `isRefreshing`, no error state. It runs because the user
         * toggled something elsewhere, so a failure must leave the screen exactly as it was.
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
            // Offline the rows come from Room and the patch above is the whole update.
            if (!connectivityRefresher.isOnline) return

            // A row the layout does not include costs no request here either.
            val sections = _uiState.value.sections
            val wantsResume = HomeSectionType.RESUME in sections
            val wantsNextUp = HomeSectionType.NEXT_UP in sections
            val wantsResumeAudio = HomeSectionType.RESUME_AUDIO in sections
            if (!wantsResume && !wantsNextUp && !wantsResumeAudio) return

            val (resume, nextUp, resumeAudio) =
                coroutineScope {
                    val resumeCall = async { if (wantsResume) repository.getResumeItems() else null }
                    val nextUpCall = async { if (wantsNextUp) repository.getNextUp() else null }
                    val resumeAudioCall = async { if (wantsResumeAudio) repository.getResumeAudioItems() else null }
                    Triple(resumeCall.await(), nextUpCall.await(), resumeAudioCall.await())
                }

            _uiState.update { state ->
                state
                    .copy(
                        // A row whose call failed keeps what it had: one flaky request must not
                        // empty a shelf the user is looking at.
                        resume = resume?.getOrNull()?.mergeLocalUserData(knownUserData) ?: state.resume,
                        nextUp = nextUp?.getOrNull()?.mergeLocalUserData(knownUserData) ?: state.nextUp,
                        resumeAudio =
                            resumeAudio?.getOrNull()?.mergeLocalUserData(knownUserData) ?: state.resumeAudio,
                    ).withDownloadStates(downloadStates)
            }
        }

        fun refresh() {
            load(isRefresh = true)
        }

        private fun load(isRefresh: Boolean) {
            // A deliberate reload asks for the server's answer, so the local overrides stop
            // applying; anything still unsynced comes back on the bus when `UserDataSyncer` resolves.
            knownUserData.clear()
            viewModelScope.launch {
                _uiState.update {
                    it.copy(isLoading = !isRefresh, isRefreshing = isRefresh, errorMessage = null)
                }

                // Cannot fail. Never resolved on a timer: a full load is the whole freshness story.
                val sections = homeLayout.getHomeSections()

                if (LIBRARY_BACKED_SECTIONS.none { it in sections }) {
                    // Skipping `getUserViews` also skips the only call that can produce an error screen.
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
                        // Anything toggled while the fetch was in flight still wins: the load
                        // cleared the overrides before starting, so only those changes are left.
                        resume = rows.resume.mergeLocalUserData(knownUserData),
                        nextUp = rows.nextUp.mergeLocalUserData(knownUserData),
                        resumeAudio = rows.resumeAudio.mergeLocalUserData(knownUserData),
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
                val resumeAudio =
                    async {
                        if (HomeSectionType.RESUME_AUDIO in sections) {
                            repository.getResumeAudioItems().getOrNull().orEmpty()
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
                    // A *Latest* call that succeeded empty means the library is empty — offline,
                    // every library with no downloads, since `getUserViews` answers from the full
                    // cached list. A library whose call *failed* is kept: flakiness must not delete
                    // a card. With *Latest* hidden there is nothing to filter by at all.
                    libraries =
                        if (wantsLatest) {
                            latest.filterNot { (_, items) -> items.isKnownEmpty() }.map { it.first }
                        } else {
                            libraries
                        },
                    resume = resume.await(),
                    nextUp = nextUp.await(),
                    resumeAudio = resumeAudio.await(),
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
            val resumeAudio: List<JellyfinItem>,
            val latest: List<LatestSection>,
        )

        private companion object {
            /** The sections that need `getUserViews`. */
            val LIBRARY_BACKED_SECTIONS =
                setOf(
                    HomeSectionType.SMALL_LIBRARY_TILES,
                    HomeSectionType.LIBRARY_BUTTONS,
                    HomeSectionType.LATEST_MEDIA,
                )

            /**
             * Long enough for two jobs: collapsing a per-episode burst into one pair of requests,
             * and giving the write time to reach the server — `UserDataRepositoryImpl` publishes on
             * the bus *before* it pushes, so a read fired immediately races its own write.
             */
            const val MEMBERSHIP_REFRESH_DEBOUNCE_MS = 1_500L
        }
    }

/** Home asks for the user's *views*, so a 404 here is a missing library, not a missing title. */
internal val HomeErrorCopy =
    AppErrorCopy(
        unknown = R.string.home_error_unknown,
        notFound = CoreUiR.string.error_not_found_library,
    )

internal fun AppError.toMessage(): UiText = toUiText(HomeErrorCopy)
