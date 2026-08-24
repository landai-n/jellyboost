package dev.jellyboost.core.common.model

/**
 * How much of a file the download pipeline asks the server for.
 *
 * [ORIGINAL] is the default: `/Items/{id}/Download`, the source file untouched. Every other entry
 * asks the server to re-encode on the way out, trading fidelity — and
 * an exact size, and byte-level resume — for a file that fits on the device.
 *
 * It lives in `:core:common` for the same reason [SegmentSkipMode] does: the preference store
 * persists it, the download pipeline acts on it, the settings screen renders it, and `:core:common`
 * is the one module all three already see.
 *
 * ### The ladder
 * The video bitrates deliberately match `PlaybackQuality`'s (20 / 8 / 3 Mbps), so the number a user
 * picks here means the same thing it means in the player's quality picker. Audio is the same for
 * every transcoded step — a stereo AAC track is a rounding error next to the video and stepping it
 * down as well would only make dialogue worse for no measurable saving.
 *
 * @property videoBitRate ceiling for the video track in bits per second, or `null` for [ORIGINAL].
 * @property maxHeight ceiling for the vertical resolution, or `null` for [ORIGINAL]. Paired with the
 *   bitrate rather than left to the server: 3 Mbps of 4 K is worse to watch than 3 Mbps of 720p.
 */
enum class DownloadQuality(
    val videoBitRate: Int?,
    val maxHeight: Int?,
) {
    /** The source file, byte for byte. The default, and the only step that is resumable. */
    ORIGINAL(null, null),

    /** ~20 Mbps, 1080p — visually indistinguishable from most remuxes, at a third of the size. */
    HIGH(BITRATE_HIGH, HEIGHT_1080P),

    /** ~8 Mbps, 1080p — a normal 1080p stream; a film lands around 5 GB. */
    MEDIUM(BITRATE_MEDIUM, HEIGHT_1080P),

    /** ~3 Mbps, 720p — a season of a show on a tablet with no room left. */
    LOW(BITRATE_LOW, HEIGHT_720P),
    ;

    /** `true` when the server has to re-encode, which is every step except [ORIGINAL]. */
    val isTranscoded: Boolean get() = videoBitRate != null

    /**
     * Bits per second the finished file is expected to average.
     *
     * The only use is the size estimate a transcoded download shows instead of a real
     * `Content-Length`; `null` for [ORIGINAL], whose size the server reports exactly.
     */
    val totalBitRate: Int? get() = videoBitRate?.plus(AUDIO_BITRATE)

    companion object {
        /** Bits per second for the AAC track of any transcoded step. */
        const val AUDIO_BITRATE: Int = 192_000

        /** Stereo: a downloaded file is watched on the device's own speakers or on headphones. */
        const val AUDIO_CHANNELS: Int = 2

        /**
         * The container every transcoded download is muxed into — see `DownloadUrlFactory`.
         *
         * **Matroska, and deliberately not `mp4`.** An `mp4` has to know where its `mdat` ends
         * before it can write the `moov` that indexes it, so a server muxing one on the fly emits
         * `ftyp → free → mdat(size 0, "to end of file")` with the `moov` appended at the tail. That
         * file is a valid mp4 to a tool that can read it backwards, and unreadable to Media3:
         * `Mp4Extractor` resolves a zero-sized `mdat` as running to EOF, swallowing the trailing
         * `moov`, and gives up with `ParserException: Loading finished before preparation is
         * complete, contentIsMalformed=true`. Every non-`ORIGINAL` download produced exactly that
         * failure with `mp4`.
         *
         * Matroska has no such ordering constraint — every element declares its own size as it is
         * written, which is why it is the basis of WebM and of every live-streamed mkv — so the
         * bytes are valid at any prefix and complete when the transfer ends. Media3 ships a full
         * `MatroskaExtractor`, and `mkv` is already in this app's own `SUPPORTED_CONTAINER_FORMATS`
         * with h264 among its codecs, so it is a container the device was always going to play.
         */
        const val CONTAINER = "mkv"

        /** Video codec for a transcode; the one format every Android decoder handles. */
        const val VIDEO_CODEC = "h264"

        /** Audio codec for a transcode. */
        const val AUDIO_CODEC = "aac"

        /**
         * The extension of a finished audio sidecar (`DownloadFileType.AUDIO`).
         *
         * **`m4a`, and safely, unlike [CONTAINER].** [CONTAINER] cannot be `mp4` because a *server*
         * muxing one on the fly cannot know where `mdat` ends until it has written it, so it appends
         * a `moov` Media3 never finds. An audio sidecar's `.m4a` is produced the opposite way: fetched
         * as [AUDIO_FETCH_CONTAINER] first, then transmuxed *locally*, after the fetch has finished
         * and every byte is already on disk — a Media3 `Transformer` writes the `moov` up front because
         * it knows the whole file it is about to emit. Same box format either way; only which side
         * assembles it — a live server or an idle local file — decides whether the `moov` lands.
         */
        const val AUDIO_SIDECAR_CONTAINER = "m4a"

        /**
         * The container an extra audio track is fetched in — see the group KDoc below for why a
         * video-shaped request is needed at all.
         */
        const val AUDIO_FETCH_CONTAINER = "mkv"

        /**
         * Bits per second for the junk video track of an audio-sidecar fetch.
         *
         * ### Why a video track is requested at all
         * The obvious route — `/Audio/{id}/stream.{container}?audioStreamIndex=N` — does not honor
         * `audioStreamIndex` on server 10.11: `EncodingHelper.AttachMediaSourceInfo` hard-codes it to
         * `null` for a non-video request, so every audio-only fetch silently returns the source's
         * *default* track regardless of which index was asked for (verified empirically).
         * `/Videos/{id}/stream.{container}` does honor it, so the extra track is fetched through the
         * video endpoint with a video stream present only because the endpoint requires one —
         * [AUDIO_FETCH_VIDEO_BITRATE] through [AUDIO_FETCH_MAX_WIDTH]
         * shape that stream to be as cheap as the server will produce, and it is discarded by a local
         * strip to [AUDIO_SIDECAR_CONTAINER] once the fetch lands. Measured on the dev server: ~54×
         * realtime, ~45 MB of junk video for a 2-hour film at these settings.
         */
        const val AUDIO_FETCH_VIDEO_BITRATE = 50_000

        /** Junk video needs no frame rate to speak of; this is a floor, not a target. */
        const val AUDIO_FETCH_MAX_FRAMERATE = 4f

        /** Junk video ceiling — [AUDIO_FETCH_VIDEO_BITRATE] already makes anything larger pointless. */
        const val AUDIO_FETCH_MAX_HEIGHT = 144

        /** Junk video ceiling, paired with [AUDIO_FETCH_MAX_HEIGHT]. */
        const val AUDIO_FETCH_MAX_WIDTH = 256

        /** The stored entry matching [name], or [ORIGINAL] for anything unrecognised. */
        fun fromNameOrDefault(name: String?): DownloadQuality = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/** 20 Mbps — above a 1080p H.264 remux, so the transcode is mostly a container change. */
private const val BITRATE_HIGH = 20_000_000

/** 8 Mbps — an ordinary 1080p stream. */
private const val BITRATE_MEDIUM = 8_000_000

/** 3 Mbps — 720p; small enough that a season fits where two episodes used to. */
private const val BITRATE_LOW = 3_000_000

private const val HEIGHT_1080P = 1080
private const val HEIGHT_720P = 720
