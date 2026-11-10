package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Small, bounded OSINT collectors. All network traffic uses NovaNet (including SOCKS). */
data class OsintFinding(
    val title: String,
    val value: String,
    val source: String,
    val confidence: String = "Source-reported"
)

data class OsintReport(
    val kind: String,
    val target: String,
    val createdAt: Long,
    val findings: List<OsintFinding>,
    val notes: List<String>
) {
    fun asText(): String = buildString {
        append("NOVA OSINT REPORT\n")
        append("Type: ").append(kind).append("\nTarget: ").append(target)
        append("\nTime: ").append(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(createdAt)))
        append("\n\nFINDINGS\n")
        if (findings.isEmpty()) append("No verified findings.\n")
        findings.forEach {
            append("\n• ").append(it.title).append("\n  ").append(it.value)
            append("\n  Source: ").append(it.source)
            append("\n  Confidence: ").append(it.confidence).append("\n")
        }
        append("\nLIMITATIONS\n")
        notes.forEach { append("• ").append(it).append("\n") }
    }

    fun asJson(): String {
        val rows = JSONArray()
        findings.forEach { f ->
            rows.put(JSONObject().put("title", f.title).put("value", f.value)
                .put("source", f.source).put("confidence", f.confidence))
        }
        val caveats = JSONArray()
        notes.forEach { caveats.put(it) }
        return JSONObject().put("tool", "NOVA OSINT").put("type", kind)
            .put("target", target).put("created_at_epoch_ms", createdAt)
            .put("findings", rows).put("limitations", caveats).toString(2)
    }
}

object OsintEngine {
    private const val MAX_RESPONSE = 512 * 1024

    fun investigate(context: Context, kind: String, rawTarget: String): OsintReport {
        val target = rawTarget.trim()
        require(target.isNotEmpty()) { "Enter a username, domain or public IP address." }
        val findings = mutableListOf<OsintFinding>()
        val notes = mutableListOf<String>()
        when (kind.lowercase(Locale.ROOT)) {
            "username" -> username(context, target, findings, notes)
            "domain" -> domain(context, target, findings, notes)
            "ip" -> ip(context, target, findings, notes)
            else -> throw IllegalArgumentException("Choose username, domain or IP investigation.")
        }
        if (findings.isEmpty()) notes.add("No result could be verified. A blocked or unavailable source is not proof that no account or record exists.")
        return OsintReport(kind, target, System.currentTimeMillis(), findings, notes)
    }

