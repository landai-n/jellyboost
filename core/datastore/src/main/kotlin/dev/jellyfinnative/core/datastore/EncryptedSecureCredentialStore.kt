package dev.jellyfinnative.core.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
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
 * Keystore, so every operation — including the lazy creation itself — runs on [Dispatchers.IO].
 *
 * If the encrypted preferences fail to open (e.g. after a backup/restore onto a different
 * device, or the Keystore key being cleared by the OS), the underlying file is deleted and
 * creation is retried once. Losing a stored session this way just means the user has to sign
 * in again — recreating the file is strictly better than crashing on every app start.
 */
@Suppress("DEPRECATION")
@Singleton
class EncryptedSecureCredentialStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SecureCredentialStore {
        @Volatile
        private var prefs: SharedPreferences? = null

        override suspend fun save(session: StoredSession) {
            withContext(Dispatchers.IO) {
                preferences()
                    .edit()
                    .putString(KEY_SERVER_ID, session.serverId.toString())
                    .putString(KEY_USER_ID, session.userId.toString())
                    .putString(KEY_ACCESS_TOKEN, session.accessToken)
                    .apply()
            }
        }

        override suspend fun read(): StoredSession? =
            withContext(Dispatchers.IO) {
                val current = preferences()
                val serverId = current.getString(KEY_SERVER_ID, null)
                val userId = current.getString(KEY_USER_ID, null)
                val accessToken = current.getString(KEY_ACCESS_TOKEN, null)
                if (serverId == null || userId == null || accessToken == null) {
                    return@withContext null
                }
                runCatching {
                    StoredSession(
                        serverId = UUID.fromString(serverId),
                        userId = UUID.fromString(userId),
                        accessToken = accessToken,
                    )
                }.getOrElse { error ->
                    Timber.w(error, "Stored session was present but unparseable; clearing it")
                    clear(current)
                    null
                }
            }

        override suspend fun clear() {
            withContext(Dispatchers.IO) {
                clear(preferences())
            }
        }

        private fun clear(target: SharedPreferences) {
            target.edit().clear().apply()
        }

        /** Must only be called while already dispatched on [Dispatchers.IO]. */
        private fun preferences(): SharedPreferences =
            prefs ?: synchronized(this) {
                prefs ?: createPreferences().also { prefs = it }
            }

        private fun createPreferences(): SharedPreferences =
            try {
                buildEncryptedPreferences()
            } catch (error: GeneralSecurityException) {
                recreateAfterCorruption(error)
            } catch (error: IOException) {
                recreateAfterCorruption(error)
            }

        private fun recreateAfterCorruption(error: Exception): SharedPreferences {
            Timber.w(
                error,
                "Encrypted credential store could not be opened; deleting and recreating it " +
                    "(any stored session is lost, user will need to sign in again)",
            )
            context.deleteSharedPreferences(PreferenceKeys.SECURE_STORE_NAME)
            return buildEncryptedPreferences()
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
