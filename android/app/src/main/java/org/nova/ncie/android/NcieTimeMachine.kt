package org.nova.ncie.android

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

    private class Hit(val source: String, val text: String)

    fun search(ctx: Context, query: String, limit: Int = 8): String {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
        if (terms.isEmpty()) return "Give me a word or two to find."

        val hits = ArrayList<Hit>()

        // notifications - NovaListener's local log (millis, pkg, title, text)
        try {
            for (l in NovaListener.readLog(ctx)) {
                val p = l.split('\t', limit = 4)
                if (p.size < 4) continue
                val text = (p[2] + " - " + p[3]).trim()
                if (matches(text, terms)) hits.add(Hit("notif", text.take(140)))
            }
        } catch (e: Exception) { }

        // saved chats
        try {
            for (chat in ChatStore.list(ctx)) {
                for (m in chat.messages) {
                    if (m.text.length < 3) continue
                    if (matches(m.text, terms)) hits.add(Hit("chat", m.text.take(140)))
                }
            }
        } catch (e: Exception) { }

        // indexed notes
        try {
            for (d in NcieKnowledge.docs(ctx)) {
                val txt = NcieKnowledge.docText(ctx, d.first)
                val snip = snippet(txt, terms)
                if (snip != null) hits.add(Hit("notes: " + d.first, snip))
            }
        } catch (e: Exception) { }

        // v9.19.0 "Polish": promises and tracked expiry dates are searchable
        // through the same Time Machine as everything else.
        try {
            val cf = java.io.File(ctx.filesDir, "commitments.txt")
            if (cf.exists()) for (l in cf.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size < 2) continue
                if (matches(p[1], terms)) hits.add(Hit("promise", p[1].take(140)))
            }
        } catch (e: Exception) { }
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
                if (matches(line, terms)) hits.add(Hit("expiry", line.take(140)))
            }
        } catch (e: Exception) { }
        if (hits.isEmpty()) return "Nothing in your stuff matches \"" + query + "\"."

        val sb = StringBuilder("Found " + hits.size + " for \"" + query + "\":\n")
        for (h in hits.take(limit)) {
            sb.append("- [").append(h.source).append("] ").append(h.text).append('\n')
        }
        if (hits.size > limit) sb.append("(").append(hits.size - limit).append(" more)")
        return sb.toString().trim()
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
