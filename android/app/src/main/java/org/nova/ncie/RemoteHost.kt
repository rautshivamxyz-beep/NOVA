package org.nova.ncie

/**
 * v9.33.50 "Nothing in the clear".
 *
 * A password does not encrypt a connection. NOVA sends the server password as a
 * bearer token and the question as the body - over plain http both are readable
 * by anything on the path, and the token can simply be replayed.
 *
 * On the user's OWN network that is a fair trade: it is their cable, and the
 * alternative is no feature at all. Across the open internet it is not a trade,
 * it is a leak - so this refuses it, and the answer is produced on the phone.
 *
 * Tailscale works through here on purpose: it hands out addresses in
 * 100.64.0.0/10, which is in the private list below, so the private-network
 * route the guide recommends is allowed while a bare public IP is not.
 *
 * Pure string logic, no Android, unit-tested in android/app/src/test/java.
 */
object RemoteHost {

    /** True when [url] is plain http to a host that is NOT on the user's own network. */
    fun isInsecurePublic(url: String): Boolean {
        val u = url.trim()
        // https is fine, and so is anything that is not http at all
        if (!u.startsWith("http://", ignoreCase = true)) return false
        val host = hostOf(u) ?: return true   // an http address we cannot read = assume public
        return !isPrivate(host)
    }

    /** The host part of [url], without scheme, path or port. Null if there is none. */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.substringBefore('/')
        // strip a :port, but leave a bare host alone
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']').removePrefix("[")   // [::1]:8080
        } else {
            authority.substringBeforeLast(':', authority)
        }
        return host.ifBlank { null }
    }

    /**
     * Is this host on a private network? Covers the RFC1918 ranges, loopback,
     * link-local names, and Tailscale's 100.64/10 carrier-grade NAT range.
     */
    fun isPrivate(host: String): Boolean {
        val h = host.lowercase().trim()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1" || h.endsWith(".local") || h.endsWith(".internal")) return true
        // A name with no dot cannot resolve on the public internet - but it has
        // to at least LOOK like a hostname. "not a host" is not one, and treating
        // it as private would be guessing in the permissive direction.
        if (!h.contains('.')) return h.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 ||                              // 10/8
            (n[0] == 172 && n[1] in 16..31) ||            // 172.16/12
            (n[0] == 192 && n[1] == 168) ||               // 192.168/16
            n[0] == 127 ||                                // loopback
            (n[0] == 100 && n[1] in 64..127) ||           // 100.64/10 - Tailscale
            (n[0] == 169 && n[1] == 254)                  // link-local
    }
}