    private fun username(ctx: Context, raw: String, out: MutableList<OsintFinding>, notes: MutableList<String>) {
        val user = raw.removePrefix("@")
        require(user.matches(Regex("^[A-Za-z0-9._-]{2,30}$"))) {
            "Username must be 2–30 characters and contain only letters, numbers, dots, underscores or hyphens."
        }
        val encoded = URLEncoder.encode(user, "UTF-8")
        val github = "https://api.github.com/users/$encoded"
        try {
            val (status, body) = request(ctx, github)
            if (status == 200) {
                val j = JSONObject(body)
                out.add(OsintFinding("GitHub public account", j.optString("login", user) +
                    " · " + j.optString("type", "account") +
                    (if (j.optString("name").isNotBlank()) " · name field: " + j.optString("name") else ""),
                    j.optString("html_url", "https://github.com/$user"), "Verified API response"))
            } else if (status == 404) {
                out.add(OsintFinding("GitHub public account", "No public account returned for this exact username.",
                    github, "Verified API response"))
            } else notes.add("GitHub API returned HTTP $status; this check is inconclusive.")
        } catch (e: Exception) { notes.add("GitHub API check failed: ${shortError(e)}") }

        val gitlab = "https://gitlab.com/api/v4/users?username=$encoded"
        try {
            val (status, body) = request(ctx, gitlab)
            if (status == 200) {
                val arr = JSONArray(body)
                if (arr.length() == 0) {
                    out.add(OsintFinding("GitLab public account", "No exact public username match returned.", gitlab, "Verified API response"))
                } else {
                    for (i in 0 until minOf(arr.length(), 5)) {
                        val j = arr.getJSONObject(i)
                        out.add(OsintFinding("GitLab account candidate", j.optString("username") +
                            (if (j.optString("name").isNotBlank()) " · " + j.optString("name") else ""),
                            j.optString("web_url", "https://gitlab.com/$user"), "Verified API response; candidate only"))
                    }
                }
            } else notes.add("GitLab API returned HTTP $status; this check is inconclusive.")
        } catch (e: Exception) { notes.add("GitLab API check failed: ${shortError(e)}") }

        val links = listOf(
            "GitHub" to "https://github.com/$user",
            "GitLab" to "https://gitlab.com/$user",
            "Reddit" to "https://www.reddit.com/user/$user/",
            "Instagram" to "https://www.instagram.com/$user/",
            "X" to "https://x.com/$user",
            "TikTok" to "https://www.tiktok.com/@$user",
            "Pinterest" to "https://www.pinterest.com/$user/",
            "Medium" to "https://medium.com/@$user"
        )
        links.forEach { (site, url) ->
            out.add(OsintFinding("$site profile URL", url, url, "Candidate URL — not proof an account exists"))
        }
        out.add(OsintFinding("Search the public web", "Search exact username: \"$user\"",
            "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode("\"$user\"", "UTF-8"),
            "Search link; inspect sources manually"))
        notes.add("A username is not a real-world identity. Similar handles can belong to different people.")
        notes.add("Many social platforms block anonymous automated checks or return generic pages. Profile URLs are leads, not confirmed matches.")
    }

    private fun domain(ctx: Context, raw: String, out: MutableList<OsintFinding>, notes: MutableList<String>) {
        val cleaned = raw.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        val host = IDN.toASCII(cleaned).lowercase(Locale.ROOT).trimEnd('.')
        require(host.length in 4..253 && host.contains('.') &&
            host.split('.').all { it.isNotEmpty() && it.length <= 63 && it.matches(Regex("^[a-z0-9-]+$")) && !it.startsWith("-") && !it.endsWith("-") }) {
            "Enter a valid domain, such as example.com."
        }
        try {
            val addresses = InetAddress.getAllByName(host).map { it.hostAddress }.distinct()
            out.add(OsintFinding("DNS address records", addresses.joinToString("\n"), "Android system DNS resolver", "Resolver response"))
        } catch (e: Exception) { notes.add("DNS lookup failed: ${shortError(e)}") }

        val rdapUrl = "https://rdap.org/domain/" + URLEncoder.encode(host, "UTF-8")
        try {
            val (status, body) = request(ctx, rdapUrl)
            if (status == 200) {
                val j = JSONObject(body)
                val registrar = j.optJSONArray("entities")?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        val entity = arr.optJSONObject(idx) ?: return@mapNotNull null
                        if (entity.optJSONArray("roles")?.toString()?.contains("registrar") == true) {
                            entity.optJSONArray("vcardArray")?.optJSONArray(1)?.toString()
                                ?: entity.optString("handle").takeIf { it.isNotBlank() }
                        } else null
                    }.firstOrNull()
                }
                if (!registrar.isNullOrBlank()) out.add(OsintFinding("Registrar data", registrar, rdapUrl))
                val ns = j.optJSONArray("nameservers")
                if (ns != null) {
                    val names = (0 until ns.length()).mapNotNull { ns.optJSONObject(it)?.optString("ldhName") }.filter { it.isNotBlank() }
                    if (names.isNotEmpty()) out.add(OsintFinding("Name servers", names.joinToString("\n"), rdapUrl))
                }
                val events = j.optJSONArray("events")
                if (events != null) {
                    val lines = (0 until events.length()).mapNotNull { idx ->
                        val e = events.optJSONObject(idx) ?: return@mapNotNull null
                        val action = e.optString("eventAction")
                        val date = e.optString("eventDate")
                        if (action.isNotBlank() || date.isNotBlank()) "$action: $date" else null
                    }
                    if (lines.isNotEmpty()) out.add(OsintFinding("RDAP domain events", lines.joinToString("\n"), rdapUrl))
                }
                out.add(OsintFinding("RDAP response", "Domain registration data returned successfully.", rdapUrl, "Verified RDAP response"))
            } else notes.add("RDAP returned HTTP $status.")
        } catch (e: Exception) { notes.add("RDAP lookup failed: ${shortError(e)}") }

        val ctUrl = "https://crt.sh/?q=%25." + URLEncoder.encode(host, "UTF-8") + "&output=json"
        try {
            val (status, body) = request(ctx, ctUrl)
            if (status == 200) {
                val arr = JSONArray(body)
                val names = linkedSetOf<String>()
                for (i in 0 until minOf(arr.length(), 500)) {
                    val name = arr.optJSONObject(i)?.optString("name_value").orEmpty()
                    name.split('\n').map { it.trim().removePrefix("*.") }
                        .filter { it == host || it.endsWith(".$host") }.forEach { names.add(it) }
                }
                out.add(OsintFinding("Certificate Transparency names", if (names.isEmpty()) "No matching names in returned records." else names.take(40).joinToString("\n"),
                    ctUrl, "Certificate-log records; historical entries may be included"))
            } else notes.add("Certificate transparency service returned HTTP $status.")
        } catch (e: Exception) { notes.add("Certificate transparency lookup failed: ${shortError(e)}") }
        notes.add("DNS and certificate records can be stale or shared by hosting providers; they do not establish who operates a domain.")
    }

    private fun ip(ctx: Context, raw: String, out: MutableList<OsintFinding>, notes: MutableList<String>) {
        val value = raw.trim().removePrefix("[").removeSuffix("]")
        val octets = value.split('.')
        val isIpv4 = octets.size == 4 && octets.all {
            val part = it.toIntOrNull()
            it.isNotEmpty() && it.length <= 3 && part != null && part in 0..255
        }
        val isIpv6 = value.contains(':') && value.matches(Regex("^[0-9a-fA-F:.]+$"))
        require(isIpv4 || isIpv6) { "Enter a literal IPv4 or IPv6 address, not a hostname." }
        val address = try { InetAddress.getByName(value) } catch (e: Exception) {
            throw IllegalArgumentException("That IP address is not valid.")
        }
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress) {
            "Private, loopback, link-local and multicast addresses are not queried."
        }
        val url = "https://rdap.org/ip/" + URLEncoder.encode(value, "UTF-8")
        try {
            val (status, body) = request(ctx, url)
            if (status == 200) {
                val j = JSONObject(body)
                listOf("name" to "Network name", "handle" to "Network handle", "country" to "Country",
                    "startAddress" to "Range start", "endAddress" to "Range end", "type" to "Network type",
                    "port43" to "WHOIS server").forEach { (key, label) ->
                    val v = j.optString(key)
                    if (v.isNotBlank() && v != "null") out.add(OsintFinding(label, v, url))
                }
                out.add(OsintFinding("RDAP response", "Registry data returned successfully.", url, "Verified RDAP response"))
            } else notes.add("RDAP returned HTTP $status.")
        } catch (e: Exception) { notes.add("IP RDAP lookup failed: ${shortError(e)}") }
        notes.add("IP geolocation is approximate and usually identifies a network/provider, not a person's home or exact location.")
        notes.add("Only public IP registry information is queried. No port scan or service probing is performed.")
    }

    private fun request(ctx: Context, url: String): Pair<Int, String> {
        require(url.startsWith("https://", true)) { "HTTPS only." }
        val conn = NovaNet.open(ctx, url, connectMs = 7000, readMs = 9000)
        try {
            val code = conn.responseCode
            if (!conn.url.protocol.equals("https", true)) throw java.io.IOException("Refused HTTPS downgrade.")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                val chars = CharArray(8192)
                val result = StringBuilder()
                while (result.length < MAX_RESPONSE) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    result.append(chars, 0, minOf(n, MAX_RESPONSE - result.length))
                }
                result.toString()
            }.orEmpty()
            return code to body
        } finally { conn.disconnect() }
    }

    private fun shortError(e: Exception): String =
        (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(120)
}
