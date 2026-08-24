package dev.jellyboost.feature.auth

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ServerAddressSafetyTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://jellyfin.example.com",
            "http://jellyfin.example.com:8096",
            "http://jellyfin.example.com:8096/jellyfin",
            "HTTP://JELLYFIN.EXAMPLE.COM",
            "http://203.0.113.7:8096",
            "http://[2001:db8::1]:8096",
            "http://notlocal.homelab.net",
        ],
    )
    fun `plain http to somewhere off the local network warns`(address: String) {
        isCleartextPublicAddress(address) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://jellyfin.example.com",
            "https://203.0.113.7:8096",
            "HTTPS://jellyfin.example.com",
            "http://localhost:8096",
            "http://127.0.0.1:8096",
            "http://127.1.2.3",
            "http://[::1]:8096",
            "http://192.168.1.10:8096",
            "http://10.0.0.5",
            "http://172.16.0.1",
            "http://172.31.255.254",
            "http://169.254.10.1",
            "http://100.64.0.1",
            "http://100.127.255.255",
            "http://[fd12:3456::1]:8096",
            "http://[fe80::1]:8096",
            "http://jellyfin.local:8096",
            "http://nas.lan",
            "http://server.home",
            "http://box.internal",
            "http://jellyfin.home.arpa",
            "http://nas:8096",
            "http://jellyfin",
        ],
    )
    fun `encrypted, loopback, private and local-only addresses stay quiet`(address: String) {
        isCleartextPublicAddress(address) shouldBe false
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "http://", "http://:8096", "not an address at all"])
    fun `nothing usable is never warned about`(address: String) {
        isCleartextPublicAddress(address) shouldBe false
    }

    @Test
    fun `172 is only private in the middle of its range`() {
        // The range that is misremembered more than any other: 172.16-31, not all of 172.
        isCleartextPublicAddress("http://172.15.0.1") shouldBe true
        isCleartextPublicAddress("http://172.32.0.1") shouldBe true
        isCleartextPublicAddress("http://172.20.0.1") shouldBe false
    }

    @Test
    fun `something that only looks like an IPv4 address is judged as a name`() {
        isCleartextPublicAddress("http://10.0.0.example") shouldBe true
        isCleartextPublicAddress("http://192.168.1.999") shouldBe true
    }

    @Test
    fun `the host is what gets classified, not the userinfo in front of it`() {
        hostOf("http://admin@jellyfin.example.com:8096/") shouldBe "jellyfin.example.com"
        isCleartextPublicAddress("http://admin@192.168.1.10:8096") shouldBe false
    }
}
