package dev.jellyboost.core.common

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A latch that lets a block run exactly once, however many times it is asked.
 *
 * ### Why the app needs one at all
 * `:app` starts its process-lifetime collaborators from `Application.onCreate` — the browse-cache
 * sweep, the user-data drain trigger, the downloaded-metadata refresher. Each of those `start()`
 * calls launches a coroutine that then lives for the life of the process, usually collecting a
 * `StateFlow` that never completes. Calling one twice does not do the work twice; it leaves a
 * *second* collector running forever, doubling every subsequent edge and every request that edge
 * causes, with nothing to make the duplication visible.
 *
 * That is not hypothetical. Android re-creates the `Application` object on some process restarts
 * without re-creating the Hilt singletons that hang off it, and the same three collaborators are
 * also reachable from tests and from any future caller with a better moment than `onCreate`. So
 * `start()` has to be idempotent by construction rather than by everybody remembering.
 *
 * ### Why a class and not a `Boolean`
 * [AtomicBoolean.compareAndSet] is the whole point: two threads racing `start()` must produce one
 * winner, and a plain `if (!started) { started = true; … }` gives them both the block. Every call
 * site needs exactly this reasoning, so it is written once here instead of once per field.
 *
 * ```kotlin
 * private val startOnce = StartOnce()
 *
 * fun start() {
 *     startOnce { scope.launch { collectForever() } }
 * }
 * ```
 *
 * Deliberately *not* a general memoizer: it holds no result, so it says nothing about whether the
 * work has finished — only that it has been asked for. Every current caller starts a coroutine and
 * returns immediately, which is exactly the shape this fits.
 */
class StartOnce {
    private val started = AtomicBoolean(false)

    /** Runs [block] on the first call; every later call does nothing and returns immediately. */
    operator fun invoke(block: () -> Unit) {
        if (started.compareAndSet(false, true)) block()
    }
}
