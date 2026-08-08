package dev.jellyboost.core.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SecureCredentialStore] backed by Jetpack `security-crypto`'s `EncryptedSharedPreferences`
 * (AES256_GCM master key, AES256_SIV key encryption, AES256_GCM value encryption).
 *
 * `EncryptedSharedPreferences`/`MasterKey` are deprecated upstream (the whole `security-crypto`
 * artifact is in maintenance mode — docs/PLAN.md risk #3) but remain the simplest correct
 * implementation available; usage is suppressed and confined entirely to this file so a future
 * swap to a hand-rolled Android Keystore AES-GCM implementation only touches this class.
 *
 * Opening (or first creating) the encrypted preferences does disk I/O and talks to the Android
 * Keystore, so every operation — including the lazy creation itself — runs on the injected
 * [IoDispatcher] (audit QUAL-3; it was a hard-coded `Dispatchers.IO` until the qualifier became
 * visible from this module, and an injected dispatcher is a test seam as much as a policy).
 *
 * What happens when it will not open is [EncryptedPreferencesOpener]'s decision, and it is not one
 * decision but two: an undecryptable file is deleted and recreated (better than crashing on every
 * app start), a file that merely could not be read right now is left exactly where it is. Either
 * way the loss is *recorded* rather than silent — see [consumeLostSession].
 */
@Suppress("DEPRECATION")
@Singleton
class EncryptedSecureCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : SecureCredentialStore {
        @Volatile
        private var prefs: SharedPreferences? = null

        @Volatile
        private var lostStoredSession = false

        private val opener =
            EncryptedPreferencesOpener(
                create = ::buildEncryptedPreferences,
                deleteStore = { context.deleteSharedPreferences(PreferenceKeys.SECURE_STORE_NAME) },
                onSessionLost = { lostStoredSession = true },
            )

        override suspend fun save(session: StoredSession) {
            withContext(ioDispatcher) {
                preferences()
                    .edit()
                    .putString(KEY_SERVER_ID, session.serverId.toString())
                    .putString(KEY_USER_ID, session.userId.toString())
                    .putString(KEY_ACCESS_TOKEN, session.accessToken)
                    .apply()
            }
        }

        override suspend fun read(): StoredSession? =
            withContext(ioDispatcher) {
                val current = preferences()
                val serverId = current.getString(KEY_SERVER_ID, null)
                val userId = current.getString(KEY_USER_ID, null)
                val accessToken = current.getString(KEY_ACCESS_TOKEN, null)
                if (serverId == null || userId == null || accessToken == null) {
                    return@withContext null
                }
                try {
                    StoredSession(
                        serverId = UUID.fromString(serverId),
                        userId = UUID.fromString(userId),
                        accessToken = accessToken,
                    )
                } catch (error: IllegalArgumentException) {
                    // Decrypted, but not a session: two ids that are no longer UUIDs. The row is
                    // unusable and is dropped — which costs the user their session, so it counts.
                    Timber.w(error, "Stored session was present but unparseable; clearing it")
                    clear(current)
                    lostStoredSession = true
                    null
                }
            }

        override suspend fun clear() {
            withContext(ioDispatcher) {
                clear(preferences())
            }
        }

        override fun consumeLostSession(): Boolean {
            val lost = lostStoredSession
            lostStoredSession = false
            return lost
        }

        private fun clear(target: SharedPreferences) {
            target.edit().clear().apply()
        }

        /** Must only be called while already dispatched on [ioDispatcher]. */
        private fun preferences(): SharedPreferences =
            prefs ?: synchronized(this) {
                prefs ?: opener.open().also { prefs = it }
            }

        private fun buildEncryptedPreferences(): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

            return EncryptedSharedPreferences.create(
                context,
                PreferenceKeys.SECURE_STORE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private companion object {
            const val KEY_SERVER_ID = "server_id"
            const val KEY_USER_ID = "user_id"
            const val KEY_ACCESS_TOKEN = "access_token"
        }
    }
