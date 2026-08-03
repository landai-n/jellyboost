package dev.jellyboost.player.syncplay.di

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SyncPlayScopeModule] — the two provider properties the SyncPlay audit pinned.
 */
class SyncPlayModuleTest {
    /**
     * The socket client must send RFC 6455 pings (audit SP-16): the app-level keep-alive succeeds
     * into the send buffer of a half-open connection for minutes, so without protocol pings a NAT
     * timeout leaves the socket reading `Connected` for ever and the reconnect loop never runs.
     */
    @Test
    fun `the socket client pings, so a half-open connection fails instead of idling`() {
        val factory = SyncPlayScopeModule.provideSyncPlaySocketFactory()

        (factory as OkHttpClient).pingIntervalMillis shouldBe 30_000
    }

    /**
     * The scope must run its coroutines one at a time (audit SP-07): the controller's and the
     * scheduler's session bookkeeping are plain unguarded fields, and serial execution with a
     * happens-before edge between tasks is the synchronization the whole design leans on. On a
     * parallel pool this loses increments; on the confined dispatcher the count is exact, always.
     */
    @Test
    fun `the SyncPlay scope executes its coroutines serially`() =
        runBlocking {
            val scope = SyncPlayScopeModule.provideSyncPlayScope()
            var counter = 0

            (1..10_000).map { scope.launch { counter++ } }.joinAll()

            counter shouldBe 10_000
            scope.cancel()
        }
}
