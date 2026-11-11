package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.136 "The report is the evidence": pin what the OSINT screen actually
 * hands the user.
 *
 * [OsintReport.asText] is the ONLY thing the user reads and the only thing
 * "Share report" sends, so it is the part of the module where a formatting slip
 * becomes a factual one - a finding silently dropped, a source line lost, or an
 * empty result rendered as if it had content. It is also pure Kotlin: no
 * Android, no Context, no network, so it can be asserted instead of eyeballed.
 *
 * The JSON half of the report is deliberately not tested here: it goes through
 * org.json, which is a stub on the JVM unit-test classpath, and pinning it
 * would mean adding a test-only dependency to the release build for one
 * formatter. The text form is what the user sees; that is what is pinned.
 */
class OsintReportTest {

    private fun report(
        kind: String = "username",
        target: String = "example",
        findings: List<OsintFinding> = emptyList(),
        notes: List<String> = emptyList()
    ) = OsintReport(kind, target, 1_700_000_000_000L, findings, notes)

    @Test
    fun `header carries the type and the target`() {
        val text = report(kind = "domain", target = "example.com").asText()
        assertTrue(text.startsWith("NOVA OSINT REPORT\n"))
        assertTrue(text.contains("Type: domain\n"))
        assertTrue(text.contains("Target: example.com\n"))
    }

    @Test
    fun `no findings says so instead of showing nothing`() {
        val text = report().asText()
        assertTrue(text.contains("No verified findings."))
    }

    @Test
    fun `a finding keeps its title value source and confidence`() {
        val text = report(findings = listOf(
            OsintFinding("GitHub public account", "octocat · User",
                "https://api.github.com/users/octocat", "Verified API response")
        )).asText()
        assertTrue(text.contains("GitHub public account"))
        assertTrue(text.contains("octocat · User"))
        assertTrue(text.contains("Source: https://api.github.com/users/octocat"))
        assertTrue(text.contains("Confidence: Verified API response"))
    }

    @Test
    fun `a finding without a stated confidence is labelled source-reported`() {
        val text = report(findings = listOf(
            OsintFinding("GitLab profile URL", "https://gitlab.com/x", "https://gitlab.com/x")
        )).asText()
        assertTrue(text.contains("Confidence: Source-reported"))
    }

    @Test
    fun `every finding is printed, none silently dropped`() {
        val findings = (1..5).map { OsintFinding("Finding $it", "value $it", "source $it") }
        val text = report(findings = findings).asText()
        findings.forEach {
            assertTrue("missing ${it.title}", text.contains(it.title))
            assertTrue("missing ${it.value}", text.contains(it.value))
        }
        assertEquals(5, Regex("Confidence: ").findAll(text).count())
    }

    @Test
    fun `limitations are printed under their own heading`() {
        val text = report(notes = listOf(
            "A username is not a real-world identity.",
            "Profile URLs are leads, not confirmed matches."
        )).asText()
        assertTrue(text.contains("LIMITATIONS"))
        assertTrue(text.contains("A username is not a real-world identity."))
        assertTrue(text.contains("Profile URLs are leads, not confirmed matches."))
    }

    @Test
    fun `the time line is present and is not the raw epoch`() {
        val text = report().asText()
        assertTrue(text.contains("Time: "))
        assertFalse(text.contains("1700000000000"))
    }

    @Test
    fun `an empty report is still a complete report`() {
        // No findings and no notes must not produce a bare header: the two
        // sections the user is told to read must both be there.
        val text = report().asText()
        assertTrue(text.contains("FINDINGS"))
        assertTrue(text.contains("LIMITATIONS"))
        assertTrue(text.contains("No verified findings."))
    }
}
