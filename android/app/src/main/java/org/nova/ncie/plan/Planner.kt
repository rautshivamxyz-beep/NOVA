package org.nova.ncie.plan

import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Intent
import org.nova.ncie.model.Plan
import org.nova.ncie.model.Route

/**
 * ② PLAN — the AI Decision Kernel.
 *
 * Decides HOW to answer before anything runs: route (tool vs LLM vs both),
 * the thinking budget (max generation tokens) and the context budget.
 * Pure function of the [Analysis] — no I/O, trivially testable.
 */
interface Planner {
    fun plan(analysis: Analysis, cacheHit: Boolean): Plan
}

class DecisionKernel(private val tools: ToolRegistry) : Planner {

    override fun plan(analysis: Analysis, cacheHit: Boolean): Plan {
        // Smart Skip / Predictive Cache: an exact repeat is answered from Learn.
        if (cacheHit) {
            return Plan(Route.CACHE, null, 0, 0, "exact request seen before — serve from cache")
        }

        // A deterministic tool can fully answer it → never wake the model.
        if (analysis.intent == Intent.CALCULATION) {
            val tool = tools.bestToolFor(analysis)
            if (tool != null) {
                return Plan(
                    route = Route.TOOL,
                    toolName = tool.name(),
                    thinkingBudgetTokens = 0,
                    contextBudgetChars = 0,
                    rationale = "deterministic answer available from '${tool.name()}' — LLM skipped",
                )
            }
        }

        // Otherwise the LLM answers; budget scales with complexity.
        // (Later: TOOL_THEN_LLM when a tool computes a fact the answer needs.)
        val tokens = thinkingBudget(analysis.complexity)
        val context = contextBudget(analysis.complexity)
        return Plan(
            route = Route.LLM,
            toolName = null,
            thinkingBudgetTokens = tokens,
            contextBudgetChars = context,
            rationale = "no tool covers this — LLM with ${tokens}t budget, complexity ${"%.1f".format(analysis.complexity)}",
        )
    }

    /** Thinking Budget Engine: simple, fast requests get short leashes. */
    private fun thinkingBudget(complexity: Double): Int {
        if (complexity < 0.35) return 128
        if (complexity < 0.7) return 384
        return 768
    }

    /** Context Budget: how much retrieved knowledge to inject into the prompt. */
    private fun contextBudget(complexity: Double): Int {
        if (complexity < 0.35) return 0
        if (complexity < 0.7) return 1500
        return 6000
    }
}
