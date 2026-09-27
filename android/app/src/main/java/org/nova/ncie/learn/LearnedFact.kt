package org.nova.ncie.learn

import java.util.Locale

/**
 * v0.9.0 — one graded memory entry: a question, its answer, and the
 * metadata the memory system needs to decide the entry's fate.
 *
 * A fact is BORN when the kernel records a verified chat answer
 * (score = the verifier's quality verdict, interactions = 1). Every
 * later re-answer of the same question MERGES into it: the answer
 * updates, interactions bump, the score blends toward the new verdict.
 * Twice-confirmed high-quality facts GRADUATE into the KnowledgeStore
 * (the Distiller's job); stale ones DECAY ([PersistentLearner.demoteStale]).
 *
 * Serialization is one escaped line, the same escaping style the
 * persistent cache has used since v0.7.0 (backslash, tab, CR and LF
 * escaped as \\, \t, \r and \n), tab-separated in this order:
 *
 *     question \t answer \t topicTokens (comma-joined) \t source \t
 *     learnedAtMillis \t lastConfirmedMillis \t score \t interactions \t
 *     provenance
 *
 * [parse] never throws: any malformed line returns null and is simply
 * skipped — a corrupted memory file is a performance loss, not a crash.
 */
data class LearnedFact(
    val question: String,
    val answer: String,
    /** Significant topic tokens of the question (question words never count). */
    val topicTokens: Set<String>,
    /** Where this fact came from. Only [SOURCE_CHAT] exists so far. */
    val source: String,
    /** When the fact was first learned (epoch millis). */
    val learnedAtMillis: Long,
    /** When it was last confirmed by a re-answer (epoch millis). */
    val lastConfirmedMillis: Long,
    /** 0.0 .. 1.0 — the verifier's quality verdict the fact carries. */
    val score: Double,
    /** How many times this question has been answered and confirmed. */
    val interactions: Int,
    /** One human-readable line, e.g. "learned from chat, quality 0.82". */
    val provenance: String,
) {

    /** The one escaped line [parse] reads back. */
    fun serialize(): String = arrayOf(
        escapeLine(question),
        escapeLine(answer),
        escapeLine(topicTokens.joinToString(",")),
        escapeLine(source),
        learnedAtMillis.toString(),
        lastConfirmedMillis.toString(),
        score.toString(),
        interactions.toString(),
        escapeLine(provenance),
    ).joinToString("\t")

    override fun toString(): String =
        "LearnedFact(q=\"$question\", score=${"%.2f".format(Locale.ROOT, score)}, " +
            "interactions=$interactions, $provenance)"

    companion object {
        /** The only source so far — facts are born from verified chat turns. */
        const val SOURCE_CHAT = "CHAT"

        /** Birth: a fresh fact from a quality-gated chat turn. */
        fun fromChat(question: String, answer: String, qualityScore: Double): LearnedFact {
            val now = System.currentTimeMillis()
            val score = qualityScore.coerceIn(0.0, 1.0)
            return LearnedFact(
                question = question.trim(),
                answer = answer.trim(),
                topicTokens = topicTokensOf(question),
                source = SOURCE_CHAT,
                learnedAtMillis = now,
                lastConfirmedMillis = now,
                score = score,
                interactions = 1,
                provenance = "learned from chat, quality ${"%.2f".format(Locale.ROOT, score)}",
            )
        }

        /**
         * Strict parse of a [serialize] line: null on anything malformed
         * — wrong field count, a stray backslash, a non-numeric number
         * field, a score outside 0..1 — never an exception.
         */
        fun parse(line: String): LearnedFact? {
            try {
                val f = line.split('\t')
                if (f.size != 9) return null
                val question = unescapeLine(f[0]) ?: return null
                val answer = unescapeLine(f[1]) ?: return null
                val tokens = unescapeLine(f[2]) ?: return null
                val source = unescapeLine(f[3]) ?: return null
                val learnedAt = f[4].toLongOrNull() ?: return null
                val lastConfirmed = f[5].toLongOrNull() ?: return null
                val score = f[6].toDoubleOrNull() ?: return null
                val interactions = f[7].toIntOrNull() ?: return null
                val provenance = unescapeLine(f[8]) ?: return null
                if (question.isEmpty() || answer.isEmpty()) return null
                if (source.isEmpty() || provenance.isEmpty()) return null
                if (learnedAt < 0 || lastConfirmed < learnedAt) return null
                if (score.isNaN() || score < 0.0 || score > 1.0) return null
                if (interactions < 1) return null
                return LearnedFact(
                    question = question,
                    answer = answer,
                    topicTokens = tokens.split(',').filter { it.isNotEmpty() }.toSet(),
                    source = source,
                    learnedAtMillis = learnedAt,
                    lastConfirmedMillis = lastConfirmed,
                    score = score,
                    interactions = interactions,
                    provenance = provenance,
                )
            } catch (_: Exception) {
                return null
            }
        }

        /** Question words never count for the topic — only the subject does. */
        private val stop = setOf(
            "a", "an", "and", "any", "about", "are", "can", "define", "describe",
            "did", "do", "does", "explain", "for", "gimme", "give", "how", "in",
            "is", "it", "its", "me", "mean", "means", "meaning", "my", "of", "on",
            "or", "please", "teach", "tell", "the", "that", "this", "to", "us",
            "was", "were", "what", "whats", "when", "where", "which", "who",
            "whos", "why", "you", "your",
        )

        /** Significant topic tokens: lowercase, alphanumeric, not a
         *  question word, and longer than one character (single digits
         *  survive — "chapter 3" keeps its 3). Same shape as the tokens
         *  [PersistentLearner] matches on, minus the synonym classes. */
        fun topicTokensOf(question: String): Set<String> =
            question.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 1 || it.all(Char::isDigit) }
                .filter { it !in stop }.toSet()
    }
}

/**
 * The escaping style every one-line format in this package uses
 * (extracted from PersistentLearner, v0.9.0, so the cache and the fact
 * lines share one implementation): backslash, tab, CR and LF become
 * \\, \t, \r and \n.
 */
internal fun escapeLine(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

/** Strict unescape: any stray backslash invalidates the whole string. */
internal fun unescapeLine(s: String): String? {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c != '\\') {
            sb.append(c)
            i++
            continue
        }
        if (i + 1 >= s.length) return null
        when (val n = s[i + 1]) {
            '\\' -> sb.append('\\')
            't' -> sb.append('\t')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            else -> return null
        }
        i += 2
    }
    return sb.toString()
}
