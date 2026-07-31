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
 * Unit tests for [TrackSelectionController] — the bridge between Jellyfin's absolute stream indices
 * and ExoPlayer's per-type track groups.
 *
 * The groups here are built the way Media3 builds them for a side-loaded subtitle: `MediaItems`
 * hands `MediaItem.SubtitleConfiguration.setId("external:<index>")` to the player, and
 * `DefaultMediaSourceFactory` copies that id onto the `Format` it synthesises for the subtitle
 * source (`Format.Builder.setId(subtitleConfiguration.id)`, in both the parse-during-extraction
 * branch and the `SingleSampleMediaSource` one).
 *
 * That is not the end of the id's journey, and assuming it was is what made a downloaded sidecar
 * unselectable offline. Side-loading a subtitle wraps everything in a `MergingMediaSource`, and
 * `MergingMediaPeriod.onPrepared` rebuilds every format of every child as
 * `setId(childIndex + ":" + format.id)` before publishing the merged groups — so what the player
 * reports is `1:external:1`, never `external:1`. Both shapes are exercised here: the merged one
 * because it is what a device produces, the bare one because nothing should depend on the wrapping.
 */
class TrackSelectionControllerTest {
    private val player = mockk<Player>(relaxed = true)
    private val applied = slot<TrackSelectionParameters>()

    /**
     * `TrackGroup`'s constructor normalises its id through `android.text.TextUtils`, which is a
     * throwing stub in a local unit test. Stubbing the one method is what keeps these assertions on
     * *real* Media3 track groups — a hand-rolled fake would prove nothing about the id matching.
     */
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
        // A transcoded download: the two sidecars are all the text there is, and stream 7 was an
        // embedded subtitle the server dropped while encoding.
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
        // Phase 0: a transcoded download fetches an extracted `.srt` for each *embedded* text
        // subtitle, because the encode drops them from the container. Stream 7 is one of those —
        // `MediaStream.isExternal` is false and the file has no text track at all, yet the picker
        // offers it and selecting it has to work.
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
        // The failure this guards: if the resolver called such a track embedded, it would shift the
        // positional count for every genuinely embedded track after it — and here it would push
        // stream 6 onto the wrong group entirely.
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
        // "Les Minions 2", transcoded MEDIUM: the encode dropped both French text subtitles from the
        // container, so `subtitle.1.fra.srt` and `subtitle.2.fra.srt` are the only text there is —
        // and side-loading them is what makes the player a `MergingMediaSource`. Its period re-ids
        // *every* format as "<childIndex>:<originalId>", so `external:2` is never what comes back.
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
        // Same re-id, on the mixed case: counting the side-loaded group as embedded puts stream 6 on
        // the sidecar's group and plays the wrong language.
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

    // ---- the slate a new media item starts from ------------------------------------------------

    @Test
    fun `reset clears audio and text overrides and re-enables text`() {
        // What the previous item left behind: a chosen audio track, a chosen subtitle, and then
        // subtitles turned off. The player is process-wide, so all three would still be in force
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
        // The transcoded case: three tracks in the cached blob, one in the file on disk.
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
        // A transcoded download of Élémentaire with every language kept: the file holds the baked
        // French VFF, and `audio.4.fra.m4a` / `audio.5.eng.m4a` are merged in as children 1 and 2.
        // There is no id to match on — `MediaItem` cannot name an audio source's tracks — so the
        // child prefix `MergingMediaPeriod` puts on the group id is the whole of the mapping.
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
        // The film also has subtitle sidecars, so `DefaultMediaSourceFactory` merged the main item
        // once for those before `ExoPlayerHandle` merged the audio files in around it: the
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

    /**
     * A transcoded download with its extra languages back: the baked French VFF in the file, and
     * streams 4 and 5 as audio sidecars — flagged side-loaded by `LocalPlaybackResolver`, in the
     * ascending-index order that *is* the merge-child order.
     */
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
     * An audio track group as a *merged* player reports it.
     *
     * The child prefix lands on the `TrackGroup`'s own id, not only on its formats:
     * `MergingMediaPeriod.onPrepared` publishes `new TrackGroup(childIndex + ":" + trackGroup.id,
     * …)` (read off the Media3 1.9.0 bytecode). That id is what selection navigates by, so it is
     * what these groups have to carry.
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
     * A text track group as the player reports it.
     *
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

    /** A subtitle track as `LocalPlaybackResolver` builds one for a sidecar on disk. */
    private fun sidecarTrack(
        index: Int,
        label: String,
    ): PlaybackTrack = PlaybackTrack(index = index, label = label, language = "fra", codec = "srt", isExternal = true)

    /**
     * The id `MergingMediaPeriod` exposes for a format of the [childIndex]-th merged source.
     *
     * Not a guess: `MergingMediaPeriod.onPrepared` rebuilds every `Format` with
     * `setId(childIndex + ":" + format.id)` before publishing the merged `TrackGroupArray`, and the
     * player has one merged source per side-loaded subtitle plus the container at 0.
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
