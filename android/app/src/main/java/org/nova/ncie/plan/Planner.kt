package org.nova.ncie.plan

import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.model.Analysis
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

class DecisionKernel(
    private val tools: ToolRegistry,
    /** v0.9.2 (opt #10): the three-tier thinking budget (trivial /
     *  medium / hard complexity) - injectable, so hosts and future
     *  adaptive planners tune budgets without editing the kernel.
     *  Defaults are the shipped values. */
    private val thinkingBudgets: Triple<Int, Int, Int> = Triple(128, 384, 768),
    /** v0.9.2 (opt #10): the matching three-tier context budget. */
    private val contextBudgets: Triple<Int, Int, Int> = Triple(0, 1500, 6000),
) : Planner {

    override fun plan(analysis: Analysis, cacheHit: Boolean): Plan {
        // Smart Skip / Predictive Cache: an exact repeat is answered from Learn.
        if (cacheHit) {
            return Plan(Route.CACHE, null, 0, 0, "exact request seen before — serve from cache")
        }

        // A registered tool that CLAIMS the request answers it
        // deterministically → never wake the model. Tools define their own
        // domain: the NCIE calculator requires a CALCULATION intent and a
        // parseable expression; the NOVA app's arithmetic tool validates
        // its own expression language (sqrt, trig in degrees, pi, ×÷, …).
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

        // v0.8.1 TOOL_THEN_LLM: no tool fully answers, but one can
        // compute an exact fact the answer needs (arithmetic buried
        // inside a worded question). EXECUTE runs the tool first and
        // injects its fact as authoritative context for the LLM.
        val assistant = tools.bestAssistantFor(analysis)
        if (assistant != null) {
            val tokens = thinkingBudget(analysis.complexity)
            val context = contextBudget(analysis.complexity)
            return Plan(
                route = Route.TOOL_THEN_LLM,
                toolName = assistant.name(),
                thinkingBudgetTokens = tokens,
                contextBudgetChars = context,
                rationale = "no tool fully answers — '${assistant.name()}' computes the exact fact first, then the LLM explains it",
            )
        }

        // Otherwise the LLM answers; budget scales with complexity.
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
        if (complexity < 0.35) return thinkingBudgets.first
        if (complexity < 0.7) return thinkingBudgets.second
        return thinkingBudgets.third
    }

    /** Context Budget: how much retrieved knowledge to inject into the prompt. */
    private fun contextBudget(complexity: Double): Int {
        if (complexity < 0.35) return contextBudgets.first
        if (complexity < 0.7) return contextBudgets.second
        return contextBudgets.third
    }
}
