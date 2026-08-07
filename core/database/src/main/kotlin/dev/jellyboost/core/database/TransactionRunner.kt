package dev.jellyboost.core.database

import androidx.room.withTransaction

/**
 * Runs several DAO calls as **one** database transaction, without handing the caller the database.
 *
 * The DAOs are deliberately dumb (see `ItemDao`'s own note): every rule the app has about *which*
 * row wins lives in `:data`, in plain Kotlin, so it can be unit tested on the JVM instead of only
 * on a device. That is worth keeping — but a read-decide-write sequence expressed as three separate
 * DAO calls is not atomic, and the browse cache had exactly that bug: `BrowseCacheWriter` read the
 * existing rows' sources, spent a merge deciding what to write, and upserted — while
 * `DownloadEnqueuer` could turn one of those rows into a `DOWNLOAD` in between. The stale snapshot
 * then wrote `BROWSE_CACHE` back over it with a lean blob, which is precisely the downgrade the
 * merge exists to prevent (audit 2026-08-06, HYG-3).
 *
 * This seam is how both properties are had at once: the *decision* stays a pure function in `:data`,
 * and the read that feeds it plus the write that follows it run inside one transaction, so nothing
 * can change underneath them. Tests substitute an implementation that simply runs the block.
 *
 * The block runs on Room's own transaction dispatcher, so a suspending body is safe here in a way
 * `RoomDatabase.runInTransaction` is not; it must still avoid work that is not database work.
 */
interface TransactionRunner {
    /**
     * Runs [block] in a transaction, committing on normal return and rolling back if it throws.
     *
     * Nesting is safe — an inner call joins the transaction the outer one opened.
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** The real thing: Room's `withTransaction`, which is what makes a *suspending* block safe. */
internal class RoomTransactionRunner(
    private val database: JellyfinDatabase,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)
}
