package dev.jellyboost.player.cast

import dev.jellyboost.player.model.PlaybackSnapshot
import dev.jellyboost.player.session.PlayerEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What `CastPlayerHandle` says about a receiver letting go of the item — the edges only. */
class RemoteItemPresenceTest {
    private val emitted = mutableListOf<PlayerEvent>()
    private val presence = RemoteItemPresence(emit = { emitted += it })

    private val playing = PlaybackSnapshot(positionMs = 600_000L, durationMs = 7_200_000L, isPlaying = true)
    private val notOurs = PlaybackSnapshot(isValid = false)

    @Test
    fun `the seconds after a load are not a drop, however long they last`() {
        presence.onLoad()

        // The receiver has not reported the new item yet: every reading is someone else's.
        repeat(100) { presence.onReading(notOurs, ready = false) }

        emitted.shouldBeEmpty()
    }

    @Test
    fun `an item only glimpsed while loading does not arm it`() {
        // A placeholder can briefly claim the item before the receiver has it; that is not "held".
        presence.onLoad()
        presence.onReading(PlaybackSnapshot(positionMs = 0L), ready = false)

        presence.onReading(notOurs, ready = false)

        emitted.shouldBeEmpty()
    }

    @Test
    fun `an item the receiver held and then let go of is reported once, with where it was`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)

        presence.onReading(notOurs, ready = false)
        presence.onReading(notOurs, ready = false)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(playing))
    }

    @Test
    fun `the position it resumes from is the last one held, not the one it was armed at`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        val later = playing.copy(positionMs = 660_000L)
        presence.onReading(later, ready = false)

        presence.onReading(notOurs, ready = false)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(later))
    }

    @Test
    fun `an item that comes back clears it, once`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        presence.onReading(notOurs, ready = false)

        presence.onReading(playing, ready = true)
        presence.onReading(playing, ready = true)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(playing), PlayerEvent.RemoteItemMissingCleared)
    }

    @Test
    fun `a new load clears it and disarms, so its own loading window is not a drop`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)
        presence.onReading(notOurs, ready = false)

        presence.onLoad()
        presence.onReading(notOurs, ready = false)

        emitted shouldBe listOf(PlayerEvent.RemoteItemMissing(playing), PlayerEvent.RemoteItemMissingCleared)
    }

    @Test
    fun `a load with nothing missing says nothing`() {
        presence.onLoad()
        presence.onReading(playing, ready = true)

        presence.onLoad()

        emitted.shouldBeEmpty()
    }
}
