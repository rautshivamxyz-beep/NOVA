package org.nova.ncie.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nova.ncie.model.Intent

/**
 * v9.33.34: the ANALYZE gate (RuleBasedAnalyzer) is the first thing every
 * turn hits - it decides the intent and the complexity that the Decision
 * Kernel then turns into a route and a thinking/context budget. It is pure
 * (no Android, no I/O), so it can be pinned by a real unit test. A silent
 * change here re-routes every question, so these lock the shipped behaviour.
 *
 * Run with `gradle testDebugUnitTest` (CI runs it on every pull request).
 */
class AnalyzerTest {

    private val analyzer = RuleBasedAnalyzer()

    @Test
    fun bareArithmeticIsACalculation() {
        val a = analyzer.analyze("2+2")
        assertEquals(Intent.CALCULATION, a.intent)
        assertTrue(a.toolSufficient)
        assertEquals(0.2, a.complexity, 1e-9)
    }

    @Test
    fun arithmeticInsideASentenceIsStillACalculation() {
        // "what is ..." plus digits is the word-problem gate
        assertEquals(Intent.CALCULATION, analyzer.analyze("what is 2+2").intent)
        assertEquals(Intent.CALCULATION, analyzer.analyze("calculate 12 * 3").intent)
        // the word-problem gate keys on the phrase AND a digit, not an operator
        assertEquals(Intent.CALCULATION, analyzer.analyze("what is 42").intent)
    }

    @Test
    fun emptyInputIsUnknown() {
        val a = analyzer.analyze("   ")
        assertEquals(Intent.UNKNOWN, a.intent)
        assertFalse(a.toolSufficient)
        assertEquals(0.3, a.complexity, 1e-9)
    }

    @Test
    fun ordinaryChatIsLowComplexity() {
        val a = analyzer.analyze("hello there friend")
        assertEquals(Intent.CHAT, a.intent)
        assertEquals(0.3, a.complexity, 1e-9)
    }

    @Test
    fun studyQuestionsGetTheKnowledgeBudget() {
        assertEquals(0.6, analyzer.analyze("explain photosynthesis").complexity, 1e-9)
        assertEquals(0.6, analyzer.analyze("who is physics wallah").complexity, 1e-9)
        assertEquals(0.6, analyzer.analyze("tell me about the taj mahal").complexity, 1e-9)
    }

    @Test
    fun aHindiOrMarathiQuestionIsKnowledgeSeekingToo() {
        // v9.33.19: any Devanagari question earns the real context budget
        assertEquals(0.6, analyzer.analyze("ताजमहल कहाँ है?").complexity, 1e-9)
    }

    @Test
    fun aShortKeywordPullEarnsTheBudget() {
        assertEquals(0.6, analyzer.analyze("bose").complexity, 1e-9)
    }

    @Test
    fun veryLongInputIsHard() {
        val long = (1..90).joinToString(" ") { "word$it" }
        assertEquals(0.9, analyzer.analyze(long).complexity, 1e-9)
    }

    @Test
    fun keywordsAreDistinctAndCapped() {
        val a = analyzer.analyze("who is physics wallah physics wallah")
        assertEquals(listOf("who", "physics", "wallah"), a.keywords)
        assertTrue(a.keywords.size <= 12)
    }

    @Test
    fun onlyCalculationsAreToolSufficient() {
        assertTrue(analyzer.analyze("3*4").toolSufficient)
        assertFalse(analyzer.analyze("hello").toolSufficient)
    }
}
