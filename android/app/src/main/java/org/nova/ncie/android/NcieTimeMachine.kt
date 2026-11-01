package org.nova.ncie.android

import org.nova.ncie.Tokens

import org.nova.NovaLog

import android.content.Context
import org.nova.ChatStore
import org.nova.NovaListener

/**
 * v9.16.13 "Time Machine": one local search across everything NOVA can
 * already see - the notification log, your saved chats and your indexed
 * notes. "find <query>" returns the matching lines with their source, so
 * you can ask "find the tracking number" or "find what mom said about the
 * trip". Keyword search, fully offline, nothing leaves the phone.
 */
object NcieTimeMachine {

    fun search(ctx: Context, query: String, limit: Int = 8): String {
        val terms = Tokens.split(query).filter { it.length >= 2 }
        if (terms.isEmpty()) return "Give me a word or two to find."

        val hits = ArrayList<Pair<String, String>>()

        // notifications - NovaListener's local log (millis, pkg, title, text)
        try {
            for (l in NovaListener.readLog(ctx)) {
                val p = l.split('\t', limit = 4)
                if (p.size < 4) continue
                val text = (p[2] + " - " + p[3]).trim()
                if (matches(text, terms)) hits.add("notif" to text.take(140))
            }
        } catch (e: Exception) { NovaLog.quiet("NcieTimeMachine", e) }

        // saved chats
        try {
            for (chat in ChatStore.list(ctx)) {
                for (m in chat.messages) {
                    if (m.text.length < 3) continue
                    if (matches(m.text, terms)) hits.add("chat" to m.text.take(140))
                }
            }
        } catch (e: Exception) { NovaLog.quiet("NcieTimeMachine", e) }

        // indexed notes
        try {
            for (d in NcieKnowledge.docs(ctx)) {
                val txt = NcieKnowledge.docText(ctx, d.first)
                val snip = snippet(txt, terms)
                if (snip != null) hits.add("notes: " + d.first to snip)
            }
        } catch (e: Exception) { NovaLog.quiet("NcieTimeMachine", e) }

        // v9.19.0 "Polish": promises and tracked expiry dates are searchable
        // through the same Time Machine as everything else.
        try {
            val cf = java.io.File(ctx.filesDir, "commitments.txt")
            if (cf.exists()) for (l in cf.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size < 2) continue
                if (matches(p[1], terms)) hits.add("promise" to p[1].take(140))
            }
        } catch (e: Exception) { NovaLog.quiet("NcieTimeMachine", e) }
        try {
            val ef = java.io.File(ctx.filesDir, "expiry.txt")
            if (ef.exists()) for (l in ef.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size < 2) continue
                val ms = p[1].toLongOrNull()
                val d = if (ms != null)
                    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(ms)) else ""
                val line = p[0] + " - expires " + d
                if (matches(line, terms)) hits.add("expiry" to line.take(140))
            }
        } catch (e: Exception) { NovaLog.quiet("NcieTimeMachine", e) }
        if (hits.isEmpty()) return "Nothing in your stuff matches \"" + query + "\"."

        val sb = StringBuilder("Found " + hits.size + " for \"" + query + "\":\n")
        for ((source, text) in rank(hits, terms, limit)) {
            sb.append("- [").append(source).append("] ").append(text).append('\n')
        }
        if (hits.size > limit) sb.append("(").append(hits.size - limit).append(" more)")
        return sb.toString().trim()
    }

    /**
     * v9.33.94 "The best hit first": the hits that hold most of the query,
     * then the rest, capped at [limit].
     *
     * The sources were collected in a fixed order - notifications, chats,
     * notes, promises, expiry - and then cut to [limit]. So "find the
     * tracking number" on a phone that had logged eight notifications
     * mentioning "number" returned eight notification lines and never showed
     * the chat message that held BOTH words, which is the one the user meant.
     * The order of the collection is an implementation detail; the order of
     * the reply should be relevance.
     *
     * The score is how many DISTINCT query words the hit contains, so a hit
     * matching two words outranks one matching one, whatever its source.
     * Ties keep the collection order (the sort is stable), so a reply is
     * repeatable rather than arbitrary.
     *
     * Pure list work, so a JVM unit test can pin it.
     */
    internal fun rank(hits: List<Pair<String, String>>, terms: List<String>,
                      limit: Int): List<Pair<String, String>> {
        if (limit <= 0) return emptyList()
        return hits.sortedByDescending { score(it.second, terms) }.take(limit)
    }

    /** How many distinct [terms] [text] contains. Pure. */
    internal fun score(text: String, terms: List<String>): Int {
        val low = text.lowercase()
        var n = 0
        for (t in terms.toSet()) if (t.isNotEmpty() && low.contains(t)) n++
        return n
    }

    private fun matches(text: String, terms: List<String>): Boolean {
        val low = text.lowercase()
        return terms.any { low.contains(it) }
    }

    /** The first line of [text] that contains a term, truncated. */
    private fun snippet(text: String, terms: List<String>): String? {
        for (line in text.lines()) {
            if (line.length < 3) continue
            if (matches(line, terms)) return line.trim().take(140)
        }
        return null
    }
}
