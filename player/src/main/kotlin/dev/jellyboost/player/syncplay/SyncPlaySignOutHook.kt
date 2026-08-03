package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.SignOutHook
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leaves any SyncPlay group before sign-out revokes the access token (audit NET-03).
 *
 * `SessionRepository.signOut` awaits this hook first; the `SessionState.LoggedOut` transition it
 * ends with then only triggers the controller's local teardown. Without the hook the leave would
 * chase a revoked token and the server would keep this session in the group — a phantom member the
 * rest of the group waits on.
 */
@Singleton
internal class SyncPlaySignOutHook
    @Inject
    constructor(
        private val controller: SyncPlayController,
    ) : SignOutHook {
        override suspend fun onSignOut() = controller.leaveBeforeSignOut()
    }
