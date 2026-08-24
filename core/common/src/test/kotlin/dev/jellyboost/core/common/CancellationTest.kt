package dev.jellyboost.core.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The cancellation case is written as a *real* cancellation rather than a hand-thrown
 * [CancellationException]: what has to survive the `catch` is the one the coroutines machinery throws to
 * unwind a cancelled job, and only a genuinely cancelled coroutine produces it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CancellationTest {
    @Test
    fun `a block that returns hands back its value`() {
        runCatchingUnlessCancelled { "sidecar" } shouldBe Result.success("sidecar")
    }

    @Test
    fun `a block that throws comes back as a failure holding what it threw`() {
        val result = runCatchingUnlessCancelled { throw IOException("disk full") }

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<IOException>().message shouldBe "disk full"
    }

    @Test
    fun `a cancelled coroutine is not caught — it unwinds`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var swallowed: Result<Unit>? = null

            val job =
                launch {
                    runCatchingUnlessCancelled {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }.also { swallowed = it }
                }

            entered.await()
            job.cancel()
            job.join()

            // Had the helper caught it, the job would have finished normally instead of ending cancelled.
            swallowed shouldBe null
            job.isCancelled shouldBe true
        }
}
