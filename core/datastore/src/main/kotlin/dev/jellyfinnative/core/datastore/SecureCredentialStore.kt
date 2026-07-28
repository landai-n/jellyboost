package dev.jellyfinnative.core.datastore

/**
 * Encrypted-at-rest storage for the single active [StoredSession].
 *
 * This is the ONLY place an access token may be persisted (docs/PLAN.md, `:core:datastore` row;
 * risk #3). The interface exists so the encryption mechanism (currently EncryptedSharedPreferences
 * via `security-crypto`) can be swapped for a hand-rolled Keystore AES-GCM implementation as a
 * one-file change if `security-crypto` is ever dropped.
 */
interface SecureCredentialStore {
    /**
     * Persists [session], replacing any previously stored session.
     */
    suspend fun save(session: StoredSession)

    /**
     * Returns the stored session, or `null` if none is stored or the stored data is
     * incomplete/unparseable.
     */
    suspend fun read(): StoredSession?

    /**
     * Wipes the stored session, if any. Used on sign-out.
     */
    suspend fun clear()
}
