package org.nova.ncie.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.57: the capability-gap log gets its first unit test.
 *
 * [GapStore] is what turns "NOVA couldn't do this" into measured evidence
 * the user can act on ("you asked for this 4 times"). It merges repeats,
 * caps itself, and round-trips through an escaped line format. A silent
 * regression means the improvement loop counts the wrong thing.
 *
 * Pure JVM, no imports - run with `gradle testDebugUnitTest` (CI runs it
 * on every pull request).
 */
class GapStoreTest {

    private fun empty() = GapStore.parse("")

    @Test
    fun repeatsMergeIntoOneCount() {
        val s = empty()
        s.note("draw a picture of a cat", 1L)
        s.note("draw a picture of a cat", 2L)
        assertEquals(1, s.size())
        assertEquals(2, s.top(1).first().count)
        assertEquals(2L, s.top(1).first().lastMillis)
    }

    @Test
    fun aBlankQuestionIsIgnored() {
        val s = empty()
        s.note("   ", 1L)
        assertEquals(0, s.size())
    }

    @Test
    fun topIsMostAskedFirstThenAlphabetical() {
        val s = empty()
        s.note("b", 1L)
        s.note("a", 1L)
        s.note("c", 5L)
        assertEquals(listOf("c", "a", "b"), s.top(3).map { it.question })
    }

    @Test
    fun theLogCapsItselfAndKeepsTheSignal() {
        val s = empty()
        repeat(3) { s.note("keep me", 1L) }          // count 3 - never the least-asked
        for (i in 0 until 99) s.note("gap$i", 1L)    // 99 more, count 1
        assertEquals(100, s.size())
        s.note("a brand new gap", 9L)                // full: one count-1 entry drops
        assertEquals(100, s.size())
        assertEquals("keep me", s.top(1).first().question)
    }

    @Test
    fun aLineSurvivesSerializeAndParse() {
        val s = empty()
        s.note("who won the 2011 world cup", 111L)
        s.note("who won the 2011 world cup", 222L)
        val back = GapStore.parse(s.serialize())
        assertEquals(1, back.size())
        assertEquals(2, back.top(1).first().count)
        assertEquals(222L, back.top(1).first().lastMillis)
    }

    @Test
    fun aNewlineInTheQuestionDoesNotBreakTheFormat() {
        val s = empty()
        s.note("line one\nline two", 5L)
        val back = GapStore.parse(s.serialize())
        assertEquals("line one\nline two", back.top(1).first().question)
    }

    @Test
    fun malformedLinesAreSkippedNotThrown() {
        assertEquals(0, GapStore.parse("garbage").size())
        assertEquals(0, GapStore.parse("q\tnotanumber\t1").size())
        assertTrue(GapStore.parse("").size() == 0)
    }
}
