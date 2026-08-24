package dev.jellyboost.player.syncplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Breaks a dependency cycle: `PlaybackReporter` needs group membership and `SyncPlayController` needs
 * the reporter's world, so neither may inject the other.
 *
 * Written only by the controller and by `PlaybackInfoResolver`'s mint path, read by anyone.
 */
@Singleton
internal class SyncPlayStatusHolder
    @Inject
    constructor() {
        private val _inGroup = MutableStateFlow(false)

        val inGroup: StateFlow<Boolean> = _inGroup.asStateFlow()

        private val _mintedPlaySessionId = MutableStateFlow<String?>(null)

        /**
         * A local file has no play session, so in a group one is minted with a `PlaybackInfo` POST.
         * `null` means not attempted or failed: report with no session id rather than not at all.
         */
        val mintedPlaySessionId: StateFlow<String?> = _mintedPlaySessionId.asStateFlow()

        fun setInGroup(inGroup: Boolean) {
            _inGroup.value = inGroup
        }

        /** Pass `null` to clear at the end of a group session. */
        fun setMintedPlaySessionId(playSessionId: String?) {
            _mintedPlaySessionId.value = playSessionId
        }
    }
