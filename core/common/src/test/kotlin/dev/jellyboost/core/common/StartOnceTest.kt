package dev.jellyboost.core.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [StartOnce] — the latch behind the three app-scope collaborators' `start()`.
 *
 * Two properties, and the second is the reason the class exists rather than a `Boolean`: a repeat
 * call does nothing, and two threads arriving together still produce exactly one run.
 */
class StartOnceTest {
    @Test
    fun `runs the block on the first call`() {
        val runs = AtomicInteger(0)
        val startOnce = StartOnce()

        startOnce { runs.incrementAndGet() }

        runs.get() shouldBe 1
    }

    @Test
    fun `does nothing on every later call`() {
        val runs = AtomicInteger(0)
        val startOnce = StartOnce()

        repeat(5) { startOnce { runs.incrementAndGet() } }

        runs.get() shouldBe 1
    }

    @Test
    fun `two instances are independent`() {
        val runs = AtomicInteger(0)
        val first = StartOnce()
        val second = StartOnce()

        first { runs.incrementAndGet() }
        second { runs.incrementAndGet() }

        runs.get() shouldBe 2
    }

    /**
     * The whole point of `compareAndSet`. A plain `if (!started) { started = true; … }` lets both
     * threads through, and the symptom in production would be a second forever-collector nobody can
     * see — so this is asserted directly rather than trusted to the field's type.
     */
    @Test
    fun `only one of many threads racing the latch runs the block`() {
        val threads = 16
        val runs = AtomicInteger(0)
        val startOnce = StartOnce()
        val everyoneReady = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        try {
            repeat(threads) {
                pool.execute {
                    everyoneReady.countDown()
                    go.await()
                    startOnce { runs.incrementAndGet() }
                    done.countDown()
                }
            }
            everyoneReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            go.countDown()
            done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
        } finally {
            pool.shutdownNow()
        }

        runs.get() shouldBe 1
    }

    private companion object {
        /** Generous: the assertion is on the count, never on how long the pool took to get there. */
        const val TIMEOUT_SECONDS = 10L
    }
}
