package dev.jellyboost.core.datastore

/**
 * The device id is NOT a secret — it travels in the `Authorization` header of every request and is displayed
 * verbatim in the server's Dashboard → Devices — so it lives in plain storage, not [SecureCredentialStore].
 *
 * Reads and writes are synchronous by design: [DeviceIdProvider] needs the id while the SDK client is being
 * constructed, before any coroutine is available.
 */
interface DeviceIdStore {
    fun read(): String?

    /** Must be durable before returning: an id lost to a crash looks like a new device and invalidates the session. */
    fun write(id: String)
}
