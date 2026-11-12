package org.nova

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** The state of one provider check. FOUND never means that a real-world identity was proved. */
enum class OsintSourceState(val label: String) {
    FOUND("FOUND"),
    NO_MATCH("NO MATCH"),
    INCONCLUSIVE("INCONCLUSIVE"),
    BLOCKED("BLOCKED"),
    RATE_LIMITED("RATE LIMITED"),
    UNAVAILABLE("UNAVAILABLE"),
    ERROR("ERROR"),
    SKIPPED("SKIPPED"),
    DISABLED("DISABLED"),
    LINK_ONLY("LINK ONLY")
}

/** Source provenance shown in both the on-screen and exported report. */
data class OsintSourceCheck(
    val source: String,
    val state: OsintSourceState,
    val detail: String,
    val url: String = "",
    val elapsedMs: Long = 0L,
    val checkedAtEpochMs: Long = System.currentTimeMillis()
)

internal data class OsintHttpResponse(val status: Int, val body: String)

internal data class OsintSourceOutcome(
    val state: OsintSourceState,
    val detail: String,
    val findings: List<OsintFinding> = emptyList()
)

internal data class OsintUsernameCollection(
    val findings: List<OsintFinding>,
    val checks: List<OsintSourceCheck>
)

internal interface OsintUsernameSourceAdapter {
    val id: String
    val label: String
    fun requestUrl(username: String): String
    fun parse(username: String, url: String, response: OsintHttpResponse): OsintSourceOutcome
}

/** Matching helpers are deliberately exact; substring similarity is never an identity signal. */
internal object OsintUsernameMatch {
    fun isExact(input: String, returned: String): Boolean =
        input.trim().equals(returned.trim(), ignoreCase = true)
}

private abstract class JsonUsernameSource : OsintUsernameSourceAdapter {
    protected fun httpProblem(response: OsintHttpResponse): OsintSourceOutcome? {
        val message = response.body.take(240).replace('\n', ' ').replace('\r', ' ')
        return when {
            response.status == 403 && message.contains("rate limit", ignoreCase = true) ->
                OsintSourceOutcome(OsintSourceState.RATE_LIMITED, "Provider rate limit reached.")
            response.status == 403 ->
                OsintSourceOutcome(OsintSourceState.BLOCKED, "Provider refused this automated public lookup.")
            response.status == 429 ->
                OsintSourceOutcome(OsintSourceState.RATE_LIMITED, "Provider rate limit reached.")
            response.status == 404 ->
                OsintSourceOutcome(OsintSourceState.NO_MATCH, "Provider returned no record for this lookup.")
            response.status in 500..599 ->
                OsintSourceOutcome(OsintSourceState.UNAVAILABLE, "Provider server error (HTTP ${response.status}).")
            response.status !in 200..299 ->
                OsintSourceOutcome(OsintSourceState.INCONCLUSIVE, "Provider returned HTTP ${response.status}; no match conclusion is possible.")
            else -> null
        }
    }

    protected fun safe(value: String, max: Int = 120): String =
        value.replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ").trim().take(max)

    protected fun encoded(username: String): String = URLEncoder.encode(username, "UTF-8")
    protected fun exact(input: String, returned: String) = OsintUsernameMatch.isExact(input, returned)
}

private object GitHubUsernameSource : JsonUsernameSource() {
    override val id = "github"
    override val label = "GitHub"
    override fun requestUrl(username: String) = "https://api.github.com/users/${encoded(username)}"

    override fun parse(username: String, url: String, response: OsintHttpResponse): OsintSourceOutcome {
        httpProblem(response)?.let { return it }
        val j = JSONObject(response.body)
        val login = j.optString("login")
        if (!exact(username, login)) {
            return OsintSourceOutcome(OsintSourceState.NO_MATCH, "The API response did not contain the exact requested handle.")
        }
        val profile = j.optString("html_url").ifBlank { "https://github.com/$login" }
        val lines = mutableListOf("Username: $login", "Account type: ${safe(j.optString("type", "unknown"))}")
        j.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { lines.add("Public display name: ${safe(it)}") }
        j.optInt("public_repos", -1).takeIf { it >= 0 }?.let { lines.add("Public repositories: $it") }
        j.optInt("followers", -1).takeIf { it >= 0 }?.let { lines.add("Followers: $it") }
        j.optInt("following", -1).takeIf { it >= 0 }?.let { lines.add("Following: $it") }
        j.optString("created_at").takeIf { it.isNotBlank() && it != "null" }?.let { lines.add("Account created: ${safe(it, 40)}") }
        lines.add("Public profile: $profile")
        return OsintSourceOutcome(
            OsintSourceState.FOUND,
            "Exact public username returned by GitHub; account ownership is not independently verified.",
            listOf(OsintFinding("GitHub public account", lines.joinToString("\n"), profile,
                "Exact provider handle; real-world identity unverified"))
        )
    }
}

