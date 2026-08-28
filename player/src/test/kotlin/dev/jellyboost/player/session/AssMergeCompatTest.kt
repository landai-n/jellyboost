package dev.jellyboost.player.session

import dev.jellyboost.player.model.AudioSidecarSpec
import dev.jellyboost.player.model.PlaybackMediaItemSpec
import dev.jellyboost.player.model.SubtitleSpec
import dev.jellyboost.player.model.externalSubtitleTrackId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AssMergeCompatTest {
    @Test
    fun `an item with neither sidecars nor side-loaded subtitles is never merged`() {
        styledAssSurvivesMerge(spec()) shouldBe true
    }

    @Test
    fun `side-loaded subtitles alone leave one merge prefix, which libass strips`() {
        styledAssSurvivesMerge(spec(subtitles = listOf(subtitle(index = 2)))) shouldBe true
    }

    @Test
    fun `audio sidecars alone leave one merge prefix on the container's own tracks`() {
        styledAssSurvivesMerge(spec(sidecars = listOf(AudioSidecarSpec(streamIndex = 3, uri = "file:///a")))) shouldBe
            true
    }

    @Test
    fun `sidecars and side-loaded subtitles together merge twice, which libass cannot follow`() {
        val spec =
            spec(
                subtitles = listOf(subtitle(index = 2)),
                sidecars = listOf(AudioSidecarSpec(streamIndex = 3, uri = "file:///a")),
            )

        styledAssSurvivesMerge(spec) shouldBe false
    }

    private fun spec(
        subtitles: List<SubtitleSpec> = emptyList(),
        sidecars: List<AudioSidecarSpec> = emptyList(),
    ) = PlaybackMediaItemSpec(
        mediaId = "item-1",
        uri = "file:///video.mkv",
        subtitles = subtitles,
        audioSidecars = sidecars,
    )

    private fun subtitle(index: Int) =
        SubtitleSpec(
            id = externalSubtitleTrackId(index),
            uri = "file:///subs.ass",
            mimeType = "text/x-ssa",
            label = "English",
            language = "eng",
        )
}
