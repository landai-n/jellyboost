package dev.jellyboost.player.syncplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two facts about SyncPlay that things outside SyncPlay need to know.
 *
 * It exists to break a dependency cycle rather than to hold state for its own sake: `PlaybackReporter`
 * has to know whether this session is in a group (a local file only reports to the server while it
 * is — docs/notes/syncplay-m11-plan.md, key decision 9), and `SyncPlayController` has to be able to
 * drive playback, which means reaching the reporter's world. Injecting the controller into the
 * reporter would close that loop and Hilt would reject it; both can depend on this instead.
 *
 * Written only by the controller and by `PlaybackInfoResolver`'s mint path (M11 Phase 6), read by
 * anyone.
 */
@Singleton
internal class SyncPlayStatusHolder
    @Inject
    constructor() {
        private val _inGroup = MutableStateFlow(false)

        /** `true` while this session is a member of a SyncPlay group. */
        val inGroup: StateFlow<Boolean> = _inGroup.asStateFlow()

        private val _mintedPlaySessionId = MutableStateFlow<String?>(null)

        /**
         * The play session id minted for an in-group local file, or `null`.
         *
         * A `LocalPlaybackMediaSource` has no play session by construction — nothing was negotiated
         * with the server to produce one. In a group we mint one anyway with a single `PlaybackInfo`
         * POST so the member shows up in the dashboard; `null` means the mint was not attempted or
         * failed, and reporting degrades to sending no session id rather than not reporting.
         */
        val mintedPlaySessionId: StateFlow<String?> = _mintedPlaySessionId.asStateFlow()

        /** Publishes group membership. Called by [SyncPlayController] only. */
        fun setInGroup(inGroup: Boolean) {
            _inGroup.value = inGroup
        }

        /** Publishes the minted play session id, or clears it at the end of a group session. */
        fun setMintedPlaySessionId(playSessionId: String?) {
            _mintedPlaySessionId.value = playSessionId
        }
    }
