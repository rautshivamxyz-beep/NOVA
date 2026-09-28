package org.nova.ncie.execute.tools

import org.nova.ncie.execute.Tool
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Intent
import java.util.ArrayDeque

/**
 * Calculator — the first NOVA tool. A safe evaluator with NO eval():
 * integers, decimals, + - * / % ^ and parentheses.
 *
 * Two deliberately independent implementations:
 *  - [evaluateDirect]  : recursive descent (grammar-driven)
 *  - [evaluateRpn]     : shunting-yard → RPN → stack machine
 * The MathVerifier cross-checks one against the other, so a routed
 * calculation is confirmed by two algorithms that share no code.
 */
class CalculatorTool : Tool {

    override fun name() = "calculator"

    override fun canHandle(analysis: Analysis): Boolean =
        analysis.intent == Intent.CALCULATION && tokenize(expressionOf(analysis.text)) != null

    override fun execute(analysis: Analysis): String {
        val expr = expressionOf(analysis.text)
        return evaluateDirect(expr)?.let { format(it) } ?: "I couldn't parse that expression."
    }

    /** v0.8.1 TOOL_THEN_LLM: a calculation-flavored request that can't
     *  be fully answered here (words around the numbers) can still
     *  yield its embedded expression as an exact fact for the LLM to
     *  build on. */
    override fun assists(analysis: Analysis): Boolean =
        analysis.intent == Intent.CALCULATION &&
            !canHandle(analysis) &&
            embeddedExpression(analysis.text) != null

    override fun contribute(analysis: Analysis): String {
        val expr = embeddedExpression(analysis.text) ?: return ""
        val v = evaluateDirect(expr) ?: return ""
        return "the expression $expr evaluates exactly to ${format(v)}"
    }

    /** The longest run of arithmetic characters in the text that
     *  actually parses AND contains an operator — "what is 5*4 in
     *  physics" yields "5*4"; a bare year like "1947" is not a
     *  computation and never qualifies. */
    fun embeddedExpression(text: String): String? {
        var best: String? = null
        for (m in Regex("[0-9(][0-9+\\-*/%^().\\s]*[0-9)]").findAll(text)) {
            val cand = m.value.trim()
            if (!Regex("[+\\-*/%^]").containsMatchIn(cand)) continue
            if (cand.length > (best?.length ?: 0) && evaluateDirect(cand) != null) best = cand
        }
        return best
    }

    /** "what is 2+2?" → "2+2"; plain math text passes through untouched. */
    fun expressionOf(text: String): String {
        val cleaned = text.lowercase()
            .replace(Regex("^(please\\s+)?(can you\\s+)?(calculate|compute|solve|what('s| is| are)|how much is)\\b"), "")
            .replace(Regex("[?.!]+\\s*$"), "").trim()
        return cleaned.ifBlank { text.trim() }
    }

    // ---------------------------------------------------------------- tokens

    private sealed interface Tok
    private data class Num(val v: Double) : Tok
    private data class Op(val c: Char) : Tok
    private object LP : Tok
    private object RP : Tok

