package org.nova.ncie.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.learn.Learner
import org.nova.ncie.learn.WeakTopic
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Intent
import org.nova.ncie.model.NovaResponse
import org.nova.ncie.model.Route

/**
 * v9.33.36: the ADAPTIVE planner (AdaptiveKernel) decorates the base kernel
 * with the one signal the base cannot see — the Learn phase. It is pure, so
 * a fake Learner pins it:
 *
 *  - the MASTERY rule: a half-known topic (fuzzy hit) gets one tier more
 *    thinking + context budget;
 *  - the STRUGGLE rule: a topic the learner keeps failing gets the same bump,
 *    matched WHOLE-WORD ("hiv" must not struggle-match "shivam");
 *  - everything else — a cold topic, a deterministic route, an already-top
 *    tier — is left exactly as the base planner made it.
 *
 * Run with `gradle testDebugUnitTest` (CI runs it on every pull request).
 */
class AdaptiveKernelTest {

    private class FakeLearner(
        private val knows: Boolean = false,
        private val weak: List<WeakTopic> = emptyList(),
    ) : Learner {
        override fun recall(text: String): NovaResponse? = null
        override fun record(text: String, response: NovaResponse) {}
        override fun stats() = "fake"
        override fun knowsTopic(text: String) = knows
        override fun weakTopics() = weak
    }

    private fun base() = DecisionKernel(ToolRegistry(emptyList()))

    private fun analysis(text: String = "x", complexity: Double = 0.5) =
        Analysis(text = text, intent = Intent.CHAT, complexity = complexity,
            toolSufficient = false, keywords = emptyList())

    @Test
    fun aColdTopicIsLeftExactlyAsTheBasePlannedIt() {
        val b = base()
        val a = analysis(complexity = 0.5)
        assertEquals(b.plan(a, false), AdaptiveKernel(b, FakeLearner()).plan(a, false))
    }

    @Test
    fun aHalfLearnedTopicGetsOneMoreTier() {
        val p = AdaptiveKernel(base(), FakeLearner(knows = true)).plan(analysis(complexity = 0.5), false)
        assertEquals(768, p.thinkingBudgetTokens)
        assertEquals(6000, p.contextBudgetChars)
        assertTrue(p.rationale.contains("half-learned"))
    }

    @Test
    fun aTrivialNearMissStepsUpToMedium() {
        val p = AdaptiveKernel(base(), FakeLearner(knows = true)).plan(analysis(complexity = 0.2), false)
        assertEquals(384, p.thinkingBudgetTokens)
        assertEquals(1500, p.contextBudgetChars)
    }

    @Test
    fun anAlreadyTopTierStaysPut() {
        val p = AdaptiveKernel(base(), FakeLearner(knows = true)).plan(analysis(complexity = 0.9), false)
        assertEquals(768, p.thinkingBudgetTokens)
        assertEquals(6000, p.contextBudgetChars)
        assertFalse(p.rationale.contains("adaptive"))
    }

    @Test
    fun aStrugglingTopicGetsOneMoreTier() {
        val learner = FakeLearner(weak = listOf(WeakTopic("photosynthesis", 3)))
        val p = AdaptiveKernel(base(), learner)
            .plan(analysis(text = "photosynthesis light reaction", complexity = 0.5), false)
        assertEquals(768, p.thinkingBudgetTokens)
        assertTrue(p.rationale.contains("struggling"))
    }

    @Test
    fun theStruggleMatchIsWholeWordNotSubstring() {
        // "hiv" must not struggle-match the query "shivam kumar"
        val b = base()
        val a = analysis(text = "shivam kumar", complexity = 0.5)
        val learner = FakeLearner(weak = listOf(WeakTopic("hiv", 2)))
        assertEquals(b.plan(a, false), AdaptiveKernel(b, learner).plan(a, false))
    }

    @Test
    fun aDeterministicRouteIsNeverBumped() {
        val p = AdaptiveKernel(base(), FakeLearner(knows = true)).plan(analysis(), cacheHit = true)
        assertEquals(Route.CACHE, p.route)
        assertEquals(0, p.thinkingBudgetTokens)
    }
}
