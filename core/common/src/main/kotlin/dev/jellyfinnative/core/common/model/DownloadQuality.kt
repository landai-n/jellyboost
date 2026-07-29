package dev.jellyfinnative.core.common.model

/**
 * How much of a file the download pipeline asks the server for (DECISIONS.md, 2026-07-29,
 * "transcoded downloads ship after all").
 *
 * [ORIGINAL] is the plan's behaviour and the default: `/Items/{id}/Download`, the source file
 * untouched. Every other entry asks the server to re-encode on the way out, trading fidelity — and
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
         * (DECISIONS.md, 2026-07-29).
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
