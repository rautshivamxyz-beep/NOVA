package org.nova.ncie.android

import org.nova.ncie.NovaKernel
import org.nova.ncie.analyze.RuleBasedAnalyzer
import org.nova.ncie.execute.LlmEngine
import org.nova.ncie.execute.Tool
import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.learn.SimpleLearner
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Plan
import org.nova.ncie.model.VerifyResult
import org.nova.ncie.plan.DecisionKernel
import org.nova.ncie.verify.Verifier

/**
 * NCIE Stage 2 (#1): the app's calculator gate, routed through the
 * DecisionKernel. MainActivity.solveArithmetic asks this object; the
 * kernel runs ① ANALYZE → ② PLAN → ③ EXECUTE → ④ VERIFY → ⑤ LEARN and
 * returns the formatted answer, or null when the text is not pure
 * arithmetic (then the app continues to the model exactly as before).
 *
 * The expression language is byte-for-byte the one the app shipped since
 * v7.0.0 — the normalization (unicode operators, comma separators,
 * sqrt/sin/cos/tan/log/ln/pi) and the evaluator (trig in degrees, log
 * base 10, right-associative ^) moved into [ArithmeticTool] unchanged.
 *
 * tryTool never touches the LLM, so this works before any model is
 * downloaded — same as the old gate.
 */
object NcieArithmetic {

    private val tool = ArithmeticTool()
    private val tools = ToolRegistry(listOf(tool))

    private val kernel = NovaKernel(
        analyzer = RuleBasedAnalyzer(),
        planner = DecisionKernel(tools),
        tools = tools,
        llm = NeverLlm,               // tryTool must never reach it
        verifier = ToolVerifier,
        learner = SimpleLearner(),
    )

    /** The formatted answer, or null when the kernel does not route this
     *  text to the arithmetic tool. */
    fun solve(text: String): String? = kernel.tryTool(text)?.answer

    /** An LlmEngine that can never be called: tryTool routes only to
     *  deterministic tools, so generation is a bug if it happens. */
    private object NeverLlm : LlmEngine {
        override fun name() = "never"
        override fun isLoaded() = false
        override fun generate(prompt: String, maxTokens: Int): String =
            throw IllegalStateException("tryTool routed to the LLM — kernel wiring bug")
    }

    /** Arithmetic is its own proof: the tool only claims text it fully
     *  parsed and evaluated. (A dual-evaluator cross-check for the app's
     *  expression language is a later Verify upgrade.) */
    private object ToolVerifier : Verifier {
        override fun verify(analysis: Analysis, plan: Plan, answer: String) =
            VerifyResult(true, 1.0, "deterministic answer from ${plan.toolName}")
    }
}

/**
 * The v7.0.0 app calculator as an NCIE Tool. canHandle is strict: it only
 * claims text it can fully evaluate, so a TOOL route guarantees a real
 * answer. Pure JVM, no Android imports — the logic that used to live in
 * MainActivity.solveArithmetic, moved verbatim.
 */
class ArithmeticTool : Tool {

    override fun name() = "app-arithmetic"

    override fun canHandle(analysis: Analysis): Boolean = evaluate(analysis.text) != null

    override fun execute(analysis: Analysis): String =
        evaluate(analysis.text) ?: throw ArithmeticException("not arithmetic: ${analysis.text}")

    /** The exact old gate: normalize, parse, require full consumption and
     *  a finite result. Returns the display string, or null. */
    fun evaluate(text: String): String? {
        val t = text.trim()
        if (t.length < 3 || t.length > 150 || t.contains('\n')) return null
        val s = t.lowercase()
            .replace("\u00d7", "*").replace("\u00f7", "/")
            .replace("\u2212", "-").replace("\u2013", "-")
            .replace(",", "").replace(" ", "")
            .replace("sqrt", "q").replace("sin", "s").replace("cos", "c")
            .replace("tan", "t").replace("log", "g").replace("ln", "n")
            .replace("pi", "p")
        if (!Regex("^[0-9+\\-*/^%().qsctgnpe]+").matches(s)) return null
        // a word made only of function letters ("ten") is not arithmetic
        if (!Regex("[0-9]").containsMatchIn(s)) return null
        if (!Regex("[+\\-*/^%]").containsMatchIn(s) && !Regex("[qsctgnp]").containsMatchIn(s)) return null
        return try {
            val p = object {
                var i = 0
                fun peek(): Char = if (i < s.length) s[i] else ' '
                fun expr(): Double {
                    var r = term()
                    while (peek() == '+' || peek() == '-') {
                        val op = s[i++]; val b = term()
                        r = if (op == '+') r + b else r - b
                    }
                    return r
                }
                fun term(): Double {
                    var r = pw()
                    while (peek() == '*' || peek() == '/' || peek() == '%') {
                        val op = s[i++]; val b = pw()
                        r = when (op) { '*' -> r * b; '/' -> r / b; else -> r % b }
                    }
                    return r
                }
                fun pw(): Double {
                    val r = unary()
                    if (peek() == '^') { i++; return Math.pow(r, pw()) }
                    return r
                }
                fun unary(): Double {
                    if (peek() == '-') { i++; return -unary() }
                    if (peek() == '+') { i++ }
                    return atom()
                }
                fun atom(): Double {
                    val ch = peek()
                    if (ch == '(') { i++; val r = expr(); if (peek() == ')') i++; return r }
                    if (ch == 'q') { i++; return Math.sqrt(inner()) }
                    if (ch == 's') { i++; return Math.sin(Math.toRadians(inner())) }
                    if (ch == 'c') { i++; return Math.cos(Math.toRadians(inner())) }
                    if (ch == 't') { i++; return Math.tan(Math.toRadians(inner())) }
                    if (ch == 'g') { i++; return Math.log10(inner()) }
                    if (ch == 'n') { i++; return Math.log(inner()) }
                    if (ch == 'p') { i++; return Math.PI }
                    if (ch == 'e') { i++; return Math.E }
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    if (i == start) throw ArithmeticException("bad token")
                    return s.substring(start, i).toDouble()
                }
                fun inner(): Double {
                    if (peek() == '(') { i++; val r = expr(); if (peek() == ')') i++; return r }
                    return atom()
                }
            }
            val v = p.expr()
            // the whole input must be part of the math - no leftovers
            if (p.i != s.length) return null
            if (!v.isFinite()) return null
            if (Math.abs(v - Math.round(v)) < 1e-9)
                Math.round(v).toString()
            else
                String.format(java.util.Locale.US, "%.6g", v)
        } catch (e: Exception) { null }
    }
}
