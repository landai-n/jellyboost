package dev.jellyfinnative.core.datastore

/**
 * Persistence seam for the installation's Jellyfin device id.
 *
 * The device id is NOT a secret — it travels in the `Authorization` header of every request and
 * is displayed verbatim in the server's Dashboard → Devices — so it deliberately lives in plain
 * storage rather than in [SecureCredentialStore].
 *
 * Reads and writes are synchronous by design: [DeviceIdProvider] needs the id while the SDK
 * client is being constructed, before any coroutine is available.
 */
interface DeviceIdStore {
    /** The persisted device id, or `null` if this installation has never generated one. */
    fun read(): String?

    /**
     * Persists [id] durably before returning — a device id lost to a crash would look like a new
     * device to the server and invalidate the session bound to the old one.
     */
    fun write(id: String)
}
