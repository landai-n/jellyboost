package dev.jellyboost.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
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
 * A projection and nothing more: every switch reads a `Flow` and writes through the same store, so
 * there is no local copy to keep in step. Sign-out is the one action whose ordering matters.
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
        /** Never reset once set: the only way out is the session flipping to `LoggedOut`. */
        private val signingOut = MutableStateFlow(false)

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
                    themeMode = prefs.themeMode,
                    dynamicColorEnabled = prefs.dynamicColorEnabled,
                    storage = storage,
                    storageLocations = locations,
                    account = session.toAccountInfo(),
                    signingOut = signingOut,
                )
            }.catch { error ->
                // `stateIn` rethrows into `viewModelScope`, which has no handler, so an upstream throw would
                // take the process down rather than degrade this screen. Defaults are honest here — the screen
                // is a projection with no local copy, and every write path below still works.
                Timber.e(error, "The settings projection failed; falling back to the defaults")
                emit(SettingsUiState())
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = SettingsUiState(),
            )

        fun setIntroSkipMode(mode: SegmentSkipMode) {
            viewModelScope.launch { appPreferences.setIntroSkipMode(mode) }
        }

        fun setOutroSkipMode(mode: SegmentSkipMode) {
            viewModelScope.launch { appPreferences.setOutroSkipMode(mode) }
        }

        fun setPipOnLeave(enabled: Boolean) {
            viewModelScope.launch { appPreferences.setPipOnLeave(enabled) }
        }

        fun setDownloadOverWifiOnly(enabled: Boolean) {
            viewModelScope.launch { appPreferences.setDownloadOverWifiOnly(enabled) }
        }

        /**
         * Deliberately not retroactive: `DownloadEnqueuer` stamps the quality onto each row at tap
         * time and the queue plans from the row, so a running transfer is untouched.
         */
        fun setDownloadQuality(quality: DownloadQuality) {
            viewModelScope.launch { appPreferences.setDownloadQuality(quality) }
        }

        /**
         * `DownloadRepository` refuses the switch outright while downloads exist and the caller has
         * not agreed to lose them; nothing moves files yet. A refusal is logged rather than surfaced
         * because it can only be reached by a race between the dialog and the confirm.
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

        fun setForceOffline(enabled: Boolean) {
            viewModelScope.launch { appPreferences.setForceOffline(enabled) }
        }

        fun setThemeMode(mode: ThemeMode) {
            viewModelScope.launch { appPreferences.setThemeMode(mode) }
        }

        /** Ignored by the theme below API 31; the row that calls this is not drawn there. */
        fun setDynamicColorEnabled(enabled: Boolean) {
            viewModelScope.launch { appPreferences.setDynamicColorEnabled(enabled) }
        }

        /**
         * The deletes run **before** the sign-out: signing out clears the credentials, and files
         * deleted afterwards would be orphaned rows nobody can re-download without logging back in.
         *
         * Nothing here navigates — `SessionRepository.signOut()` flips `sessionState` and `:app`'s
         * `LogoutRedirectEffect` takes it from there, which is also what makes a server-driven logout
         * land in the same place.
         *
         * Runs in the **application** scope: a user backing out of Settings during the network
         * goodbye would otherwise cancel this between the deletes and the credential wipe and stay
         * quietly signed in.
         */
        fun signOut(deleteDownloads: Boolean) {
            // Never lowered again: the sign-out is unstoppable from here, and its completion takes the
            // user off this screen.
            signingOut.value = true
            appScope.launch {
                if (deleteDownloads) deleteEveryDownload()
                sessionRepository.signOut()
            }
        }

        private suspend fun deleteEveryDownload() {
            // A snapshot, not a subscription: the list shrinks as we delete from it.
            val items = downloads.observeDownloads().first()
            items.forEach { item ->
                val result = downloads.delete(item.itemId)
                if (result is AppResult.Failure) {
                    Timber.w("Could not delete download %s before signing out: %s", item.itemId, result.error)
                }
            }
        }

        /**
         * `combine` tops out at five typed flows and the state needs twelve sources, so the
         * preferences fold into one intermediate rather than dropping to the `Array<Any?>` overload.
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
                appPreferences.themeMode,
                appPreferences.dynamicColorEnabled,
            ) { rest, quality, themeMode, dynamicColor ->
                rest.copy(
                    downloadQuality = quality,
                    themeMode = themeMode,
                    dynamicColorEnabled = dynamicColor,
                )
            }

        private data class Preferences(
            val introSkipMode: SegmentSkipMode,
            val outroSkipMode: SegmentSkipMode,
            val pipOnLeave: Boolean,
            val downloadOverWifiOnly: Boolean,
            val forceOffline: Boolean,
            val downloadQuality: DownloadQuality = DownloadQuality.ORIGINAL,
            val themeMode: ThemeMode = ThemeMode.SYSTEM,
            val dynamicColorEnabled: Boolean = false,
        )

        private companion object {
            /** Survives a configuration change, so rotating does not re-read DataStore for the same values. */
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }

private fun SessionState.toAccountInfo(): AccountInfo? =
    when (this) {
        is SessionState.LoggedIn -> AccountInfo(userName = userName, serverName = serverName)
        SessionState.LoggedOut, SessionState.Unknown -> null
    }
