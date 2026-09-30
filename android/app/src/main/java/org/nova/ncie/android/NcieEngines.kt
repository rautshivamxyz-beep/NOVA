package org.nova.ncie.android

import android.content.Context
import java.io.File

/**
 * v9.6.0 "Engine Pack": the two file-backed engines behind the chat turn.
 *
 *  - Experience Engine (filesDir/experience.txt): the questions whose
 *    answers the user REGENERATED. Tapping the refresh icon under a reply
 *    logs the original question here (one `millis\tquestion` line, capped
 *    at 200, oldest dropped). When the same question comes back as a
 *    normal chat turn, NcieChat prefixes the prompt with a
 *    try-a-better-answer instruction and skips the answer cache for it,
 *    so the engine informs the next answer instead of the cache replaying
 *    the old one. "what have you learned" reads this file deterministically.
 *  - Predictive Cache (filesDir/answer_cache.txt): one `question␟answer`
 *    line per entry (the same unit separator the other stores use). ONLY
 *    normal LLM chat answers are stored - never commands, deterministic
 *    intercepts, skills, grounded answers or tutor turns. An exact repeat
 *    of the question (case-insensitive, trimmed) is answered instantly,
 *    verbatim, before any generation; regenerating bypasses the read and
 *    refreshes the entry. Capped at 200 entries (FIFO on append); answers
 *    over 4000 chars are never stored.
 *
 * Fully local, deterministic, pure string/file work - no new dependency,
 * no model changes, nothing leaves the phone.
 */
object NcieEngines {

    private const val CAP = 200
    private const val SEP = '\u241F'   // unit separator, like the other stores

    /** v9.13.0 "Audit Fixes" (re-derived a): the engine stores' shared
     *  lock - experience.txt and answer_cache.txt are read on the main
     *  thread (isExperienced / cachedAnswer in the chat turn) while
     *  logExperience / cacheAnswer read-modify-write them on IO at turn
     *  completion; overlapping calls could drop an entry or read a
     *  half-written file. Tiny, private, no dependency. */
    private val storeLock = Any()

    private fun experienceFile(ctx: Context) = File(ctx.filesDir, "experience.txt")
    private fun answerFile(ctx: Context) = File(ctx.filesDir, "answer_cache.txt")

    private fun readLines(f: File): List<String> = try {
        if (f.exists()) f.readLines() else emptyList()
    } catch (e: Exception) { emptyList() }

    // ------------------------------------------------ Experience Engine

    /** The user regenerated the answer to [question] - log it. One line
     *  `millis\tquestion` (newlines flattened, the log stays one line per
     *  entry), capped at 200 entries, oldest dropped first. Call from a
     *  background thread. */
    fun logExperience(ctx: Context, question: String) {
        try {
            val q = question.trim().replace("\n", " ")
            if (q.isEmpty()) return
            synchronized(storeLock) {
                val lines = readLines(experienceFile(ctx)).toMutableList()
                lines.add(System.currentTimeMillis().toString() + "\t" + q)
                while (lines.size > CAP) lines.removeAt(0)
                experienceFile(ctx).writeText(lines.joinToString("\n") + "\n")
            }
        } catch (e: Exception) { }
    }

    /** True when [question] (case-insensitive, trimmed) is one the user
     *  regenerated an answer to. */
    fun isExperienced(ctx: Context, question: String): Boolean {
        val q = question.trim().lowercase()
        if (q.isEmpty()) return false
        synchronized(storeLock) {
            for (l in readLines(experienceFile(ctx)))
                if (l.substringAfter('\t', "").trim().lowercase() == q) return true
        }
        return false
    }

    /** The last [n] logged questions, oldest first - the body of the
     *  "what have you learned" answer. */
    fun lastLearned(ctx: Context, n: Int = 20): List<String> =
        readLines(experienceFile(ctx)).map { it.substringAfter('\t', "").trim() }
            .filter { it.isNotEmpty() }.takeLast(n)

    // ------------------------------------------------ Predictive Cache

    /** The cached answer for an exact (case-insensitive, trimmed) repeat
     *  of [question], verbatim, or null when nothing is stored. */
    fun cachedAnswer(ctx: Context, question: String): String? {
        val q = question.trim().lowercase()
        if (q.isEmpty()) return null
        synchronized(storeLock) {
            for (l in readLines(answerFile(ctx))) {
                val i = l.indexOf(SEP)
                if (i < 1) continue
                if (l.substring(0, i).trim().lowercase() == q)
                    return unescape(l.substring(i + 1))
            }
        }
        return null
    }

    /** Store/refresh [answer] under its exact [question] (an existing
     *  entry for the question is replaced, so a regenerate refreshes it).
     *  Capped at 200 entries (FIFO on append); answers over 4000 chars
     *  are never stored. Call from a background thread. */
    fun cacheAnswer(ctx: Context, question: String, answer: String) {
        try {
            val q = question.trim().replace("\n", " ")
            val a = answer.trim()
            if (q.isEmpty() || a.isEmpty() || a.length > 4000) return
            synchronized(storeLock) {
                val lines = readLines(answerFile(ctx)).toMutableList()
                lines.removeAll { it.substringBefore(SEP).trim().lowercase() == q.lowercase() }
                lines.add(escape(q) + SEP + escape(a))
                while (lines.size > CAP) lines.removeAt(0)
                answerFile(ctx).writeText(lines.joinToString("\n") + "\n")
            }
        } catch (e: Exception) { }
    }

    /** One line per entry: backslashes double, newlines become \n - the
     *  stored answer reads back verbatim through [unescape]. */
    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
