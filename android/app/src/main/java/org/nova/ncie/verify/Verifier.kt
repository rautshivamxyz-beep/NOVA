package org.nova.ncie.verify

import org.nova.ncie.execute.tools.CalculatorTool
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Intent
import org.nova.ncie.model.Plan
import org.nova.ncie.model.Route
import org.nova.ncie.model.VerifyResult

/**
 * ④ VERIFY — check the answer before the user sees it.
 *
 * The MathVerifier is the strongest member of the Truth Engine family:
 * math has one right answer, and the calculator has TWO independent
 * evaluators (recursive-descent and shunting-yard), so a routed
 * calculation is cross-checked with an algorithm that shares no code
 * with the one that produced it.
 *
 * v0.7.0: [quality] adds the checks that need no ground truth — blank
 * answers, leaked chat boilerplate, length limits and source grounding.
 */
interface Verifier {
    fun verify(analysis: Analysis, plan: Plan, answer: String): VerifyResult

    /**
     * v0.7.0 — answer-quality checks that need no ground truth. Hosts
     * run this on every LLM answer (the deterministic routes are already
     * covered by [verify]):
     *  - a blank answer fails outright
     *  - leaked chat boilerplate fails: a transcript header ("NOVA:",
     *    "You:") or strict-mode prologue ("From general knowledge...") —
     *    the failure modes the NOVA app's cleanReplyText repairs reactively
     *  - more than [maxWords] words fails, when a limit is given
     *  - with [sources], the answer must ground in them: a solid
     *    fraction of its significant words must appear in the source text
     */
    fun quality(answer: String, sources: List<String> = emptyList(), maxWords: Int = 0): VerifyResult {
        val a = answer.trim()
        if (a.isEmpty()) {
            return VerifyResult(false, 0.0, "answer is empty")
        }
        if (Regex("(?m)^\\s*(?:NOVA|You)\\s*:").containsMatchIn(a)) {
            return VerifyResult(false, 0.1, "answer leaked a chat transcript header")
        }
        if (Regex("(?i)from general knowledge").containsMatchIn(a)) {
            return VerifyResult(false, 0.2, "answer leaked strict-mode boilerplate")
        }
        val words = a.split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (maxWords > 0 && words.size > maxWords) {
            return VerifyResult(false, 0.4, "${words.size} words — over the $maxWords-word limit")
        }
        if (sources.isNotEmpty()) {
            // v0.9.2 (opt #9): whole-word grounding. The old substring
            // test counted "paris" as grounded by "comparison" - the same
            // word-boundary bug the wiki title search once had.
            val srcWords = sources.joinToString(" ").lowercase()
                .split(Regex("\\W+")).filter { it.isNotEmpty() }.toHashSet()
            val sig = words.map { it.lowercase() }.filter { it.length > 3 }.distinct()
            if (sig.isNotEmpty()) {
                val covered = sig.count { it in srcWords }
                val ratio = covered.toDouble() / sig.size
                if (ratio < 0.3) {
                    return VerifyResult(
                        false, 0.3,
                        "answer drifts from its sources ($covered/${sig.size} significant terms grounded)",
                    )
                }
                // v0.9.2 (opt #1): a grounded answer earns a CONTINUOUS score
                // instead of the flat 0.6, so a well-grounded answer can
                // actually climb past the graduation bar (0.7). The old flat
                // score froze every learned fact at 0.6 forever.
                return VerifyResult(
                    true, 0.5 + 0.45 * ratio,
                    "quality checks passed (grounding ${(100 * ratio).toInt()}%)",
                )
            }
        }
        return VerifyResult(true, 0.6, "quality checks passed (no ground truth needed)")
    }
}

class MathVerifier(private val calculator: CalculatorTool) : Verifier {

    override fun verify(analysis: Analysis, plan: Plan, answer: String): VerifyResult {
        if (plan.route == Route.CACHE) {
            return VerifyResult(true, 0.95, "served from cache (verified when first computed)")
        }
        if (analysis.intent != Intent.CALCULATION) {
            // Nothing deterministic to check — quality() covers the rest.
            return VerifyResult(false, 0.5, "no deterministic check available for this intent")
        }

        // v0.8.1: worded calculations ("what is 5*4 in physics") have no
        // parseable full expression — verify against the embedded one,
        // the same expression TOOL_THEN_LLM would contribute as a fact.
        val full = calculator.expressionOf(analysis.text)
        val expr = if (calculator.evaluateDirect(full) != null) full
            else calculator.embeddedExpression(analysis.text)
                ?: return VerifyResult(false, 0.3, "expression could not be evaluated")
        val direct = calculator.evaluateDirect(expr)
        val rpn = calculator.evaluateRpn(expr)
        if (direct == null || rpn == null) {
            return VerifyResult(false, 0.3, "expression could not be evaluated")
        }
        if (direct != rpn) {
            return VerifyResult(false, 0.0, "internal cross-check disagreed: $direct vs $rpn")
        }

        val claimed = lastNumberIn(answer)
        return if (claimed != null && approxEquals(claimed, direct)) {
            VerifyResult(true, 1.0, "two independent evaluators agree: $direct")
        } else {
            VerifyResult(false, 0.2, "answer does not contain the computed value $direct")
        }
    }

    private fun lastNumberIn(s: String): Double? =
        Regex("-?\\d+(\\.\\d+)?").findAll(s).lastOrNull()?.value?.toDoubleOrNull()

    private fun approxEquals(a: Double, b: Double): Boolean =
        Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.abs(b))
}
