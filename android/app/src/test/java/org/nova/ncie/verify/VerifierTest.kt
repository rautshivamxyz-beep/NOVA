package org.nova.ncie.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nova.ncie.execute.tools.CalculatorTool
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Intent
import org.nova.ncie.model.Plan
import org.nova.ncie.model.Route

/**
 * v9.33.35: the VERIFY gate is the last thing before the user sees an answer.
 * Both pieces are pure JVM (no Android, no I/O), so they are pinned by real
 * unit tests:
 *
 *  - [MathVerifier] cross-checks a routed calculation with the calculator's
 *    SECOND independent evaluator, and refuses an answer that does not carry
 *    the computed value.
 *  - [Verifier.quality] is the no-ground-truth pass run on every LLM answer:
 *    blank answers, leaked chat boilerplate, over-long answers and answers
 *    that drift from their sources must all be caught.
 *
 * Run with `gradle testDebugUnitTest` (CI runs it on every pull request).
 */
class VerifierTest {

    private val calc = CalculatorTool()

    private fun analysis(intent: Intent = Intent.CALCULATION, text: String = "2+2") =
        Analysis(text = text, intent = intent, complexity = 0.5,
            toolSufficient = intent == Intent.CALCULATION, keywords = emptyList())

    private fun plan(route: Route) =
        Plan(route = route, toolName = null, thinkingBudgetTokens = 0,
            contextBudgetChars = 0, rationale = "test")

    // ---------------------------------------------------------- MathVerifier

    @Test
    fun aCachedAnswerIsTrusted() {
        val v = MathVerifier(calc).verify(analysis(), plan(Route.CACHE), "4")
        assertTrue(v.passed)
        assertEquals(0.95, v.qualityScore, 1e-9)
    }

    @Test
    fun aNonCalculationHasNoDeterministicCheck() {
        val v = MathVerifier(calc).verify(analysis(Intent.CHAT), plan(Route.LLM), "hello")
        assertFalse(v.passed)
        assertEquals(0.5, v.qualityScore, 1e-9)
    }

    @Test
    fun anAnswerWithTheComputedValuePasses() {
        val v = MathVerifier(calc).verify(analysis(), plan(Route.TOOL), "2+2 = 4")
        assertTrue(v.passed)
        assertEquals(1.0, v.qualityScore, 1e-9)
    }

    @Test
    fun anAnswerWithoutTheComputedValueFails() {
        val v = MathVerifier(calc).verify(analysis(), plan(Route.TOOL), "the answer is five")
        assertFalse(v.passed)
        assertEquals(0.2, v.qualityScore, 1e-9)
    }

    // ------------------------------------------------------------- quality()

    @Test
    fun aBlankAnswerFailsOutright() {
        val verifier = MathVerifier(calc)
        assertEquals(0.0, verifier.quality("").qualityScore, 1e-9)
        assertEquals(0.0, verifier.quality("   ").qualityScore, 1e-9)
        assertFalse(verifier.quality("").passed)
    }

    @Test
    fun leakedChatBoilerplateFails() {
        val verifier = MathVerifier(calc)
        assertFalse(verifier.quality("NOVA: hi there").passed)
        assertFalse(verifier.quality("You: what is this").passed)
        assertFalse(verifier.quality("From general knowledge, Paris is the capital.").passed)
    }

    @Test
    fun anOverLongAnswerFailsWhenALimitIsGiven() {
        val v = MathVerifier(calc).quality("one two three four five", maxWords = 3)
        assertFalse(v.passed)
        assertEquals(0.4, v.qualityScore, 1e-9)
    }

    @Test
    fun anAnswerThatGroundsInItsSourcesPasses() {
        val v = MathVerifier(calc).quality(
            "Photosynthesis converts light into glucose",
            sources = listOf("Photosynthesis converts light into glucose"))
        assertTrue(v.passed)
        // every significant term (len>3) is grounded -> 0.5 + 0.45*1.0
        assertEquals(0.95, v.qualityScore, 1e-9)
    }

    @Test
    fun anAnswerThatDriftsFromItsSourcesFails() {
        val v = MathVerifier(calc).quality(
            "Quantum physics describes electrons and photons",
            sources = listOf("The Taj Mahal is in Agra"))
        assertFalse(v.passed)
        assertEquals(0.3, v.qualityScore, 1e-9)
    }

    @Test
    fun withNoSourcesAPlainAnswerPasses() {
        val v = MathVerifier(calc).quality("This is a fine ordinary answer")
        assertTrue(v.passed)
        assertEquals(0.6, v.qualityScore, 1e-9)
    }
}
