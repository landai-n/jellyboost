package dev.jellyfinnative.player.resolve

import androidx.media3.common.MimeTypes
import dev.jellyfinnative.data.downloads.offline.DownloadedMediaProvider
import dev.jellyfinnative.data.downloads.offline.DownloadedSubtitle
import dev.jellyfinnative.data.downloads.offline.DownloadedTrickplay
import dev.jellyfinnative.player.PlayMethod
import dev.jellyfinnative.player.PlayerFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LocalPlaybackResolver].
 *
 * The milestone's promise is that the player looks the same offline, and this class is where that
 * is either true or not: the tracks it builds are what the pickers render. So the assertions are
 * about *which* tracks are offered — in particular that an external subtitle with no sidecar on disk
 * is silently withheld rather than offered as a dead entry.
 */
class LocalPlaybackResolverTest {
    private val downloads = mockk<DownloadedMediaProvider>()
    private val resolver = LocalPlaybackResolver(downloads)

    private val request = PlaybackResolveRequest(itemId = PlayerFixtures.ITEM_ID)

    @Test
    fun `an item that is not downloaded resolves to nothing`() =
        runTest {
            coEvery { downloads.get(PlayerFixtures.ITEM_ID) } returns null

            resolver.resolve(request).shouldBeNull()
        }

    @Test
    fun `a downloaded item is always direct play, off the file on disk`() =
        runTest {
            downloaded()

            val source = resolver.resolve(request).shouldNotBeNull()

            source.playMethod shouldBe PlayMethod.DIRECT_PLAY
            source.mediaUri shouldBe PlayerFixtures.LOCAL_MEDIA_URI
            source.runTimeTicks shouldBe PlayerFixtures.RUN_TIME_TICKS
            source.itemId shouldBe PlayerFixtures.ITEM_ID
        }

    @Test
    fun `starts at the position the caller asked to resume from`() =
        runTest {
            downloaded()

            val source =
                resolver.resolve(request.copy(startPositionTicks = 36_000_000_000L)).shouldNotBeNull()

            source.startPositionTicks shouldBe 36_000_000_000L
        }

    // ---- tracks -------------------------------------------------------------------------------

