package dev.jellyboost.player.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for [jellyfinIndexOfTrackId] — the round trip from the id `ExoMediaSourceFactory` gives
 * a side-loaded subtitle back to the Jellyfin stream index it names.
 *
 * The id does not survive the journey unchanged: every `MergingMediaPeriod` the source passes
 * through rebuilds each format as `setId(childIndex + ":" + format.id)`, so the number of leading
 * numeric prefixes is a property of *how the player was assembled*, not of the track. A downloaded
 * item can be merged twice — `ExoPlayerHandle` merges the audio sidecars in, over a main source
 * `DefaultMediaSourceFactory` has already merged the subtitles into — which is what makes this a
 * loop rather than a single strip.
 */
class PlaybackMediaItemSpecTest {
    @Test
    fun `reads the index straight off an unmerged id`() {
        // What the factory set, before any merge touched it.
        jellyfinIndexOfTrackId("external:2") shouldBe 2
    }

    @Test
    fun `reads the index through one merge prefix`() {
        // Side-loading a subtitle is itself a merge: this is what a streamed item reports.
        jellyfinIndexOfTrackId("1:external:2") shouldBe 2
    }

    @Test
    fun `reads the index through two merge prefixes`() {
        // A downloaded item with both audio sidecars and subtitles: the inner merge is the
        // subtitle assembly, the outer one is the audio sidecars, and the main source is child 0
        // of both.
        jellyfinIndexOfTrackId("0:1:external:2") shouldBe 2
    }

    @Test
    fun `a container track whose merged id merely looks numeric is not one of ours`() {
        // Matroska names its tracks "1", "2", … — stripping the prefix leaves a bare number, which
        // is not an `external:` id and must not be read as a Jellyfin stream index.
        jellyfinIndexOfTrackId("0:2").shouldBeNull()
    }

    @Test
    fun `a doubly merged container track is likewise not one of ours`() {
        jellyfinIndexOfTrackId("0:0:2").shouldBeNull()
    }

    @Test
    fun `an id that is not ours at all reads as nothing`() {
        jellyfinIndexOfTrackId("audio-fra").shouldBeNull()
        jellyfinIndexOfTrackId("0:audio-fra").shouldBeNull()
        // A format with no id at all is the normal case for a container track.
        jellyfinIndexOfTrackId(null).shouldBeNull()
    }

    @Test
    fun `an external id with no readable index is refused rather than guessed at`() {
        jellyfinIndexOfTrackId("external:").shouldBeNull()
        jellyfinIndexOfTrackId("1:external:x").shouldBeNull()
    }
}
