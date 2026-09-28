package org.nova.ncie.plan

import org.nova.ncie.learn.Learner
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Plan
import org.nova.ncie.model.Route

/**
 * v0.9.3 (#10): the ADAPTIVE planner — the rules that were deliberately
 * left undecided when budgets became injectable (v0.9.2). It decorates
 * any base Planner and adjusts the LLM budgets with a signal the base
 * cannot see: the Learn phase itself.
 *
 * THE MASTERY RULE: a question the memory HALF-knows — the exact recall
 * misses, but [Learner.knowsTopic] reports a fuzzy hit — gets one tier
 * more thinking and context budget. The user is re-engaging a half-learned
 * topic: a better-grounded answer now completes the Distiller's graduation
 * streak instead of filing another weak fact next to the ones that
 * stalled. Everything else is the base planner's decision, untouched:
 *
 *  - a fully known question never reaches PLAN (recall serves it first),
 *  - a cold topic has nothing to bump on (first answers stay exactly as
 *    cheap as the base planner made them),
 *  - deterministic tools keep their zero budgets,
 *  - a question already on the top tier stays there.
 */
class AdaptiveKernel(
    private val base: Planner,
    private val learner: Learner,
    /** The tier ladders the base planner was built with — the bump reads
     *  the CURRENT tier off the plan and steps one rung up. */
    private val thinkingBudgets: Triple<Int, Int, Int> = Triple(128, 384, 768),
    private val contextBudgets: Triple<Int, Int, Int> = Triple(0, 1500, 6000),
) : Planner {

    override fun plan(analysis: Analysis, cacheHit: Boolean): Plan {
        val p = base.plan(analysis, cacheHit)
        // Budgets only exist on the LLM routes.
        if (p.route != Route.LLM && p.route != Route.TOOL_THEN_LLM) return p
        // A probe failure must never break planning — the learner is
        // optional infrastructure, the answer is not.
        val nearMiss = try { learner.knowsTopic(analysis.text) } catch (_: Exception) { false }
        if (!nearMiss) return p
        val tier = thinkingBudgets.toList().indexOf(p.thinkingBudgetTokens)
        if (tier < 0 || tier == 2) return p   // custom budget, or already top tier
        return p.copy(
            thinkingBudgetTokens = thinkingBudgets.toList()[tier + 1],
            contextBudgetChars = maxOf(p.contextBudgetChars, contextBudgets.toList()[tier + 1]),
            rationale = p.rationale +
                " — adaptive: half-learned topic (fuzzy memory hit), budget one tier up",
        )
    }
}