    @Test
    fun `builds the audio picker from the cached media source`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams =
                            listOf(
                                PlayerFixtures.audioStream(index = 1, displayTitle = "English - AC3"),
                                PlayerFixtures.audioStream(index = 2, language = "fra", displayTitle = "French"),
                            ),
                        defaultAudioStreamIndex = 2,
                    ),
            )

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(1, 2)
            source.audioTracks.map { it.label } shouldContainExactly listOf("English - AC3", "French")
            source.audioTracks.single { it.isDefault }.index shouldBe 2
            source.selectedAudioIndex shouldBe 2
        }

    @Test
    fun `offers embedded subtitles even though nothing was downloaded for them`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams = listOf(PlayerFixtures.subtitleStream(index = 3, isExternal = false)),
                    ),
            )

            // ExoPlayer reads them straight out of the container, so they cost nothing to offer.
            val source = resolver.resolve(request).shouldNotBeNull()

            source.subtitleTracks.single().index shouldBe 3
            source.externalSubtitles.shouldBeEmpty()
        }

    @Test
    fun `withholds an external subtitle whose sidecar was never downloaded`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams =
                            listOf(
                                PlayerFixtures.subtitleStream(index = 3, isExternal = true),
                                PlayerFixtures.subtitleStream(index = 4, isExternal = true),
                            ),
                    ),
                subtitles = listOf(DownloadedSubtitle(streamIndex = 3, uri = "file:///downloads/s.3.srt")),
            )

            // A picker entry that cannot do anything is worse than one fewer language.
            val source = resolver.resolve(request).shouldNotBeNull()

            source.subtitleTracks.map { it.index } shouldContainExactly listOf(3)
            source.externalSubtitles.single().url shouldBe "file:///downloads/s.3.srt"
        }

    @Test
    fun `side-loads a downloaded sidecar with the MIME type of its stream`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams =
                            listOf(
                                PlayerFixtures.subtitleStream(
                                    index = 3,
                                    codec = "ass",
                                    language = "fra",
                                    displayTitle = "French (full)",
                                ),
                            ),
                    ),
                subtitles = listOf(DownloadedSubtitle(streamIndex = 3, uri = "file:///downloads/s.3.ass")),
            )

            val subtitle =
                resolver
                    .resolve(request)
                    .shouldNotBeNull()
                    .externalSubtitles
                    .single()

            subtitle.index shouldBe 3
            subtitle.mimeType shouldBe MimeTypes.TEXT_SSA
            subtitle.language shouldBe "fra"
            subtitle.label shouldBe "French (full)"
        }

    @Test
    fun `falls back to the sidecar's own extension when the cached blob is gone`() =
        runTest {
            // The file name the pipeline wrote is `subtitle.<index>.<language>.<format>`, so the
            // extension *is* the format the server converted to.
            downloaded(mediaSource = null, subtitles = listOf(DownloadedSubtitle(3, "file:///d/s.3.eng.srt")))

            val subtitle =
                resolver
                    .resolve(request)
                    .shouldNotBeNull()
                    .externalSubtitles
                    .single()

            subtitle.mimeType shouldBe MimeTypes.APPLICATION_SUBRIP
            subtitle.language shouldBe "und"
        }

    @Test
    fun `honours an explicit track choice over the item's defaults`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams =
                            listOf(
                                PlayerFixtures.audioStream(index = 1),
                                PlayerFixtures.audioStream(index = 2),
                                PlayerFixtures.subtitleStream(index = 3, isExternal = false),
                            ),
                        defaultAudioStreamIndex = 1,
                        defaultSubtitleStreamIndex = 3,
                    ),
            )

            val source =
                resolver
                    .resolve(request.copy(audioStreamIndex = 2, subtitleStreamIndex = -1))
                    .shouldNotBeNull()

            source.selectedAudioIndex shouldBe 2
            // -1 is the explicit "no subtitles"; null would re-select the item's default.
            source.selectedSubtitleIndex.shouldBeNull()
        }

    @Test
    fun `never selects a default subtitle it is not offering`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams = listOf(PlayerFixtures.subtitleStream(index = 3, isExternal = true)),
                        defaultSubtitleStreamIndex = 3,
                    ),
            )

            // The default is an external track whose sidecar is not on disk.
            resolver
                .resolve(request)
                .shouldNotBeNull()
                .selectedSubtitleIndex
                .shouldBeNull()
        }

    @Test
    fun `plays an item whose cached blob can no longer be decoded`() =
        runTest {
            downloaded(mediaSource = null, runTimeTicks = 0L)

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.shouldBeEmpty()
            source.subtitleTracks.shouldBeEmpty()
            source.runTimeTicks shouldBe 0L
        }

    // ---- trickplay ----------------------------------------------------------------------------

    @Test
    fun `carries the downloaded trickplay tiles onto the source`() =
        runTest {
            downloaded(trickplay = PlayerFixtures.downloadedTrickplay())

            val trickplay =
                resolver
                    .resolve(request)
                    .shouldNotBeNull()
                    .trickplay
                    .shouldNotBeNull()

            trickplay.tileUris shouldContainExactly
                listOf("file:///downloads/t.0.jpg", "file:///downloads/t.1.jpg")
            trickplay.intervalMs shouldBe 10_000
            trickplay.thumbnailCount shouldBe 250
        }

    @Test
    fun `addresses a thumbnail by sheet, column and row`() =
        runTest {
            downloaded(trickplay = PlayerFixtures.downloadedTrickplay())
            val trickplay =
                resolver
                    .resolve(request)
                    .shouldNotBeNull()
                    .trickplay
                    .shouldNotBeNull()

            // 10x10 thumbnails per sheet, one every 10 s: 23 min in is thumbnail 138 — sheet 1,
            // cell 38, i.e. row 3 column 8. This is the arithmetic M9's scrubber will draw with.
            val thumbnail = trickplay.tileFor(positionMs = 1_380_000L).shouldNotBeNull()

            thumbnail.uri shouldBe "file:///downloads/t.1.jpg"
            thumbnail.row shouldBe 3
            thumbnail.column shouldBe 8
        }

    @Test
    fun `clamps a position past the last thumbnail instead of running off the sheet`() =
        runTest {
            downloaded(
                trickplay =
                    PlayerFixtures.downloadedTrickplay(thumbnailCount = 150, tileUris = listOf("file:///t.0.jpg")),
            )
            val trickplay =
                resolver
                    .resolve(request)
                    .shouldNotBeNull()
                    .trickplay
                    .shouldNotBeNull()

            // 3_000_000 ms / 10 s = thumbnail 300, clamped to the last one (149) — which lives on a
            // second sheet that was never generated, so there is nothing to draw rather than a
            // read past the end of the only one.
            trickplay.tileFor(positionMs = 3_000_000L).shouldBeNull()
            trickplay.tileFor(positionMs = 0L).shouldNotBeNull().uri shouldBe "file:///t.0.jpg"
        }

    private fun downloaded(
        mediaSource: MediaSourceInfo? = PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true),
        runTimeTicks: Long = PlayerFixtures.RUN_TIME_TICKS,
        subtitles: List<DownloadedSubtitle> = emptyList(),
        trickplay: DownloadedTrickplay? = null,
    ) {
        coEvery { downloads.get(PlayerFixtures.ITEM_ID) } returns
            PlayerFixtures.downloadedMedia(
                mediaSource = mediaSource,
                runTimeTicks = runTimeTicks,
                subtitles = subtitles,
                trickplay = trickplay,
            )
    }
}
