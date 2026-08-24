package dev.jellyboost.player.resolve

import dev.jellyboost.core.common.UNDEFINED_LANGUAGE
import dev.jellyboost.data.downloads.offline.DownloadedAudio
import dev.jellyboost.data.downloads.offline.DownloadedMedia
import dev.jellyboost.data.downloads.offline.DownloadedMediaProvider
import dev.jellyboost.data.downloads.offline.DownloadedTrickplay
import dev.jellyboost.player.deviceprofile.CodecHelpers
import dev.jellyboost.player.model.ExternalAudio
import dev.jellyboost.player.model.ExternalSubtitle
import dev.jellyboost.player.model.LocalPlaybackMediaSource
import dev.jellyboost.player.model.LocalTrickplay
import dev.jellyboost.player.model.PlaybackTrack
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline counterpart of [PlaybackInfoResolver], answering from what is on the device with **no
 * network call of any kind** — not even to build a URL.
 *
 * A subtitle track is offered whenever **its sidecar is on disk**, whatever the stream's own
 * `isExternal` says: a transcoded download fetches an extracted `.srt` for embedded text subtitles
 * too. An embedded stream with no sidecar survives only a download the server did not re-encode.
 *
 * `/Videos/{id}/stream.mkv` takes exactly one `audioStreamIndex`, so a transcoded download holds
 * exactly one audio track, re-encoded to stereo AAC, while the cached `BaseItemDto` still describes
 * the *source* — hence [DownloadedMedia.isTranscoded] gating anything embedded, and
 * [DownloadedMedia.bakedAudioStreamIndex] naming the track that survived *in the file*. The other
 * languages come back as audio-only `.m4a` sidecars: the baked track stays first, and everything
 * after it keeps the pipeline's ascending-index order, which is the merge-child order the mapping
 * back to Jellyfin stream indices rests on.
 *
 * [LocalPlaybackMediaSource.allAudioTracks] and `allSubtitleTracks` carry the source's **full**
 * stream list instead: online the picker draws that, and a track the file does not hold is reached
 * by streaming the item ([PlaybackResolveRequest.forceRemote]).
 */
