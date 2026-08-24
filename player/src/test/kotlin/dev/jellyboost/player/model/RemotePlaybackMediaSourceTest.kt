package dev.jellyboost.player.model

import dev.jellyboost.player.PlayerFixtures
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * `RemotePlaybackMediaSource.toString()` prints no URL.
 *
 * The token in a transcoding URL is a live credential, and the generated data-class `toString()`
 * would put it in a log line or an exception message the first time anyone printed a whole source.
 * The assertions are on the *token*, not on the wording, so the redaction cannot be defeated by a
 * later rewrite of the format string.
 */
class RemotePlaybackMediaSourceTest {
    @Test
    fun `the access token in a transcoding URL never reaches toString`() {
        val printed =
            PlayerFixtures
                .remoteSource(
                    transcodingUrl = "/videos/1/master.m3u8?ApiKey=$TOKEN&MediaSourceId=1",
                ).toString()

        printed shouldNotContain TOKEN
        printed shouldContain "transcodingUrl=<redacted>"
    }

    @Test
    fun `a server-supplied path and the subtitle delivery URLs are held back too`() {
        val printed =
            PlayerFixtures
                .remoteSource(
                    path = "http://server/media/movie.mkv?api_key=$TOKEN",
                    externalSubtitles =
                        listOf(
                            ExternalSubtitle(
                                index = 3,
                                url = "/videos/1/Subtitles/3/0/Stream.vtt?api_key=$TOKEN",
                                mimeType = "text/vtt",
                                label = "English",
                                language = "eng",
                            ),
                        ),
                ).toString()

        printed shouldNotContain TOKEN
        printed shouldContain "path=<redacted>"
        printed shouldContain "externalSubtitles=1"
    }

    @Test
    fun `absence stays readable, because it is a fact and not a secret`() {
        val printed = PlayerFixtures.remoteSource(path = null, transcodingUrl = null).toString()

        printed shouldContain "transcodingUrl=null"
        printed shouldContain "path=null"
    }

    @Test
    fun `the fields a log is actually read for still print`() {
        val printed = PlayerFixtures.remoteSource().toString()

        printed shouldContain "itemId=${PlayerFixtures.ITEM_ID}"
        printed shouldContain "playSessionId=${PlayerFixtures.PLAY_SESSION_ID}"
        printed shouldContain "container=mkv"
    }

    private companion object {
        const val TOKEN = "1f4c9b2ea77e4a0f8d3c6b5a09e71d24"
    }
}
