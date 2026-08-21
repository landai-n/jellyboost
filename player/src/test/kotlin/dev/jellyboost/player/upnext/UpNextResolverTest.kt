package dev.jellyboost.player.upnext

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import dev.jellyboost.core.common.model.UserData
import dev.jellyboost.data.JellyfinRepository
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Unit tests for [UpNextResolver].
 *
 * The interesting half is what it deliberately is *not*: the server's own "next up" answers "the
 * next episode you have not seen", and on a rewatch that is a completely different episode from the
 * one that follows the one playing. A card is drawn on the strength of this answer and a tap plays
 * it, so being positionally right on a rewatch is the property worth pinning.
 *
 * The other half is that every miss is silent. Nothing here throws, nothing surfaces an error, and
 * `null` is the ordinary answer for most of what a player opens.
 */
internal class UpNextResolverTest {
    private val repository = mockk<JellyfinRepository>()
    private val resolver = UpNextResolver(repository)

    @Test
    fun `offers the episode after this one`() =
        runTest {
            givenSeries(episode(1, 1), episode(1, 2), episode(1, 3))

            val next = resolver.resolve("s1e1")

            next?.itemId shouldBe "s1e2"
            next?.title shouldBe "Episode 2"
            next?.indexNumber shouldBe 2
            next?.parentIndexNumber shouldBe 1
        }

    @Test
    fun `crosses a season boundary`() =
        runTest {
            // `getSeriesEpisodes` returns the whole series in playing order, so the first episode of
            // the next season simply *is* the next entry — the case a per-season lookup would miss.
            givenSeries(episode(1, 1), episode(1, 2), episode(2, 1))

            val next = resolver.resolve("s1e2")

            next?.itemId shouldBe "s2e1"
            next?.parentIndexNumber shouldBe 2
            next?.indexNumber shouldBe 1
        }

    @Test
    fun `a rewatch is offered the positional successor, not the next unwatched episode`() =
        runTest {
            // The whole series is watched except the finale — which is exactly what the server's
            // "next up" would answer with. Someone rewatching episode 1 wants episode 2.
            givenSeries(
                episode(1, 1, watched = true),
                episode(1, 2, watched = true),
                episode(1, 3, watched = false),
            )

            resolver.resolve("s1e1")?.itemId shouldBe "s1e2"
        }

    @Test
    fun `the last episode of a series has no successor`() =
        runTest {
            givenSeries(episode(1, 1), episode(1, 2))

            resolver.resolve("s1e2").shouldBeNull()
        }

    @Test
    fun `a film is not offered anything`() =
        runTest {
            coEvery { repository.getItem("film") } returns
                AppResult.Success(JellyfinItem(id = "film", name = "Arrival", type = ItemType.MOVIE))

            resolver.resolve("film").shouldBeNull()
        }

    @Test
    fun `an episode whose series listing fails is offered nothing`() =
        runTest {
            coEvery { repository.getItem("s1e1") } returns AppResult.Success(episode(1, 1))
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns AppResult.Failure(AppError.Network())

            resolver.resolve("s1e1").shouldBeNull()
        }

    @Test
    fun `an item that cannot be fetched at all is offered nothing`() =
        runTest {
            coEvery { repository.getItem("s1e1") } returns AppResult.Failure(AppError.Network())

            resolver.resolve("s1e1").shouldBeNull()
        }

    @Test
    fun `an episode the series listing does not contain is offered nothing`() =
        runTest {
            // The offline shape of the same miss: the item is known, but the listing this device can
            // produce — only what is downloaded — does not hold it.
            coEvery { repository.getItem("s1e9") } returns AppResult.Success(episode(1, 9))
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns
                AppResult.Success(listOf(episode(1, 1), episode(1, 2)))

            resolver.resolve("s1e9").shouldBeNull()
        }

    @Test
    fun `prefers the episode's thumb over its primary image`() =
        runTest {
            val withBoth = episode(1, 2).copy(thumbImageUrl = "https://server/thumb", primaryImageUrl = "https://s/p")
            coEvery { repository.getItem("s1e1") } returns AppResult.Success(episode(1, 1))
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns
                AppResult.Success(listOf(episode(1, 1), withBoth))

            resolver.resolve("s1e1")?.imageUrl shouldBe "https://server/thumb"
        }

    @Test
    fun `falls back to the primary image, which for an episode is a still`() =
        runTest {
            val primaryOnly = episode(1, 2).copy(primaryImageUrl = "https://server/still")
            coEvery { repository.getItem("s1e1") } returns AppResult.Success(episode(1, 1))
            coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns
                AppResult.Success(listOf(episode(1, 1), primaryOnly))

            resolver.resolve("s1e1")?.imageUrl shouldBe "https://server/still"
        }

    /** Stubs both calls so that every episode in [episodes] resolves against the same listing. */
    private fun givenSeries(vararg episodes: JellyfinItem) {
        episodes.forEach { coEvery { repository.getItem(it.id) } returns AppResult.Success(it) }
        coEvery { repository.getSeriesEpisodes(SERIES_ID) } returns AppResult.Success(episodes.toList())
    }

    private fun episode(
        season: Int,
        number: Int,
        watched: Boolean = false,
    ): JellyfinItem =
        JellyfinItem(
            id = "s${season}e$number",
            name = "Episode $number",
            type = ItemType.EPISODE,
            indexNumber = number,
            parentIndexNumber = season,
            seriesId = SERIES_ID,
            seriesName = "A Series",
            userData = UserData(played = watched),
        )

    private companion object {
        const val SERIES_ID = "series-1"
    }
}
