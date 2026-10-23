package org.nova

import android.content.Context
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * v9.15.0 "Private Fetch": the single place every outbound HTTP(S)
 * request goes through, so NOVA can route its online lookups through a
 * SOCKS proxy - Orbot/Tor, or any proxy the user controls - and refuse
 * cleartext.
 *
 * Design law, kept: nothing here changes unless the user turns the proxy
 * on. With it off, requests go direct, exactly as before.
 */
object NovaNet {

    /** Orbot's default SOCKS endpoint on the same device. */
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 9050

    /** The proxy to use, or null for a direct connection. */
    fun proxy(context: Context?): Proxy? {
        if (context == null) return null
        val s = Settings(context.applicationContext)
        if (!s.proxyEnabled) return null
        val host = s.proxyHost.ifBlank { DEFAULT_HOST }
        val port = if (s.proxyPort in 1..65535) s.proxyPort else DEFAULT_PORT
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

    /**
     * Opens an HttpURLConnection over the configured proxy (or direct),
     * with privacy-friendly defaults: a generic User-Agent, a bounded
     * timeout, no extra identifying headers. https is expected; callers
     * should not pass cleartext URLs.
     */
    fun open(context: Context?, url: String, connectMs: Int = 10000,
             readMs: Int = 20000): HttpURLConnection {
        val u = URL(url)
        val p = proxy(context)
        val conn = (if (p != null) u.openConnection(p) else u.openConnection())
            as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        // v9.26.0 "Search fix": a real browser User-Agent. The old
        // "NOVA-local-assistant/1.0" string made DuckDuckGo serve an
        // anti-bot page with no result links at all, so every web search
        // came back empty. A common browser UA also blends in with the
        // crowd, so this is better for privacy than a unique app string.
        conn.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Accept",
            "text/html,application/xhtml+xml,application/json,text/plain,*/*")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return conn
    }

    /**
     * v9.33.3 "Really private": a check the user can actually run. With the
     * proxy on but Orbot not running, every lookup failed with "couldn't find
     * anything" - which looks identical to a lookup that simply found nothing,
     * so the proxy looked broken with no way to tell. This reports the exit
     * address, so the answer is unambiguous.
     */
    fun testProxy(context: Context?): String {
        if (context == null) return "No app context - cannot test."
        val s = Settings(context.applicationContext)
        if (!s.proxyEnabled)
            return "The SOCKS proxy is OFF, so lookups go out with this phone's own IP. " +
                "Turn it on in Settings, under Privacy."
        val host = s.proxyHost.ifBlank { DEFAULT_HOST }
        val port = if (s.proxyPort in 1..65535) s.proxyPort else DEFAULT_PORT
        return try {
            val ip = getText(context, "https://api.ipify.org", 8000, 12000).trim()
            if (ip.length in 7..45 && ip.all { it.isDigit() || it == '.' || it in "abcdefABCDEF:" })
                "The proxy works. Sites NOVA reads now see " + ip + " instead of your own address."
            else
                "The proxy answered, but not with an address. Check " + host + ":" + port + "."
        } catch (e: Exception) {
            "Could not reach the internet through " + host + ":" + port + " (" +
                e.javaClass.simpleName + "). Start Orbot and make sure its SOCKS port is " +
                port + "."
        }
    }

    /** v9.33.58: hard cap on any response body - a hostile or huge page can
     *  never OOM the app. 4 MB is far more than any article NOVA reads. */
    private const val MAX_BODY = 4 * 1024 * 1024

    /** GET a URL as text, through the configured proxy (or direct). */
    fun getText(context: Context?, url: String, connectMs: Int = 10000,
                readMs: Int = 20000): String {
        val conn = open(context, url, connectMs, readMs)
        try {
            // v9.33.58 (improvement pass): force the connection (and any
            // redirects) and refuse an https -> http downgrade, so a lookup
            // can never silently fall back to cleartext.
            conn.responseCode
            val finalUrl = conn.url.toString()
            if (url.startsWith("https", ignoreCase = true) &&
                !finalUrl.startsWith("https", ignoreCase = true))
                throw java.io.IOException("refusing cleartext redirect to " + finalUrl)
            return conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(64 * 1024)
                val sb = StringBuilder()
                while (sb.length < MAX_BODY) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, minOf(n, MAX_BODY - sb.length))
                }
                sb.toString()
            }
        } finally {
            conn.disconnect()
        }
    }
}
