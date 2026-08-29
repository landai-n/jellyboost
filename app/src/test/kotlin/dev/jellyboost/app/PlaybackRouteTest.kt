package dev.jellyboost.app

import dev.jellyboost.core.common.model.ItemType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `Routes.Player` has no audio path, so a track that reaches a video row must not be sent to it —
 * the second line of defence behind the resume rows' own video-only queries.
 */
class PlaybackRouteTest {
    @Test
    @DisplayName("a track resumes in the music queue, whichever row it was tapped in")
    fun audioNeverOpensTheVideoPlayer() {
        playbackRouteFor(ItemType.AUDIO) shouldBe PlaybackRoute.MUSIC_QUEUE
    }

    @Test
    @DisplayName("movies and episodes keep the video player")
    fun videoKindsKeepTheirRoute() {
        playbackRouteFor(ItemType.MOVIE) shouldBe PlaybackRoute.VIDEO_PLAYER
        playbackRouteFor(ItemType.EPISODE) shouldBe PlaybackRoute.VIDEO_PLAYER
    }

    @Test
    @DisplayName("an unknown kind takes the video route rather than silently starting a queue")
    fun unknownFallsBackToVideo() {
        // The downloads row reaches this with a wiped cache entry: the player can at least say why
        // it failed, where a one-track music queue would fail silently.
        playbackRouteFor(ItemType.UNKNOWN) shouldBe PlaybackRoute.VIDEO_PLAYER
    }
}
