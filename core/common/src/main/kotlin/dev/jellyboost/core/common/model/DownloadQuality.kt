package dev.jellyboost.core.common.model

/**
 * How much of a file the download pipeline asks the server for.
 *
 * [ORIGINAL] is the source file untouched, and the only step with an exact size and byte-level resume. The
 * video bitrates deliberately match `PlaybackQuality`'s (20 / 8 / 3 Mbps) so a number means the same thing
 * in both pickers; audio is the same for every transcoded step, being a rounding error next to the video.
 *
 * @property maxHeight paired with the bitrate rather than left to the server: 3 Mbps of 4 K is worse to
 *   watch than 3 Mbps of 720p.
 */
enum class DownloadQuality(
    val videoBitRate: Int?,
    val maxHeight: Int?,
) {
    ORIGINAL(null, null),

    HIGH(BITRATE_HIGH, HEIGHT_1080P),

    MEDIUM(BITRATE_MEDIUM, HEIGHT_1080P),

    LOW(BITRATE_LOW, HEIGHT_720P),
    ;

    val isTranscoded: Boolean get() = videoBitRate != null

    /** Drives the size estimate a transcoded download shows; `null` for [ORIGINAL], whose size the server reports. */
    val totalBitRate: Int? get() = videoBitRate?.plus(AUDIO_BITRATE)

    companion object {
        const val AUDIO_BITRATE: Int = 192_000

        /** Stereo: a downloaded file is watched on the device's own speakers or on headphones. */
        const val AUDIO_CHANNELS: Int = 2

        /**
         * **Matroska, and deliberately not `mp4`.** A server muxing an `mp4` on the fly cannot know where
         * `mdat` ends before writing it, so it emits `mdat(size 0)` with the `moov` appended at the tail;
         * `Mp4Extractor` resolves the zero-sized `mdat` to EOF, swallows the `moov` and fails with
         * `ParserException: Loading finished before preparation is complete`. Every non-`ORIGINAL` download
         * produced exactly that. Matroska elements declare their own size, so the bytes are valid at any prefix.
         */
        const val CONTAINER = "mkv"

        /** Video codec for a transcode; the one format every Android decoder handles. */
        const val VIDEO_CODEC = "h264"

        const val AUDIO_CODEC = "aac"

        /**
         * **`m4a`, and safely, unlike [CONTAINER].** A sidecar is fetched as [AUDIO_FETCH_CONTAINER] and
         * transmuxed *locally* after every byte is on disk, so the `Transformer` writes the `moov` up front.
         * Only which side assembles the box — a live server or an idle local file — decides whether it lands.
         */
        const val AUDIO_SIDECAR_CONTAINER = "m4a"

        /** See [AUDIO_FETCH_VIDEO_BITRATE] for why a video-shaped request is needed at all. */
        const val AUDIO_FETCH_CONTAINER = "mkv"

        /**
         * Bits per second for the junk video track of an audio-sidecar fetch.
         *
         * `/Audio/{id}/stream` does **not** honor `audioStreamIndex` on server 10.11 —
         * `EncodingHelper.AttachMediaSourceInfo` hard-codes it to `null` for a non-video request, so every
         * audio-only fetch returns the source's *default* track (verified empirically). `/Videos/{id}/stream`
         * does honor it, so an extra track is fetched through the video endpoint with a video stream present
         * only because the endpoint requires one; it is stripped locally afterwards. Measured on the dev
         * server: ~54x realtime, ~45 MB of junk video for a 2-hour film.
         */
        const val AUDIO_FETCH_VIDEO_BITRATE = 50_000

        /** Junk video needs no frame rate to speak of; this is a floor, not a target. */
        const val AUDIO_FETCH_MAX_FRAMERATE = 4f

        /** Junk video ceiling — [AUDIO_FETCH_VIDEO_BITRATE] already makes anything larger pointless. */
        const val AUDIO_FETCH_MAX_HEIGHT = 144

        const val AUDIO_FETCH_MAX_WIDTH = 256

        fun fromNameOrDefault(name: String?): DownloadQuality = entries.firstOrNull { it.name == name } ?: ORIGINAL
    }
}

/** 20 Mbps — above a 1080p H.264 remux, so the transcode is mostly a container change. */
private const val BITRATE_HIGH = 20_000_000

private const val BITRATE_MEDIUM = 8_000_000

private const val BITRATE_LOW = 3_000_000

private const val HEIGHT_1080P = 1080
private const val HEIGHT_720P = 720
