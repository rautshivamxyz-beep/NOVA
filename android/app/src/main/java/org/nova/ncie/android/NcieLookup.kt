package org.nova.ncie.android

import org.nova.ncie.Tokens

/**
 * F2 "Notes lookup" (v9.33.80): a deterministic lookup that answers with the
 * user's OWN lines, verbatim, and never calls the model.
 *
 * "from my notes: <question>" (v9.5.0) RETRIEVES and then asks the model to
 * word an answer. That is the right shape for a question. It is the wrong
 * shape for "where did I write about X": the user wants the line, and the
 * model adds a paraphrase, a wait for the first token, and a chance to
 * misquote. This is the lookup: retrieve the matching chunks, pick the single
 * most relevant LINE out of each, and print it with its source. No generation,
 * so nothing can be invented, and it answers with no model loaded at all.
 *
 * Pure string work: no Android, no engine, no network - unit-tested.
 */
object NcieLookup {

    /** "find in my notes: X" / "search my notes for X" / "look through my
     *  documents for X". */
    private val FIND = Regex(
        "(?i)^\\s*(?:find|search|look)\\s+(?:in|through|inside)?\\s*" +
            "(?:my|all\\s+my)\\s+(?:notes?|documents?)\\s*(?:for\\s+)?:?\\s*(.+)$")

    /** "which note mentions X" / "what document says X". */
    private val WHERE = Regex(
        "(?i)^\\s*(?:which|what)\\s+(?:note|document)s?\\s+" +
            "(?:mentions?|says?|has|talks?\\s+about)\\s+(.+)$")

    /** How many sources the reply quotes. Retrieval returns at most four. */
    private const val MAX_QUOTES = 4

    /** The query a lookup command carries, or null when this is not one.
     *  A pasted page is not a command, so anything long is refused - the
     *  same guard the other deterministic commands use. */
    fun query(text: String): String? {
        val t = text.trim()
        if (t.length > 160) return null
        for (re in listOf(FIND, WHERE)) {
            val m = re.find(t) ?: continue
            val q = m.groupValues[1].trim().trim('?', '.', '!', ' ', ':', ',')
            if (q.length >= 2) return q
        }
        return null
    }

    /**
     * The whole reply. [hits] are (source, chunk) pairs from
     * NcieGround.retrieve; [sources] is how many sources the user has, so
     * "nothing found" can say what it looked in.
     */
    fun body(query: String, hits: List<Pair<String, String>>, sources: Int): String {
        if (sources == 0) {
            return "You have no notes or documents yet - paste a page into " +
                "Knowledge, or scan one, and ask me again."
        }
        val terms = termsOf(query)
        val quotes = ArrayList<Pair<String, String>>()
        for (h in hits) {
            if (quotes.size >= MAX_QUOTES) break
            val line = bestLine(h.second, query)
            if (!holdsTerm(line, terms)) continue
            quotes.add(h.first to window(line, terms, 240))
        }
        if (quotes.isEmpty()) return nothingFound(query, sources)
        val sb = StringBuilder("```\nYour notes on \"").append(query).append("\":\n")
        for (i in quotes.indices) {
            sb.append("\n").append(i + 1).append(". ").append(quotes[i].first).append(" - ")
            sb.append(quotes[i].second.replace("```", "'''")).append("\n")
        }
        sb.append("```\n")
        sb.append("Quoted straight from your notes - nothing added, no model used.")
        return sb.toString()
    }

    /** v9.33.84: the honest reply when not one line shares a word. */
    private fun nothingFound(query: String, sources: Int): String {
        val what = if (sources == 1) "1 source" else sources.toString() + " sources"
        return "Nothing in your notes mentions \"" + query + "\". I looked in " + what + "."
    }

