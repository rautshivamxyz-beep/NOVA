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
}
