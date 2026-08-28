package dev.jellyboost.core.datastore

import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.core.common.model.SegmentSkipMode
import dev.jellyboost.core.common.model.ThemeMode
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
     * Defaults to [ThemeMode.SYSTEM]: the device's own light/dark setting is an answer the user already gave
     * once for every app, and an app that overrides it by default is the one that looks wrong.
     */
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * Material You: the scheme is derived from the wallpaper (API 31+) instead of the Jellyfin palette.
     * Defaults to **off** — the brand primary is pinned by a decision the user opts out of, not into
     * (DECISIONS 2026-08-01, superseded while this is on).
     */
    val dynamicColorEnabled: Flow<Boolean>

    suspend fun setDynamicColorEnabled(enabled: Boolean)

    /**
     * Renders ASS/SSA through libass instead of Media3's own `SsaParser`, which keeps only alignment,
     * a couple of colours, size and bold/italic and drops fonts, positioning, karaoke and animation.
     *
     * Defaults to **off**, and is read once while the player is built — a change reaches the next
     * playback, not the one on screen.
     */
    val styledAssSubtitles: Flow<Boolean>

    suspend fun setStyledAssSubtitles(enabled: Boolean)

    /**
     * Not a user setting and not surfaced in Settings: the player's bandwidth detector writes it after a
     * successful measurement and reads it back on a fresh start as a **prior** for Auto quality. `null` means
     * nothing learned yet, which degrades to uncapped behaviour.
     */
    val maxStreamingBitrate: Flow<Int?>

    suspend fun setMaxStreamingBitrate(bitrate: Int?)
}
