package dev.jellyboost.data

import dev.jellyboost.core.common.model.ItemType
import dev.jellyboost.core.database.dao.ItemDao
import dev.jellyboost.core.database.entities.DownloadedItemKey
import dev.jellyboost.core.database.entities.ItemEntity
import dev.jellyboost.core.database.entities.ItemSource
import io.mockk.coEvery
import java.util.UUID

/** What the grid stub captured of the arguments the repository handed the DAO. */
internal class GridStubs(
    val types: MutableList<List<ItemType>> = mutableListOf(),
    val descending: MutableList<Boolean> = mutableListOf(),
)

/**
 * Stands the offline grid's two reads up over [rows]: the ordered key projection, then the page's
 * blobs.
 *
 * The keys are answered **in the order given** rather than re-sorted here — ordering is the
 * statement's job, and a fake that re-implemented `COLLATE NOCASE` would only be pinning itself.
 *
 * @param played the ids this user has watched; everything else is unwatched, which is what the
 *   query's `LEFT JOIN`/`COALESCE` produces for an item with no `user_data` row.
 * @param readIds collects the id lists the page read asked for, for the test that pins that the grid
 *   reads one page of blobs and not the whole library.
 */
internal fun stubGrid(
    itemDao: ItemDao,
    rows: List<ItemEntity>,
    played: Set<UUID> = emptySet(),
    favorites: Set<UUID> = emptySet(),
    readIds: MutableList<List<UUID>> = mutableListOf(),
): GridStubs {
    val stubs = GridStubs()
    coEvery {
        itemDao.downloadedListKeys(
            source = ItemSource.DOWNLOAD,
            types = capture(stubs.types),
            userId = any(),
            descending = capture(stubs.descending),
        )
    } returns
        rows.map { row ->
            DownloadedItemKey(
                id = row.id,
                genres = row.genres,
                productionYear = row.productionYear,
                officialRating = row.officialRating,
                played = row.id in played,
                isFavorite = row.id in favorites,
            )
        }
    coEvery { itemDao.getItems(any()) } answers {
        val ids = firstArg<List<UUID>>()
        readIds += ids
        rows.filter { it.id in ids.toSet() }
    }
    return stubs
}
