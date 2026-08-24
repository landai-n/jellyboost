package dev.jellyboost.core.database

import androidx.room.withTransaction

/**
 * Runs several DAO calls as **one** database transaction, without handing the caller the database.
 *
 * A read-decide-write sequence expressed as three DAO calls is not atomic, and the browse cache had exactly
 * that bug: `BrowseCacheWriter` read the rows' sources, spent a merge deciding, and upserted — while
 * `DownloadEnqueuer` turned one of them into a `DOWNLOAD` in between, so the stale snapshot wrote
 * `BROWSE_CACHE` back over it. The *decision* stays a pure function in `:data`; the read that feeds it and
 * the write that follows run inside one transaction.
 *
 * The block runs on Room's own transaction dispatcher, so a suspending body is safe here in a way
 * `RoomDatabase.runInTransaction` is not; it must still avoid work that is not database work.
 */
interface TransactionRunner {
    /** Nesting is safe — an inner call joins the transaction the outer one opened. */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

internal class RoomTransactionRunner(
    private val database: JellyfinDatabase,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)
}
