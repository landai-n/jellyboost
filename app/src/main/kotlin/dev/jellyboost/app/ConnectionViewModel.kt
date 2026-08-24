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
 * A *thin* view over the `@Singleton` [ConnectionStateProvider], never an owner of state: several
 * composables instantiate their own copy and all of them observe and mutate the same singleton.
 */
@HiltViewModel
class ConnectionViewModel
    @Inject
    constructor(
        private val connectionStateProvider: ConnectionStateProvider,
        private val appPreferences: AppPreferences,
    ) : ViewModel() {
        val connectionState: StateFlow<ConnectionState> = connectionStateProvider.state

        /** Persisted; survives a restart. */
        fun setForceOffline(enabled: Boolean) {
            Timber.i("Force-offline toggled to %s", enabled)
            viewModelScope.launch {
                appPreferences.setForceOffline(enabled)
                Timber.i("Force-offline=%s persisted", enabled)
            }
        }

        /** Cheap to call repeatedly: the provider debounces probes. */
        fun refresh() {
            connectionStateProvider.refresh()
        }
    }
