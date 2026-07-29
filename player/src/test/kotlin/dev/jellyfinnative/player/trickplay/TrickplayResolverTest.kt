package dev.jellyfinnative.player.trickplay

import dev.jellyfinnative.player.PlayerFixtures
import dev.jellyfinnative.player.api.PlayerApi
import dev.jellyfinnative.player.api.StreamUrlFactory
import dev.jellyfinnative.player.model.LocalTrickplay
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.UUID

/**
 * Unit tests for [TrickplayResolver].
 *
 * The two things worth pinning are the two that fail invisibly: how many tile sheets the server has
 * (it never says — the count is derived, and an off-by-one leaves the last minutes of a film with no
 * preview), and which width is chosen when the server holds several. Everything else is the absence
 * path, which has to be silent.
 */
class TrickplayResolverTest {
    private val api = mockk<PlayerApi>()
    private val urls =
        object : StreamUrlFactory {
            override fun directPlayUrl(
                itemId: UUID,
                mediaSourceId: String,
                playSessionId: String,
            ) = "unused"

            override fun directStreamUrl(
                itemId: UUID,
                container: String,
                mediaSourceId: String,
                playSessionId: String,
            ) = "unused"

            override fun absoluteUrl(path: String) = "unused"

            override fun trickplayTileUrl(
                itemId: UUID,
                width: Int,
                tileIndex: Int,
                mediaSourceId: String?,
            ) = "https://server/Videos/$itemId/Trickplay/$width/$tileIndex.jpg"
        }

    private val resolver = TrickplayResolver(api, urls)

    // ---- offline -------------------------------------------------------------------------------

    @Test
    fun `a downloaded item uses the sheets already on disk and asks the server nothing`() =
        runTest {
            val source = PlayerFixtures.localSource(trickplay = localTrickplay())

            val tiles = resolver.resolve(source).shouldNotBeNull()

            tiles.tileUris shouldContainExactly listOf("file:///t.0.jpg", "file:///t.1.jpg")
            tiles.columns shouldBe 10
            tiles.intervalMs shouldBe 10_000
            // No `coEvery` was set on the api: reaching it would have thrown.
        }

    @Test
    fun `a downloaded item without trickplay has none`() =
        runTest {
            resolver.resolve(PlayerFixtures.localSource()).shouldBeNull()
        }

    @Test
    fun `a downloaded item whose sheets never landed has none`() =
        runTest {
            val source = PlayerFixtures.localSource(trickplay = localTrickplay(tileUris = emptyList()))

            resolver.resolve(source).shouldBeNull()
        }

    // ---- online --------------------------------------------------------------------------------

    @Test
    fun `derives one URL per sheet from the thumbnail count`() =
        runTest {
            // 250 thumbnails, 100 to a sheet: three sheets, the last one partly empty.
            coEvery { api.getTrickplayInfo(any()) } returns
                mapOf(PlayerFixtures.ITEM_ID.toString() to mapOf("320" to info(width = 320)))

            val tiles = resolver.resolve(PlayerFixtures.remoteSource()).shouldNotBeNull()

            tiles.tileUris shouldContainExactly
                listOf(
                    "https://server/Videos/${PlayerFixtures.ITEM_ID}/Trickplay/320/0.jpg",
                    "https://server/Videos/${PlayerFixtures.ITEM_ID}/Trickplay/320/1.jpg",
                    "https://server/Videos/${PlayerFixtures.ITEM_ID}/Trickplay/320/2.jpg",
                )
            tiles.thumbnailWidth shouldBe 320
            tiles.thumbnailCount shouldBe 250
        }

    @Test
    fun `picks the width closest to the one the scrubber asked for`() =
        runTest {
            coEvery { api.getTrickplayInfo(any()) } returns
                mapOf(
                    PlayerFixtures.ITEM_ID.toString() to
                        mapOf(
                            "160" to info(width = 160),
                            "480" to info(width = 480),
                            "1280" to info(width = 1280),
                        ),
                )

            // 480 is 160 away from 320; 160 is 160 away too, but 480 is not the first — the closest
            // by distance is what matters, and a tie is decided by iteration order, so this asserts
            // the case with a clear winner.
            val tiles = resolver.resolve(PlayerFixtures.remoteSource(), preferredWidth = 500).shouldNotBeNull()

            tiles.thumbnailWidth shouldBe 480
        }

    @Test
    fun `prefers the geometry of the media source being played`() =
        runTest {
            coEvery { api.getTrickplayInfo(any()) } returns
                mapOf(
                    "some-other-source" to mapOf("320" to info(width = 320, thumbnailCount = 10)),
                    // The server answers PlaybackInfo with the dash-less spelling of the id.
                    PlayerFixtures.DASHLESS_ITEM_ID to mapOf("320" to info(width = 320, thumbnailCount = 250)),
                )

            val tiles = resolver.resolve(PlayerFixtures.remoteSource()).shouldNotBeNull()

            tiles.thumbnailCount shouldBe 250
        }

    @Test
    fun `an item the server generated no thumbnails for has none`() =
        runTest {
            coEvery { api.getTrickplayInfo(any()) } returns emptyMap()

            resolver.resolve(PlayerFixtures.remoteSource()).shouldBeNull()
        }

    @Test
    fun `a server that cannot answer leaves the scrubber plain instead of failing playback`() =
        runTest {
            coEvery { api.getTrickplayInfo(any()) } throws IOException("no route to host")

            resolver.resolve(PlayerFixtures.remoteSource()).shouldBeNull()
        }

    @Test
    fun `nonsense geometry is refused rather than dividing by zero`() =
        runTest {
            coEvery { api.getTrickplayInfo(any()) } returns
                mapOf(PlayerFixtures.ITEM_ID.toString() to mapOf("320" to info(interval = 0)))

            resolver.resolve(PlayerFixtures.remoteSource()).shouldBeNull()
        }

    private fun info(
        width: Int = 320,
        thumbnailCount: Int = 250,
        interval: Int = 10_000,
    ): TrickplayInfoDto =
        TrickplayInfoDto(
            width = width,
            height = width * 9 / 16,
            tileWidth = 10,
            tileHeight = 10,
            thumbnailCount = thumbnailCount,
            interval = interval,
            bandwidth = 0,
        )

    private fun localTrickplay(tileUris: List<String> = listOf("file:///t.0.jpg", "file:///t.1.jpg")): LocalTrickplay =
        LocalTrickplay(
            width = 320,
            height = 180,
            tileWidth = 10,
            tileHeight = 10,
            thumbnailCount = 250,
            intervalMs = 10_000,
            tileUris = tileUris,
        )
}