    private fun tokenize(s: String): List<Tok>? {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    val v = s.substring(i, j).toDoubleOrNull() ?: return null
                    out.add(Num(v)); i = j
                }
                c in "+-*/%^" -> { out.add(Op(c)); i++ }
                c == '(' -> { out.add(LP); i++ }
                c == ')' -> { out.add(RP); i++ }
                else -> return null // any stray letter kills the parse
            }
        }
        return out
    }

    // ---------------------------------------- path A: recursive descent parser

    /** expr := term (('+'|'-') term)* */
    fun evaluateDirect(expr: String): Double? {
        val toks = tokenize(expr) ?: return null
        val pos = intArrayOf(0)
        val v = expr(toks, pos) ?: return null
        return if (pos[0] == toks.size) v else null
    }

    private fun peek(toks: List<Tok>, pos: IntArray): Tok? = toks.getOrNull(pos[0])

    private fun expr(toks: List<Tok>, pos: IntArray): Double? {
        var left = term(toks, pos) ?: return null
        while (true) {
            val t = peek(toks, pos) as? Op ?: break
            if (t.c != '+' && t.c != '-') break
            pos[0]++
            val right = term(toks, pos) ?: return null
            left = if (t.c == '+') left + right else left - right
        }
        return left
    }

    private fun term(toks: List<Tok>, pos: IntArray): Double? { // * / %
        var left = unary(toks, pos) ?: return null
        while (true) {
            val t = peek(toks, pos) as? Op ?: break
            if (t.c != '*' && t.c != '/' && t.c != '%') break
            pos[0]++
            val right = unary(toks, pos) ?: return null
            left = when (t.c) {
                '*' -> left * right
                '/' -> if (right == 0.0) return null else left / right
                else -> if (right == 0.0) return null else left % right
            }
        }
        return left
    }

    /** unary := ('-'|'+') unary | power  — so -3^2 == -(3^2) and 2*-3 works */
    private fun unary(toks: List<Tok>, pos: IntArray): Double? {
        val t = peek(toks, pos) ?: return null
        if (t is Op && (t.c == '-' || t.c == '+')) {
            pos[0]++
            val v = unary(toks, pos) ?: return null
            return if (t.c == '-') -v else v
        }
        return power(toks, pos)
    }

    /** power := atom ('^' unary)?  — right-associative */
    private fun power(toks: List<Tok>, pos: IntArray): Double? {
        val base = atom(toks, pos) ?: return null
        val t = peek(toks, pos) as? Op ?: return base
        if (t.c != '^') return base
        pos[0]++
        val exp = unary(toks, pos) ?: return null
        return Math.pow(base, exp)
    }

    private fun atom(toks: List<Tok>, pos: IntArray): Double? {
        val t = peek(toks, pos) ?: return null
        return when (t) {
            is Num -> { pos[0]++; t.v }
            LP -> {
                pos[0]++
                val v = expr(toks, pos) ?: return null
                if (peek(toks, pos) == RP) { pos[0]++; v } else null
            }
            else -> null
        }
    }

    // --------------------------- path B: shunting-yard → RPN → stack machine

    fun evaluateRpn(expr: String): Double? {
        val toks = tokenize(expr) ?: return null
        val out = ArrayDeque<Double>()
        val ops = ArrayDeque<Char>()
        val prec = mapOf('+' to 1, '-' to 1, '*' to 2, '/' to 2, '%' to 2, 'u' to 3, '^' to 4)

        fun applyTop() {
            val op = ops.pop()
            if (op == 'u') {
                val a = out.pop()
                out.push(-a)
                return
            }
            if (out.size < 2) throw ArithmeticException("missing operand")
            val b = out.pop()
            val a = out.pop()
            val r = when (op) {
                '+' -> a + b; '-' -> a - b; '*' -> a * b
                '/' -> if (b == 0.0) throw ArithmeticException("div0") else a / b
                '%' -> if (b == 0.0) throw ArithmeticException("div0") else a % b
                else -> Math.pow(a, b)
            }
            if (r.isNaN() || r.isInfinite()) throw ArithmeticException("bad value")
            out.push(r)
        }

        try {
            var prevWasValue = false
            for (t in toks) {
                when (t) {
                    is Num -> { out.push(t.v); prevWasValue = true }
                    is Op -> {
                        val unaryMinus = t.c == '-' && !prevWasValue
                        val unaryPlus = t.c == '+' && !prevWasValue
                        if (unaryMinus) {
                            ops.push('u') // right-assoc, nothing to pop against
                        } else if (unaryPlus) {
                            // no-op
                        } else {
                            while (ops.isNotEmpty() && ops.peek() != '(' &&
                                (prec[ops.peek()]!! > prec[t.c]!! ||
                                    (prec[ops.peek()] == prec[t.c] && t.c != '^'))) applyTop()
                            ops.push(t.c)
                        }
                        prevWasValue = false
                    }
                    LP -> { ops.push('('); prevWasValue = false }
                    RP -> {
                        while (ops.isNotEmpty() && ops.peek() != '(') applyTop()
                        if (ops.isEmpty()) return null
                        ops.pop()
                        prevWasValue = true
                    }
                }
            }
            while (ops.isNotEmpty()) {
                if (ops.peek() == '(') return null
                applyTop()
            }
        } catch (_: Exception) {
            return null
        }
        return if (out.size == 1) out.peek() else null
    }

    private fun format(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
