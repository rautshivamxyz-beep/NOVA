package org.nova.ncie

import org.nova.ncie.analyze.Analyzer
import org.nova.ncie.execute.LlmEngine
import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.execute.tools.CalculatorTool
import org.nova.ncie.knowledge.KnowledgeStore
import org.nova.ncie.learn.LearnedFact
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
        // v0.9.2 (opt #10): the Learn phase's counters ride along in the
        // PLAN trace - what an adaptive planner could have used, visible
        val ls = learner.statsData()
        val plan = timed(trace, "PLAN", if (ls != null)
            "facts=${ls.factCount} cache=${ls.cacheEntries}" else "") {
            planner.plan(analysis, cached != null)
        }

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
            Route.LLM -> {
                // The Plan's context budget is spent HERE: retrieved knowledge
                // is prepended to the prompt, capped at the budget.
                val context = knowledgeContext(analysis, plan.contextBudgetChars)
                contextChars = context.length
                answer = llm.generate(context + analysis.text, plan.thinkingBudgetTokens)
                llmUsed = true
            }
            Route.TOOL_THEN_LLM -> {
                // v0.8.1: the tool's exact fact is injected as
                // authoritative context ahead of everything else — a
                // blank contribution degrades to the plain LLM path.
                val fact = plan.toolName?.let { tools.byName(it) }?.let { tool ->
                    try { tool.contribute(analysis) } catch (_: Exception) { "" }
                } ?: ""
                val factCtx = if (fact.isBlank()) ""
                    else "(Exact computed fact from the '${plan.toolName}' tool: $fact.\n" +
                        "Use it as given — do not recompute it differently.)\n\n"
                val context = knowledgeContext(analysis, plan.contextBudgetChars)
                contextChars = factCtx.length + context.length
                answer = llm.generate(factCtx + context + analysis.text, plan.thinkingBudgetTokens)
                llmUsed = true
            }
        }
        trace.add(PhaseTrace("EXECUTE", (System.nanoTime() - t) / 1_000_000,
            if (llmUsed) "llm=${llm.name()} budget=${plan.thinkingBudgetTokens}t" +
                (if (contextChars > 0) " ctx=${contextChars}c" else "") +
                (if (plan.route == Route.TOOL_THEN_LLM) " tool=${plan.toolName}" else "")
            else "tool=${plan.toolName}"))

        // ④ VERIFY (+ Response Repair) ---------------------------------------
        t = System.nanoTime()
        var verdict = verifier.verify(analysis, plan, answer)
        var repaired = false
        // v0.8.1: repair only fires on the plain LLM route — a
        // TOOL_THEN_LLM request already used the tool for its fact;
        // re-running execute() on a request the tool can't fully
        // answer would only produce a parse complaint.
        if (!verdict.passed && analysis.toolSufficient && plan.route == Route.LLM) {
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
        // v0.9.0: quality-gated memory — an answer that also clears the
        // quality bar is born as a graded fact, which twice-confirmed
        // facts graduate from into the knowledge base (see Distiller).
        // Pure TOOL turns (the calculator) are deterministic: there is
        // nothing to learn from them, so they stay out of the memory.
        if (plan.route != Route.CACHE && plan.route != Route.TOOL &&
            verdict.qualityScore >= LEARN_QUALITY_THRESHOLD
        ) {
            learner.record(LearnedFact.fromChat(request, answer, verdict.qualityScore))
        }
        trace.add(PhaseTrace("LEARN", (System.nanoTime() - t) / 1_000_000, "cached for next time"))

        return response
    }

    /**
     * Tool-only gate for callers that check BEFORE the model runs:
     * ① ANALYZE → ② PLAN, and only when the plan routes to a deterministic
     * tool does it ③ EXECUTE (+ ④ VERIFY + ⑤ LEARN).
     *
     * Returns null when no tool claims the request — the caller then
     * continues down its normal (LLM) path. The LLM engine is never
     * touched, so the kernel can be wired to an engine that is not even
     * loaded yet: the NOVA app runs this gate for calculator requests
     * before any model is downloaded.
     *
     * A cached repeat is served straight from Learn.
     */
    fun tryTool(request: String): NovaResponse? {
        val trace = ArrayList<PhaseTrace>()

        // ① ANALYZE ---------------------------------------------------------
        val analysis = timed(trace, "ANALYZE") { analyzer.analyze(request) }

        // ⑤ LEARN consulted as a gate (same as ask) ---------------------------
        learner.recall(request)?.let { return it }

        // ② PLAN --------------------------------------------------------------
        val plan = timed(trace, "PLAN") { planner.plan(analysis, cacheHit = false) }
        if (plan.route != Route.TOOL) return null

        // ③ EXECUTE -----------------------------------------------------------
        var t = System.nanoTime()
        val tool = plan.toolName?.let { tools.byName(it) } ?: return null
        val answer = try {
            tool.execute(analysis)
        } catch (_: Exception) {
            return null // claimed but failed — let the caller's path take it
        }
        if (answer.isBlank()) return null
        trace.add(PhaseTrace("EXECUTE", (System.nanoTime() - t) / 1_000_000, "tool=${plan.toolName}"))

        // ④ VERIFY ------------------------------------------------------------
        t = System.nanoTime()
        val verdict = verifier.verify(analysis, plan, answer)
        trace.add(PhaseTrace("VERIFY", (System.nanoTime() - t) / 1_000_000, verdict.notes))

        // ⑤ LEARN -------------------------------------------------------------
        t = System.nanoTime()
        val response = NovaResponse(
            answer = answer,
            plan = plan,
            verify = verdict,
            repaired = false,
            cacheHit = false,
            llmUsed = false,
            trace = trace,
        )
        learner.record(request, response)
        trace.add(PhaseTrace("LEARN", (System.nanoTime() - t) / 1_000_000, "cached for next time"))

        return response
    }

    private fun <T> timed(
        trace: ArrayList<PhaseTrace>,
        phase: String,
        detail: String = "",
        block: () -> T,
    ): T {
        val start = System.nanoTime()
        val result = block()
        trace.add(PhaseTrace(phase, (System.nanoTime() - start) / 1_000_000, detail))
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
        /**
         * v0.9.0: the quality bar a verified answer must clear to be born
         * as a graded fact. Score semantics: verify() returns 1.0 for
         * cross-checked math, 0.95 for cache, and 0.5 as the neutral
         * floor when no deterministic check applies to the intent
         * (exactly what an ordinary chat answer is); quality() returns
         * 0.6 on a clean pass; every real failure is 0.0–0.4. 0.5
         * therefore admits "nothing said otherwise" and excludes
         * everything that actually failed a check.
         */
        const val LEARN_QUALITY_THRESHOLD = 0.5

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
