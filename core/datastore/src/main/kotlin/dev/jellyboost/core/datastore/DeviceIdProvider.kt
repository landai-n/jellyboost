package dev.jellyboost.core.datastore

import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stable identity this installation presents to Jellyfin as its device id.
 *
 * A Jellyfin server keys **one access token per (user, device id)**: signing in with a device id
 * that is already registered revokes the token previously issued for it. The device id must
 * therefore be stable for the lifetime of an installation and unique *between* installations.
 *
 * The SDK's default (`androidDevice(context)` → `Settings.Secure.ANDROID_ID`) satisfies the first
 * requirement but not the second: since Android 8 the SSAID is scoped per *signing key*, not per
 * package, so two apps signed with the same key — e.g. our `dev.jellyboost.app.debug` and the
 * locally-signed `dev.jellyboost.app` release variant used for profiling — receive the *same*
 * ANDROID_ID and silently revoke each other's session on every sign-in. Hence: a random UUID,
 * generated once and persisted per installation.
 *
 * The id is not cleared on sign-out. It is device identity, not a credential, and keeping it
 * stable means signing back in re-uses the same Dashboard → Devices entry instead of leaving a
 * trail of dead ones.
 */
@Singleton
class DeviceIdProvider
    @Inject
    constructor(
        private val store: DeviceIdStore,
    ) {
        /**
         * The persisted device id, generating and storing one on first use.
         *
         * Resolved lazily and then cached for the process lifetime, so the (single, small) disk
         * read happens once — at the point the SDK client is built.
         */
        val deviceId: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadOrCreate() }

        private fun loadOrCreate(): String =
            store.read()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString().also { generated ->
                Timber.i("No device id stored yet; generated a new one for this installation")
                store.write(generated)
            }
    }
