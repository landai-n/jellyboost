package dev.jellyboost.core.common

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A latch that lets a block run exactly once, however many times it is asked.
 *
 * [AtomicBoolean.compareAndSet] is the whole point: two threads racing `start()` must produce one winner,
 * where a plain `if (!started) { started = true; … }` gives them both the block. The callers start
 * process-lifetime collectors, so a second run leaves a duplicate collector alive forever with nothing to
 * make it visible.
 *
 * Deliberately *not* a memoizer: it holds no result, so it says nothing about whether the work has finished.
 */
class StartOnce {
    private val started = AtomicBoolean(false)

    operator fun invoke(block: () -> Unit) {
        if (started.compareAndSet(false, true)) block()
    }
}
