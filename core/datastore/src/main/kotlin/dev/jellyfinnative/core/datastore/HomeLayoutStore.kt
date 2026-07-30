package dev.jellyfinnative.core.datastore

import dev.jellyfinnative.core.common.model.HomeSectionType

/**
 * Persistence seam for the last home-section layout the server told us about.
 *
 * This is a **cache of a server value**, not a user preference: the layout is configured in
 * jellyfin-web (Settings → Home) and read back from DisplayPreferences. It lives here rather than
 * in [AppPreferences] for that reason — nothing in the app ever writes it on the user's behalf,
 * and it is worthless without the server it came from. Its only job is to keep the home screen in
 * the shape the user configured while offline, and on the load right after a failed fetch.
 *
 * Reads and writes are synchronous, like [DeviceIdStore]: it is one short string, the caller
 * already runs on an IO dispatcher, and a suspending seam would buy nothing.
 */
interface HomeLayoutStore {
    /** The last resolved layout, or `null` if the server has never been asked on this device. */
    fun read(): List<HomeSectionType>?

    /** Replaces the persisted layout. Losing this write costs one re-fetch, nothing more. */
    fun write(sections: List<HomeSectionType>)

    /**
     * Drops the persisted layout.
     *
     * Called on sign-out (audit ARCH-12): without this, a fetch failure in the window right after
     * a different user signs in would fall back to whatever the *previous* user's server told this
     * device — [read] cannot tell whose layout it is holding.
     */
    fun clear()
}
