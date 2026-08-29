package dev.jellyboost.app

import dev.jellyboost.core.common.Routes
import dev.jellyboost.core.common.model.ItemType

/** [Routes.Player] is video-only — it has no audio path at all. */
internal enum class PlaybackRoute {
    VIDEO_PLAYER,
    MUSIC_QUEUE,
}

/**
 * The item decides, not the surface the tap came from: a video row that drifts and holds a track must
 * still resume it in the music queue. An unknown kind takes the video route — the only one that
 * surfaces a failure rather than playing nothing.
 */
internal fun playbackRouteFor(type: ItemType): PlaybackRoute =
    if (type == ItemType.AUDIO) PlaybackRoute.MUSIC_QUEUE else PlaybackRoute.VIDEO_PLAYER
