package dev.jellyboost.feature.detail

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.common.model.JellyfinItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * Unit tests for [seasonSiblings] — the episode page's "More from this season" row
 * (episode-detail-shortcuts, DECISIONS.md).
 */
class SeasonSiblingsTest {
    @Test
    fun `the current episode is excluded from its own siblings row`() {
        val current = episode("2")
        val seasonEpisodes = listOf(episode("1"), current, episode("3"))

        seasonSiblings(seasonEpisodes, current.id).map { it.id } shouldContainExactly listOf("1", "3")
    }

    @Test
    fun `a season of one episode has no siblings`() {
        val current = episode("1")

        seasonSiblings(listOf(current), current.id).shouldBeEmpty()
    }

    @Test
    fun `no season episodes fetched means no siblings`() {
        seasonSiblings(emptyList(), "episode-1").shouldBeEmpty()
    }

    private fun episode(id: String) = JellyfinItem(id = id, name = "Episode $id", type = ItemType.EPISODE)
}
