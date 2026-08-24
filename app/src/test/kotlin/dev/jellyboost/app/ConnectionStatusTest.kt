package dev.jellyboost.app

import dev.jellyboost.core.network.ConnectionState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ConnectionStatusTest {
    @Test
    @DisplayName("an online app has no status to show")
    fun onlineHasNoStatus() {
        ConnectionState.ONLINE.toStatus() shouldBe null
    }

    @Test
    @DisplayName("each offline reason maps to its own status")
    fun offlineReasonsMapToTheirOwnStatus() {
        ConnectionState.OFFLINE_NO_NETWORK.toStatus() shouldBe ConnectionStatus.NO_NETWORK
        ConnectionState.OFFLINE_SERVER_UNREACHABLE.toStatus() shouldBe ConnectionStatus.SERVER_UNREACHABLE
        ConnectionState.OFFLINE_FORCED.toStatus() shouldBe ConnectionStatus.FORCED
    }

    @Test
    @DisplayName("every status explains itself with its own message")
    fun everyStatusHasItsOwnMessage() {
        val messages = ConnectionStatus.entries.map { it.messageRes }
        messages.distinct().size shouldBe ConnectionStatus.entries.size
        messages.all { it != 0 } shouldBe true
    }

    @Test
    @DisplayName("only the two recoverable reasons offer an action")
    fun onlyRecoverableReasonsOfferAnAction() {
        // Nothing to retry while there is no network at all.
        ConnectionStatus.NO_NETWORK.actionLabelRes shouldBe null
        (ConnectionStatus.SERVER_UNREACHABLE.actionLabelRes != null) shouldBe true
        (ConnectionStatus.FORCED.actionLabelRes != null) shouldBe true
    }
}
