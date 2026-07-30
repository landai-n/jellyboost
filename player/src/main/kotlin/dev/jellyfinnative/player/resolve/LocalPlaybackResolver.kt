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
 * A subtitle track is offered whenever **its sidecar is on disk**, whatever the stream's own
 * `isExternal` says. That is the whole of the rule since the sidecar pipeline stopped being limited
 * to genuinely external streams (docs/notes/offline-multitrack-design.md, phase 0): a transcoded
 * download now fetches an extracted `.srt` for each embedded text subtitle too, and a file on disk
 * is a file on disk. Everything else is withheld unless the media file itself can supply it: an
 * embedded stream with no sidecar survives only in a download the server did not re-encode, and a
 * sidecar that failed to download (bitmap formats are never planned, and any optional file is
 * allowed to fail) is not offered at all — a picker entry that cannot do anything is worse than one
 * fewer language.
 *
 * That is also what makes a **transcoded** download honest about audio. `/Videos/{id}/stream.mkv`
 * takes exactly one `audioStreamIndex`, so the file holds exactly one audio track, re-encoded to
 * stereo AAC, and nothing else of the original's audio. The cached `BaseItemDto` still describes the
 * *source*, so taking its stream list at face value offers tracks that are not in the file:
 * selecting one cannot be satisfied, and offline there is no server to re-ask.
 * [DownloadedMedia.isTranscoded] is therefore consulted before anything embedded is offered, and
 * [DownloadedMedia.bakedAudioStreamIndex] names the one audio track that did survive.
 *
 * ### …and what is offered anyway, when there is a server
 * The withheld tracks are not discarded, they are set aside:
 * [LocalPlaybackMediaSource.allAudioTracks] and `allSubtitleTracks` carry the source's **full**
 * stream list, built from the same cached blob by the same mapper. Online, the picker draws that
 * list and a track the file does not hold is reached by streaming the item instead
 * ([PlaybackResolveRequest.forceRemote]); offline the picker draws the playable subset above and the
 * extra list is never looked at. Building both here costs one more pass over a list that is already
 * in memory, and keeps "what is in the file" and "what the item has" from being two different
 * classes' opinions.
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
            val transcoded = downloaded.isTranscoded

            val audio = streams.audioTracksInFile(downloaded)
            val subtitles =
                streams.filter { stream ->
                    stream.type == MediaStreamType.SUBTITLE &&
                        // A sidecar is its own file; the media file's quality is irrelevant to it,
                        // and so is whether the stream it was extracted from was external.
                        // Otherwise: an embedded stream only survives into a file the server did
                        // not re-encode, and an external one with no sidecar is simply not here.
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
                audioTracks = audio.map { it.toTrack(defaultAudioIndex) },
                // A track backed by a sidecar is side-loaded whatever the stream said it was, and
                // that flag is what routes selection through the exact `external:<index>` id
                // instead of counting positions among container groups a transcode does not have.
                subtitleTracks =
                    subtitles.map { it.toTrack(defaultSubtitleIndex, sideLoaded = it.index in sidecars) },
                externalSubtitles = downloaded.toExternalSubtitles(streams),
                // The source's own lists, for the picker to draw while there is a server to ask.
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
         * The audio streams the file on disk actually contains.
         *
         * For an original download that is every audio stream of the source. For a transcoded one it
         * is exactly one, because the endpoint takes exactly one `audioStreamIndex`. Which one is
         * read off the row: [DownloadedMedia.bakedAudioStreamIndex] is what the download *asked
         * for*, so the picker entry is labelled from the source stream that was actually encoded —
         * the right language and the right mix, even though the bytes are now stereo AAC.
         *
         * Two fallbacks behind it, in order, and each is a real case rather than defensiveness:
         * - a row written before schema v8 recorded no index, and the download it describes named
         *   none either, so the server picked the source's `defaultAudioStreamIndex` — assuming
         *   that is exactly right for those rows and wrong for nothing;
         * - failing both, the first audio stream, which is what an item declaring no default gets.
         */
        private fun List<MediaStream>.audioTracksInFile(downloaded: DownloadedMedia): List<MediaStream> {
            val audio = filter { it.type == MediaStreamType.AUDIO }
            if (!downloaded.isTranscoded) return audio

            val baked = downloaded.bakedAudioStreamIndex
            val legacyDefault = downloaded.mediaSource?.defaultAudioStreamIndex
            return listOfNotNull(
                audio.firstOrNull { it.index == baked }
                    ?: audio.firstOrNull { it.index == legacyDefault }
                    ?: audio.firstOrNull(),
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
