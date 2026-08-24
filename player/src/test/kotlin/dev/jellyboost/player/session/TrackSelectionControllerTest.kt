package dev.jellyboost.player.session

import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import dev.jellyboost.player.PlayMethod
import dev.jellyboost.player.PlayerFixtures
import dev.jellyboost.player.model.PlaybackTrack
import dev.jellyboost.player.model.externalSubtitleTrackId
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Media3 gives a side-loaded subtitle's `Format` the id `MediaItem.SubtitleConfiguration` set it
 * ("external:<index>"), but side-loading wraps it in a `MergingMediaSource`, and
 * `MergingMediaPeriod.onPrepared` re-ids every format as `childIndex + ":" + format.id` before
 * publishing — so the player actually reports `1:external:1`, never `external:1`. Both shapes are
 * exercised here: merged because it's what a device produces, bare because nothing should depend
 * on the wrapping.
 */
class TrackSelectionControllerTest {
    private val player = mockk<Player>(relaxed = true)
    private val applied = slot<TrackSelectionParameters>()

    // `TrackGroup` normalises its id through `android.text.TextUtils`, a throwing stub in a local
    // unit test — stub it so these stay real Media3 track groups, not hand-rolled fakes.
    @BeforeEach
    fun stubAndroidTextUtils() {
        mockkStatic(TextUtils::class)
        every { TextUtils.isEmpty(any()) } answers { (firstArg<CharSequence?>()?.length ?: 0) == 0 }
    }

    @AfterEach
    fun unstubAndroidTextUtils() {
        unmockkStatic(TextUtils::class)
    }

    private fun controller(vararg groups: Tracks.Group): TrackSelectionController {
        every { player.currentTracks } returns Tracks(groups.toList())
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT
        every { player.trackSelectionParameters = capture(applied) } returns Unit
        return TrackSelectionController(player)
    }

    // ---- side-loaded subtitles ------------------------------------------------------------------

    @Test
    fun `selects a side-loaded subtitle by the track id the media item gave it`() {
        val english = textGroup(externalSubtitleTrackId(0))
        val french = textGroup(externalSubtitleTrackId(1))
        val controller = controller(english, french)

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(
                        PlaybackTrack(index = 0, label = "English", language = "eng", codec = "srt", isExternal = true),
                        PlaybackTrack(index = 1, label = "French", language = "fra", codec = "srt", isExternal = true),
                    ),
            )

        controller.selectSubtitle(source, jellyfinIndex = 1) shouldBe true

