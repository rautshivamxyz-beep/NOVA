package org.nova.ncie

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Remote destination safety checks for private-only mode.
 *
 * A bearer key is not encryption. Plain HTTP to a public host exposes both the
 * credential and prompt, so private-only mode refuses that route.
 *
 * This parser deliberately fails closed: malformed URLs, user-info credentials,
 * missing hosts, and unrecognised address formats are not treated as private.
 */
object RemoteHost {

    /** True when [url] is plain HTTP and the destination cannot be proven local. */
    fun isInsecurePublic(url: String): Boolean {
        val trimmed = url.trim()
        val uri = try { URI(trimmed) } catch (_: Exception) { return true }
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        // User-info in a URL is unnecessary for NOVA and makes destination
        // inspection easier to get wrong. Refuse it instead of guessing.
        if (uri.rawUserInfo != null) return true
        val host = hostOf(trimmed) ?: return true
        return !isPrivate(host)
    }

    /** Parse the actual URI authority; return null for malformed or ambiguous URLs. */
    fun hostOf(url: String): String? {
        val uri = try { URI(url.trim()) } catch (_: Exception) { return null }
        if (!uri.scheme.equals("http", ignoreCase = true) &&
            !uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host ?: return null
        return host.removePrefix("[").removeSuffix("]").ifBlank { null }
    }

    /**
     * Recognise RFC1918 IPv4, loopback, link-local, Tailscale CGNAT, and IPv6
     * loopback/ULA/link-local. Hostnames are allowed only for explicit local
     * naming conventions; arbitrary dotted hostnames are not presumed private.
     */
    fun isPrivate(host: String): Boolean {
        val h = host.lowercase().trim()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal")) return true

        if (h.contains(':')) {
            val address = try { InetAddress.getByName(h) } catch (_: Exception) { return false }
            if (address !is Inet6Address) return false
            val b = address.address
            // ::1 loopback
            if (b.dropLast(1).all { it.toInt() == 0 } && b.last().toInt() == 1) return true
            val first = b[0].toInt() and 0xff
            val second = b[1].toInt() and 0xff
            // fc00::/7 unique-local and fe80::/10 link-local
            return (first and 0xfe) == 0xfc ||
                (first == 0xfe && (second and 0xc0) == 0x80)
        }

        if (!h.contains('.')) {
            // Single-label machine names are used on home LANs, but malformed
            // labels must not accidentally pass as a trusted destination.
            return h.all { it.isLetterOrDigit() || it == '-' } &&
                h.firstOrNull()?.isLetterOrDigit() == true &&
                h.lastOrNull()?.isLetterOrDigit() == true
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168) ||
            n[0] == 127 ||
            (n[0] == 100 && n[1] in 64..127) ||
            (n[0] == 169 && n[1] == 254)
    }
}
