package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpsRedirectTest {
    @Test
    fun resolvesRelativeHttpsRedirect() {
        assertEquals("https://example.com/new/path",
            HttpsRedirect.resolve("https://example.com/old/page", "../new/path"))
    }

    @Test
    fun refusesHttpsDowngrade() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsRedirect.resolve("https://example.com/a", "http://example.net/b")
        }
    }

    @Test
    fun refusesCredentialBearingRedirect() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsRedirect.resolve("https://example.com/a", "https://user:pass@example.net/b")
        }
    }

    @Test
    fun refusesNonHttpsSource() {
        assertThrows(IllegalArgumentException::class.java) {
            HttpsRedirect.resolve("http://example.com/a", "/b")
        }
    }

    @Test
    fun refusesLoopbackAndPrivateIpv4Redirects() {
        for (destination in listOf(
            "https://127.0.0.1/admin",
            "https://10.0.0.8/",
            "https://172.16.0.4/",
            "https://192.168.1.1/",
            "https://169.254.169.254/latest/meta-data/"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                HttpsRedirect.resolve("https://example.com/a", destination)
            }
        }
    }

    @Test
    fun refusesLoopbackAndUniqueLocalIpv6Redirects() {
        for (destination in listOf("https://[::1]/", "https://[fc00::1]/", "https://[fe80::1]/")) {
            assertThrows(IllegalArgumentException::class.java) {
                HttpsRedirect.resolve("https://example.com/a", destination)
            }
        }
    }

    @Test
    fun refusesLocalAndSingleLabelHostnames() {
        for (destination in listOf(
            "https://localhost/", "https://printer.local/", "https://nas/",
            "https://service.internal/", "https://router.home.arpa/"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                HttpsRedirect.resolve("https://example.com/a", destination)
            }
        }
    }

    @Test
    fun refusesNonCanonicalNumericIpSpellings() {
        for (destination in listOf("https://127.1/", "https://2130706433/")) {
            assertThrows(IllegalArgumentException::class.java) {
                HttpsRedirect.resolve("https://example.com/a", destination)
            }
        }
    }

    @Test
    fun allowsPublicHostnamesAndPublicIpLiterals() {
        assertEquals("https://example.org/path",
            HttpsRedirect.resolve("https://example.com/a", "https://example.org/path"))
        assertEquals("https://1.1.1.1/",
            HttpsRedirect.resolve("https://example.com/a", "https://1.1.1.1/"))
    }
}
