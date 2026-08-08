package dev.jellyboost.player.music

import dev.jellyboost.data.downloads.offline.DownloadedMedia
import dev.jellyboost.data.downloads.offline.DownloadedMediaProvider
import dev.jellyboost.player.PlayMethod
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MusicStreamResolver].
 *
 * Three things here are only visible on a server dashboard or in airplane mode, so they are pinned
 * hard: a downloaded track never reaches the network, the play method the reports carry is derived
 * from the container we actually asked the server about, and every queue entry gets its **own**
 * play session id — two tracks sharing one would make the second's start report close the first's
 * session.
 */
class MusicStreamResolverTest {
    private val downloads = mockk<DownloadedMediaProvider>()
    private val urls = MusicFixtures.FakeAudioStreamUrlFactory()
    private val resolver = MusicStreamResolver(downloads, urls)

    @Test
    fun `a downloaded track plays from disk, direct, with no server session`() =
        runTest {
            val downloaded = mockk<DownloadedMedia>()
            coEvery { downloaded.mediaUri } returns "file:///storage/music/track.flac"
            coEvery { downloaded.mediaSourceId } returns "source-1"
            coEvery { downloaded.runTimeTicks } returns 1_234L
            coEvery { downloads.get(MusicFixtures.TRACK_IDS[0]) } returns downloaded

            val stream = resolver.resolve(MusicFixtures.track(0)).shouldNotBeNull()

            stream.uri shouldBe "file:///storage/music/track.flac"
            stream.playMethod shouldBe PlayMethod.DIRECT_PLAY
            stream.playSessionId shouldBe null
            stream.isLocal shouldBe true
            // Not one URL was built: offline means offline, right down to the string.
            urls.requests.shouldHaveSize(0)
        }

    @Test
    fun `a container this device can play is reported as direct play`() =
        runTest {
            coEvery { downloads.get(any()) } returns null

            val stream = resolver.resolve(MusicFixtures.track(0, container = "flac")).shouldNotBeNull()

            stream.playMethod shouldBe PlayMethod.DIRECT_PLAY
            stream.isLocal shouldBe false
        }

    @Test
    fun `a container this device cannot play is reported as a transcode`() =
        runTest {
            coEvery { downloads.get(any()) } returns null

            val stream = resolver.resolve(MusicFixtures.track(0, container = "ape")).shouldNotBeNull()

            stream.playMethod shouldBe PlayMethod.TRANSCODE
        }

    @Test
    fun `a track whose container the server did not name is reported as direct play`() =
        runTest {
            coEvery { downloads.get(any()) } returns null

            val stream = resolver.resolve(MusicFixtures.track(0, container = null)).shouldNotBeNull()

            stream.playMethod shouldBe PlayMethod.DIRECT_PLAY
        }

    @Test
    fun `a multi-container item direct-plays when any of its containers is playable`() =
        runTest {
            coEvery { downloads.get(any()) } returns null

            // Jellyfin reports some files with a comma-separated container list ("mov,mp4,m4a").
            val stream = resolver.resolve(MusicFixtures.track(0, container = "mov,mp4,m4a")).shouldNotBeNull()

            stream.playMethod shouldBe PlayMethod.DIRECT_PLAY
        }

    @Test
    fun `the universal URL carries the negotiation terms and its own play session id`() =
        runTest {
            coEvery { downloads.get(any()) } returns null

            val stream = resolver.resolve(MusicFixtures.track(0)).shouldNotBeNull()

            // maxStreamingBitrate is the *direct-play* ceiling (the video path's 120 Mbps number)
            // and audioBitRate the transcode's 384 kbps quality. The old request sent 384 kbps as
            // the ceiling, which forced even direct-capable flac through the encoder — that value
            // was the bug this assertion used to pin, hence the change.
            urls.requests.single() shouldBe
                "${MusicFixtures.TRACK_IDS[0]}|opus+mp3+aac+m4a+flac+webma+webm+wav+ogg|aac|ts|120000000|384000"
            stream.uri shouldContain "PlaySessionId=${stream.playSessionId}"
        }

    @Test
    fun `every queue entry gets its own play session id`() =
        runTest {
            coEvery { downloads.get(any()) } returns null

            val sessions = MusicFixtures.album().map { resolver.resolve(it)?.playSessionId }

            sessions.toSet().shouldHaveSize(sessions.size)
        }

    @Test
    fun `an item whose id is not an id is dropped rather than played`() =
        runTest {
            coEvery { downloads.get(any()) } returns null

            resolver.resolve(MusicFixtures.track(0).copy(id = "not-an-id")) shouldBe null
        }
}
