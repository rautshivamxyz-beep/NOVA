package org.nova

import android.content.Context
import java.net.URI

/** User-controlled public-source collection settings. Disabled modules do no network work. */
data class OsintPreferences(
    val github: Boolean = true,
    val gitlab: Boolean = true,
    val profileLeads: Boolean = true,
    val webSearchLink: Boolean = true,
    val dns: Boolean = true,
    val rdap: Boolean = true,
    val certificateTransparency: Boolean = true,
    val customSourcesEnabled: Boolean = false,
    val maxResults: Int = 40,
    val timeBudgetSeconds: Int = 20,
    val customSources: List<OsintCustomSource> = emptyList()
) {
    fun normalized(): OsintPreferences = copy(
        maxResults = maxResults.coerceIn(5, 200),
        timeBudgetSeconds = timeBudgetSeconds.coerceIn(5, 120),
        customSources = customSources.take(10).filter { it.isValid() }
    )

    companion object {
        fun quick() = OsintPreferences(github = true, gitlab = false, profileLeads = false,
            webSearchLink = true, dns = true, rdap = true, certificateTransparency = false,
            maxResults = 15, timeBudgetSeconds = 10)
        fun deep() = OsintPreferences(maxResults = 100, timeBudgetSeconds = 60)
        fun load(context: Context): OsintPreferences {
            val p = context.getSharedPreferences("nova_osint", Context.MODE_PRIVATE)
            val custom = p.getString("custom_sources", "").orEmpty().lineSequence()
                .mapNotNull { OsintCustomSource.parse(it) }.take(10).toList()
            return OsintPreferences(
                github = p.getBoolean("github", true),
                gitlab = p.getBoolean("gitlab", true),
                profileLeads = p.getBoolean("profile_leads", true),
                webSearchLink = p.getBoolean("web_search", true),
                dns = p.getBoolean("dns", true),
                rdap = p.getBoolean("rdap", true),
                certificateTransparency = p.getBoolean("ct", true),
                maxResults = p.getInt("max_results", 40).coerceIn(5, 200),
                timeBudgetSeconds = p.getInt("budget_seconds", 20).coerceIn(5, 120),
                customSources = custom
            )
        }
        fun save(context: Context, value: OsintPreferences) {
            val v = value.normalized()
            context.getSharedPreferences("nova_osint", Context.MODE_PRIVATE).edit()
                .putBoolean("github", v.github).putBoolean("gitlab", v.gitlab)
                .putBoolean("profile_leads", v.profileLeads).putBoolean("web_search", v.webSearchLink)
                .putBoolean("dns", v.dns).putBoolean("rdap", v.rdap)
                .putBoolean("ct", v.certificateTransparency)
                .putBoolean("custom_sources_enabled", v.customSourcesEnabled)
                .putInt("max_results", v.maxResults).putInt("budget_seconds", v.timeBudgetSeconds)
                .putString("custom_sources", v.customSources.joinToString("\n") { it.toConfigLine() })
                .apply()
        }
    }
}

/** Format: display name|https://public-provider.example/search?q={query} */
data class OsintCustomSource(val name: String, val urlTemplate: String) {
    fun isValid(): Boolean = try {
        val uri = URI(urlTemplate.replace("{query}", "test"))
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.port in listOf(-1, 443) &&
            urlTemplate.contains("{query}") && urlTemplate.length <= 1500 &&
            name.isNotBlank() && name.length <= 50
    } catch (_: Exception) { false }

    fun toConfigLine() = "$name|$urlTemplate"

    companion object {
        fun parse(line: String): OsintCustomSource? {
            val split = line.trim().split("|", limit = 2)
            if (split.size != 2) return null
            return OsintCustomSource(split[0].trim(), split[1].trim()).takeIf { it.isValid() }
        }
    }
}
