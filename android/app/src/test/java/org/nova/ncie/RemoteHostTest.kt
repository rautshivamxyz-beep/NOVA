package org.nova.ncie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.50: plain http to a public address is refused, because the server
 * password travels as a bearer token and the question as the body - both
 * readable by anything on the path.
 */
class RemoteHostTest {

    @Test
    fun aPublicAddressOverPlainHttpIsRefused() {
        assertTrue(RemoteHost.isInsecurePublic("http://80.1.2.3:8080"))
        assertTrue(RemoteHost.isInsecurePublic("http://example.com:8080"))
        assertTrue(RemoteHost.isInsecurePublic("http://130.61.20.7:8080/v1/chat/completions"))
    }

    @Test
    fun theUsersOwnNetworkIsAllowed() {
        assertTrue(!RemoteHost.isInsecurePublic("http://192.168.1.5:8080"))
        assertTrue(!RemoteHost.isInsecurePublic("http://10.0.0.9:8080"))
        assertTrue(!RemoteHost.isInsecurePublic("http://172.16.4.4:8080"))
        assertTrue(!RemoteHost.isInsecurePublic("http://localhost:8080"))
        assertTrue(!RemoteHost.isInsecurePublic("http://my-laptop:8080"))
    }

    @Test
    fun tailscaleIsAllowedBecauseItIsTheRecommendedRoute() {
        // 100.64.0.0/10 - a private overlay, not the open internet
        assertTrue(!RemoteHost.isInsecurePublic("http://100.101.102.103:8080"))
    }

    @Test
    fun httpsIsAlwaysAllowed() {
        assertFalse(RemoteHost.isInsecurePublic("https://my-server.example.com"))
        assertFalse(RemoteHost.isInsecurePublic("https://80.1.2.3:8443"))
    }

    @Test
    fun anUnreadableHttpAddressIsTreatedAsPublic() {
        // no host at all - do not guess in the permissive direction
        assertTrue(RemoteHost.isInsecurePublic("http://"))
    }

    @Test
    fun theHostIsReadCorrectly() {
        assertEquals("192.168.1.5", RemoteHost.hostOf("http://192.168.1.5:8080"))
        assertEquals("192.168.1.5", RemoteHost.hostOf("http://192.168.1.5"))
        assertEquals("example.com", RemoteHost.hostOf("http://example.com:8080/v1/models"))
        assertEquals("example.com", RemoteHost.hostOf("https://example.com"))
    }

    @Test
    fun privateRangesAreRecognised() {
        assertTrue(RemoteHost.isPrivate("192.168.0.1"))
        assertTrue(RemoteHost.isPrivate("172.31.255.254"))
        assertTrue(RemoteHost.isPrivate("169.254.1.1"))
        assertFalse(RemoteHost.isPrivate("172.32.0.1"))
        assertFalse(RemoteHost.isPrivate("8.8.8.8"))
        assertFalse(RemoteHost.isPrivate("not a host"))
    }
}
