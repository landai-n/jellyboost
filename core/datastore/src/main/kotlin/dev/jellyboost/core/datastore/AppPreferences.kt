package dev.jellyboost.core.datastore

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import kotlinx.coroutines.flow.Flow

/**
 * The app's persisted, non-secret user settings. Access tokens are deliberately absent — they live only in
 * [SecureCredentialStore], never in DataStore and never in Room.
 */
interface AppPreferences {
    /** A *user* decision, not an observation: it forces the Room path even on a perfect network. */
    val forceOffline: Flow<Boolean>

    suspend fun setForceOffline(enabled: Boolean)

    /**
     * Turned into WorkManager's `NetworkType.UNMETERED` constraint at enqueue, so leaving Wi-Fi suspends an
     * in-flight transfer and returning resumes it from its byte offset. Defaults to **on**: a multi-gigabyte
     * film pulled over a metered connection is a mistake the user cannot undo.
     */
    val downloadOverWifiOnly: Flow<Boolean>

    suspend fun setDownloadOverWifiOnly(enabled: Boolean)

    /**
     * Read **once per enqueue** and stamped onto the download row; the pipeline never consults it again for an
     * item already queued, so a change affects the next download and not the running one.
     */
    val downloadQuality: Flow<DownloadQuality>

    suspend fun setDownloadQuality(quality: DownloadQuality)

    /**
     * `null` is not "no storage": it means *the primary volume*. Storing the default as an absent key keeps it
     * indistinguishable from a fresh install, so a device whose volume ids change still resolves somewhere
     * writable.
     *
     * The value is a **stable token** — the volume's UUID, or `"primary"` — deliberately not an index into
     * `getExternalFilesDirs` (which reorders when a card is removed) nor a path (stable only while mounted).
     * An id no volume answers to falls back to the primary volume rather than failing.
     */
    val downloadStorageVolumeId: Flow<String?>

    suspend fun setDownloadStorageVolumeId(volumeId: String?)

    /**
     * Defaults to [SegmentSkipMode.SHOW_BUTTON]: the server's segment data is a plugin's guess, and a wrong
     * guess that offers a button is a button nobody presses, while a wrong guess that seeks is a film that jumps.
     */
    val introSkipMode: Flow<SegmentSkipMode>

    suspend fun setIntroSkipMode(mode: SegmentSkipMode)

    /** Same default and reasoning as [introSkipMode]. */
    val outroSkipMode: Flow<SegmentSkipMode>

    suspend fun setOutroSkipMode(mode: SegmentSkipMode)

    /** Defaults to **on**: the alternative to a floating window is a film that vanishes when the user leaves. */
    val pipOnLeave: Flow<Boolean>

    suspend fun setPipOnLeave(enabled: Boolean)

    /**
     * Not a user setting and not surfaced in Settings: the player's bandwidth detector writes it after a
     * successful measurement and reads it back on a fresh start as a **prior** for Auto quality. `null` means
     * nothing learned yet, which degrades to uncapped behaviour.
     */
    val maxStreamingBitrate: Flow<Int?>

    suspend fun setMaxStreamingBitrate(bitrate: Int?)
}
