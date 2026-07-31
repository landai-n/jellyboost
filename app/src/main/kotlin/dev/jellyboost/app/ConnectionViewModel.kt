package dev.jellyboost.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.core.network.ConnectionState
import dev.jellyboost.core.network.connectivity.ConnectionStateProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Exposes the app-wide connection state to Compose, and the two things the user can do about it.
 *
 * Deliberately a *thin* view over the `@Singleton` [ConnectionStateProvider] rather than an owner
 * of state: several composables (the offline banner in `AppScaffold`, the offline-mode toggle in
 * the home top bar) instantiate their own copy of this ViewModel, and they all observe and mutate
 * the same underlying singleton — so no wiring has to be threaded through the NavHost.
 */
@HiltViewModel
class ConnectionViewModel
    @Inject
    constructor(
        private val connectionStateProvider: ConnectionStateProvider,
        private val appPreferences: AppPreferences,
    ) : ViewModel() {
        /** The current connection state; drives the banner and every repository call. */
        val connectionState: StateFlow<ConnectionState> = connectionStateProvider.state

        /** Turns forced offline mode on or off (persisted; survives a restart). */
        fun setForceOffline(enabled: Boolean) {
            Timber.i("Force-offline toggled to %s", enabled)
            viewModelScope.launch {
                appPreferences.setForceOffline(enabled)
                Timber.i("Force-offline=%s persisted", enabled)
            }
        }

        /**
         * Re-probes the server — on app resume, and when the user taps *Retry* on the banner.
         *
         * Cheap to call repeatedly: the provider debounces probes.
         */
        fun refresh() {
            connectionStateProvider.refresh()
        }
    }