private object GitLabUsernameSource : JsonUsernameSource() {
    override val id = "gitlab"
    override val label = "GitLab"
    override fun requestUrl(username: String) = "https://gitlab.com/api/v4/users?username=${encoded(username)}"

    override fun parse(username: String, url: String, response: OsintHttpResponse): OsintSourceOutcome {
        httpProblem(response)?.let { return it }
        val rows = JSONArray(response.body)
        val exactRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { exact(username, it.optString("username")) }
        if (exactRows.isEmpty()) {
            val detail = if (rows.length() == 0) "No public account was returned for this exact username."
                else "The API returned only non-exact candidates; none was counted as a match."
            return OsintSourceOutcome(OsintSourceState.NO_MATCH, detail)
        }
        val findings = exactRows.take(5).map { j ->
            val handle = j.optString("username")
            val profile = j.optString("web_url").ifBlank { "https://gitlab.com/$handle" }
            val lines = mutableListOf("Username: $handle")
            j.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let {
                lines.add("Public display name: ${safe(it)}")
            }
            j.optString("created_at").takeIf { it.isNotBlank() && it != "null" }?.let {
                lines.add("Account created: ${safe(it, 40)}")
            }
            lines.add("Public profile: $profile")
            OsintFinding("GitLab public account", lines.joinToString("\n"), profile,
                "Exact provider handle; real-world identity unverified")
        }
        return OsintSourceOutcome(OsintSourceState.FOUND,
            "Exact public username match returned by GitLab; account ownership is not independently verified.", findings)
    }
}

private object DevToUsernameSource : JsonUsernameSource() {
    override val id = "devto"
    override val label = "DEV Community"
    override fun requestUrl(username: String) =
        "https://dev.to/api/users/by_username?url=${encoded(username)}"

    override fun parse(username: String, url: String, response: OsintHttpResponse): OsintSourceOutcome {
        httpProblem(response)?.let { return it }
        val j = JSONObject(response.body)
        val handle = j.optString("username")
        if (!exact(username, handle)) {
            return OsintSourceOutcome(OsintSourceState.NO_MATCH,
                "The provider response did not contain the exact requested username.")
        }
        val profile = "https://dev.to/$handle"
        val lines = mutableListOf("Username: $handle", "Public profile: $profile")
        j.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let {
            lines.add("Public display name: ${safe(it)}")
        }
        j.optString("joined_at").takeIf { it.isNotBlank() && it != "null" }?.let {
            lines.add("Joined: ${safe(it, 40)}")
        }
        j.optInt("articles_count", -1).takeIf { it >= 0 }?.let {
            lines.add("Published article count: $it")
        }
        return OsintSourceOutcome(OsintSourceState.FOUND,
            "Exact public DEV Community username returned; profile text and private account data are not collected.",
            listOf(OsintFinding("DEV Community public account", lines.joinToString("\n"), profile,
                "Exact provider handle; real-world identity unverified")))
    }
}

private object HackerNewsUsernameSource : JsonUsernameSource() {
    override val id = "hackernews"
    override val label = "Hacker News"
    override fun requestUrl(username: String) =
        "https://hacker-news.firebaseio.com/v0/user/${encoded(username)}.json"

    override fun parse(username: String, url: String, response: OsintHttpResponse): OsintSourceOutcome {
        httpProblem(response)?.let { return it }
        if (response.body.trim() == "null") {
            return OsintSourceOutcome(OsintSourceState.NO_MATCH, "No public Hacker News profile was returned.")
        }
        val j = JSONObject(response.body)
        val handle = j.optString("id")
        if (!exact(username, handle)) {
            return OsintSourceOutcome(OsintSourceState.NO_MATCH,
                "The provider response did not contain the exact requested handle.")
        }
        val profile = "https://news.ycombinator.com/user?id=${encoded(handle)}"
        val lines = mutableListOf("Username: $handle", "Public profile: $profile")
        j.optInt("karma", -1).takeIf { it >= 0 }?.let { lines.add("Public karma: $it") }
        j.optJSONArray("submitted")?.let { lines.add("Public submissions listed: ${it.length()}") }
        j.optLong("created", -1L).takeIf { it > 0 }?.let {
            lines.add("Account created (UTC): ${java.time.Instant.ofEpochSecond(it)}")
        }
        return OsintSourceOutcome(OsintSourceState.FOUND,
            "Exact public Hacker News handle returned; identity is not independently verified.",
            listOf(OsintFinding("Hacker News public account", lines.joinToString("\n"), profile,
                "Exact provider handle; real-world identity unverified")))
    }
}

