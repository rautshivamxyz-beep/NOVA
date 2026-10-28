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
        if (hits.isEmpty()) {
            val what = if (sources == 1) "1 source" else sources.toString() + " sources"
            return "Nothing in your notes mentions \"" + query + "\". I looked in " + what + "."
        }
        val sb = StringBuilder("```\nYour notes on \"").append(query).append("\":\n")
        var n = 0
        for (h in hits) {
            if (n >= MAX_QUOTES) break
            n++
            sb.append("\n").append(n).append(". ").append(h.first).append(" - ")
            sb.append(excerpt(h.second, query).replace("```", "'''")).append("\n")
        }
        sb.append("```\n")
        sb.append("Quoted straight from your notes - nothing added, no model used.")
        return sb.toString()
    }

    /** The single most relevant line of a chunk, trimmed around the match.
     *  A line that holds more of the query's words wins; ties keep the
     *  earlier line, so the result is stable rather than arbitrary. */
    fun excerpt(chunk: String, query: String, maxChars: Int = 240): String {
        val terms = Tokens.contentWords(query)
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

    /** A flat, bounded slice of one line, centred on the first matched word. */
    private fun window(line: String, terms: List<String>, maxChars: Int): String {
        val flat = line.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxChars) return flat
        val lower = flat.lowercase()
        var at = -1
        for (t in terms) {
            val i = lower.indexOf(t)
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
