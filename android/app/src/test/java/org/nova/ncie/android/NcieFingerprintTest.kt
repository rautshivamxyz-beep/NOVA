package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.102 "The embedding index knows what it was built from".
 *
 * The index was rebuilt only when the chunk COUNT changed, so an edit that
 * kept the count (a corrected sentence, a re-saved document, one document out
 * and another of equal size in) left every later question scored against the
 * old text's vectors - and the scores were read as if they belonged to the new
 * chunks. These pin the fingerprint that replaces the count: stable for the
 * same list, different for any change at all.
 *
 * Written with assertEquals/assertTrue on purpose - a Long comparison through
 * assertNotEquals has more than one JUnit overload to choose from.
 */
class NcieFingerprintTest {

    private fun differs(a: List<String>, b: List<String>) =
        NcieFingerprint.ofTexts(a) != NcieFingerprint.ofTexts(b)

    @Test
    fun theSameListAlwaysGivesTheSameValue() {
        val texts = listOf("alpha beta", "gamma", "delta epsilon")
        assertTrue(NcieFingerprint.ofTexts(texts) == NcieFingerprint.ofTexts(texts.toList()))
        assertTrue(NcieFingerprint.ofText("hello") == NcieFingerprint.ofText("hello"))
    }

    @Test
    fun anEditThatKeepsTheChunkCountStillChangesTheValue() {
        // the bug: same count, different text
        val before = listOf("the cell membrane controls what enters", "mitochondria make energy")
        val after = listOf("the cell WALL controls what enters", "mitochondria make energy")
        assertEquals(before.size, after.size)
        assertTrue("an edit that keeps the count must still differ", differs(before, after))
    }

    @Test
    fun oneDocumentOutAndAnotherInIsStillAChange() {
        // same count again, completely different material
        val before = listOf("photosynthesis notes", "respiration notes")
        val after = listOf("trigonometry notes", "vectors notes")
        assertEquals(before.size, after.size)
        assertTrue("different documents of the same size must differ", differs(before, after))
    }

    @Test
    fun theOrderOfChunksIsPartOfTheValue() {
        assertTrue("the order must matter",
            differs(listOf("one", "two"), listOf("two", "one")))
    }

    @Test
    fun theChunkBoundaryIsPartOfTheValue() {
        assertTrue("the chunk boundary must matter",
            differs(listOf("ab", "c"), listOf("a", "bc")))
    }

    @Test
    fun theNumberOfChunksIsPartOfTheValue() {
        assertTrue("a trailing empty chunk must matter",
            differs(listOf("only"), listOf("only", "")))
        assertTrue("an empty list must differ from one empty chunk",
            differs(emptyList(), listOf("")))
    }

    @Test
    fun nonAsciiTextIsFingerprintedByItsBytes() {
        // Hindi notes must not collide with their ASCII neighbours
        assertTrue("देवनागरी must not collide with ASCII",
            NcieFingerprint.ofText("पानी") != NcieFingerprint.ofText("pani"))
        assertTrue(NcieFingerprint.ofText("पानी") == NcieFingerprint.ofText("पानी"))
    }
}
