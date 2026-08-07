package dev.jellyboost.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching], minus the one thing `runCatching` must never catch.
 *
 * `kotlin.runCatching` catches [Throwable], and inside a coroutine that includes the
 * [CancellationException] the structured-concurrency machinery throws to unwind a cancelled job.
 * Swallowing it turns "the screen went away" into "the operation failed": the coroutine keeps
 * running past its scope's death, the caller reports an error nobody asked about, and a retry
 * budget is spent on a user-initiated abort. That hazard has been found and fixed in this codebase
 * three separate times (`SubtitleSidecarTopUp`, the `DownloadQueue.reconcile` comment, the
 * `DownloadWorker` decision record) — this is the one place it is now solved.
 *
 * Semantics are otherwise `runCatching`'s exactly: the value on success, a [Result.failure] holding
 * whatever else was thrown, and a cancellation propagated untouched to the coroutine that owns it.
 *
 * ```kotlin
 * val groups = runCatchingUnlessCancelled { api.getGroups() }
 *     .onFailure { Timber.w(it, "Could not list SyncPlay groups") }
 *     .getOrNull()
 * ```
 *
 * Note this is *not* a substitute for narrowing: where a block can only fail one way (pure Room, so
 * `SQLiteException`), a typed `catch` still says more than a `Result` does. Reach for this where the
 * failure really is "anything at all" — a fire-and-forget pass whose only contract is that it never
 * throws.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> runCatchingUnlessCancelled(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }
