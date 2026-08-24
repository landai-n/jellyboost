package dev.jellyboost.core.datastore

import android.content.SharedPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Unit tests for [EncryptedPreferencesOpener] — which failures cost the user their session.
 *
 * The class exists so that this decision can be pinned without an Android Keystore; everything
 * around it in [EncryptedSecureCredentialStore] is Jetpack plumbing.
 */
class EncryptedPreferencesOpenerTest {
    private val prefs = mockk<SharedPreferences>()
    private var deletes = 0
    private var losses = 0

    @Test
    fun `a store that opens is left alone`() {
        val opener = opener(failures = ArrayDeque())

        opener.open() shouldBe prefs

        deletes shouldBe 0
        losses shouldBe 0
    }

    @Test
    fun `a store that cannot be decrypted is recreated, and the loss is recorded`() {
        // A Keystore key the OS cleared, or a backup restored onto another device: nothing will
        // ever read this file again, so recreating it beats crashing on every app start.
        val opener = opener(failures = ArrayDeque(listOf(GeneralSecurityException("bad key"))))

        opener.open() shouldBe prefs

        deletes shouldBe 1
        losses shouldBe 1
    }

    @Test
    fun `a transient read failure never deletes the stored session`() {
        // Treating an IOException exactly like a decryption failure would throw away a session
        // that was perfectly intact just because a volume was busy or unmounted.
        val opener = opener(failures = ArrayDeque(listOf(IOException("volume busy"))))

        shouldThrow<IOException> { opener.open() }

        deletes shouldBe 0
        losses shouldBe 0
    }

    @Test
    fun `a recreation that fails in turn still reports the session as lost`() {
        // The file is already gone by then; a caller that learned nothing would sign the user out
        // in silence.
        val opener =
            opener(
                failures =
                    ArrayDeque(
                        listOf(GeneralSecurityException("bad key"), GeneralSecurityException("still bad")),
                    ),
            )

        shouldThrow<GeneralSecurityException> { opener.open() }

        deletes shouldBe 1
        losses shouldBe 1
    }

    /** An opener whose `create` throws [failures] in order and then succeeds. */
    private fun opener(failures: ArrayDeque<Exception>) =
        EncryptedPreferencesOpener(
            create = { failures.removeFirstOrNull()?.let { throw it } ?: prefs },
            deleteStore = { deletes++ },
            onSessionLost = { losses++ },
        )
}
