package org.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsintCustomSourceTest {
    @Test
    fun `accepts a named HTTPS template with query placeholder`() {
        assertTrue(OsintCustomSource("Research API",
            "https://search.example.org/v1?q={query}").isValid())
    }

    @Test
    fun `rejects cleartext HTTP`() {
        assertFalse(OsintCustomSource("Unsafe", "http://search.example.org?q={query}").isValid())
    }

    @Test
    fun `requires an encoded query placeholder`() {
        assertFalse(OsintCustomSource("Static", "https://search.example.org/status").isValid())
    }

    @Test
    fun `rejects embedded credentials and unexpected ports`() {
        assertFalse(OsintCustomSource("Credential URL",
            "https://user:pass@search.example.org?q={query}").isValid())
        assertFalse(OsintCustomSource("Odd port",
            "https://search.example.org:8443?q={query}").isValid())
    }

    @Test
    fun `rejects blank or overlong names and malformed URLs`() {
        assertFalse(OsintCustomSource(" ", "https://search.example.org?q={query}").isValid())
        assertFalse(OsintCustomSource("x".repeat(51),
            "https://search.example.org?q={query}").isValid())
        assertFalse(OsintCustomSource("Broken", "not a URL").isValid())
    }
}
