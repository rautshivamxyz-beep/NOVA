package org.nova.ncie.execute.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v9.33.14: the first real unit tests for NOVA's pure-JVM kernel. Run with
 * `gradle testDebugUnitTest` (CI runs them on every pull request).
 *
 * [unaryMinusBindsLooserThanPower] pins the v9.33.7 calculator fix: the
 * shipped evaluator used to return 4 for -2^2 and 9 for -3^2.
 * [theTwoEvaluatorsAgree] pins the two independent evaluators to each other.
 */
class CalculatorToolTest {

    private val calc = CalculatorTool()

    @Test
    fun unaryMinusBindsLooserThanPower() {
        assertEquals(-4.0, calc.evaluateDirect("-2^2")!!, 1e-9)
        assertEquals(-9.0, calc.evaluateDirect("-3^2")!!, 1e-9)
        assertEquals(4.0, calc.evaluateDirect("(-2)^2")!!, 1e-9)
    }

    @Test
    fun powerIsRightAssociative() {
        assertEquals(512.0, calc.evaluateDirect("2^3^2")!!, 1e-9)
    }

    @Test
    fun theTwoEvaluatorsAgree() {
        for (e in listOf("2+2", "12*(3+4)^2", "2^3^2", "-2^2", "100/8", "7%3", "3-4*2")) {
            val d = calc.evaluateDirect(e)
            val r = calc.evaluateRpn(e)
            assertEquals("evaluateDirect($e)", d!!, r!!, 1e-9)
        }
    }

    @Test
    fun nonFiniteResultsAreRefusedByBothEvaluators() {
        for (expression in listOf("10^1000", "0^-1", "(-1)^0.5")) {
            assertNull("direct: $expression", calc.evaluateDirect(expression))
            assertNull("RPN: $expression", calc.evaluateRpn(expression))
        }
    }

    @Test
    fun divisionByZeroIsRefused() {
        assertNull(calc.evaluateDirect("1/0"))
    }
}
