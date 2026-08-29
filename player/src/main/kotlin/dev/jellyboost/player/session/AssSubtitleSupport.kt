package dev.jellyboost.player.session

import android.content.Context
import android.system.Os
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.di.ApplicationScope
import dev.jellyboost.core.common.runCatchingUnlessCancelled
import dev.jellyboost.core.datastore.AppPreferences
import dev.jellyboost.player.model.FontSpec
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * libass under Media3, behind the default-off `styledAssSubtitles` preference.
 *
 * The preference is read **once per player build**, because that is the only moment the renderers,
 * the extractors and the subtitle parser can be chosen. `ExoPlayerHandle` builds its player lazily
 * and releases it only when the video session ends *and* the playback service is gone, so a change in
 * Settings never reaches the playback on screen — and reaches the next one only if the player was
 * rebuilt in between. A music session keeps the same instance across the handover on purpose
 * (`MusicPlaybackController.relinquishToOther` releases the *adapter*, not the player), so a toggle
 * flipped while music is loaded waits for the player to go. The setting's supporting text says
 * "with nothing else playing" for that reason; `AssPreferenceStalenessTest` pins the shape, and
 * DECISIONS 2026-08-28 records why the rebuild was not taken.
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
        @ApplicationContext private val context: Context,
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
            installFontConfig()
            return runCatchingUnlessCancelled {
                val handler = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
                handler.ass
                handler
            }.onFailure { error ->
                Timber.w(error, "libass is unavailable here; ASS/SSA falls back to Media3's own renderer")
            }.getOrNull()
        }

        /**
         * Points fontconfig at a configuration that exists, **before** anything can load libass:
         * `FONTCONFIG_FILE` is read once, inside the `ass_set_fonts` call that `AssHandler` makes
         * when it builds its renderer, and nothing re-reads it afterwards. [AssFontConfig] carries
         * the defect this works around.
         *
         * A failure here is permanent for the process and not fatal — libass keeps the platform's
         * unusable fontconfig defaults, which is the behaviour that shipped before this — so it is
         * logged and playback continues rather than dropping styled subtitles altogether.
         */
        private fun installFontConfig() {
            runCatchingUnlessCancelled {
                val config = AssFontConfig.install(filesDir = context.filesDir, cacheDir = context.cacheDir)
                Os.setenv(AssFontConfig.ENVIRONMENT_VARIABLE, config.absolutePath, true)
            }.onFailure { error ->
                Timber.w(error, "libass keeps the platform's fontconfig defaults; word spacing may collapse")
            }
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
         * Registers the fonts a **transcoded download** carries beside its ASS sidecar. The server's
         * re-encode holds video and audio only, so the attachments `withAssMkvSupport` would read out of
         * an `ORIGINAL` container are not there, and without this every styled line falls back to the
         * default family. Downloaded items only: a streamed one either still has its attachments in the
         * container or is a transcode, where subtitles arrive as VTT and libass never runs.
         *
         * **Goes to `handler.ass` and not to `AssHandler.addFont`, and the difference decides whether any
         * of this works.** `AssHandler.addFont` passes a face straight to libass only once the handler
         * already has tracks, and parks it in a `pendingFonts` list otherwise. That list is drained by
         * `createTrack` — *after* its first statement, `createRenderIfNeeded()`. libass builds its font
         * lookup once, inside the `ass_set_fonts` call that creating the renderer makes, and never
         * revisits it, so a face parked before the first track is handed over one step too late and is
         * ignored for the whole session. Measured on the test tablet: fonts registered, and every style
         * still resolved to `Roboto-Bold`. `Ass.addFont` is the same native call without the detour, and
         * from here it lands in the library while `prepare` is still running — before any track exists,
         * and so before the renderer that reads it.
         *
         * **Blocking, deliberately.** The bytes have to be in the library before the first `createTrack`,
         * and posting them to another thread reopens by luck exactly the race the paragraph above closes
         * by construction. It is a local read of a few tens of KB per face on a path that is already
         * doing file I/O to open the media.
         *
         * Never clears: [AssHandler] outlives the item, and dropping the accumulated set would also drop
         * the attachments an extractor added for a container playing right now. The bound is one video
         * session's items, at tens of KB a face, and a name collision resolves to the same face anyway.
         *
         * A failure is per-font and permanent for that file — a truncated download, a blob FreeType will
         * not parse — so it is logged and the rest are still offered.
         */
        fun addFonts(fonts: List<FontSpec>) {
            if (fonts.isEmpty()) return
            val ass = _handler.value?.ass ?: return
            var loaded = 0
            fonts.forEach { font ->
                runCatchingUnlessCancelled {
                    ass.addFont(font.name, File(font.path).readBytes())
                    loaded++
                }.onFailure { error ->
                    Timber.w(error, "Attached font %s did not load; its styles fall back", font.name)
                }
            }
            Timber.i("Registered %d of %d attached fonts with libass", loaded, fonts.size)
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
