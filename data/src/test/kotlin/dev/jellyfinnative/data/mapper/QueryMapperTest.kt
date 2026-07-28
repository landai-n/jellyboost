package dev.jellyfinnative.data.mapper

import dev.jellyfinnative.core.common.model.FilterOptions
import dev.jellyfinnative.core.common.model.ItemQuery
import dev.jellyfinnative.core.common.model.ItemType
import dev.jellyfinnative.core.common.model.SortBy
import dev.jellyfinnative.core.common.model.SortOrder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.junit.jupiter.api.Test
import java.util.UUID
import org.jellyfin.sdk.model.api.SortOrder as SdkSortOrder

/** Unit tests for the domain [ItemQuery] → SDK `getItems` translation. */
class QueryMapperTest {
    private val libraryId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    private fun ItemQuery.request() =
        toGetItemsRequest(
            fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
            imageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB),
        )

    @Test
    fun `carries the library, item types and paging window`() {
        val request =
            ItemQuery(
                parentId = libraryId.toString(),
                itemTypes = listOf(ItemType.MOVIE, ItemType.SERIES),
                startIndex = 150,
                limit = 50,
            ).request()

        request.parentId shouldBe libraryId
        request.includeItemTypes shouldContainExactly listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
        request.startIndex shouldBe 150
        request.limit shouldBe 50
        request.recursive shouldBe true
    }

    @Test
    fun `asks only for what a card draws`() {
        val request = ItemQuery().request()

        request.fields shouldContainExactly listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO)
        request.enableImageTypes shouldContainExactly
            listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)
        request.imageTypeLimit shouldBe 1
        request.enableUserData shouldBe true
    }

    @Test
    fun `leaves the total record count off - placeholders are disabled`() {
        ItemQuery().request().enableTotalRecordCount shouldBe false
    }

    @Test
    fun `maps every sort key onto its SDK counterpart`() {
        SortBy.SORT_NAME.toSdk() shouldBe ItemSortBy.SORT_NAME
        SortBy.DATE_CREATED.toSdk() shouldBe ItemSortBy.DATE_CREATED
        SortBy.PREMIERE_DATE.toSdk() shouldBe ItemSortBy.PREMIERE_DATE
        SortBy.COMMUNITY_RATING.toSdk() shouldBe ItemSortBy.COMMUNITY_RATING
        SortBy.RUNTIME.toSdk() shouldBe ItemSortBy.RUNTIME
        SortBy.RANDOM.toSdk() shouldBe ItemSortBy.RANDOM
    }

    @Test
    fun `sends the sort key and direction the grid picked`() {
        val request =
            ItemQuery(sortBy = SortBy.PREMIERE_DATE, sortOrder = SortOrder.DESCENDING).request()

        request.sortBy shouldContainExactly listOf(ItemSortBy.PREMIERE_DATE)
        request.sortOrder shouldContainExactly listOf(SdkSortOrder.DESCENDING)
    }

    @Test
    fun `forwards every filter facet`() {
        val request =
            ItemQuery(
                filters =
                    FilterOptions(
                        genres = listOf("Science Fiction", "Thriller"),
                        years = listOf(2021, 2016),
                        officialRatings = listOf("PG-13"),
                        isPlayed = false,
                        isFavorite = true,
                    ),
            ).request()

        request.genres shouldContainExactly listOf("Science Fiction", "Thriller")
        request.years shouldContainExactly listOf(2021, 2016)
        request.officialRatings shouldContainExactly listOf("PG-13")
        request.isPlayed shouldBe false
        request.isFavorite shouldBe true
    }

    @Test
    fun `omits filters the user did not set`() {
        val request = ItemQuery().request()

        request.genres.shouldBeEmpty()
        request.years.shouldBeEmpty()
        request.isPlayed.shouldBeNull()
        request.isFavorite.shouldBeNull()
    }

    @Test
    fun `sends a search term only when it carries text`() {
        ItemQuery(searchTerm = "dune").request().searchTerm shouldBe "dune"
        ItemQuery(searchTerm = "   ").request().searchTerm.shouldBeNull()
        ItemQuery(searchTerm = null).request().searchTerm.shouldBeNull()
    }

    @Test
    fun `a query without a library asks across the whole user root`() {
        ItemQuery().request().parentId.shouldBeNull()
    }

    @Test
    fun `drops item types the server has no name for`() {
        val request = ItemQuery(itemTypes = listOf(ItemType.MOVIE, ItemType.UNKNOWN)).request()

        request.includeItemTypes shouldContainExactly listOf(BaseItemKind.MOVIE)
    }

    @Test
    fun `maps the remaining item types`() {
        ItemType.SEASON.toBaseItemKind() shouldBe BaseItemKind.SEASON
        ItemType.EPISODE.toBaseItemKind() shouldBe BaseItemKind.EPISODE
        ItemType.COLLECTION_FOLDER.toBaseItemKind() shouldBe BaseItemKind.COLLECTION_FOLDER
        ItemType.FOLDER.toBaseItemKind() shouldBe BaseItemKind.FOLDER
        ItemType.UNKNOWN.toBaseItemKind().shouldBeNull()
    }

    @Test
    fun `maps the sort direction`() {
        SortOrder.ASCENDING.toSdk() shouldBe SdkSortOrder.ASCENDING
        SortOrder.DESCENDING.toSdk() shouldBe SdkSortOrder.DESCENDING
    }
}
