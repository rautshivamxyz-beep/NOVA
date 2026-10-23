package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v9.33.55: the app's own arithmetic gate gets its first unit test.
 *
 * [ArithmeticTool] is what decides a turn is answered instantly on the
 * device, with no model call at all. A regression here is silent - the
 * text simply falls through to the LLM, or a wrong number is shown as a
 * confident instant answer - so the shipped expression language is pinned
 * here: the v9.33.7 unary-minus rule (-2^2 == -4), right-associative
 * powers, the unicode operators, comma separators, the trig/log helpers,
 * and the strict gate that refuses anything that is not pure arithmetic.
 *
 * Pure JVM, no Android imports - run with `gradle testDebugUnitTest`
 * (CI runs it on every pull request).
 */
class ArithmeticToolTest {

    private val tool = ArithmeticTool()

    private fun eval(e: String): String? = tool.evaluate(e)

    @Test
    fun additionAndPrecedence() {
        assertEquals("4", eval("2+2"))
        assertEquals("-5", eval("3-4*2"))
        assertEquals("84", eval("12*(3+4)"))
    }

    @Test
    fun unaryMinusBindsLooserThanPower() {
        // v9.33.7: the old order returned 4 for -2^2 and 9 for -3^2
        assertEquals("-4", eval("-2^2"))
        assertEquals("-9", eval("-3^2"))
        assertEquals("4", eval("(-2)^2"))
    }

    @Test
    fun powerIsRightAssociative() {
        assertEquals("512", eval("2^3^2"))
    }

    @Test
    fun moduloAndDivision() {
        assertEquals("1", eval("7%3"))
        assertEquals("12.5", eval("100/8"))
    }

    @Test
    fun unicodeOperatorsAndSeparators() {
        assertEquals("42", eval("6\u00d77"))
        assertEquals("3", eval("6\u00f72"))
        assertEquals("1002", eval("1,000+2"))
    }

    @Test
    fun functionsAndConstants() {
        assertEquals("4", eval("sqrt(16)"))
        assertEquals("0.5", eval("sin(30)"))
        assertEquals("2", eval("log(100)"))
        assertEquals("0", eval("ln(1)"))
        assertEquals("6.28319", eval("2*pi"))
    }

    @Test
    fun nonArithmeticIsRefused() {
        // a word made only of function letters is not arithmetic
        assertNull(eval("ten"))
        assertNull(eval("hello"))
        assertNull(eval("what is 2+2"))
        assertNull(eval("2+2 apples"))
        assertNull(eval(""))
        assertNull(eval("2+"))
    }
}
