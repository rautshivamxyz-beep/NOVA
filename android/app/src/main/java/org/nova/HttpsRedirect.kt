package org.nova

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Validates a redirect before opening its destination. */
internal object HttpsRedirect {
    fun resolve(currentUrl: String, location: String): String {
        val base = URI(currentUrl)
        require(base.scheme.equals("https", ignoreCase = true) &&
            !base.host.isNullOrBlank() && base.rawUserInfo == null) {
            "HTTPS redirect source required"
        }
        val target = try {
            base.resolve(URI(location.trim())).normalize()
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid redirect destination", e)
        }
        val host = target.host
        require(target.scheme.equals("https", ignoreCase = true) &&
            !host.isNullOrBlank() && target.rawUserInfo == null) {
            "Refusing non-HTTPS or credential-bearing redirect"
        }
        require(!isUnsafeDestination(host!!)) {
            "Refusing HTTPS redirect to a local or non-public destination"
        }
        return target.toASCIIString()
    }

    /**
     * Redirects are not allowed to turn an ordinary public fetch into a
     * request to loopback, RFC1918, link-local, carrier-grade NAT, or a
     * single-label/local hostname. Do not resolve DNS names here: doing so
     * would bypass the configured SOCKS resolver and leak DNS locally.
     * DNS-rebinding of a public name remains a network-layer limitation.
     */
    private fun isUnsafeDestination(rawHost: String): Boolean {
        val host = rawHost.removePrefix("[").removeSuffix("]").trimEnd('.')
            .lowercase()
        if (host.isBlank() || '%' in host) return true

        if (host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".lan") ||
            host.endsWith(".home.arpa")) return true

        if (':' in host) {
            val address = try { InetAddress.getByName(host) }
                catch (_: Exception) { return true }
            // Mapped IPv4 is rejected too: it is easy to overlook when
            // checking only the IPv6 ULA/link-local ranges.
            if (address !is Inet6Address) return true
            val b = address.address
            val first = b[0].toInt() and 0xff
            return address.isAnyLocalAddress || address.isLoopbackAddress ||
                address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress || (first and 0xfe) == 0xfc
        }

        val ascii = try { IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase() }
            catch (_: Exception) { return true }

        // Treat alternative numeric IPv4 spellings (127.1, one integer,
        // octal/hex-like forms) as unsafe rather than passing them to a resolver.
        if (ascii.all { it.isDigit() || it == '.' }) {
            val parts = ascii.split('.')
            if (parts.size != 4) return true
            val octets = parts.map { it.toIntOrNull() ?: return true }
            if (octets.any { it !in 0..255 }) return true
            return unsafeIpv4(octets)
        }

        // Do not allow search-suffix expansion of names such as "printer".
        return !ascii.contains('.')
    }

    private fun unsafeIpv4(o: List<Int>): Boolean {
        val a = o[0]
        val b = o[1]
        val c = o[2]
        return a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 192 && b == 0) ||
            (a == 198 && b in 18..19) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113)
    }
}
