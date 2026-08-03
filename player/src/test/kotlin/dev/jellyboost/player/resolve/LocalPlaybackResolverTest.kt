package dev.jellyboost.player.resolve

import androidx.media3.common.MimeTypes
import dev.jellyboost.core.common.model.DownloadQuality
import dev.jellyboost.data.downloads.offline.DownloadedAudio
import dev.jellyboost.data.downloads.offline.DownloadedMediaProvider
import dev.jellyboost.data.downloads.offline.DownloadedSubtitle
import dev.jellyboost.data.downloads.offline.DownloadedTrickplay
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
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

    // ---- a transcoded download is not the file the cached blob describes -------------------------

    /**
     * The streams of the repro item (Élémentaire, a MEDIUM download): two external SRT sidecars, the
     * video, three AC3 tracks with the French VFF one default, and two embedded French SRTs. The
     * file on disk holds one AAC track and no subtitles at all — verified by reading its Matroska
     * `Tracks` element off the device.
     */
    private fun transcodedFilmStreams() =
        listOf(
            PlayerFixtures.subtitleStream(index = 0, language = "eng", displayTitle = "English"),
            PlayerFixtures.subtitleStream(index = 1, language = "fra", displayTitle = "French"),
            PlayerFixtures.audioStream(index = 3, language = "fra", displayTitle = "French VFF"),
            PlayerFixtures.audioStream(index = 4, language = "fra", displayTitle = "French VFQ"),
            PlayerFixtures.audioStream(index = 5, language = "eng", displayTitle = "English VO"),
            PlayerFixtures.subtitleStream(index = 6, isExternal = false, displayTitle = "French forced"),
            PlayerFixtures.subtitleStream(index = 7, isExternal = false, displayTitle = "French full"),
        )

    private fun transcodedFilm(
        defaultAudioStreamIndex: Int? = 3,
        defaultSubtitleStreamIndex: Int? = null,
        bakedAudioStreamIndex: Int? = null,
        sidecars: List<Int> = listOf(0, 1),
        audioSidecars: List<Int> = emptyList(),
    ) = downloaded(
        mediaSource =
            PlayerFixtures.mediaSourceInfo(
                mediaStreams = transcodedFilmStreams(),
                defaultAudioStreamIndex = defaultAudioStreamIndex,
                defaultSubtitleStreamIndex = defaultSubtitleStreamIndex,
            ),
        quality = DownloadQuality.MEDIUM,
        bakedAudioStreamIndex = bakedAudioStreamIndex,
        subtitles = sidecars.map { DownloadedSubtitle(streamIndex = it, uri = "file:///downloads/s.$it.srt") },
        audio = audioSidecars.map { DownloadedAudio(streamIndex = it, uri = "file:///downloads/audio.$it.m4a") },
    )

    @Test
    fun `a transcoded download offers only the one audio track the file holds`() =
        runTest {
            // No pin on the row — a download written before schema v8. That request named no
            // audioStreamIndex either, so the server encoded the source's default and dropped the
            // other two, and assuming that default is exactly right for these rows.
            transcodedFilm()

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(3)
            source.audioTracks.single().label shouldBe "French VFF"
            source.audioTracks.single().isDefault shouldBe true
            source.selectedAudioIndex shouldBe 3
        }

    @Test
    fun `a transcoded download offers the audio track its row recorded, not the source's default`() =
        runTest {
            // The download asked for stream 5, so stream 5 is what is in the file — whatever the
            // source calls default. This is the assumption the M10 track fix flagged, now a record.
            transcodedFilm(defaultAudioStreamIndex = 3, bakedAudioStreamIndex = 5)

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(5)
            source.audioTracks.single().label shouldBe "English VO"
            source.selectedAudioIndex shouldBe 5
        }

    @Test
    fun `a pin naming a stream the blob no longer has falls back rather than offering nothing`() =
        runTest {
            // The server renumbered its streams between the download and this play; an empty audio
            // picker on a film that plainly has sound is the worse of the two answers.
            transcodedFilm(defaultAudioStreamIndex = 3, bakedAudioStreamIndex = 99)

            resolver
                .resolve(request)
                .shouldNotBeNull()
                .audioTracks
                .map { it.index } shouldContainExactly listOf(3)
        }

    @Test
    fun `a transcoded download falls back to the first audio stream when none is default`() =
        runTest {
            transcodedFilm(defaultAudioStreamIndex = null)

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(3)
            source.selectedAudioIndex shouldBe 3
        }

    @Test
    fun `a transcoded download offers no embedded subtitles, because the file has none`() =
        runTest {
            transcodedFilm()

            val source = resolver.resolve(request).shouldNotBeNull()

            // Only the two sidecars, which are their own files on disk and unaffected by the encode.
            source.subtitleTracks.map { it.index } shouldContainExactly listOf(0, 1)
            source.externalSubtitles.map { it.index } shouldContainExactly listOf(0, 1)
        }

    @Test
    fun `a transcoded download never preselects an embedded subtitle the server dropped`() =
        runTest {
            transcodedFilm(defaultSubtitleStreamIndex = 6)

            resolver
                .resolve(request)
                .shouldNotBeNull()
                .selectedSubtitleIndex
                .shouldBeNull()
        }

    // ---- embedded subtitles that came back as sidecars (phase 0) ---------------------------------

    @Test
    fun `a transcoded download offers an embedded subtitle whose sidecar is on disk`() =
        runTest {
            // Élémentaire's streams 6 and 7 are embedded French SRTs. The transcode dropped them
            // from the file, and the download fetched each as its own `.srt` — so they are back.
            transcodedFilm(sidecars = listOf(0, 1, 6, 7))

            val source = resolver.resolve(request).shouldNotBeNull()

            source.subtitleTracks.map { it.index } shouldContainExactly listOf(0, 1, 6, 7)
            source.subtitleTracks.single { it.index == 6 }.label shouldBe "French forced"
            source.externalSubtitles.map { it.index } shouldContainExactly listOf(0, 1, 6, 7)
        }

    @Test
    fun `a sidecar-backed embedded track is marked side-loaded, so selection matches it by id`() =
        runTest {
            transcodedFilm(sidecars = listOf(6))

            val track =
                resolver
                    .resolve(request)
                    .shouldNotBeNull()
                    .subtitleTracks
                    .single { it.index == 6 }

            // The stream itself says `isExternal = false`. What the flag has to describe here is how
            // the track reaches ExoPlayer, because `TrackSelectionController` counts anything not
            // flagged among the *container's* text groups — of which a transcode has none.
            track.isExternal shouldBe true
        }

    @Test
    fun `an embedded subtitle with no sidecar is still withheld from a transcoded download`() =
        runTest {
            transcodedFilm(sidecars = listOf(6))

            // Stream 7 was never fetched — an older row, or an optional file that failed. Offering
            // it would be a picker entry that cannot be satisfied and no server to re-ask.
            resolver
                .resolve(request)
                .shouldNotBeNull()
                .subtitleTracks
                .map { it.index } shouldContainExactly
                listOf(6)
        }

    @Test
    fun `a default subtitle the sidecar restored is preselected again`() =
        runTest {
            transcodedFilm(defaultSubtitleStreamIndex = 6, sidecars = listOf(6))

            resolver.resolve(request).shouldNotBeNull().selectedSubtitleIndex shouldBe 6
        }

    @Test
    fun `an original download's embedded track is not marked side-loaded`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(mediaStreams = transcodedFilmStreams(), defaultAudioStreamIndex = 3),
                quality = DownloadQuality.ORIGINAL,
            )

            // It plays out of the container, and the planner deliberately fetches no sidecar for it.
            resolver
                .resolve(request)
                .shouldNotBeNull()
                .subtitleTracks
                .single { it.index == 6 }
                .isExternal shouldBe false
        }

    @Test
    fun `an original download still offers every track of the source file`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams = transcodedFilmStreams(),
                        defaultAudioStreamIndex = 3,
                    ),
                quality = DownloadQuality.ORIGINAL,
                subtitles = listOf(DownloadedSubtitle(streamIndex = 0, uri = "file:///downloads/s.0.eng.srt")),
            )

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(3, 4, 5)
            // Index 1's sidecar is missing, so it is withheld; the embedded pair is untouched.
            source.subtitleTracks.map { it.index } shouldContainExactly listOf(0, 6, 7)
        }

    // ---- audio tracks that came back as sidecars (phase 2) ---------------------------------------

    @Test
    fun `a transcoded download offers every audio language its sidecars restored`() =
        runTest {
            // Élémentaire again: the encode baked in the French VFF and the download fetched the
            // other two as their own `.m4a` files.
            transcodedFilm(bakedAudioStreamIndex = 3, audioSidecars = listOf(4, 5))

            val source = resolver.resolve(request).shouldNotBeNull()

            // The baked track is first, and stays the default — it is merge child 0.
            source.audioTracks.map { it.index } shouldContainExactly listOf(3, 4, 5)
            source.audioTracks.map { it.label } shouldContainExactly
                listOf("French VFF", "French VFQ", "English VO")
            source.audioTracks.single { it.isDefault }.index shouldBe 3
            source.selectedAudioIndex shouldBe 3
        }

    @Test
    fun `a sidecar-backed audio track is marked side-loaded and the baked one is not`() =
        runTest {
            transcodedFilm(bakedAudioStreamIndex = 3, audioSidecars = listOf(4, 5))

            val source = resolver.resolve(request).shouldNotBeNull()

            // What routes selection through the merge-child order rather than a position among the
            // container's own audio groups, of which a transcode has exactly one.
            source.audioTracks.single { it.index == 3 }.isExternal shouldBe false
            source.audioTracks.filter { it.isExternal }.map { it.index } shouldContainExactly listOf(4, 5)
        }

    @Test
    fun `carries the audio sidecars in the order they become merge children`() =
        runTest {
            transcodedFilm(bakedAudioStreamIndex = 3, audioSidecars = listOf(4, 5))

            val source = resolver.resolve(request).shouldNotBeNull()

            // Element i is merge child i+1; re-ordering this list plays the wrong language.
            source.externalAudio.map { it.index } shouldContainExactly listOf(4, 5)
            source.externalAudio.map { it.uri } shouldContainExactly
                listOf("file:///downloads/audio.4.m4a", "file:///downloads/audio.5.m4a")
        }

    @Test
    fun `skips an audio sidecar that duplicates the baked track`() =
        runTest {
            // The pin says 3 and a row claims a sidecar for 3 too. Offering it would list French VFF
            // twice and push every later child one position out of step with its track.
            transcodedFilm(bakedAudioStreamIndex = 3, audioSidecars = listOf(3, 5))

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(3, 5)
            source.externalAudio.map { it.index } shouldContainExactly listOf(5)
        }

    @Test
    fun `skips an audio sidecar for a stream the cached blob no longer describes`() =
        runTest {
            // The server renumbered its streams; there is nothing to label the picker entry with.
            transcodedFilm(bakedAudioStreamIndex = 3, audioSidecars = listOf(5, 99))

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(3, 5)
            source.externalAudio.map { it.index } shouldContainExactly listOf(5)
        }

    @Test
    fun `restores a selection naming a sidecar audio track`() =
        runTest {
            transcodedFilm(bakedAudioStreamIndex = 3, audioSidecars = listOf(4, 5))

            // A previous session left the English VO selected; it is now a track this file plays,
            // so it must survive rather than fall back to the baked default.
            val source = resolver.resolve(request.copy(audioStreamIndex = 5)).shouldNotBeNull()

            source.selectedAudioIndex shouldBe 5
        }

    @Test
    fun `an original download has no audio sidecars, whatever rows it carries`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(mediaStreams = transcodedFilmStreams(), defaultAudioStreamIndex = 3),
                quality = DownloadQuality.ORIGINAL,
                audio = listOf(DownloadedAudio(streamIndex = 5, uri = "file:///downloads/audio.5.m4a")),
            )

            val source = resolver.resolve(request).shouldNotBeNull()

            // Every track is already in the file; merging a sidecar in would offer one of them twice.
            source.audioTracks.map { it.index } shouldContainExactly listOf(3, 4, 5)
            source.audioTracks.none { it.isExternal } shouldBe true
            source.externalAudio.shouldBeEmpty()
        }

    @Test
    fun `an original download withholds an external audio stream — its file is not on this device`() =
        runTest {
            // The DL-08 case 1: an `.mka` beside the video is listed in the source's streams with
            // isExternal = true, but the download fetched only the container — offering the track
            // routes selection to a server that offline playback exists to do without.
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams =
                            listOf(
                                PlayerFixtures.audioStream(index = 1, displayTitle = "English - AC3"),
                                PlayerFixtures.audioStream(
                                    index = 2,
                                    language = "jpn",
                                    displayTitle = "Japanese (external)",
                                    isExternal = true,
                                ),
                            ),
                        defaultAudioStreamIndex = 1,
                    ),
                quality = DownloadQuality.ORIGINAL,
            )

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(1)
            source.audioTracks.none { it.isExternal } shouldBe true
            // The full source list still names it, for the picker to draw while online.
            source.allAudioTracks.map { it.index } shouldContainExactly listOf(1, 2)
        }

    @Test
    fun `a baked track that happens to be external is not counted as a merge child`() =
        runTest {
            // The DL-08 case 2: the encode baked in a track whose *source* stream was external.
            // Flagging it side-loaded made `TrackSelectionController` count it among the merge
            // children — every ordinal shifted by one, so the baked language played the first
            // sidecar's file and the last sidecar pointed at a child that does not exist.
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(
                        mediaStreams =
                            listOf(
                                PlayerFixtures.audioStream(
                                    index = 3,
                                    displayTitle = "French VFF (external)",
                                    isExternal = true,
                                ),
                                PlayerFixtures.audioStream(index = 4, language = "fra", displayTitle = "French VFQ"),
                            ),
                        defaultAudioStreamIndex = 3,
                    ),
                quality = DownloadQuality.MEDIUM,
                bakedAudioStreamIndex = 3,
                audio = listOf(DownloadedAudio(streamIndex = 4, uri = "file:///downloads/audio.4.m4a")),
            )

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(3, 4)
            // Only the sidecar-backed track is a merge child; the baked one is child 0 unflagged.
            source.audioTracks.single { it.index == 3 }.isExternal shouldBe false
            source.audioTracks.single { it.index == 4 }.isExternal shouldBe true
            source.externalAudio.map { it.index } shouldContainExactly listOf(4)
        }

    @Test
    fun `a transcoded download with no audio sidecars is untouched`() =
        runTest {
            transcodedFilm(bakedAudioStreamIndex = 5)

            val source = resolver.resolve(request).shouldNotBeNull()

            source.audioTracks.map { it.index } shouldContainExactly listOf(5)
            source.externalAudio.shouldBeEmpty()
        }

    // ---- the source's own lists, for the connectivity-aware picker -------------------------------

    @Test
    fun `carries the source's full track lists alongside the ones the file can play`() =
        runTest {
            transcodedFilm(sidecars = listOf(0, 1))

            val source = resolver.resolve(request).shouldNotBeNull()

            // What the picker draws online: everything the item has, whatever the encode dropped.
            source.allAudioTracks.map { it.index } shouldContainExactly listOf(3, 4, 5)
            source.allSubtitleTracks.map { it.index } shouldContainExactly listOf(0, 1, 6, 7)
            // What it draws offline, unchanged: only what the file and its sidecars hold.
            source.audioTracks.map { it.index } shouldContainExactly listOf(3)
            source.subtitleTracks.map { it.index } shouldContainExactly listOf(0, 1)
        }

    @Test
    fun `labels the full audio list from the source's own default, not the baked track`() =
        runTest {
            transcodedFilm(defaultAudioStreamIndex = 3, bakedAudioStreamIndex = 5)

            val source = resolver.resolve(request).shouldNotBeNull()

            // The extra list describes the *item*; the file's own idea of default is the other one.
            source.allAudioTracks.single { it.isDefault }.index shouldBe 3
            source.allAudioTracks.map { it.label } shouldContainExactly
                listOf("French VFF", "French VFQ", "English VO")
            source.audioTracks.single().index shouldBe 5
        }

    @Test
    fun `an original download's two lists are the same list`() =
        runTest {
            downloaded(
                mediaSource =
                    PlayerFixtures.mediaSourceInfo(mediaStreams = transcodedFilmStreams(), defaultAudioStreamIndex = 3),
                quality = DownloadQuality.ORIGINAL,
                subtitles = listOf(DownloadedSubtitle(streamIndex = 0, uri = "file:///downloads/s.0.eng.srt")),
            )

            val source = resolver.resolve(request).shouldNotBeNull()

            // Nothing was dropped except the one sidecar that is missing, so being online adds
            // exactly that one entry and the picker is otherwise identical either way.
            source.allAudioTracks.map { it.index } shouldContainExactly source.audioTracks.map { it.index }
            source.allSubtitleTracks.map { it.index } shouldContainExactly listOf(0, 1, 6, 7)
            source.subtitleTracks.map { it.index } shouldContainExactly listOf(0, 6, 7)
        }

    @Test
    fun `an item whose cached blob is gone offers nothing to stream either`() =
        runTest {
            downloaded(mediaSource = null)

            val source = resolver.resolve(request).shouldNotBeNull()

            // There is no stream list to draw an online picker from; it plays anyway.
            source.allAudioTracks.shouldBeEmpty()
            source.allSubtitleTracks.shouldBeEmpty()
        }

    @Test
    fun `drops a requested track the downloaded file does not hold`() =
        runTest {
            transcodedFilm()

            // A stale selection carried in from a previous session or a re-resolve.
            val source =
                resolver
                    .resolve(request.copy(audioStreamIndex = 5, subtitleStreamIndex = 7))
                    .shouldNotBeNull()

            source.selectedAudioIndex shouldBe 3
            source.selectedSubtitleIndex.shouldBeNull()
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

    @Suppress("LongParameterList")
    private fun downloaded(
        mediaSource: MediaSourceInfo? = PlayerFixtures.mediaSourceInfo(supportsDirectPlay = true),
        runTimeTicks: Long = PlayerFixtures.RUN_TIME_TICKS,
        quality: DownloadQuality = DownloadQuality.ORIGINAL,
        bakedAudioStreamIndex: Int? = null,
        subtitles: List<DownloadedSubtitle> = emptyList(),
        audio: List<DownloadedAudio> = emptyList(),
        trickplay: DownloadedTrickplay? = null,
    ) {
        coEvery { downloads.get(PlayerFixtures.ITEM_ID) } returns
            PlayerFixtures.downloadedMedia(
                mediaSource = mediaSource,
                runTimeTicks = runTimeTicks,
                quality = quality,
                bakedAudioStreamIndex = bakedAudioStreamIndex,
                subtitles = subtitles,
                audio = audio,
                trickplay = trickplay,
            )
    }
}
