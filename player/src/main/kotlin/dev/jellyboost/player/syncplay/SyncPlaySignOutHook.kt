package dev.jellyboost.player.syncplay

import dev.jellyboost.core.network.SignOutHook
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Must run before sign-out revokes the access token: a leave that chases a revoked token leaves the
 * server holding this session in the group, a phantom member the rest wait on.
 */
@Singleton
internal class SyncPlaySignOutHook
    @Inject
    constructor(
        private val controller: SyncPlayController,
    ) : SignOutHook {
        override suspend fun onSignOut() = controller.leaveBeforeSignOut()
    }
