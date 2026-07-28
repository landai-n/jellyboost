package dev.jellyfinnative.core.datastore

import kotlinx.coroutines.flow.Flow

/**
 * The app's persisted user settings (docs/PLAN.md, ":core:datastore").
 *
 * Everything here is non-secret and DataStore-backed. Access tokens are deliberately absent —
 * they live only in [SecureCredentialStore], never in DataStore and never in Room.
 *
 * Settings arrive per milestone; M6 adds the one the offline read path needs. The remaining keys
 * already named in [PreferenceKeys] (Wi-Fi-only downloads, max bitrate, storage location) join
 * this interface with the milestones that consume them (M7/M9).
 */
interface AppPreferences {
    /**
     * `true` while the user has pinned the app to offline mode.
     *
     * This is a *user* decision, not an observation: it feeds `ConnectionStateProvider` as
     * `OFFLINE_FORCED` and makes every repository call take the Room path even on a perfect
     * network. Emits the stored value immediately and then on every change.
     */
    val forceOffline: Flow<Boolean>

    /** Turns forced offline mode on or off. */
    suspend fun setForceOffline(enabled: Boolean)
}
