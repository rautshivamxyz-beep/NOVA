package org.nova.ncie.android

import org.nova.NovaLog
import org.nova.ncie.Tokens

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.nova.Chat
import org.nova.ChatStore
import org.nova.MainActivity
import org.nova.Msg
import org.nova.Role
import java.io.File

/**
 * v9.2.0 "Rolling Chat Summary": the beginning of the conversation,
 * folded into filesDir/chat_summary.txt every 12 user messages so a
 * long chat never loses its start. The summary is written by the same
 * local engine that powers tutor mode (NovaEngineAdapter.generate on
 * Dispatchers.IO) and rides along as a preamble in every normal
 * turn. Nothing leaves the phone, no new dependencies, minSdk 23.
 *
 * The counter lives in filesDir/chat_count.txt rather than being
 * derived from the history length: the history is trimmed to the last
 * 8 messages after every roll and persists across restarts, so its
 * length says nothing about the distance to the next roll.
 *
 * v9.3.0 "Audit Fixes I" (audit: one global summary leaks between
 * chats): the summary file and its counter are now keyed by the chat's
 * id - chat_summary_<id>.txt / chat_count_<id>.txt - so every chat
 * keeps (and forgets) its own start. "forget our conversation" clears
 * only the current chat's files. v9.2.0's global chat_summary.txt /
 * chat_count.txt are deleted once at startup (migrateLegacy, called
 * from MainActivity.onCreate) - they are never read again either way.
 */
object NcieSummary {
    /** Roll the summary once this many user messages have completed. */
    private const val EVERY = 12
    /** History kept in memory (and on disk) after a roll. */
    private const val KEEP = 8


    private const val MAX_RETRIEVED_ITEMS = 5
    private const val MAX_RETRIEVED_CHARS = 1100

    private val MEMORY_QUERY_STOP = setOf(
        "again", "also", "answer", "continue", "detail", "detailed", "elaborate",
        "explain", "further", "give", "more", "please", "same", "show", "tell",
        "that", "these", "this", "those", "what", "which", "with", "about",
        "user", "project", "preference", "decision", "correction", "open", "topic", "person", "task"
    )
    private val MEMORY_FOLLOW_UP = Regex(
        "(?i)\\b(?:it|this|that|these|those|him|her|them|they|same|again|more|" +
            "continue|elaborate|expand|further)\\b|\\bwhat about\\b"
    )

    /** Resolve a short contextual follow-up against the immediately previous
     * user topic before searching the compacted memory. Pure local string work. */
    internal fun retrievalQuery(query: String, previousUser: String?): String {
        val prior = previousUser?.trim().orEmpty()
        return if (prior.isNotEmpty() && MEMORY_FOLLOW_UP.containsMatchIn(query))
            "$prior $query"
        else query
    }

    /**
     * Retrieve only summary items that overlap the current topic. This runs
     * only when chat context is being rebuilt, not on every normal turn.
     * Items are scored by query coverage and specificity; selected items are
     * restored to conversation order and bounded before entering the prompt.
     */
    internal fun retrieveRelevant(
        summary: String?,
        query: String,
        previousUser: String? = null,
        maxItems: Int = MAX_RETRIEVED_ITEMS,
        maxChars: Int = MAX_RETRIEVED_CHARS,
    ): String {
        if (maxItems <= 0 || maxChars <= 0) return ""
        val clean = sanitize(summary)
        if (clean.isBlank()) return ""
        val resolvedQuery = retrievalQuery(query, previousUser)
        val queryTerms = memoryTerms(resolvedQuery)
        if (queryTerms.isEmpty()) return ""

        val candidates = clean.lines().flatMap { raw ->
            val line = raw.trim()
                .removePrefix("- ").removePrefix("* ").removePrefix("• ")
            if (line.isBlank()) emptyList()
            else line.split(Regex("(?<=[.!?।])\\\\s+"))
                .map { it.trim() }.filter { it.isNotBlank() }
        }.distinctBy { it.lowercase() }

        if (candidates.isEmpty()) return ""
        val scored = candidates.mapIndexedNotNull { index, item ->
            val terms = memoryTerms(item)
            if (terms.isEmpty()) return@mapIndexedNotNull null
            val overlap = queryTerms.count { it in terms }
            val minimum = if (queryTerms.size >= 3) 2 else 1
            if (overlap < minimum) return@mapIndexedNotNull null
            val coverage = overlap.toDouble() / queryTerms.size.coerceAtLeast(1)
            val specificity = overlap.toDouble() / terms.size.coerceAtLeast(1)
            val recency = (index + 1).toDouble() / candidates.size
            val score = coverage * 0.65 + specificity * 0.25 + recency * 0.10
            Triple(index, score, item)
        }.sortedWith(compareByDescending<Triple<Int, Double, String>> { it.second }
            .thenByDescending { it.first })
            .take(maxItems)
            .sortedBy { it.first }

        val result = StringBuilder()
        for ((_, _, item) in scored) {
            val extra = (if (result.isEmpty()) 0 else 1) + item.length
            if (result.length + extra > maxChars) continue
            if (result.isNotEmpty()) result.append('\\n')
            result.append(item)
        }
        return result.toString()
    }

    private fun memoryTerms(text: String): Set<String> =
        Tokens.split(text)
            .filter { it.length > 1 || it.all(Char::isDigit) }
            .filter { it !in Tokens.STOP && it !in MEMORY_QUERY_STOP }
            .toSet()

    /**
     * The native model already retains the live conversation in its KV cache.
     * Re-send the rolling summary only when the app must rebuild that context
     * (chat restore/context trim), not on every turn.
     */
    internal fun shouldInject(
        summary: String?,
        needsContextCarry: Boolean,
        hasSkill: Boolean,
        freshForCode: Boolean
    ): Boolean = !summary.isNullOrBlank() && needsContextCarry && !hasSkill && !freshForCode

