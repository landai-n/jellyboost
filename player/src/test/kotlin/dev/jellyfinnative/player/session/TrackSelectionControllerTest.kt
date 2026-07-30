package dev.jellyfinnative.player.session

import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.model.PlaybackTrack
import dev.jellyfinnative.player.model.externalSubtitleTrackId
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
 * `DefaultMediaSourceFactory` copies that id straight onto the `Format` it synthesises for the
 * subtitle source (`Format.Builder.setId(subtitleConfiguration.id)`, in both the
 * parse-during-extraction branch and the `SingleSampleMediaSource` one). So a text `Format` carrying
 * `external:1` is exactly what the player reports, and matching it is what makes a downloaded
 * sidecar selectable offline — the only subtitles a transcoded download has at all.
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

    @Test
    fun `turning subtitles off disables the text renderer rather than failing`() {
        val controller = controller(textGroup(externalSubtitleTrackId(0)))

        controller.selectSubtitle(PlayerFixtures.localSource(), jellyfinIndex = null) shouldBe true

        applied.captured.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) shouldBe true
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

    private fun group(format: Format): Tracks.Group =
        Tracks.Group(
            TrackGroup(format),
            // adaptiveSupported =
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
}
