package dev.jellyboost.player.model

import dev.jellyboost.player.PlayerFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** `isSameLoadAs`: what the cast coordinator compares a detached source against the receiver's load with. */
class PlaybackMediaSourceLoadTest {
    private val loaded = PlayerFixtures.remoteSource()

    @Test
    fun `a track chosen in place is still the same load`() {
        loaded.withSelectedSubtitle(null).isSameLoadAs(loaded) shouldBe true
        loaded.withSelectedSubtitle(3).isSameLoadAs(loaded) shouldBe true
        loaded.withSelectedAudio(2).isSameLoadAs(loaded) shouldBe true
    }

    @Test
    fun `a re-negotiation is a new load, even of the same item`() {
        loaded.copy(playSessionId = "another-session").isSameLoadAs(loaded) shouldBe false
    }

    @Test
    fun `nothing is the same load as no load`() {
        loaded.isSameLoadAs(null) shouldBe false
    }

    @Test
    fun `another item or media source is another load`() {
        loaded.copy(mediaSourceId = "source-2").isSameLoadAs(loaded) shouldBe false
        loaded
            .copy(itemId = UUID.fromString("9e8d7c6b-5a49-4382-a1b0-c9d8e7f6a5b4"))
            .isSameLoadAs(loaded) shouldBe false
    }
}
