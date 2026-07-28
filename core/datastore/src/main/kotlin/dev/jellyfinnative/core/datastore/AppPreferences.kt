package dev.jellyfinnative.core.datastore

import kotlinx.coroutines.flow.Flow

/**
 * The app's persisted user settings (docs/PLAN.md, ":core:datastore").
 *
 * Everything here is non-secret and DataStore-backed. Access tokens are deliberately absent —
 * they live only in [SecureCredentialStore], never in DataStore and never in Room.
 *
 * Settings arrive per milestone; M6 adds the one the offline read path needs and M7 the one the
 * download queue needs. The remaining keys already named in [PreferenceKeys] (max bitrate, storage
 * location) join this interface with the milestones that consume them (M9).
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

    // ---- M7 — downloads ------------------------------------------------------------------------

    /**
     * `true` while downloads are restricted to unmetered networks.
     *
     * Read when the download work is (re-)enqueued and turned into WorkManager's
     * `NetworkType.UNMETERED` constraint, so an in-flight transfer is suspended by the system the
     * moment the device leaves Wi-Fi and resumes — from its byte offset — when it comes back
     * (docs/PLAN.md, "Download pipeline" → Enqueue).
     *
     * Defaults to **on**: a multi-gigabyte film pulled over a metered connection is the kind of
     * mistake a user cannot undo, so the safe direction is the one that costs nothing but a toggle.
     */
    val downloadOverWifiOnly: Flow<Boolean>

    /** Turns the Wi-Fi-only download restriction on or off. */
    suspend fun setDownloadOverWifiOnly(enabled: Boolean)
}
