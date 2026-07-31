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
 * The [PlayerHandle] everything above the seam actually holds: whichever player is playing.
 *
 * Casting does not change *what* `PlayerViewModel` does — resolve, prepare, report, switch tracks —
 * only where the bytes are decoded, so the cheapest correct design is the one that leaves the
 * ViewModel unaware: one binding, one seam, and a pointer underneath it that
 * [dev.jellyboost.player.cast.CastSessionCoordinator] moves when a cast session starts or ends.
 * Nothing else may call [setActive]; two parties deciding which player is live is the one way this
 * can strand a session on a television.
 *
 * **With no cast session this is a pass-through and must stay one.** Every method below is a single
 * delegation with no branch in it, which is what makes "casting changed nothing about playing
 * something on your own" a property of the code rather than of the tests.
 *
 * The cast handle arrives through a [Provider] rather than as an instance: constructing it is the
 * first thing in the app that loads a `com.google.android.gms` class, and a device without Play
 * services must never do that. Since only a started session calls `setActive(Cast)`, and a session
 * cannot start without the Cast stack, the provider is only ever asked on devices that have one.
 *
 * `PlaybackService` keeps injecting the concrete [ExoPlayerHandle] and is deliberately untouched: it
 * owns the *local* media session and notification, which is exactly what should disappear while a
 * television is playing.
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

        /** The player currently in charge; [local] until a cast session says otherwise. */
        val activeHandle: StateFlow<PlayerHandle> = _activeHandle.asStateFlow()

        private val active: PlayerHandle get() = _activeHandle.value

        /**
         * The cast handle, once something has actually asked for one.
         *
         * Remembered rather than re-fetched from the [Provider], so that [stopInactive] can silence
         * a cast player that exists without *creating* one that does not — which on a device with no
         * Play services would load the very classes this indirection exists to avoid.
         */
        private var castIfCreated: PlayerHandle? = null

        /**
         * The active handle's events, and only the active one's.
         *
         * `flatMapLatest` rather than a merge: a collector that kept hearing from the player it just
         * stopped would attribute its `Ended` — which is what stopping one produces — to the session
         * that replaced it, and `PlayerViewModel.onEnded` closes the screen on that event.
         */
        override val events: Flow<PlayerEvent> = _activeHandle.flatMapLatest { it.events }

        override val player: Player? get() = active.player

        /** Points playback at [target]. Called by the cast coordinator and by nothing else. */
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
         * Silences the player that is no longer in charge.
         *
         * Separate from [setActive], and called only after it, because the two are not the same
         * decision: routing says where the *next* command goes, while this ends what the previous
         * player was still doing. A phone that kept playing under a television is the everyday
         * consequence of skipping it, and `ExoPlayerHandle.stop` takes the local media notification
         * down with it — which is precisely what should happen when the film has moved elsewhere
         * (docs/notes/chromecast-m12-plan.md, decision 1).
         *
         * Not folded into [setActive] on purpose: a switch is not always a handover — the cast side
         * of one ends with a receiver that has already gone — and the caller that knows which it is
         * is [dev.jellyboost.player.cast.CastSessionCoordinator].
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

/** Where playback is happening — the whole of what [RoutingPlayerHandle] routes between. */
internal enum class PlaybackTarget {
    Local,
    Cast,
}
