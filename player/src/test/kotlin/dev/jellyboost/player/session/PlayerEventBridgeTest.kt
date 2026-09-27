package dev.jellyboost.player.session

import androidx.media3.common.Player
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The buffering half of the shared listener — the one both handles get, so a local rebuffer and a
 * receiver's minutes of loading reach the screen the same way.
 */
class PlayerEventBridgeTest {
    private val emitted = mutableListOf<PlayerEvent>()
    private var afterEventsCalls = 0

    private val listener =
        playerEventListener(
            emit = { emitted += it },
            afterEvents = { afterEventsCalls++ },
        )

    private var state = Player.STATE_IDLE
    private var playWhenReady = false

    private val player =
        mockk<Player> {
            every { playbackState } answers { state }
            every { playWhenReady } answers { this@PlayerEventBridgeTest.playWhenReady }
        }

    private fun batch(
        state: Int,
        playWhenReady: Boolean,
    ) {
        this.state = state
        this.playWhenReady = playWhenReady
        listener.onEvents(player, mockk(relaxed = true))
    }

    private val buffering get() = emitted.filterIsInstance<PlayerEvent.Buffering>().map { it.isBuffering }

    @Test
    fun `a player waiting for data it means to play is buffering`() {
        batch(Player.STATE_BUFFERING, playWhenReady = true)

        buffering shouldBe listOf(true)
    }

    @Test
    fun `a paused player filling its buffer is not, to anyone looking at it`() {
        batch(Player.STATE_BUFFERING, playWhenReady = false)

        buffering.shouldBeEmpty()
    }

    @Test
    fun `a pause while still buffering ends it, though the state never changed`() {
        batch(Player.STATE_BUFFERING, playWhenReady = true)

        batch(Player.STATE_BUFFERING, playWhenReady = false)

        buffering shouldBe listOf(true, false)
    }

    @Test
    fun `becoming ready ends it`() {
        batch(Player.STATE_BUFFERING, playWhenReady = true)

        batch(Player.STATE_READY, playWhenReady = true)

        buffering shouldBe listOf(true, false)
    }

    @Test
    fun `only changes are said, so a burst of batches is one event`() {
        batch(Player.STATE_BUFFERING, playWhenReady = true)
        batch(Player.STATE_BUFFERING, playWhenReady = true)
        batch(Player.STATE_READY, playWhenReady = true)
        batch(Player.STATE_READY, playWhenReady = false)

        buffering shouldBe listOf(true, false)
    }

    @Test
    fun `every batch ends with the handle's own check`() {
        batch(Player.STATE_READY, playWhenReady = true)
        batch(Player.STATE_READY, playWhenReady = true)

        afterEventsCalls shouldBe 2
    }
}
