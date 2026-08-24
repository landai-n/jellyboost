package dev.jellyboost.core.datastore

/**
 * Encrypted-at-rest storage for the single active [StoredSession].
 *
 * This is the ONLY place an access token may be persisted. The interface exists so the encryption
 * mechanism (currently EncryptedSharedPreferences via `security-crypto`) can be swapped for a
 * hand-rolled Keystore AES-GCM implementation as a one-file change if `security-crypto` is ever
 * dropped.
 */
interface SecureCredentialStore {
    /**
     * Persists [session], replacing any previously stored session.
     */
    suspend fun save(session: StoredSession)

    /**
     * Returns the stored session, or `null` if none is stored or the stored data is
     * incomplete/unparseable.
     *
     * A *transient* storage failure is deliberately not folded into `null` here: `null` means
     * "there is nothing to restore", and a busy disk is not that. It propagates, and the session
     * layer signs this run out without touching what is stored.
     */
    suspend fun read(): StoredSession?

    /**
     * Wipes the stored session, if any. Used on sign-out.
     */
    suspend fun clear()

    /**
     * Whether the store itself destroyed a stored session since this was last asked — reading it
     * clears the flag.
     *
     * The distinction it exists for is the one the user can see: a store that could not be
     * decrypted (a cleared Keystore key, a restored backup, a tampered file) is wiped and recreated,
     * which is indistinguishable from a first run unless somebody says so. The session layer turns
     * this into the one-shot message the auth screen shows.
     *
     * Not `suspend`: it reads a flag the last [read] already set.
     */
    fun consumeLostSession(): Boolean
}