@Singleton
internal class LocalPlaybackResolver
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
            val transcoded = downloaded.isTranscoded

            val bakedAudio = streams.bakedAudioStream(downloaded)
            val audioSidecars = downloaded.audioSidecarsOnDisk(streams, bakedAudio)
            val audioSidecarIndices = audioSidecars.mapTo(mutableSetOf()) { it.streamIndex }
            val audio = streams.audioTracksInFile(downloaded, bakedAudio, audioSidecarIndices)
            val subtitles =
                streams.filter { stream ->
                    stream.type == MediaStreamType.SUBTITLE &&
                        // A sidecar is its own file, so the media file's quality is irrelevant to
                        // it; an embedded stream survives only a download that was not re-encoded.
                        (stream.index in sidecars || (!transcoded && !stream.isExternal))
                }

            val defaultAudioIndex =
                when {
                    transcoded -> audio.firstOrNull()?.index
                    else -> downloaded.mediaSource?.defaultAudioStreamIndex
                }
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
                audioTracks = audio.toAudioTracks(defaultAudioIndex, audioSidecarIndices),
                // The side-loaded flag is what routes selection through the exact `external:<index>`
                // id instead of counting positions among container groups a transcode does not have.
                subtitleTracks =
                    subtitles.map { it.toTrack(defaultSubtitleIndex, sideLoaded = it.index in sidecars) },
                externalSubtitles = downloaded.toExternalSubtitles(streams),
                // Order preserved from the provider's ascending-index list: it is the order
                // `ExoPlayerHandle` builds its merge children in, and the only handle selection has
                // on which ExoPlayer audio group is which Jellyfin stream.
                externalAudio = audioSidecars.map { ExternalAudio(index = it.streamIndex, uri = it.uri) },
                // Labelled off the source's defaults rather than the file's, because that is what
                // they describe — the item, not the copy of it on this device.
                allAudioTracks =
                    streams
                        .filter { it.type == MediaStreamType.AUDIO }
                        .map { it.toTrack(downloaded.mediaSource?.defaultAudioStreamIndex) },
                allSubtitleTracks =
                    streams
                        .filter { it.type == MediaStreamType.SUBTITLE }
                        .map {
                            it.toTrack(
                                downloaded.mediaSource?.defaultSubtitleStreamIndex,
                                sideLoaded = it.index in sidecars || it.isExternal,
                            )
                        },
                // A request can carry a track index from a previous session or from a re-resolve;
                // one this file does not hold must not leave the picker pointing at nothing.
                selectedAudioIndex =
                    request.audioStreamIndex?.takeIf { index -> audio.any { it.index == index } }
                        ?: defaultAudioIndex,
                // -1 is the caller's explicit "no subtitles"; null lets the item's default stand.
                selectedSubtitleIndex =
                    request.subtitleStreamIndex
                        ?.takeIf { index -> index >= 0 && subtitles.any { it.index == index } }
                        ?: defaultSubtitleIndex.takeIf { request.subtitleStreamIndex == null },
                trickplay = downloaded.trickplay?.toLocalTrickplay(),
            )
        }

        /**
         * The baked track stays first, and that placement is load-bearing twice over: it is what
         * `defaultAudioIndex` reads, and it is merge child 0, the primary source
         * `ExoPlayerHandle.prepare` builds everything else around.
         */
        private fun List<MediaStream>.audioTracksInFile(
            downloaded: DownloadedMedia,
            bakedAudio: MediaStream?,
            sidecarIndices: Set<Int>,
        ): List<MediaStream> {
            val audio = filter { it.type == MediaStreamType.AUDIO }
            // An external audio stream (an `.mka` beside the video on the server) is its own file,
            // is never in the downloaded container, and no sidecar is planned for it.
            if (!downloaded.isTranscoded) return audio.filter { !it.isExternal }
            return listOfNotNull(bakedAudio) + audio.filter { it.index in sidecarIndices }
        }

        /**
         * The sidecar set *alone* decides the side-loaded flag — deliberately not
         * `MediaStream.isExternal`: the flag means "this track has a merge child", so flagging a
         * baked track whose source stream was external would shift every merge-child ordinal by one.
         */
        private fun List<MediaStream>.toAudioTracks(
            defaultIndex: Int?,
            sidecarIndices: Set<Int>,
        ): List<PlaybackTrack> = map { it.toTrack(defaultIndex, sideLoaded = it.index in sidecarIndices) }

        /**
         * [DownloadedMedia.bakedAudioStreamIndex] is what the download *asked for*, so the picker
         * entry is labelled from the source stream that was encoded — right language, right mix,
         * even though the bytes are now stereo AAC. Two fallbacks behind it: a row written before
         * schema v8 recorded no index and named none in the download either, so the server picked
         * the source's `defaultAudioStreamIndex`; failing both, the first audio stream.
         */
        private fun List<MediaStream>.bakedAudioStream(downloaded: DownloadedMedia): MediaStream? {
            if (!downloaded.isTranscoded) return null
            val audio = filter { it.type == MediaStreamType.AUDIO }
            val baked = downloaded.bakedAudioStreamIndex
            val legacyDefault = downloaded.mediaSource?.defaultAudioStreamIndex
            return audio.firstOrNull { it.index == baked }
                ?: audio.firstOrNull { it.index == legacyDefault }
                ?: audio.firstOrNull()
        }

        /**
         * The pipeline's ascending-index order is passed through untouched: it becomes the
         * merge-child order, and so the mapping back from an ExoPlayer track group to a Jellyfin
         * stream. A sidecar naming the **baked** index is dropped: it would offer the same language
         * twice and shift every later child by one.
         */
        private fun DownloadedMedia.audioSidecarsOnDisk(
            streams: List<MediaStream>,
            bakedAudio: MediaStream?,
        ): List<DownloadedAudio> {
            if (!isTranscoded) return emptyList()
            return audio.filter { sidecar ->
                sidecar.streamIndex != bakedAudio?.index &&
                    streams.any { it.index == sidecar.streamIndex && it.type == MediaStreamType.AUDIO }
            }
        }

        /**
         * The MIME type comes from the stream's codec when the cached blob still has it, and from
         * the file's own extension otherwise: the download pipeline names each sidecar
         * `subtitle.<index>.<language>.<format>`, so the extension *is* the format the server
         * converted to.
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
