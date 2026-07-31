package dev.jellyboost.player

/**
 * How a media source is being delivered. Chosen by `PlaybackInfoResolver` online (M5) and
 * always [DIRECT_PLAY] for downloaded files (M8).
 */
enum class PlayMethod {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE,
    ;

    val requiresServerSession: Boolean get() = this == TRANSCODE
}
