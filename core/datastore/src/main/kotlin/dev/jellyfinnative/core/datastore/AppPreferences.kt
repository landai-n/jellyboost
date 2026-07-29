package dev.jellyfinnative.core.datastore

import dev.jellyfinnative.core.common.model.DownloadQuality
import dev.jellyfinnative.core.common.model.SegmentSkipMode
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

    /**
     * How much of the file a download asks the server for (M9).
     *
     * Read **once per enqueue**, by `DownloadEnqueuer`, and stamped onto the download row; the
     * pipeline never consults it again for an item already in the queue (DECISIONS.md, 2026-07-29).
     * Changing it therefore affects the next download the user starts, not the one running.
     *
     * Defaults to [DownloadQuality.ORIGINAL], which is the plan's behaviour: the source file, byte
     * for byte, with an exact size and byte-level resume.
     */
    val downloadQuality: Flow<DownloadQuality>

    /** Sets the quality future downloads are fetched at. */
    suspend fun setDownloadQuality(quality: DownloadQuality)

    // M9 player -----------------------------------------------------------------------------------

    /**
     * What the player does when playback enters an intro (docs/PLAN.md, "M9 Polish" → segment skip).
     *
     * Defaults to [SegmentSkipMode.SHOW_BUTTON]: the server's segment data is a guess produced by a
     * plugin, and a wrong guess that offers a button is a button nobody presses, while a wrong guess
     * that seeks is a film that jumps.
     */
    val introSkipMode: Flow<SegmentSkipMode>

    /** Sets what the player does when playback enters an intro. */
    suspend fun setIntroSkipMode(mode: SegmentSkipMode)

    /** What the player does when playback enters an outro; same default and reasoning as the intro. */
    val outroSkipMode: Flow<SegmentSkipMode>

    /** Sets what the player does when playback enters an outro. */
    suspend fun setOutroSkipMode(mode: SegmentSkipMode)

    /**
     * `true` while leaving the app during video playback should enter picture-in-picture.
     *
     * Defaults to **on**, matching every other video app on the platform: the alternative to a
     * floating window is a film that vanishes, and the user can always dismiss the window.
     */
    val pipOnLeave: Flow<Boolean>

    /** Turns the leave-into-picture-in-picture behaviour on or off. */
    suspend fun setPipOnLeave(enabled: Boolean)
}
