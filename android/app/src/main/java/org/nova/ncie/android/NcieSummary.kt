package org.nova.ncie.android

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

    private fun summaryFile(c: Context, id: String): File =
        File(c.filesDir, "chat_summary_$id.txt")
    private fun countFile(c: Context, id: String): File =
        File(c.filesDir, "chat_count_$id.txt")

    /** v9.3.0: v9.2.0's global summary files - delete them if an old
     * install still has them (each run is a no-op once they are gone). */
    fun migrateLegacy(c: Context) {
        try { File(c.filesDir, "chat_summary.txt").delete() } catch (e: Exception) { }
        try { File(c.filesDir, "chat_count.txt").delete() } catch (e: Exception) { }
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
        try { summaryFile(c, id).delete() } catch (e: Exception) { }
        try { countFile(c, id).delete() } catch (e: Exception) { }
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
        val prompt = "Below is an old summary of an earlier conversation, " +
            "followed by recent messages. Write a compact updated summary " +
            "(max 200 words) keeping: user facts, decisions made, open " +
            "threads, important names/numbers. OLD SUMMARY: $old. " +
            "RECENT: $recent"
        // v9.13.0 "Audit Fixes" (HIGH 6): the roll is a real generation
        // (it shares the native engine with the chat turn) - flag it so
        // the embedder's pause logic is accurate while it runs.
        NcieChat.generating = true
        val reply = try { NovaEngineAdapter.generate(prompt, 300)
            } catch (e: Exception) { "" } finally { NcieChat.generating = false }
        val clean = reply.trim()
        if (clean.isEmpty() || clean.startsWith("[engine")) return false
        return try { summaryFile(c, id).writeText(clean); true } catch (e: Exception) { false }
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
            try { ChatStore.save(act, chat) } catch (e: Exception) { }
        }
    }

    private fun count(c: Context, id: String): Int = try {
        countFile(c, id).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
    } catch (e: Exception) { 0 }

    private fun writeCount(c: Context, id: String, n: Int) {
        try { countFile(c, id).writeText(n.toString()) } catch (e: Exception) { }
    }
}
