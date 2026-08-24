package dev.jellyboost.core.network

/**
 * Work that must run against the still-valid session **before** sign-out revokes the token: anything needing
 * a working credential to say goodbye (the SyncPlay group leave) cannot wait for `LoggedOut`, because by then
 * its request is guaranteed to 401. Bind with `@Binds @IntoSet` from the module that owns it.
 *
 * Best-effort: a failure is logged and sign-out proceeds, because nothing may strand the user in a signed-in
 * UI. Every hook shares one `SessionRepository.SERVER_GOODBYE_TIMEOUT` budget with the session-ended report,
 * and one still running when it expires is cancelled.
 */
fun interface SignOutHook {
    /** Called with the session still valid; must tolerate the server being unreachable. */
    suspend fun onSignOut()
}
