package dev.jellyboost.player

enum class PlayMethod {
    DIRECT_PLAY,
    DIRECT_STREAM,
    TRANSCODE,
    ;

    val requiresServerSession: Boolean get() = this == TRANSCODE
}
