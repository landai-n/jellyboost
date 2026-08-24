package dev.jellyboost.data.downloads.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jellyboost.core.common.di.MainDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns the mkv an audio sidecar is *fetched* as into the m4a it is *stored* as.
 *
 * An extra audio language cannot be fetched audio-only: server 10.11 hard-codes `audioStreamIndex`
 * to null for a non-video request, so `/Audio/{id}/stream` always answers with the source's default
 * track whatever index was asked for. `/Videos/{id}/stream.mkv` does honor it, so the track is
 * fetched with the cheapest video the server will make and the video is dropped here, once the whole
 * file is on disk.
 */
internal interface AudioSidecarExtractor {
    /** Transmux the audio track of [source] (mkv, video+audio) into [target] (m4a, audio only). Throws on failure. */
    suspend fun extract(
        source: File,
        target: File,
    )
}

/**
 * [AudioSidecarExtractor] over a Media3 `Transformer`, transmuxing and never re-encoding.
 *
 * ### Why this is a transmux and not a transcode
 * Nothing is asked of the [EditedMediaItem] that requires a decoder: [EditedMediaItem.Builder
 * .setRemoveVideo] drops a track rather than changing one, and no audio format is requested, so
 * `Transformer` takes its transmuxing path and the AAC frames the server already produced are copied
 * into the mp4 box structure byte for byte. That is why a two-hour sidecar is seconds of work rather
 * than minutes, and why the sidecar's audio is exactly the audio that was downloaded.
 *
 * ### Why the main thread
 * `Transformer` asserts it is created, started and cancelled on one thread with a `Looper`, and
 * posts its callbacks back to it. The application's main thread is the only such thread this class
 * can be sure of, so the whole exchange runs there — it is an I/O-free supervisor of the muxer's own
 * background threads, not the work itself. Cancellation arrives from an arbitrary thread, so it is
 * posted back rather than called where it lands.
 *
 * That hop is *injected* ([MainDispatcher]) rather than written as `Dispatchers.Main`: the hard-coded
 * form has no main looper in a JVM unit test, which would make the whole transmux path device-only
 * to exercise. The `Looper` the cancellation path posts to is still the real one — see
 * [extract] — because that is `Transformer`'s own requirement and not a scheduling choice.
 */
@Singleton
@UnstableApi
internal class TransformerAudioSidecarExtractor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    ) : AudioSidecarExtractor {
        override suspend fun extract(
            source: File,
            target: File,
        ) = withContext(mainDispatcher) {
            suspendCancellableCoroutine { continuation ->
                val transformer =
                    Transformer
                        .Builder(context)
                        .addListener(
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    Timber.i(
                                        "Stripped %s to %s (%d ms of audio)",
                                        source.name,
                                        target.name,
                                        exportResult.durationMs,
                                    )
                                    continuation.resume(Unit)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    continuation.resumeWithException(exportException)
                                }
                            },
                        ).build()

                val edited =
                    EditedMediaItem
                        .Builder(MediaItem.fromUri(source.toUri()))
                        .setRemoveVideo(true)
                        .build()

                continuation.invokeOnCancellation {
                    // Never on the cancelling thread: `Transformer` refuses every call made off the
                    // thread it was built on.
                    Handler(Looper.getMainLooper()).post { transformer.cancel() }
                }

                transformer.start(edited, target.absolutePath)
            }
        }
    }