private object StackOverflowUsernameSource : JsonUsernameSource() {
    override val id = "stackoverflow"
    override val label = "Stack Overflow"
    override fun requestUrl(username: String) =
        "https://api.stackexchange.com/2.3/users?order=desc&sort=reputation&inname=${encoded(username)}&site=stackoverflow&pagesize=20"

    override fun parse(username: String, url: String, response: OsintHttpResponse): OsintSourceOutcome {
        httpProblem(response)?.let { return it }
        val root = JSONObject(response.body)
        val items = root.optJSONArray("items") ?: JSONArray()
        val exactRows = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { exact(username, it.optString("display_name")) }
        if (exactRows.isEmpty()) {
            val similar = items.length()
            val detail = if (similar == 0) "No matching public display name was returned."
                else "No exact display-name candidate. $similar similar names were deliberately not treated as matches."
            return OsintSourceOutcome(OsintSourceState.NO_MATCH, detail)
        }
        val findings = exactRows.take(3).map { j ->
            val name = safe(j.optString("display_name"))
            val profile = j.optString("link")
            val badges = j.optJSONObject("badge_counts")
            val lines = mutableListOf("Display name: $name")
            j.optInt("reputation", -1).takeIf { it >= 0 }?.let { lines.add("Reputation: $it") }
            badges?.let {
                lines.add("Badges: ${it.optInt("gold", 0)} gold · ${it.optInt("silver", 0)} silver · ${it.optInt("bronze", 0)} bronze")
            }
            j.optLong("creation_date", -1L).takeIf { it > 0 }?.let {
                lines.add("Account created (UTC): ${java.time.Instant.ofEpochSecond(it)}")
            }
            if (profile.isNotBlank()) lines.add("Public profile: $profile")
            OsintFinding("Stack Overflow display-name candidate", lines.joinToString("\n"),
                profile.ifBlank { url }, "Exact display-name only; not a username or identity confirmation")
        }
        return OsintSourceOutcome(OsintSourceState.FOUND,
            "Exact display-name candidate(s) returned. Stack Overflow searches display names, so these are not confirmed username or identity matches.",
            findings)
    }
}

/** Independent public-source adapters for username investigations. */
internal object OsintUsernameSources {
    private val adapters: List<OsintUsernameSourceAdapter> = listOf(
        GitHubUsernameSource,
        GitLabUsernameSource,
        DevToUsernameSource,
        HackerNewsUsernameSource,
        StackOverflowUsernameSource
    )

    fun collect(
        username: String,
        enabled: Map<String, Boolean>,
        deadline: Long,
        httpGet: (String) -> OsintHttpResponse
    ): OsintUsernameCollection {
        val findings = mutableListOf<OsintFinding>()
        val checks = mutableListOf<OsintSourceCheck>()
        adapters.forEach { adapter ->
            val url = adapter.requestUrl(username)
            val started = System.currentTimeMillis()
            if (enabled[adapter.id] != true) {
                checks.add(OsintSourceCheck(adapter.label, OsintSourceState.DISABLED,
                    "Disabled in investigation settings.", url, 0L, started))
                return@forEach
            }
            if (System.currentTimeMillis() >= deadline) {
                checks.add(OsintSourceCheck(adapter.label, OsintSourceState.SKIPPED,
                    "The shared time budget expired before this source was checked.", url, 0L, started))
                return@forEach
            }
            val outcome = try {
                adapter.parse(username, url, httpGet(url))
            } catch (e: Exception) {
                OsintSourceOutcome(OsintSourceState.ERROR,
                    "The provider response could not be processed: ${(e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(120)}")
            }
            findings.addAll(outcome.findings)
            checks.add(OsintSourceCheck(adapter.label, outcome.state, outcome.detail, url,
                (System.currentTimeMillis() - started).coerceAtLeast(0L), started))
        }
        return OsintUsernameCollection(findings, checks)
    }

    internal fun moduleIds(): Set<String> = adapters.map { it.id }.toSet()
}
