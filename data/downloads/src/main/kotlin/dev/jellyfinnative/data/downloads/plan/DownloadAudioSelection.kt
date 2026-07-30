package dev.jellyfinnative.data.downloads.plan

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * The one audio track a transcoded download asks the server to encode.
 *
 * `/Videos/{id}/stream.{container}` takes exactly **one** `audioStreamIndex` — there is no
 * repeatable form and no "all tracks" parameter — so a transcode keeps one audio track and drops the
 * rest (docs/notes/offline-multitrack-design.md, "Hard ceiling"). Until this existed the parameter
 * was simply omitted and the server picked for us; the client then had to *guess*, at playback time,
 * which track that had been. Naming it turns a guess into a record.
 *
 * The rule is deliberately the least surprising one available offline: the source's own
 * [org.jellyfin.sdk.model.api.MediaSourceInfo.defaultAudioStreamIndex] — which is what the server
 * would have chosen anyway, so the bytes and the size estimate are unchanged — falling back to the
 * first audio stream when the item declares no default or declares one that names no audio stream.
 * A preferred-audio-language preference is phase 2's business, and it plugs in here.
 *
 * @return `null` when the item has no audio streams at all. The URL then omits the parameter rather
 *   than sending an index that names nothing, and the download row records no pin.
 */
val BaseItemDto.downloadAudioStreamIndex: Int?
    get() {
        val source = mediaSources?.firstOrNull() ?: return null
        val audio = source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }
        if (audio.isEmpty()) return null

        val default = source.defaultAudioStreamIndex
        return audio.firstOrNull { it.index == default }?.index ?: audio.first().index
    }
