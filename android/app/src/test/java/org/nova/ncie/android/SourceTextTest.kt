package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.70: the fetched-source text rule gets its first unit tests.
 *
 * [cleanSourceText] is the single implementation of "turn a fetched source
 * into a short, readable answer" (v9.33.7 merged the two drifted copies -
 * OnlineFetch.cleanSource and an inline copy in NcieChat - into one). It is
 * pure, deterministic string work and it runs on every online lookup, so a
 * silent regression here is felt as a search-result-style paragraph or a
 * truncated mid-sentence answer, with nothing failing anywhere. Pinned
 * directly.
 *
 * Pure JVM, no Android calls - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class SourceTextTest {

    // ---- citation markers and whitespace hygiene ----

    @Test
    fun citationMarkersAreRemoved() {
        assertEquals("Fact one. Fact two. Done.", cleanSourceText("Fact one.[1] Fact two.[23] Done."))
    }

    @Test
    fun whitespaceIsCollapsedAndTrimmed() {
        assertEquals("line1 line2 line3", cleanSourceText("line1\n\nline2\t\tline3"))
        assertEquals("Hello world", cleanSourceText("  Hello   world  "))
    }

    // ---- a short source is returned unchanged ----

    @Test
    fun aShortSourceIsReturnedUnchanged() {
        assertEquals("Short answer.", cleanSourceText("Short answer."))
    }

    // ---- the 420-character cap ----

    @Test
    fun aLongSourceWithNoSentenceBreakIsCutAt420() {
        assertEquals("a".repeat(420), cleanSourceText("a".repeat(500)))
    }

    @Test
    fun aLongSourceIsCutBackToTheLastSentenceEnd() {
        // a ". " past position 150 inside the first 420 chars is the cut
        val out = cleanSourceText("w".repeat(200) + ". " + "z".repeat(300))
        assertEquals("w".repeat(200) + ".", out)
        assertTrue(out.endsWith("."))
    }

    @Test
    fun aPeriodBeforePosition150IsNotUsedAsTheCut() {
        // the only ". " sits at 140, which is not past the 150 guard, so the
        // hard 420 cut stands
        val out = cleanSourceText("x".repeat(140) + ". " + "y".repeat(400))
        assertEquals("x".repeat(140) + ". " + "y".repeat(278), out)
        assertEquals(420, out.length)
    }
}