    private fun summaryFile(c: Context, id: String): File =
        File(c.filesDir, "chat_summary_$id.txt")
    private fun countFile(c: Context, id: String): File =
        File(c.filesDir, "chat_count_$id.txt")

    /** v9.3.0: v9.2.0's global summary files - delete them if an old
     * install still has them (each run is a no-op once they are gone). */
    fun migrateLegacy(c: Context) {
        try { File(c.filesDir, "chat_summary.txt").delete() } catch (e: Exception) { NovaLog.quiet("NcieSummary", e) }
        try { File(c.filesDir, "chat_count.txt").delete() } catch (e: Exception) { NovaLog.quiet("NcieSummary", e) }
    }

    /** The stored summary for [id]'s chat, or null when absent or blank. */
    fun read(c: Context, id: String): String? = try {
        val s = summaryFile(c, id).takeIf { it.exists() }?.readText()?.trim()
        if (s.isNullOrEmpty()) null else s
    } catch (e: Exception) { null }

    /** v9.12.1 "Context Diet": the summary sanitizer. The summarizer
     *  sometimes preserves the meta markers of the prompts it was fed
     *  ("New message:", "Answer:", "(Reply", "— end", "Nova:"), and the
     *  model then mimics them in its replies ("New message: ..." instead
     *  of an answer). Lines carrying any of those markers are dropped
     *  (case-insensitive, trimmed-line match) before the summary is
     *  injected into the preamble. Returns "" when nothing survives. */
    fun sanitize(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder()
        for (l in s.lines()) {
            val low = l.trim().lowercase()
            if (low.contains("new message") || low.contains("answer:") ||
                low.contains("(reply") || low.contains("— end") ||
                low.contains("nova:")) continue
            sb.append(l).append('\n')
        }
        return sb.toString().trim()
    }

    /** True once EVERY user messages have completed since the last roll. */
    fun due(c: Context, id: String): Boolean = count(c, id) >= EVERY

    /** Count one user message toward the next roll. */
    fun bump(c: Context, id: String) { writeCount(c, id, count(c, id) + 1) }

    /** Counter back to zero (the next message opens a new window). */
    fun reset(c: Context, id: String) { writeCount(c, id, 0) }

    /** "forget our conversation": THIS chat's summary and its counter
     *  are gone - every other chat keeps its own. */
    fun clear(c: Context, id: String) {
        try { summaryFile(c, id).delete() } catch (e: Exception) { NovaLog.quiet("NcieSummary", e) }
        try { countFile(c, id).delete() } catch (e: Exception) { NovaLog.quiet("NcieSummary", e) }
    }

    /**
     * Fold the exchanges since the last roll into the summary of the
     * chat [id]. Runs on
     * Dispatchers.IO (NcieTutor's pattern) and must complete before the
     * caller's own generation starts - both share the native engine.
     * [messages] is a main-thread snapshot of the chat history.
     * Returns true when a new summary was written.
     */
    fun roll(c: Context, id: String, messages: List<Msg>): Boolean {
        val old = read(c, id) ?: "(none)"
        var recent = messages.joinToString("\n") { m ->
            (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(400)
        }
        if (recent.length > 6000) recent = recent.takeLast(6000)
        val prompt = "You are updating NOVA's searchable memory for this chat. " +
            "Merge the old summary with the recent messages into at most 200 words. " +
            "Output one standalone memory item per line; each line must make sense by itself " +
            "and preserve the exact people, project names, numbers, dates and relationships. " +
            "Start each item with one label: [USER], [PREFERENCE], [PROJECT], [DECISION], " +
            "[CORRECTION], [OPEN], or [TOPIC]. Preserve stable user-stated facts, explicit " +
            "preferences, project state and decisions, important entities, corrections, and " +
            "unfinished tasks. Keep speaker attribution explicit. An assistant claim is not " +
            "a user fact. A correction overrides the claim it corrects. Mark unresolved or " +
            "unsupported claims as disputed/unverified. Remove duplicates and superseded " +
            "details; do not invent missing information. OLD MEMORY: $old. RECENT MESSAGES: $recent"
        // v9.13.0 "Audit Fixes" (HIGH 6): the roll is a real generation
        // (it shares the native engine with the chat turn) - flag it so
        // the embedder's pause logic is accurate while it runs.
        NcieChat.generating = true
        val reply = try { NovaEngineAdapter.generate(prompt, 300)
            } catch (e: Exception) { "" } finally { NcieChat.generating = false }
        val clean = reply.trim()
        if (clean.isEmpty() || clean.startsWith("[engine")) return false
        return NcieFiles.writeOrNote(summaryFile(c, id), clean, "NcieSummary")
    }

    /**
     * After a successful roll: keep only the last KEEP messages, both in
     * memory and in the persisted chat file. Call on the main thread;
     * the save itself runs on Dispatchers.IO like every ChatStore save.
     */
    fun trimHistory(act: MainActivity, chat: Chat) {
        // the user switched chats while the summary was generating -
        // never trim a chat that is no longer on screen
        if (act.currentChat !== chat) return
        val keep = chat.messages.takeLast(KEEP)
        chat.messages.clear()
        chat.messages.addAll(keep)
        act.scope.launch(Dispatchers.IO) {
            ChatStore.save(act, chat)
        }
    }

    private fun count(c: Context, id: String): Int = try {
        countFile(c, id).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
    } catch (e: Exception) { 0 }

    private fun writeCount(c: Context, id: String, n: Int) {
        NcieFiles.writeOrNote(countFile(c, id), n.toString(), "NcieSummary")
    }
}
