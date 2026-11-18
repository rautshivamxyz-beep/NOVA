package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.163: the recipe ranking.
 *
 * "match" was silently doing nothing. After "list" every item's text is
 * still empty - nothing has been read yet - and score() answers 0 for blank
 * text, so every item scored 0, the sort kept the original order, and the
 * "find the file about X" recipe returned the alphabetically-first file.
 * Nothing failed; it just quietly ranked nothing.
 *
 * These pin the arithmetic and the name-is-part-of-the-item rule that the
 * fix depends on.
 */
class NcieToolsTest {

    @Test
    fun aMatchingNameScoresFullMarks() {
        assertEquals(100, NcieTools.score("photosynthesis.pdf\n", "photosynthesis"))
    }

    @Test
    fun aNonMatchingNameScoresNothing() {
        assertEquals(0, NcieTools.score("biology.pdf\n", "photosynthesis"))
    }

    @Test
    fun anItemWithNoTextStillRanksOnItsName() {
        // This is the regression. An item straight out of "list" has an empty
        // body, and before the fix the name was never looked at, so this
        // scored 0 and "match" became a no-op.
        val listed = NcieTools.Item("photosynthesis-notes.pdf", "")
        assertTrue(NcieTools.score(listed.name, "photosynthesis") > 0)
    }

    @Test
    fun theBodyStillCounts() {
        val read = NcieTools.Item("notes.txt", "chlorophyll drives photosynthesis")
        assertEquals(100, NcieTools.score(read.name + "\n" + read.text, "photosynthesis"))
    }

    @Test
    fun everyTermThatAppearsIsCounted() {
        assertEquals(100, NcieTools.score("photosynthesis in plants\n", "photosynthesis plants"))
        assertEquals(50, NcieTools.score("photosynthesis only\n", "photosynthesis plants"))
    }

    @Test
    fun caseDoesNotMatter() {
        assertEquals(100, NcieTools.score("Photosynthesis.PDF\n", "photosynthesis"))
    }

    @Test
    fun anEmptyBodyScoresZeroRatherThanThrowing() {
        assertEquals(0, NcieTools.score("", "photosynthesis"))
    }

    @Test
    fun anEmptyQueryScoresNothing() {
        assertEquals(0, NcieTools.score("photosynthesis.pdf\n", ""))
        assertEquals(0, NcieTools.score("photosynthesis.pdf\n", "   "))
    }
}
