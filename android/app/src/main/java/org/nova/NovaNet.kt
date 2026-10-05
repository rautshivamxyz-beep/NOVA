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

    /** GET a URL as text, through the configured proxy (or direct). */
    fun getText(context: Context?, url: String, connectMs: Int = 10000,
                readMs: Int = 20000): String {
        val conn = open(context, url, connectMs, readMs)
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
