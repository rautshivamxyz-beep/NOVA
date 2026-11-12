package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsintUsernameSourcesTest {
    @Test
    fun `handle matching is exact and case insensitive`() {
        assertTrue(OsintUsernameMatch.isExact("OctoCat", "octocat"))
        assertTrue(OsintUsernameMatch.isExact("  octocat ", "octocat"))
    }

    @Test
    fun `substring matches are not accepted as exact usernames`() {
        assertFalse(OsintUsernameMatch.isExact("octo", "octocat"))
        assertFalse(OsintUsernameMatch.isExact("octocat1", "octocat"))
    }

    @Test
    fun `the registry keeps each provider as its own module`() {
        assertEquals(
            setOf("github", "gitlab", "devto", "hackernews", "stackoverflow"),
            OsintUsernameSources.moduleIds()
        )
    }

    @Test
    fun `source coverage distinguishes no match from a failed request`() {
        val report = OsintReport(
            kind = "username",
            target = "sampleuser",
            createdAt = 1_700_000_000_000L,
            findings = emptyList(),
            notes = emptyList(),
            checks = listOf(
                OsintSourceCheck("GitHub", OsintSourceState.NO_MATCH,
                    "No public account returned.", "https://api.github.com/users/sampleuser"),
                OsintSourceCheck("DEV Community", OsintSourceState.ERROR,
                    "The response could not be processed.", "https://dev.to/api/users/by_username?url=sampleuser")
            )
        )
        val text = report.asText()
        assertTrue(text.contains("GitHub: NO MATCH"))
        assertTrue(text.contains("DEV Community: ERROR"))
        assertFalse(text.contains("GitHub: ERROR"))
    }

    @Test
    fun `stack exchange results are explicitly described as display name candidates`() {
        val finding = OsintFinding(
            "Stack Overflow display-name candidate",
            "Display name: sampleuser\nReputation: 12",
            "https://stackoverflow.com/users/1",
            "Exact display-name only; not a username or identity confirmation"
        )
        assertTrue(finding.confidence.contains("not a username"))
    }

    @Test
    fun `GitHub adapter accepts only the exact returned handle and collects public metadata`() {
        val result = OsintUsernameSources.parseResponse(
            "github", "octocat", 200,
            """{"login":"octocat","type":"User","name":"The Octocat","public_repos":8,"followers":10,"following":5,"created_at":"2011-01-25T18:44:36Z","html_url":"https://github.com/octocat"}"""
        )
        assertEquals(OsintSourceState.FOUND, result?.state)
        val value = result?.findings?.single()?.value.orEmpty()
        assertTrue(value.contains("Public display name: The Octocat"))
        assertTrue(value.contains("Public repositories: 8"))
        assertTrue(value.contains("Followers: 10"))
        assertTrue(value.contains("Account created:"))
        val wrong = OsintUsernameSources.parseResponse(
            "github", "octocat", 200, """{"login":"octocat-dev"}"""
        )
        assertEquals(OsintSourceState.NO_MATCH, wrong?.state)
        assertTrue(wrong?.findings?.isEmpty() == true)
    }

    @Test
    fun `provider failures are not reported as no match`() {
        assertEquals(
            OsintSourceState.RATE_LIMITED,
            OsintUsernameSources.parseResponse("github", "sample", 429, "")?.state
        )
        assertEquals(
            OsintSourceState.BLOCKED,
            OsintUsernameSources.parseResponse("devto", "sample", 403, "Forbidden")?.state
        )
        assertEquals(
            OsintSourceState.UNAVAILABLE,
            OsintUsernameSources.parseResponse("hackernews", "sample", 503, "Unavailable")?.state
        )
        assertEquals(
            OsintSourceState.NO_MATCH,
            OsintUsernameSources.parseResponse("github", "sample", 404, "")?.state
        )
    }

    @Test
    fun `GitLab candidates are filtered to exact usernames`() {
        val result = OsintUsernameSources.parseResponse(
            "gitlab", "sample", 200,
            """[{"username":"sample-dev","name":"Wrong person","web_url":"https://gitlab.com/sample-dev"},{"username":"sample","name":"Exact handle","web_url":"https://gitlab.com/sample"}]"""
        )
        assertEquals(OsintSourceState.FOUND, result?.state)
        assertEquals(1, result?.findings?.size)
        assertTrue(result?.findings?.single()?.value?.contains("Exact handle") == true)
        val noExact = OsintUsernameSources.parseResponse(
            "gitlab", "sample", 200,
            """[{"username":"sample-dev","name":"Wrong person"}]"""
        )
        assertEquals(OsintSourceState.NO_MATCH, noExact?.state)
    }

    @Test
    fun `Stack Overflow names remain display-name candidates`() {
        val result = OsintUsernameSources.parseResponse(
            "stackoverflow", "sample",
            200,
            """{"items":[{"display_name":"sample","reputation":25,"link":"https://stackoverflow.com/users/1","creation_date":1600000000,"badge_counts":{"gold":1,"silver":2,"bronze":3}}]}"""
        )
        assertEquals(OsintSourceState.FOUND, result?.state)
        assertTrue(result?.findings?.single()?.title?.contains("candidate", true) == true)
        assertTrue(result?.findings?.single()?.confidence?.contains("not a username") == true)
    }

    @Test
    fun `disabled providers are represented without making requests`() {
        val result = OsintUsernameSources.collect(
            "sample",
            mapOf("github" to false, "gitlab" to false, "devto" to false,
                "hackernews" to false, "stackoverflow" to false),
            System.currentTimeMillis() + 10_000L
        ) { throw AssertionError("disabled provider must not request the network") }
        assertTrue(result.findings.isEmpty())
        assertEquals(5, result.checks.size)
        assertTrue(result.checks.all { it.state == OsintSourceState.DISABLED })
    }
}
