package dev.jellyboost.core.datastore

import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A Jellyfin server keys **one access token per (user, device id)**: signing in with a device id that is
 * already registered revokes the token previously issued for it. The id must therefore be stable for the life
 * of an installation and unique *between* installations.
 *
 * The SDK's default (`Settings.Secure.ANDROID_ID`) fails the second half: since Android 8 the SSAID is scoped
 * per *signing key*, not per package, so two variants signed with the same key receive the same id and
 * silently revoke each other's session on every sign-in. Hence a random UUID, persisted per installation.
 *
 * Not cleared on sign-out: it is device identity, not a credential, and keeping it stable re-uses the same
 * Dashboard → Devices entry instead of leaving a trail of dead ones.
 */
@Singleton
class DeviceIdProvider
    @Inject
    constructor(
        private val store: DeviceIdStore,
    ) {
        /** Resolved lazily and cached for the process lifetime, so the disk read happens once. */
        val deviceId: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadOrCreate() }

        private fun loadOrCreate(): String =
            store.read()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString().also { generated ->
                Timber.i("No device id stored yet; generated a new one for this installation")
                store.write(generated)
            }
    }
