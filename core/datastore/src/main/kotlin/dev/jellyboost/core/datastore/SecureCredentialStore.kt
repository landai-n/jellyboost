package dev.jellyboost.core.datastore

/**
 * The ONLY place an access token may be persisted. The interface exists so the encryption mechanism
 * (currently EncryptedSharedPreferences) can be swapped for a Keystore AES-GCM one as a one-file change.
 */
interface SecureCredentialStore {
    suspend fun save(session: StoredSession)

    /**
     * A *transient* storage failure is deliberately not folded into `null`: `null` means "there is nothing to
     * restore", and a busy disk is not that. It propagates, and the session layer signs this run out without
     * touching what is stored.
     */
    suspend fun read(): StoredSession?

    suspend fun clear()

    /**
     * A store that could not be decrypted (cleared Keystore key, restored backup, tampered file) is wiped and
     * recreated, which is indistinguishable from a first run unless somebody says so. Reading clears the flag.
     * Not `suspend`: it reads a flag the last [read] already set.
     */
    fun consumeLostSession(): Boolean
}
