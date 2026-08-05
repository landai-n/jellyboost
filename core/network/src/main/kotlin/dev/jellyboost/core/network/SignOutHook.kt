package dev.jellyboost.core.network

/**
 * Work that must run against the still-valid session **before** sign-out revokes the token
 * (audit NET-03).
 *
 * `SessionRepository.signOut` awaits every bound hook, then tells the server the session ended —
 * which revokes the access token — and only then reports `SessionState.LoggedOut`. Anything that
 * needs a working credential to say goodbye cleanly (the SyncPlay group leave is the canonical
 * case) therefore cannot wait for the `LoggedOut` state transition: by then its request is
 * guaranteed to 401. Bind an implementation with `@Binds @IntoSet` from the module that owns it.
 *
 * Hooks are best-effort: a failure is logged and sign-out proceeds regardless, because nothing may
 * strand the user in a signed-in UI. So is their *duration* — every hook shares one
 * `SessionRepository.SERVER_GOODBYE_TIMEOUT` budget with the session-ended report, and a hook still
 * running when it expires is cancelled and the sign-out finishes without it.
 */
fun interface SignOutHook {
    /** Called with the session still valid; must tolerate the server being unreachable. */
    suspend fun onSignOut()
}
