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
 */
interface Verifier {
    fun verify(analysis: Analysis, plan: Plan, answer: String): VerifyResult
}

class MathVerifier(private val calculator: CalculatorTool) : Verifier {

    override fun verify(analysis: Analysis, plan: Plan, answer: String): VerifyResult {
        if (plan.route == Route.CACHE) {
            return VerifyResult(true, 0.95, "served from cache (verified when first computed)")
        }
        if (analysis.intent != Intent.CALCULATION) {
            // Nothing deterministic to check yet — grammar/fact checks come later.
            return VerifyResult(false, 0.5, "no deterministic check available for this intent")
        }

        val expr = calculator.expressionOf(analysis.text)
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
