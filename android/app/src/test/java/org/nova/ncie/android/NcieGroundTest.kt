package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v9.33.65: the document-grounding chunker gets its first unit tests.
 *
 * [NcieGround.chunksOf] decides how the user's own documents are split
 * before they are retrieved and fed to the model. The split governs how
 * much of a source fits in one grounding prompt: a chunk that is too big
 * pushes the answer out of the budget; a split that drops text silently
 * loses the part of the notes the answer needed. [groundPrompt] is the
 * deterministic prompt the model is asked to answer from, and must carry
 * the source label so the reply can cite it.
 *
 * Pure JVM, no Android calls - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class NcieGroundTest {

    @Test
    fun emptyTextProducesNoChunks() {
        assertEquals(emptyList<String>(), NcieGround.chunksOf(""))
    }

    @Test
    fun aShortParagraphIsOneChunk() {
        assertEquals(listOf("para one"), NcieGround.chunksOf("para one"))
    }

    @Test
    fun paragraphsUnderTheCapAreJoinedWithANewline() {
        assertEquals(listOf("A\nB"), NcieGround.chunksOf("A\nB"))
    }

    @Test
    fun aParagraphBoundaryIsThePreferredCut() {
        // 1400 + 1 + 200 > 1500, so the first paragraph is flushed whole
        val chunks = NcieGround.chunksOf("a".repeat(1400) + "\n" + "b".repeat(200))
        assertEquals(2, chunks.size)
        assertEquals(1400, chunks[0].length)
        assertEquals(200, chunks[1].length)
    }

    @Test
    fun aSingleOversizedParagraphIsSplitAt1500() {
        val chunks = NcieGround.chunksOf("x".repeat(3100))
        assertEquals(2, chunks.size)
        assertEquals(1500, chunks[0].length)
        assertEquals(1600, chunks[1].length)
    }

    @Test
    fun theGroundingPromptNamesTheSourceAndAsksTheQuestion() {
        val p = NcieGround.groundPrompt("What is X?", listOf("notes.txt" to "X is a letter."))
        assertTrue(p.startsWith("Answer using ONLY the TEXT below."))
        assertTrue(p.contains("X is a letter. (Source: notes.txt)"))
        assertTrue(p.endsWith("Question: What is X?"))
    }

    @Test
    fun theGroundingPromptCarriesEveryChunk() {
        val p = NcieGround.groundPrompt("q", listOf("a" to "first", "b" to "second"))
        assertTrue(p.contains("first (Source: a)"))
        assertTrue(p.contains("second (Source: b)"))
    }
}
