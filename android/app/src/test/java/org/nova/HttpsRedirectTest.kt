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
}
