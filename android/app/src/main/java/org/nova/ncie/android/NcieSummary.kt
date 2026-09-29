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
 */
object NcieSummary {
    /** Roll the summary once this many user messages have completed. */
    private const val EVERY = 12
    /** History kept in memory (and on disk) after a roll. */
    private const val KEEP = 8

    private fun summaryFile(c: Context): File = File(c.filesDir, "chat_summary.txt")
    private fun countFile(c: Context): File = File(c.filesDir, "chat_count.txt")

    /** The stored summary, or null when absent or blank. */
    fun read(c: Context): String? = try {
        val s = summaryFile(c).takeIf { it.exists() }?.readText()?.trim()
        if (s.isNullOrEmpty()) null else s
    } catch (e: Exception) { null }

    /** True once EVERY user messages have completed since the last roll. */
    fun due(c: Context): Boolean = count(c) >= EVERY

    /** Count one user message toward the next roll. */
    fun bump(c: Context) { writeCount(c, count(c) + 1) }

    /** Counter back to zero (the next message opens a new window). */
    fun reset(c: Context) { writeCount(c, 0) }

    /** "forget our conversation": the summary and its counter are gone. */
    fun clear(c: Context) {
        try { summaryFile(c).delete() } catch (e: Exception) { }
        try { countFile(c).delete() } catch (e: Exception) { }
    }

    /**
     * Fold the exchanges since the last roll into the summary. Runs on
     * Dispatchers.IO (NcieTutor's pattern) and must complete before the
     * caller's own generation starts - both share the native engine.
     * [messages] is a main-thread snapshot of the chat history.
     * Returns true when a new summary was written.
     */
    fun roll(c: Context, messages: List<Msg>): Boolean {
        val old = read(c) ?: "(none)"
        var recent = messages.joinToString("\n") { m ->
            (if (m.role == Role.USER) "You: " else "NOVA: ") + m.text.take(400)
        }
        if (recent.length > 6000) recent = recent.takeLast(6000)
        val prompt = "Below is an old summary of an earlier conversation, " +
            "followed by recent messages. Write a compact updated summary " +
            "(max 200 words) keeping: user facts, decisions made, open " +
            "threads, important names/numbers. OLD SUMMARY: $old. " +
            "RECENT: $recent"
        val reply = try { NovaEngineAdapter.generate(prompt, 300) } catch (e: Exception) { "" }
        val clean = reply.trim()
        if (clean.isEmpty() || clean.startsWith("[engine")) return false
        return try { summaryFile(c).writeText(clean); true } catch (e: Exception) { false }
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

    private fun count(c: Context): Int = try {
        countFile(c).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
    } catch (e: Exception) { 0 }

    private fun writeCount(c: Context, n: Int) {
        try { countFile(c).writeText(n.toString()) } catch (e: Exception) { }
    }
}
