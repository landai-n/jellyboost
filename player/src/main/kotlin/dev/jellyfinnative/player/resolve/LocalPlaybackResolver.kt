package dev.jellyfinnative.player.resolve

import dev.jellyfinnative.data.downloads.offline.DownloadedMedia
import dev.jellyfinnative.data.downloads.offline.DownloadedMediaProvider
import dev.jellyfinnative.data.downloads.offline.DownloadedTrickplay
import dev.jellyfinnative.player.deviceprofile.CodecHelpers
import dev.jellyfinnative.player.model.ExternalSubtitle
import dev.jellyfinnative.player.model.LocalPlaybackMediaSource
import dev.jellyfinnative.player.model.LocalTrickplay
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline counterpart of [PlaybackInfoResolver] (docs/PLAN.md, "Playback pipeline" → Offline).
 *
 * Where the online resolver asks the server how to play an item, this one answers the same question
 * from what is already on the device: the downloaded media file, the streams recorded in the cached
 * `BaseItemDto`, the downloaded subtitle sidecars and the trickplay tiles. It makes **no network
 * call of any kind** — not even to build a URL — which is what lets a downloaded film start in
 * airplane mode as fast as it does on Wi-Fi.
 *
 * The result is deliberately the same sealed type the online path produces, carrying the same
 * `PlaybackTrack` objects built by the same [toTrack] mapper, so the audio and subtitle pickers are
 * identical either way. There is no play method to decide: a local file is
 * [dev.jellyfinnative.player.PlayMethod.DIRECT_PLAY] or it is nothing.
 *
 * ### What is offered, and what is quietly withheld
 * Embedded tracks are all offered — ExoPlayer reads them straight out of the container. An
 * *external* subtitle stream is only offered when its sidecar was actually downloaded: the download
 * pipeline skips bitmap formats and an optional file is allowed to fail, and a picker entry that
 * cannot do anything is worse than one fewer language.
 */
@Singleton
class LocalPlaybackResolver
    @Inject
    constructor(
        private val downloads: DownloadedMediaProvider,
    ) {
        /**
         * @return the item played from local storage, or `null` when it is not (fully) downloaded —
         *   the caller then falls back to the server.
         */
        suspend fun resolve(request: PlaybackResolveRequest): LocalPlaybackMediaSource? {
            val downloaded = downloads.get(request.itemId) ?: return null
            Timber.i("Playing %s from local storage", request.itemId)

            val streams = downloaded.mediaSource?.mediaStreams.orEmpty()
            val sidecars = downloaded.subtitles.associateBy { it.streamIndex }

            val audio = streams.filter { it.type == MediaStreamType.AUDIO }
            val subtitles =
                streams.filter { stream ->
                    stream.type == MediaStreamType.SUBTITLE &&
                        (!stream.isExternal || stream.index in sidecars)
                }

            val defaultAudioIndex = downloaded.mediaSource?.defaultAudioStreamIndex
            val defaultSubtitleIndex =
                downloaded.mediaSource
                    ?.defaultSubtitleStreamIndex
                    ?.takeIf { index -> subtitles.any { it.index == index } }

            return LocalPlaybackMediaSource(
                itemId = request.itemId,
                mediaSourceId = downloaded.mediaSourceId,
                mediaUri = downloaded.mediaUri,
                runTimeTicks = downloaded.runTimeTicks,
                startPositionTicks = request.startPositionTicks,
                audioTracks = audio.map { it.toTrack(defaultAudioIndex) },
                subtitleTracks = subtitles.map { it.toTrack(defaultSubtitleIndex) },
                externalSubtitles = downloaded.toExternalSubtitles(streams),
                selectedAudioIndex = request.audioStreamIndex ?: defaultAudioIndex,
                // -1 is the caller's explicit "no subtitles"; null lets the item's default stand.
                selectedSubtitleIndex =
                    request.subtitleStreamIndex?.takeIf { it >= 0 }
                        ?: defaultSubtitleIndex.takeIf { request.subtitleStreamIndex == null },
                trickplay = downloaded.trickplay?.toLocalTrickplay(),
            )
        }

        /**
         * The downloaded sidecars, as ExoPlayer sources.
         *
         * The MIME type comes from the stream's codec when the cached blob still has it, and from
         * the file's own extension otherwise — the download pipeline names each sidecar
         * `subtitle.<index>.<language>.<format>`, so the extension *is* the format the server
         * converted to, and a subtitle track surviving a blob that no longer decodes is worth the
         * three lines.
         */
        private fun DownloadedMedia.toExternalSubtitles(streams: List<MediaStream>): List<ExternalSubtitle> =
            subtitles.mapNotNull { sidecar ->
                val stream = streams.firstOrNull { it.index == sidecar.streamIndex }
                val mimeType =
                    CodecHelpers.subtitleMimeType(stream?.codec)
                        ?: CodecHelpers.subtitleMimeType(sidecar.uri.substringAfterLast('.', ""))
                        ?: return@mapNotNull null

                ExternalSubtitle(
                    index = sidecar.streamIndex,
                    url = sidecar.uri,
                    mimeType = mimeType,
                    label = stream?.displayTitle.orEmpty(),
                    language = stream?.language ?: UNDEFINED_LANGUAGE,
                )
            }
    }

/** ISO 639-2 code the Jellyfin server uses when a stream declares no language. */
private const val UNDEFINED_LANGUAGE = "und"

/** The download pipeline's trickplay description, in the player's own vocabulary. */
private fun DownloadedTrickplay.toLocalTrickplay(): LocalTrickplay =
    LocalTrickplay(
        width = width,
        height = height,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        thumbnailCount = thumbnailCount,
        intervalMs = intervalMs,
        tileUris = tileUris,
    )
