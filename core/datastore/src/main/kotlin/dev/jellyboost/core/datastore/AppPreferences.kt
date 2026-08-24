package dev.jellyboost.core.datastore

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import kotlinx.coroutines.flow.Flow

/**
 * The app's persisted user settings.
 *
 * Everything here is non-secret and DataStore-backed. Access tokens are deliberately absent —
 * they live only in [SecureCredentialStore], never in DataStore and never in Room.
 *
 * Every key named in [PreferenceKeys] is consumed here.
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

    // ---- downloads ------------------------------------------------------------------------------

    /**
     * `true` while downloads are restricted to unmetered networks.
     *
     * Read when the download work is (re-)enqueued and turned into WorkManager's
     * `NetworkType.UNMETERED` constraint, so an in-flight transfer is suspended by the system the
     * moment the device leaves Wi-Fi and resumes — from its byte offset — when it comes back.
     *
     * Defaults to **on**: a multi-gigabyte film pulled over a metered connection is the kind of
     * mistake a user cannot undo, so the safe direction is the one that costs nothing but a toggle.
     */
    val downloadOverWifiOnly: Flow<Boolean>

    /** Turns the Wi-Fi-only download restriction on or off. */
    suspend fun setDownloadOverWifiOnly(enabled: Boolean)

    /**
     * How much of the file a download asks the server for.
     *
     * Read **once per enqueue**, by `DownloadEnqueuer`, and stamped onto the download row; the
     * pipeline never consults it again for an item already in the queue. Changing it therefore
     * affects the next download the user starts, not the one running.
     *
     * Defaults to [DownloadQuality.ORIGINAL]: the source file, byte for byte, with an exact size and
     * byte-level resume.
     */
    val downloadQuality: Flow<DownloadQuality>

    /** Sets the quality future downloads are fetched at. */
    suspend fun setDownloadQuality(quality: DownloadQuality)

    /**
     * The id of the volume downloaded files are written to, or `null` while the default holds.
     *
     * `null` is not "no storage": it means *the primary volume*, which is what a fresh install
     * uses and what every download before the picker existed was written to. Storing the default as
     * an absent key rather than as the literal id keeps the two indistinguishable, so a device
     * whose volume ids change under it still resolves to somewhere writable.
     *
     * The value is a **stable token** — the volume's UUID, or `"primary"` — and deliberately not an
     * index into `getExternalFilesDirs`, which reorders when a card is removed, nor a path, which
     * is only stable while the card is mounted. A stored id no volume answers to (the card was
     * taken out) falls back to the primary volume rather than failing; see `StorageLocationManager`.
     */
    val downloadStorageVolumeId: Flow<String?>

    /** Chooses the volume downloads are written to; `null` restores the default. */
    suspend fun setDownloadStorageVolumeId(volumeId: String?)

    // player --------------------------------------------------------------------------------------

    /**
     * What the player does when playback enters an intro.
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

    /**
     * The last measured streaming ceiling in bits per second, or `null` while none was ever measured.
     *
     * Not a user setting and not surfaced in Settings: the player's bandwidth detector writes it
     * after a successful throughput measurement, and reads it back on a fresh start as a **prior** —
     * the value Auto quality uses before (or instead of) a measurement of its own. `null` therefore
     * means "nothing learned yet", which degrades to uncapped behaviour.
     */
    val maxStreamingBitrate: Flow<Int?>

    /** Records the measured streaming ceiling; `null` forgets it. */
    suspend fun setMaxStreamingBitrate(bitrate: Int?)
}
