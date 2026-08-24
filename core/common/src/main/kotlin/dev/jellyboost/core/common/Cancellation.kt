package dev.jellyboost.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching], minus the one thing it must never catch.
 *
 * `kotlin.runCatching` catches [Throwable], which inside a coroutine includes the [CancellationException]
 * structured concurrency throws to unwind a cancelled job. Swallowing it turns "the screen went away" into
 * "the operation failed": the coroutine runs past its scope's death, the caller reports an error nobody asked
 * about, and a retry budget is spent on a user-initiated abort. Found and fixed three separate times here.
 *
 * Not a substitute for narrowing: where a block can only fail one way, a typed `catch` still says more.
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
