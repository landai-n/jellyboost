package dev.jellyboost.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.SessionRepository
import dev.jellyboost.core.network.model.SessionState
import dev.jellyboost.data.downloads.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * State holder for the Settings screen.
 *
 * It is a projection and nothing more: every switch reads a `Flow` and writes through the same
 * store, so there is no local copy to keep in step and no save/restore code. Flipping a switch and
 * killing the process mid-flip leaves the stored value as the single answer.
 *
 * Sign-out is the one action with an ordering that matters — see [signOut].
 */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val appPreferences: AppPreferences,
        private val sessionRepository: SessionRepository,
        private val downloads: DownloadRepository,
        @ApplicationScope private val appScope: CoroutineScope,
    ) : ViewModel() {
        /**
         * Whether a sign-out is in flight.
         *
         * Never reset once set: the only way out of that state is the session flipping to
         * `LoggedOut`, which navigates the user off this screen entirely (see [signOut]).
         */
        private val signingOut = MutableStateFlow(false)

        /** The single source of truth for [SettingsScreen]. */
        val uiState: StateFlow<SettingsUiState> =
            combine(
                preferences(),
                downloads.observeStorage(),
                downloads.observeStorageLocations(),
                sessionRepository.sessionState,
                signingOut,
            ) { prefs, storage, locations, session, signingOut ->
                SettingsUiState(
                    introSkipMode = prefs.introSkipMode,
                    outroSkipMode = prefs.outroSkipMode,
                    pipOnLeave = prefs.pipOnLeave,
                    downloadOverWifiOnly = prefs.downloadOverWifiOnly,
                    downloadQuality = prefs.downloadQuality,
                    forceOffline = prefs.forceOffline,
                    storage = storage,
                    storageLocations = locations,
                    account = session.toAccountInfo(),
                    signingOut = signingOut,
                )
            }.catch { error ->
                // `stateIn` rethrows into `viewModelScope`, and a ViewModel scope has no handler —
                // so an upstream throw here would not degrade the Settings screen, it would take
                // the process down with it. The screen falls back to its defaults: it
                // is a projection with no local copy, so defaults are the honest thing to draw and
                // every write path below still works.
                Timber.e(error, "The settings projection failed; falling back to the defaults")
                emit(SettingsUiState())
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = SettingsUiState(),
            )

        /** What the player does when playback enters an intro. */
        fun setIntroSkipMode(mode: SegmentSkipMode) {
            viewModelScope.launch { appPreferences.setIntroSkipMode(mode) }
        }

        /** What the player does when playback enters an outro. */
        fun setOutroSkipMode(mode: SegmentSkipMode) {
            viewModelScope.launch { appPreferences.setOutroSkipMode(mode) }
        }

        /** Whether leaving the app during playback enters picture-in-picture. */
        fun setPipOnLeave(enabled: Boolean) {
            viewModelScope.launch { appPreferences.setPipOnLeave(enabled) }
        }

        /** Restricts downloads to unmetered networks, or lifts the restriction. */
        fun setDownloadOverWifiOnly(enabled: Boolean) {
            viewModelScope.launch { appPreferences.setDownloadOverWifiOnly(enabled) }
        }

        /**
         * Sets the quality **future** downloads are fetched at.
         *
         * Deliberately not retroactive: `DownloadEnqueuer` stamps the quality onto each row when
         * the user taps Download, and the queue plans from the row. Changing this while a
         * transfer is running leaves that transfer exactly as it was.
         */
        fun setDownloadQuality(quality: DownloadQuality) {
            viewModelScope.launch { appPreferences.setDownloadQuality(quality) }
        }

        /**
         * Points future downloads at another volume.
         *
         * The screen asks before setting [deleteExistingDownloads], but the rule is not the
         * screen's: `DownloadRepository` refuses the switch outright while downloads exist and the
         * caller has not agreed to lose them (nothing moves files yet). A refusal is logged
         * rather than surfaced: it can only be reached by a race with a download starting between
         * the dialog and the confirm, and the picker simply stays put.
         */
        fun setStorageLocation(
            volumeId: String,
            deleteExistingDownloads: Boolean,
        ) {
            viewModelScope.launch {
                val result = downloads.setStorageLocation(volumeId, deleteExistingDownloads)
                if (result is AppResult.Failure) {
                    Timber.w("Could not switch download storage to %s: %s", volumeId, result.error)
                }
            }
        }

        /** Pins the app to offline mode, or releases it. */
        fun setForceOffline(enabled: Boolean) {
            viewModelScope.launch { appPreferences.setForceOffline(enabled) }
        }

        /**
         * Ends the session, optionally taking the downloaded files with it.
         *
         * The deletes run **before** the sign-out, and that order is the whole point: signing out
         * clears the credentials, and files deleted afterwards would be orphaned rows nobody can
         * re-download without logging back in. Individual failures are logged and stepped over —
         * a file the OS will not let go of is not a reason to keep a user signed in.
         *
         * Nothing here navigates. `SessionRepository.signOut()` flips `sessionState` to
         * `LoggedOut`, and `:app`'s `LogoutRedirectEffect` takes it from there — which is also what
         * makes a server-driven logout land in the same place as this button.
         *
         * The work runs in the **application** scope rather than [viewModelScope], because none of
         * it is this screen's to abandon. Signing out with an unreachable server waits on a network
         * goodbye (capped in `SessionRepository`, but still seconds long), and a user who backs out
         * of Settings during that wait would otherwise clear this ViewModel, cancelling the
         * coroutine somewhere between the deletes and the credential wipe, and stay quietly signed
         * in. The state a sign-out leaves behind must be reached whether or not the screen that
         * asked for it is still on screen.
         */
        fun signOut(deleteDownloads: Boolean) {
            // Never lowered again: the sign-out is now unstoppable, and its completion takes the
            // user off this screen (`LogoutRedirectEffect`), so there is no "back to normal" state
            // for this screen to return to.
            signingOut.value = true
            appScope.launch {
                if (deleteDownloads) deleteEveryDownload()
                sessionRepository.signOut()
            }
        }

        private suspend fun deleteEveryDownload() {
            // A snapshot, not a subscription: the list shrinks as we delete from it, and collecting
            // a live Flow while mutating what it reports is a loop waiting to happen.
            val items = downloads.observeDownloads().first()
            items.forEach { item ->
                val result = downloads.delete(item.itemId)
                if (result is AppResult.Failure) {
                    Timber.w("Could not delete download %s before signing out: %s", item.itemId, result.error)
                }
            }
        }

        /**
         * The six preference keys as one value.
         *
         * `combine` tops out at five typed flows, and the state needs ten sources; folding the
         * preferences into one intermediate keeps the outer `combine` typed rather than dropping to
         * the `Array<Any?>` overload and casting each element back. The sixth key is folded in by a
         * second `combine` over the first for the same reason — one more nested call is cheaper to
         * read than five untyped array indices.
         */
        private fun preferences(): Flow<Preferences> =
            combine(
                combine(
                    appPreferences.introSkipMode,
                    appPreferences.outroSkipMode,
                    appPreferences.pipOnLeave,
                    appPreferences.downloadOverWifiOnly,
                    appPreferences.forceOffline,
                ) { intro, outro, pip, wifiOnly, forceOffline ->
                    Preferences(
                        introSkipMode = intro,
                        outroSkipMode = outro,
                        pipOnLeave = pip,
                        downloadOverWifiOnly = wifiOnly,
                        forceOffline = forceOffline,
                    )
                },
                appPreferences.downloadQuality,
            ) { rest, quality -> rest.copy(downloadQuality = quality) }

        private data class Preferences(
            val introSkipMode: SegmentSkipMode,
            val outroSkipMode: SegmentSkipMode,
            val pipOnLeave: Boolean,
            val downloadOverWifiOnly: Boolean,
            val forceOffline: Boolean,
            val downloadQuality: DownloadQuality = DownloadQuality.ORIGINAL,
        )

        private companion object {
            /**
             * Keeps the projection alive across a configuration change, so rotating the tablet does
             * not re-read DataStore and re-query storage for the same values.
             */
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }

/** The signed-in user, or `null` for every other session state. */
private fun SessionState.toAccountInfo(): AccountInfo? =
    when (this) {
        is SessionState.LoggedIn -> AccountInfo(userName = userName, serverName = serverName)
        SessionState.LoggedOut, SessionState.Unknown -> null
    }
