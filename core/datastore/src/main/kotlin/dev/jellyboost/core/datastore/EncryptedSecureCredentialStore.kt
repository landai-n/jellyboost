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
 * [SecureCredentialStore] on `security-crypto`'s `EncryptedSharedPreferences` (AES256_GCM master key,
 * AES256_SIV keys, AES256_GCM values). The artifact is deprecated upstream, so the usage is suppressed and
 * confined to this file: a swap to hand-rolled Keystore AES-GCM must touch only this class.
 *
 * Opening the store does disk I/O and talks to the Keystore, so every operation — the lazy creation included
 * — runs on the injected [IoDispatcher]. What happens when it will not open is [EncryptedPreferencesOpener]'s
 * decision, and the loss is *recorded* rather than silent (see [consumeLostSession]).
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
                    // Decrypted, but not a session: ids that are no longer UUIDs. Dropping the row costs the
                    // user their session, so it counts as a loss.
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
