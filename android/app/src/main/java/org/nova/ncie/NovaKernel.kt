package org.nova.ncie

import org.nova.ncie.analyze.Analyzer
import org.nova.ncie.execute.LlmEngine
import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.execute.tools.CalculatorTool
import org.nova.ncie.knowledge.KnowledgeStore
import org.nova.ncie.learn.Learner
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.NovaResponse
import org.nova.ncie.model.PhaseTrace
import org.nova.ncie.model.Route
import org.nova.ncie.plan.Planner
import org.nova.ncie.verify.Verifier

/**
 * The NOVA Core Intelligence Engine.
 *
 *   Request → ① ANALYZE → ② PLAN → ③ EXECUTE → ④ VERIFY → ⑤ LEARN → Answer
 *
 * The kernel owns the pipeline and NOTHING else. Every phase is injected as
 * an interface, so the llama.cpp engine, the MNN engine, new tools and new
 * verifiers slot in without touching this file — the lesson learned from
 * NOVA-android's 4,000-line MainActivity.
 */
class NovaKernel(
    private val analyzer: Analyzer,
    private val planner: Planner,
    private val tools: ToolRegistry,
    private val llm: LlmEngine,
    private val verifier: Verifier,
    private val learner: Learner,
    /** Optional personal knowledge base — retrieved chunks are injected
     *  into LLM prompts within the Plan's context budget (offline RAG). */
    private val knowledge: KnowledgeStore? = null,
) {

    fun ask(request: String): NovaResponse {
        val trace = ArrayList<PhaseTrace>()

        // ① ANALYZE ---------------------------------------------------------
        var t = System.nanoTime()
        val analysis: Analysis = timed(trace, "ANALYZE") { analyzer.analyze(request) }

        // ② PLAN (with the Learn phase consulted as a gate) -------------------
        val cached = learner.recall(request)
        val plan = timed(trace, "PLAN") { planner.plan(analysis, cached != null) }

        // ③ EXECUTE ----------------------------------------------------------
        var answer: String
        var llmUsed = false
        var contextChars = 0
        t = System.nanoTime()
        when (plan.route) {
            Route.CACHE -> answer = cached!!.answer
            Route.TOOL -> {
                val tool = tools.byName(plan.toolName!!)
                answer = tool?.execute(analysis) ?: "route error: tool '${plan.toolName}' not found"
            }
            Route.LLM, Route.TOOL_THEN_LLM -> {
                // The Plan's context budget is spent HERE: retrieved knowledge
                // is prepended to the prompt, capped at the budget.
                val context = knowledgeContext(analysis, plan.contextBudgetChars)
                contextChars = context.length
                answer = llm.generate(context + analysis.text, plan.thinkingBudgetTokens)
                llmUsed = true
            }
        }
        trace.add(PhaseTrace("EXECUTE", (System.nanoTime() - t) / 1_000_000,
            if (llmUsed) "llm=${llm.name()} budget=${plan.thinkingBudgetTokens}t" +
                (if (contextChars > 0) " ctx=${contextChars}c" else "")
            else "tool=${plan.toolName}"))

        // ④ VERIFY (+ Response Repair) ---------------------------------------
        t = System.nanoTime()
        var verdict = verifier.verify(analysis, plan, answer)
        var repaired = false
        if (!verdict.passed && analysis.toolSufficient) {
            // Repair: a deterministic tool exists — its answer outranks the failed one.
            val tool = tools.bestToolFor(analysis)
            if (tool != null) {
                answer = tool.execute(analysis)
                verdict = verifier.verify(analysis, plan, answer)
                repaired = true
            }
        }
        trace.add(PhaseTrace("VERIFY",
            (System.nanoTime() - t) / 1_000_000,
            if (repaired) "repaired → ${verdict.notes}" else verdict.notes))

        // ⑤ LEARN -------------------------------------------------------------
        t = System.nanoTime()
        val response = NovaResponse(
            answer = answer,
            plan = plan,
            verify = verdict,
            repaired = repaired,
            cacheHit = plan.route == Route.CACHE,
            llmUsed = llmUsed,
            trace = trace,
        )
        if (plan.route != Route.CACHE) learner.record(request, response)
        trace.add(PhaseTrace("LEARN", (System.nanoTime() - t) / 1_000_000, "cached for next time"))

        return response
    }

    private fun <T> timed(trace: ArrayList<PhaseTrace>, phase: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        trace.add(PhaseTrace(phase, (System.nanoTime() - start) / 1_000_000, ""))
        return result
    }

    /** Rarity-weighted chunks from the knowledge base, capped at the Plan's
     *  context budget. Empty when there is no store, no budget, or nothing
     *  relevant — the model then answers from its own knowledge alone. */
    private fun knowledgeContext(analysis: Analysis, budgetChars: Int): String {
        if (budgetChars <= 0) return ""
        val store = knowledge ?: return ""
        val hits = store.search(analysis.text, maxResults = 4)
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        var used = 0
        for (h in hits) {
            if (used + h.text.length > budgetChars) break
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(h.text)
            used += h.text.length
        }
        if (sb.isEmpty()) return ""
        return "(From the user's notes [${hits.first().doc}]:\n$sb\n\n" +
            "Answer using these notes where they apply.)\n\n"
    }

    companion object {
        /** A ready-to-run kernel with every default implementation. */
        fun defaults(): NovaKernel {
            val calculator = CalculatorTool()
            val tools = ToolRegistry(listOf(calculator))
            return NovaKernel(
                analyzer = org.nova.ncie.analyze.RuleBasedAnalyzer(),
                planner = org.nova.ncie.plan.DecisionKernel(tools),
                tools = tools,
                llm = org.nova.ncie.execute.StubLlmEngine(),
                verifier = org.nova.ncie.verify.MathVerifier(calculator),
                learner = org.nova.ncie.learn.SimpleLearner(),
            )
        }
    }
}
