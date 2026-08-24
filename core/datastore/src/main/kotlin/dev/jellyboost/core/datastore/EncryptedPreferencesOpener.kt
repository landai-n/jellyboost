package dev.jellyboost.core.datastore

import android.content.SharedPreferences
import timber.log.Timber
import java.security.GeneralSecurityException

/**
 * Opens the encrypted credential file, and decides what a failure to open it costs the user.
 *
 * The two ways it can fail are not the same thing, and treating them alike loses a session that a
 * transient failure should have left alone:
 *
 * - a [GeneralSecurityException] means the file cannot be **decrypted** — a Keystore key the OS
 *   cleared, a backup restored onto another device, a tampered file. Nothing will ever read it
 *   again, so it is deleted and recreated. The session is genuinely gone, and [onSessionLost] says
 *   so, because a sign-in form appearing with no explanation is how a corrupted store looks
 *   identical to a first run;
 * - an `IOException` means the file could not be **read** — an unmounted or full volume, a
 *   transient failure. Deleting a perfectly good encrypted session because the disk was busy for a
 *   moment contradicts `SessionRepository`'s own documented contract ("a transient storage failure
 *   leaves the stored session alone"), so it propagates untouched: this app run is signed out, what
 *   is stored is not.
 *
 * Split out of [EncryptedSecureCredentialStore] so that this decision is unit-testable without an
 * Android Keystore.
 *
 * @param create builds (or opens) the encrypted preferences.
 * @param deleteStore removes the underlying file so that [create] can start from nothing.
 * @param onSessionLost called exactly once per wipe, before the retry, so that a retry which fails
 *   in turn still reports the loss.
 */
internal class EncryptedPreferencesOpener(
    private val create: () -> SharedPreferences,
    private val deleteStore: () -> Unit,
    private val onSessionLost: () -> Unit,
) {
    /** Opens the store, recreating it only when what is there can no longer be decrypted. */
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
