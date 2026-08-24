package dev.jellyboost.core.datastore

import android.content.SharedPreferences
import timber.log.Timber
import java.security.GeneralSecurityException

/**
 * The two ways opening the encrypted credential file can fail are not the same thing, and treating them alike
 * loses a session a transient failure should have left alone:
 *
 * - a [GeneralSecurityException] means the file cannot be **decrypted** (a cleared Keystore key, a backup
 *   restored onto another device, a tampered file). Nothing will ever read it again, so it is deleted and
 *   recreated, and [onSessionLost] says so — a sign-in form with no explanation is how a corrupted store
 *   looks identical to a first run;
 * - an `IOException` means the file could not be **read** (an unmounted or full volume). It propagates
 *   untouched: this app run is signed out, what is stored is not.
 *
 * @param onSessionLost called exactly once per wipe, before the retry, so a retry that fails in turn still
 *   reports the loss.
 */
internal class EncryptedPreferencesOpener(
    private val create: () -> SharedPreferences,
    private val deleteStore: () -> Unit,
    private val onSessionLost: () -> Unit,
) {
    fun open(): SharedPreferences =
        try {
            create()
        } catch (error: GeneralSecurityException) {
            Timber.w(
                error,
                "Encrypted credential store could not be decrypted; deleting and recreating it " +
                    "(any stored session is lost, the user will have to sign in again)",
            )
            deleteStore()
            onSessionLost()
            create()
        }
}