        // Positional matching would have picked the English group; the id is what makes it exact.
        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe externalSubtitleTrackId(1)
    }

    @Test
    fun `refuses a subtitle no side-loaded and no embedded track can supply`() {
        val controller = controller(textGroup(externalSubtitleTrackId(0)))

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(
                        PlaybackTrack(index = 0, label = "English", language = "eng", codec = "srt", isExternal = true),
                        PlaybackTrack(index = 7, label = "French full", language = "fra", codec = "srt"),
                    ),
            )

        controller.selectSubtitle(source, jellyfinIndex = 7) shouldBe false
    }

    @Test
    fun `selects an embedded subtitle the download side-loaded as a sidecar`() {
        val controller = controller(textGroup(externalSubtitleTrackId(7)))

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(
                        PlaybackTrack(
                            index = 7,
                            label = "French full",
                            language = "fra",
                            codec = "srt",
                            // What `LocalPlaybackResolver` sets for a track its sidecar backs,
                            // whatever the stream itself claimed to be.
                            isExternal = true,
                        ),
                    ),
            )

        controller.selectSubtitle(source, jellyfinIndex = 7) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe externalSubtitleTrackId(7)
    }

    @Test
    fun `a sidecar-backed track is never counted among the embedded ones`() {
        // Counting it as embedded would shift the positional count for stream 6 onto the wrong group.
        val controller =
            controller(
                textGroup(externalSubtitleTrackId(7)),
                textGroup(id = "embedded-fra"),
            )

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(
                        PlaybackTrack(
                            index = 7,
                            label = "French full",
                            language = "fra",
                            codec = "srt",
                            isExternal = true,
                        ),
                        PlaybackTrack(index = 6, label = "French forced", language = "fra", codec = "srt"),
                    ),
            )

        controller.selectSubtitle(source, jellyfinIndex = 6) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe "embedded-fra"
    }

    @Test
    fun `matches an embedded subtitle by its position among the embedded ones`() {
        // The side-loaded group is in the same list and must not be counted.
        val controller =
            controller(
                textGroup(externalSubtitleTrackId(0)),
                textGroup(id = "embedded-fra"),
                textGroup(id = "embedded-deu"),
            )

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(
                        PlaybackTrack(index = 0, label = "English", language = "eng", codec = "srt", isExternal = true),
                        PlaybackTrack(index = 6, label = "French", language = "fra", codec = "srt"),
                        PlaybackTrack(index = 7, label = "German", language = "deu", codec = "srt"),
                    ),
            )

        controller.selectSubtitle(source, jellyfinIndex = 7) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe "embedded-deu"
    }

    // ---- the ids Media3 actually reports once a subtitle is side-loaded --------------------------

    @Test
    fun `selects a sidecar subtitle through the id a merged source reports`() {
        val forced = textGroup(mergedTrackId(1, externalSubtitleTrackId(1)))
        val full = textGroup(mergedTrackId(2, externalSubtitleTrackId(2)))
        val controller = controller(forced, full)

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(
                        sidecarTrack(index = 1, label = "French forced"),
                        sidecarTrack(index = 2, label = "French"),
                    ),
            )

        controller.selectSubtitle(source, jellyfinIndex = 2) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe mergedTrackId(2, externalSubtitleTrackId(2))
    }

    @Test
    fun `a merged sidecar group is still excluded from the embedded count`() {
        // Counting the side-loaded group as embedded would put stream 6 on the sidecar's group — wrong language.
        val controller =
            controller(
                textGroup(mergedTrackId(1, externalSubtitleTrackId(7))),
                textGroup(mergedTrackId(0, "embedded-fra")),
            )

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(
                        sidecarTrack(index = 7, label = "French full"),
                        PlaybackTrack(index = 6, label = "French forced", language = "fra", codec = "srt"),
                    ),
            )

        controller.selectSubtitle(source, jellyfinIndex = 6) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe mergedTrackId(0, "embedded-fra")
    }

    @Test
    fun `a container track whose merged id merely looks numeric is not read as one of ours`() {
        // Matroska names its tracks "1", "2", …; merged they become "0:1", "0:2". Nothing in them is
        // a Jellyfin stream index, and treating one as such would select an arbitrary language.
        val controller = controller(textGroup(mergedTrackId(0, "2")))

        val source =
            PlayerFixtures.localSource(
                subtitleTracks =
                    listOf(sidecarTrack(index = 2, label = "French")),
            )

        controller.selectSubtitle(source, jellyfinIndex = 2) shouldBe false
    }

    @Test
    fun `turning subtitles off disables the text renderer rather than failing`() {
        val controller = controller(textGroup(externalSubtitleTrackId(0)))

        controller.selectSubtitle(PlayerFixtures.localSource(), jellyfinIndex = null) shouldBe true

        applied.captured.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) shouldBe true
    }

    // ---- a transcode's in-manifest HLS subtitle renditions ---------------------------------------

    @Test
    fun `selects an HLS rendition by its position in the master playlist`() {
        // Media3 ids a rendition "<groupId>:<NAME>" — nothing about that is a Jellyfin index, so
        // position (server writes one rendition per text stream, in Jellyfin stream order) is all there is.
        val controller =
            controller(
                textGroup(id = "subs:English"),
                textGroup(id = "subs:French"),
                textGroup(id = "subs:German"),
            )

        val controllerSource =
            PlayerFixtures.remoteSource(
                playMethod = PlayMethod.TRANSCODE,
                subtitleTracks =
                    listOf(
                        renditionTrack(index = 2, label = "English", language = "eng"),
                        renditionTrack(index = 3, label = "French", language = "fra"),
                        renditionTrack(index = 4, label = "German", language = "deu"),
                    ),
            )

        controller.selectSubtitle(controllerSource, jellyfinIndex = 4) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe "subs:German"
    }

    @Test
    fun `selecting a rendition re-enables the text renderer that off had disabled`() {
        val controller = controller(textGroup(id = "subs:English"))

        val controllerSource =
            PlayerFixtures.remoteSource(
                playMethod = PlayMethod.TRANSCODE,
                subtitleTracks = listOf(renditionTrack(index = 2, label = "English", language = "eng")),
            )

        controller.selectSubtitle(controllerSource, jellyfinIndex = 2) shouldBe true

        applied.captured.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) shouldBe false
    }

    @Test
    fun `a burned-in subtitle takes no place in the rendition count`() {
        // Jellyfin builds no rendition for a burned-in stream; counting it would push German onto
        // French's rendition instead.
        val controller =
            controller(
                textGroup(id = "subs:English"),
                textGroup(id = "subs:German"),
            )

        val controllerSource =
            PlayerFixtures.remoteSource(
                playMethod = PlayMethod.TRANSCODE,
                subtitleTracks =
                    listOf(
                        renditionTrack(index = 2, label = "English", language = "eng"),
                        PlaybackTrack(
                            index = 3,
                            label = "French PGS",
                            language = "fra",
                            codec = "pgssub",
                            isExternal = true,
                        ),
                        renditionTrack(index = 4, label = "German", language = "deu"),
                    ),
            )

        controller.selectSubtitle(controllerSource, jellyfinIndex = 4) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe "subs:German"

        controller.selectSubtitle(controllerSource, jellyfinIndex = 3) shouldBe false
    }

    // ---- the slate a new media item starts from ------------------------------------------------

    @Test
    fun `reset clears audio and text overrides and re-enables text`() {
        // The player is process-wide, so the previous item's overrides would still be in force
        // when the next item is prepared.
        val audio = audioGroup("audio-fra")
        val text = textGroup(externalSubtitleTrackId(1))
        every { player.currentTracks } returns Tracks(listOf(audio, text))
        every { player.trackSelectionParameters } returns
            TrackSelectionParameters.DEFAULT
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(audio.mediaTrackGroup, 0))
                .setOverrideForType(TrackSelectionOverride(text.mediaTrackGroup, 0))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        every { player.trackSelectionParameters = capture(applied) } returns Unit

        TrackSelectionController(player).reset()

        applied.captured.overrides.isEmpty() shouldBe true
        // Left disabled, a preselected subtitle on the next item would render nothing at all.
        applied.captured.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) shouldBe false
    }

    // ---- audio ----------------------------------------------------------------------------------

    @Test
    fun `selects an audio track by its position among the file's audio streams`() {
        val controller = controller(audioGroup("audio-eng"), audioGroup("audio-fra"))

        val source =
            PlayerFixtures.localSource(
                audioTracks =
                    listOf(
                        PlaybackTrack(index = 3, label = "English", language = "eng", codec = "ac3"),
                        PlaybackTrack(index = 4, label = "French", language = "fra", codec = "ac3"),
                    ),
            )

        controller.selectAudio(source, jellyfinIndex = 4) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .getFormat(0)
            .id shouldBe "audio-fra"
    }

    @Test
    fun `refuses an audio track the file does not contain`() {
        val controller = controller(audioGroup("audio-fra"))

        val source =
            PlayerFixtures.localSource(
                audioTracks =
                    listOf(
                        PlaybackTrack(index = 3, label = "French VFF", language = "fra", codec = "ac3"),
                        PlaybackTrack(index = 5, label = "English VO", language = "eng", codec = "ac3"),
                    ),
            )

        controller.selectAudio(source, jellyfinIndex = 5) shouldBe false
    }

    // ---- audio sidecars, matched by merge-child order (phase 2) ---------------------------------

    @Test
    fun `selects a sidecar audio track by the merge child it was built as`() {
        // `MediaItem` cannot name an audio source's tracks, so the child prefix `MergingMediaPeriod`
        // puts on the group id is the whole of the mapping.
        val controller =
            controller(
                mergedAudioGroup("0:1"),
                mergedAudioGroup("1:0"),
                mergedAudioGroup("2:0"),
            )

        controller.selectAudio(sidecarFilm(), jellyfinIndex = 5) shouldBe true

        // Stream 5 is the *second* external track, so it is child 2 — not child 1, which a plain
        // positional count over the audio groups would have picked.
        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .id shouldBe "2:0"
    }

    @Test
    fun `selects the first sidecar audio track`() {
        val controller =
            controller(
                mergedAudioGroup("0:1"),
                mergedAudioGroup("1:0"),
                mergedAudioGroup("2:0"),
            )

        controller.selectAudio(sidecarFilm(), jellyfinIndex = 4) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .id shouldBe "1:0"
    }

    @Test
    fun `the baked audio track is still found positionally, past the sidecar groups`() {
        val controller =
            controller(
                mergedAudioGroup("0:1"),
                mergedAudioGroup("1:0"),
                mergedAudioGroup("2:0"),
            )

        controller.selectAudio(sidecarFilm(), jellyfinIndex = 3) shouldBe true

        // The two sidecar groups are excluded before counting; counting them would put the baked
        // track on child 1 and play the wrong language.
        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .id shouldBe "0:1"
    }

    @Test
    fun `a doubly merged primary group still counts as the container's own`() {
        // Subtitle sidecars merge the container once, then audio sidecars merge it again: the
        // container's audio group arrives prefixed twice. Only the outer prefix is the child index.
        val controller =
            controller(
                mergedAudioGroup("0:0:1"),
                mergedAudioGroup("1:0"),
                mergedAudioGroup("2:0"),
            )

        controller.selectAudio(sidecarFilm(), jellyfinIndex = 3) shouldBe true

        applied.captured.overrides.values
            .single()
            .mediaTrackGroup
            .id shouldBe "0:0:1"
    }

    @Test
    fun `refuses an audio track no sidecar and no container group supplies`() {
        // Only one sidecar was merged in, but the source claims two — a file that failed to
        // download. Selecting the missing one must fail rather than land on the wrong child.
        val controller = controller(mergedAudioGroup("0:1"), mergedAudioGroup("1:0"))

        controller.selectAudio(sidecarFilm(), jellyfinIndex = 5) shouldBe false
    }

    /** Streams 4 and 5 are audio sidecars, in the ascending-index order that *is* the merge-child order. */
    private fun sidecarFilm() =
        PlayerFixtures.localSource(
            audioTracks =
                listOf(
                    PlaybackTrack(index = 3, label = "French VFF", language = "fra", codec = "aac"),
                    PlaybackTrack(index = 4, label = "French VFQ", language = "fra", codec = "aac", isExternal = true),
                    PlaybackTrack(index = 5, label = "English VO", language = "eng", codec = "aac", isExternal = true),
                ),
        )

    /**
     * The child prefix lands on the `TrackGroup`'s own id, not only on its formats:
     * `MergingMediaPeriod.onPrepared` publishes `new TrackGroup(childIndex + ":" + trackGroup.id, …)`
     * (read off the Media3 1.9.0 bytecode). That id is what selection navigates by.
     */
    private fun mergedAudioGroup(groupId: String): Tracks.Group =
        Tracks.Group(
            TrackGroup(
                groupId,
                Format
                    .Builder()
                    .setId(groupId)
                    .setSampleMimeType(MimeTypes.AUDIO_AAC)
                    .build(),
            ),
            // adaptiveSupported =
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )

    /**
     * No `language` is set on the [Format]: `Format`'s constructor normalises one through
     * `Util.normalizeLanguageCode`, which reaches `android.text.TextUtils` and is a throwing stub in
     * a local unit test. Nothing here needs it — the id is precisely what is under test.
     */
    private fun textGroup(id: String?): Tracks.Group =
        group(
            Format
                .Builder()
                .setId(id)
                .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
                .build(),
        )

    private fun audioGroup(id: String): Tracks.Group =
        group(
            Format
                .Builder()
                .setId(id)
                .setSampleMimeType(MimeTypes.AUDIO_AC3)
                .build(),
        )

    /** Not side-loaded, whatever the stream itself was — a rendition carries no `external:<index>` id. */
    private fun renditionTrack(
        index: Int,
        label: String,
        language: String,
    ): PlaybackTrack = PlaybackTrack(index = index, label = label, language = language, codec = "srt")

    /** A subtitle track as `LocalPlaybackResolver` builds one for a sidecar on disk. */
    private fun sidecarTrack(
        index: Int,
        label: String,
    ): PlaybackTrack = PlaybackTrack(index = index, label = label, language = "fra", codec = "srt", isExternal = true)

    /**
     * `MergingMediaPeriod.onPrepared` rebuilds every `Format` with `setId(childIndex + ":" + format.id)`
     * before publishing the merged `TrackGroupArray`; the player has one merged source per
     * side-loaded subtitle plus the container at 0.
     */
    private fun mergedTrackId(
        childIndex: Int,
        id: String,
    ): String = "$childIndex:$id"

    private fun group(format: Format): Tracks.Group =
        Tracks.Group(
            TrackGroup(format),
            // adaptiveSupported =
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
}
