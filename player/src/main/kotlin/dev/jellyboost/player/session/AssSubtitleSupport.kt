package dev.jellyboost.player.session

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.datastore.AppPreferences
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * libass under Media3, behind the default-off `styledAssSubtitles` preference.
 *
 * The preference is read **once per player build**, because that is the only moment the renderers,
 * the extractors and the subtitle parser can be chosen: `ExoPlayerHandle` builds its player lazily
 * and releases it at session teardown, so a change in Settings reaches the next playback and not the
 * one on screen. The setting's supporting text says exactly that.
 *
 * `OVERLAY_OPEN_GL` matches jellyfin-androidtv: full animation, HDR-safe (unlike the `EFFECTS_*`
 * modes, androidx/media#723), and it rasterises on its own thread instead of the UI one.
 *
 * Local playback only. `CastPlayerHandle` never reaches this class — a receiver renders its own
 * subtitles, and there is no surface here to draw on.
 */
@Singleton
@UnstableApi
internal class AssSubtitleSupport
    @Inject
    constructor(
        preferences: AppPreferences,
        @ApplicationScope scope: CoroutineScope,
    ) {
        private val enabled: StateFlow<Boolean> =
            preferences.styledAssSubtitles
                .distinctUntilChanged()
                .stateIn(scope, SharingStarted.Eagerly, initialValue = false)

        @Suppress("ktlint:standard:backing-property-naming")
        private val _handler = MutableStateFlow<AssHandler?>(null)

        /** What `PlayerScreen` hangs its `AssSubtitleView` off; `null` whenever libass is not driving. */
        val handler: StateFlow<AssHandler?> = _handler.asStateFlow()

        /**
         * `null` when the preference is off **or** when libass cannot load here — an ABI without a
         * `libass.so`, a stripped release, an OpenGL stack the renderer refuses. That failure is
         * permanent for this process and there is a working alternative, so it degrades to Media3's
         * own `SsaParser` with a log instead of failing the playback.
         *
         * Touching `ass` is what forces `System.loadLibrary`; the handler's own constructor is lazy
         * and would defer the error to the first subtitle sample, mid-playback.
         */
        fun createHandler(): AssHandler? {
            if (!enabled.value) return null
            return runCatchingUnlessCancelled {
                val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
                handler.ass
                handler
            }.onFailure { error ->
                Timber.w(error, "libass is unavailable here; ASS/SSA falls back to Media3's own renderer")
            }.getOrNull()
        }

        /** Called once the player exists: the handler listens to it for tracks, video size and clock. */
        fun attach(
            handler: AssHandler,
            player: ExoPlayer,
        ) {
            handler.init(player)
            _handler.value = handler
        }

        /**
         * Cleared **before** the native release, so no composition can hand a freed handler to a new
         * `AssSubtitleView`. Idempotent, like the player release that calls it.
         */
        fun release() {
            val handler = _handler.value ?: return
            _handler.value = null
            runCatchingUnlessCancelled { handler.release() }
                .onFailure { error -> Timber.w(error, "libass did not release cleanly") }
        }
    }
