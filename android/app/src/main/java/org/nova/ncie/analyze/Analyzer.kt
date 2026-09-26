package org.nova.ncie.analyze

import org.nova.ncie.model.Analysis
import org.nova.ncie.model.Intent

/**
 * ① ANALYZE
 *
 * Turns raw user text into a structured [Analysis] BEFORE any model runs.
 * Rule-based first: zero latency, zero memory, works on 4 GB devices.
 * A small local classifier can be swapped in later behind the same interface.
 */
interface Analyzer {
    fun analyze(text: String): Analysis
}

/**
 * Heuristic analyzer. Deliberately dumb and fast — the point of NCIE is
 * that "understanding the task" is a cheap gate in front of an expensive model.
 */
class RuleBasedAnalyzer : Analyzer {

    private val mathChars = Regex("^[0-9+\\-*/^%().\\s]+$")
    private val mathOperators = Regex("[+\\-*/^%]")
    private val digit = Regex("\\d")

    override fun analyze(text: String): Analysis {
        val t = text.trim()
        val hasDigits = digit.containsMatchIn(t)
        val onlyMath = t.isNotEmpty() && mathChars.matches(t) && mathOperators.containsMatchIn(t)

        // Words that signal arithmetic even inside a sentence.
        val wordProblem = listOf("calculate", "compute", "what is", "how much is", "solve")
            .any { it in t.lowercase() } && hasDigits

        val intent = when {
            onlyMath || wordProblem -> Intent.CALCULATION
            t.isEmpty() -> Intent.UNKNOWN
            else -> Intent.CHAT
        }

        val lower = t.lowercase()
        val words = lower.split(Regex("\\W+")).filter { it.length > 2 }
        // Knowledge-seeking questions — the port of the NOVA app's v5.4
        // "study question" gate — plus notes/document questions and short
        // keyword pulls ("bose") deserve a real context budget: the
        // offline-RAG path runs for them.
        val studyQ = lower.startsWith("explain ") || lower.startsWith("teach me ") ||
            lower.startsWith("what is ") || lower.startsWith("what are ") ||
            lower.startsWith("who is ") || lower.startsWith("who was ") ||
            lower.startsWith("define ") || lower.startsWith("describe ") ||
            lower.startsWith("tell me about ") || lower.contains(" explain ") ||
            lower.contains(" teach me ") || lower.contains(" what is ")
        val keywordPull = words.isNotEmpty() && words.size <= 2 && t.length < 40
        val knowledgey = listOf("notes", "document", "pdf", "material").any { it in lower }
        val complexity = when {
            intent == Intent.CALCULATION -> 0.2
            knowledgey || studyQ || keywordPull -> 0.6
            t.length > 400 || words.size > 80 -> 0.9
            t.length > 120 -> 0.6
            else -> 0.3
        }

        return Analysis(
            text = t,
            intent = intent,
            complexity = complexity,
            toolSufficient = intent == Intent.CALCULATION,
            keywords = words.distinct().take(12),
        )
    }
}
