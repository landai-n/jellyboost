package dev.jellyboost.core.network

import dev.jellyboost.core.common.AppError
import dev.jellyboost.core.common.AppResult
import dev.jellyboost.core.common.getOrNull
import dev.jellyboost.core.network.TestFixtures.recommendedServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Unit tests for the pure candidate-scoring rules used by server setup. */
class RecommendedServerSelectionTest {
    @Test
    @DisplayName("a GREAT candidate wins even when a GOOD one comes first")
    fun greatBeatsGood() {
        val servers =
            listOf(
                recommendedServer("https://good.example", RecommendedServerInfoScore.GOOD),
                recommendedServer("https://great.example", RecommendedServerInfoScore.GREAT),
            )

        selectRecommendedServer(servers).getOrNull()?.address shouldBe "https://great.example"
    }

    @Test
    @DisplayName("the first GREAT candidate wins when several are GREAT")
    fun firstGreatWins() {
        val servers =
            listOf(
                recommendedServer("https://first.example", RecommendedServerInfoScore.GREAT),
                recommendedServer("https://second.example", RecommendedServerInfoScore.GREAT),
            )

        selectRecommendedServer(servers).getOrNull()?.address shouldBe "https://first.example"
    }

    @Test
    @DisplayName("falls back to the first GOOD candidate when none is GREAT")
    fun firstGoodFallback() {
        val servers =
            listOf(
                recommendedServer("https://bad.example", RecommendedServerInfoScore.BAD),
                recommendedServer("https://good-one.example", RecommendedServerInfoScore.GOOD),
                recommendedServer("https://good-two.example", RecommendedServerInfoScore.GOOD),
            )

        selectRecommendedServer(servers).getOrNull()?.address shouldBe "https://good-one.example"
    }

    @Test
    @DisplayName("unusable candidates are split into unreachable and incompatible")
    fun partitionsUnusableCandidates() {
        val servers =
            listOf(
                recommendedServer(
                    address = "https://silent.example",
                    score = RecommendedServerInfoScore.BAD,
                    systemInfo = Result.failure(IllegalStateException("no answer")),
                ),
                recommendedServer(
                    address = "https://old.example",
                    score = RecommendedServerInfoScore.BAD,
                ),
                recommendedServer(
                    address = "https://slow.example",
                    score = RecommendedServerInfoScore.OK,
                ),
            )

        val result = selectRecommendedServer(servers)

        result.shouldBeInstanceOf<AppResult.Failure>()
        val error = result.error
        error.shouldBeInstanceOf<AppError.ServerResolution>()
        error.unreachableAddresses shouldBe listOf("https://silent.example")
        error.incompatibleAddresses shouldBe listOf("https://old.example", "https://slow.example")
    }

    @Test
    @DisplayName("no candidates at all fails with two empty address lists")
    fun noCandidates() {
        val result = selectRecommendedServer(emptyList())

        result.shouldBeInstanceOf<AppResult.Failure>()
        val error = result.error
        error.shouldBeInstanceOf<AppError.ServerResolution>()
        error.unreachableAddresses shouldBe emptyList()
        error.incompatibleAddresses shouldBe emptyList()
    }
}
