package dev.jellyboost.core.datastore

import dev.jellyboost.core.common.model.HomeSectionType

/**
 * A **cache of a server value**, not a user preference: the layout is configured in jellyfin-web and read
 * back from DisplayPreferences, so nothing in the app ever writes it on the user's behalf. Its only job is to
 * keep the home screen in the configured shape while offline, and on the load right after a failed fetch.
 *
 * Reads and writes are synchronous, like [DeviceIdStore]: one short string, on a caller already on IO.
 */
interface HomeLayoutStore {
    /** `null` if the server has never been asked on this device. */
    fun read(): List<HomeSectionType>?

    fun write(sections: List<HomeSectionType>)

    /**
     * Called on sign-out: without it, a fetch failure just after a different user signs in falls back to the
     * *previous* user's layout — [read] cannot tell whose it is holding.
     */
    fun clear()
}