    /**
     * v9.33.84: true when [line] holds at least one of the query's content
     * words. Retrieval can hand back a chunk that matched only semantically,
     * with no word in common with the question; quoting its first line as
     * "your notes on X" would be a paraphrase dressed up as a quote.
     */
    private fun holdsTerm(line: String, terms: List<String>): Boolean {
        if (terms.isEmpty()) return false
        val norm = Tokens.norm(line)
        return terms.any { norm.contains(" " + it + " ") }
    }

    /** The single most relevant line of a chunk, trimmed around the match.
     *  A line that holds more of the query's words wins; ties keep the
     *  earlier line, so the result is stable rather than arbitrary. */
    fun excerpt(chunk: String, query: String, maxChars: Int = 240): String {
        val terms = termsOf(query)
        val lines = chunk.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var best = ""
        var bestScore = -1
        for (l in lines) {
            val norm = Tokens.norm(l)
            var score = 0
            for (t in terms) if (norm.contains(" " + t + " ")) score++
            if (score > bestScore) {
                bestScore = score
                best = l
            }
        }
        if (best.isEmpty()) best = chunk.trim()
        return window(best, terms, maxChars)
    }

    /**
     * v9.33.84: the whole line [excerpt] would quote, unclipped. The caller
     * needs it to decide whether that line shares a word with the question
     * at all before spending one of the [MAX_QUOTES] slots on it.
     */
    internal fun bestLine(chunk: String, query: String): String =
        excerpt(chunk, query, Int.MAX_VALUE)

    /**
     * v9.33.83: the query's content words, with the shared function-word list
     * removed. "what is the boiling point of water" used to hand [excerpt]
     * the five words what/the/boiling/point/water, so a distractor line that
     * merely said "what" and "the" outscored the line that said "boiling"
     * and the user was quoted the wrong line. A query made only of function
     * words keeps its words, so the excerpt still has something to look for.
     */
    internal fun termsOf(query: String): List<String> {
        val all = Tokens.contentWords(query)
        val content = all.filter { it !in Tokens.STOP }
        return if (content.isEmpty()) all else content
    }

    /**
     * v9.33.83: the first WHOLE-WORD occurrence of [term] in [lower], or -1.
     * A plain indexOf found "the" inside "There" and "art" inside
     * "startup", so a long line was sliced around a substring that had
     * nothing to do with the question and the quoted window often did not
     * contain the word the user searched for. The boundaries are checked by
     * hand because Java's word-boundary anchor is ASCII-only and would never
     * match a Devanagari word at all.
     */
    private fun firstWord(lower: String, term: String): Int {
        if (term.isEmpty()) return -1
        var i = lower.indexOf(term)
        while (i >= 0) {
            val end = i + term.length
            val before = i <= 0 || !isWordChar(lower[i - 1])
            val after = end >= lower.length || !isWordChar(lower[end])
            if (before && after) return i
            i = lower.indexOf(term, i + 1)
        }
        return -1
    }

    /** Letters, digits and combining marks of any script are one word. */
    private fun isWordChar(c: Char): Boolean {
        if (Character.isLetterOrDigit(c)) return true
        val t = Character.getType(c)
        return t == Character.NON_SPACING_MARK.toInt() ||
            t == Character.COMBINING_SPACING_MARK.toInt()
    }

    /** A flat, bounded slice of one line, centred on the first matched word. */
    private fun window(line: String, terms: List<String>, maxChars: Int): String {
        val flat = line.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        // v9.33.83: keep the indices usable - a few code points lowercase to
        // two characters, which would shift every index in this function.
        val lower = flat.lowercase().let { if (it.length == flat.length) it else flat }
        var at = -1
        for (t in terms) {
            val i = firstWord(lower, t)
            if (i >= 0 && (at < 0 || i < at)) at = i
        }
        if (at < 0) return flat.take(maxChars).trimEnd() + "..."
        val start = maxOf(0, at - maxChars / 3)
        val end = minOf(flat.length, start + maxChars)
        val cut = flat.substring(start, end).trim()
        val head = if (start > 0) "..." else ""
        val tail = if (end < flat.length) "..." else ""
        return head + cut + tail
    }
}
