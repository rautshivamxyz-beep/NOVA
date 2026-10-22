package org.nova.ncie.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nova.ncie.execute.Tool
import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Intent
import org.nova.ncie.model.Route

/**
 * v9.33.34: the PLAN gate (DecisionKernel) picks the route and the two
 * budgets for every turn. It is a pure function of the Analysis, so it is
 * trivially testable with a fake Tool - no model, no Android. These pin the
 * four routes and the three-tier thinking/context budgets: a regression
 * here either wakes the model when a tool could have answered (slow, and a
 * waste of RAM on a 4 GB phone) or starves a hard question of context.
 *
 * Run with `gradle testDebugUnitTest` (CI runs it on every pull request).
 */
class DecisionKernelTest {

    /** A tool that claims (or only assists) whatever it is told to. */
    private class FakeTool(
        private val name: String,
        private val handles: Boolean = false,
        private val helps: Boolean = false,
    ) : Tool {
        override fun name() = name
        override fun canHandle(analysis: Analysis) = handles
        override fun execute(analysis: Analysis) = "$name answer"
        override fun assists(analysis: Analysis) = helps
        override fun contribute(analysis: Analysis) = if (helps) "42" else ""
    }

    private fun analysis(intent: Intent = Intent.CHAT, complexity: Double = 0.5) =
        Analysis(text = "x", intent = intent, complexity = complexity,
            toolSufficient = intent == Intent.CALCULATION, keywords = emptyList())

    @Test
    fun exactRepeatIsServedFromCache() {
        val p = DecisionKernel(ToolRegistry(emptyList())).plan(analysis(), cacheHit = true)
        assertEquals(Route.CACHE, p.route)
        assertNull(p.toolName)
        assertEquals(0, p.thinkingBudgetTokens)
        assertEquals(0, p.contextBudgetChars)
    }

    @Test
    fun aClaimingToolAnswersWithoutTheModel() {
        val tool = FakeTool("calc", handles = true)
        val p = DecisionKernel(ToolRegistry(listOf(tool))).plan(analysis(), cacheHit = false)
        assertEquals(Route.TOOL, p.route)
        assertEquals("calc", p.toolName)
        // the model must not be woken at all
        assertEquals(0, p.thinkingBudgetTokens)
        assertEquals(0, p.contextBudgetChars)
    }

    @Test
    fun anAssistingToolComputesAFactThenTheModelExplains() {
        val tool = FakeTool("arith", helps = true)
        val p = DecisionKernel(ToolRegistry(listOf(tool))).plan(analysis(complexity = 0.5), cacheHit = false)
        assertEquals(Route.TOOL_THEN_LLM, p.route)
        assertEquals("arith", p.toolName)
        // budgets scale with complexity, same as the plain LLM path
        assertEquals(384, p.thinkingBudgetTokens)
        assertEquals(1500, p.contextBudgetChars)
    }

    @Test
    fun withNoToolTheModelAnswers() {
        val p = DecisionKernel(ToolRegistry(emptyList())).plan(analysis(), cacheHit = false)
        assertEquals(Route.LLM, p.route)
        assertNull(p.toolName)
    }

    @Test
    fun thinkingAndContextBudgetsScaleInThreeTiers() {
        val k = DecisionKernel(ToolRegistry(emptyList()))
        // trivial
        k.plan(analysis(complexity = 0.2), false).let {
            assertEquals(128, it.thinkingBudgetTokens)
            assertEquals(0, it.contextBudgetChars)
        }
        // medium
        k.plan(analysis(complexity = 0.5), false).let {
            assertEquals(384, it.thinkingBudgetTokens)
            assertEquals(1500, it.contextBudgetChars)
        }
        // hard
        k.plan(analysis(complexity = 0.9), false).let {
            assertEquals(768, it.thinkingBudgetTokens)
            assertEquals(6000, it.contextBudgetChars)
        }
    }
}
