package dev.jellyboost.player.session

import androidx.media3.common.Player
import dev.jellyboost.player.di.CastPlayback
import dev.jellyboost.player.di.LocalPlayback
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.PlaybackMediaSource
import dev.jellyboost.player.model.PlaybackSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * **With no cast session this must stay a branchless pass-through**; every method below is a single delegation.
 *
 * The cast handle comes through a [Provider] because constructing it loads `com.google.android.gms`, which a
 * device without Play services must never do — only a started session asks for it.
 *
 * `PlaybackService` deliberately keeps injecting the concrete [ExoPlayerHandle]: it owns the *local* session and
 * notification, which should disappear while a television is playing.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoutingPlayerHandle
    @Inject
    constructor(
        @LocalPlayback private val local: PlayerHandle,
        @CastPlayback private val cast: Provider<PlayerHandle>,
    ) : PlayerHandle {
        private val _activeHandle = MutableStateFlow(local)

        val activeHandle: StateFlow<PlayerHandle> = _activeHandle.asStateFlow()

        private val active: PlayerHandle get() = _activeHandle.value

        /** Remembered, not re-fetched: [stopInactive] must silence a cast player without ever creating one. */
        private var castIfCreated: PlayerHandle? = null

        /**
         * `flatMapLatest`, not a merge: the stopped player's `Ended` would otherwise be attributed to the session
         * that replaced it, and `PlayerViewModel.onEnded` closes the screen on that event.
         */
        override val events: Flow<PlayerEvent> = _activeHandle.flatMapLatest { it.events }

        override val player: Player? get() = active.player

        /** Called by the cast coordinator and by nothing else: two parties routing strands sessions on a TV. */
        fun setActive(target: PlaybackTarget) {
            val handle =
                when (target) {
                    PlaybackTarget.Local -> local
                    PlaybackTarget.Cast -> cast.get().also { castIfCreated = it }
                }
            if (_activeHandle.value === handle) return
            Timber.i("Playback now routed to %s", target)
            _activeHandle.value = handle
        }

        /**
         * Silences the player that is no longer in charge; must be called after [setActive], never folded into it —
         * a switch is not always a handover, and only the cast coordinator knows which it is.
         */
        fun stopInactive() {
            val inactive = if (active === local) castIfCreated else local
            inactive?.stop()
        }

        override fun prepare(
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) = active.prepare(spec, startPositionMs, playWhenReady)

        override fun prepare(
            source: PlaybackMediaSource,
            spec: PlaybackMediaItemSpec,
            startPositionMs: Long,
            playWhenReady: Boolean,
        ) = active.prepare(source, spec, startPositionMs, playWhenReady)

        override fun play() = active.play()

        override fun pause() = active.pause()

        override fun seekTo(positionMs: Long) = active.seekTo(positionMs)

        override fun snapshot(): PlaybackSnapshot = active.snapshot()

        override fun selectAudioTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int,
        ): Boolean = active.selectAudioTrack(source, jellyfinIndex)

        override fun selectSubtitleTrack(
            source: PlaybackMediaSource,
            jellyfinIndex: Int?,
        ): Boolean = active.selectSubtitleTrack(source, jellyfinIndex)

        override fun setPlaybackSpeed(speed: Float) = active.setPlaybackSpeed(speed)

        override val supportsPlaybackSpeed: Boolean get() = active.supportsPlaybackSpeed

        override fun stop() = active.stop()

        override fun release() = active.release()
    }

internal enum class PlaybackTarget {
    Local,
    Cast,
}
