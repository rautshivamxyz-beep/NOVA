package org.nova.ncie.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nova.ChatStore
import org.nova.NovaEngine
import org.nova.Role
import org.nova.Settings

/**
 * v9.22.0 "Busy replies": makes NOVA's away-messages sound like the user.
 *
 * The style is learned from the user's OWN past messages (the same trick the
 * "write in my style" tool uses), and the model rewrites the busy lines in
 * that style. Because NOVA has no foreground service, the model only exists
 * while the app is open - so the lines are generated HERE, on demand, and
 * the notification auto-responder then rotates through the saved lines.
 * Everything is on-device.
 */
object NcieStyle {

    /** The user's own past messages, as style examples (or "" when there is
     *  not enough to learn from yet). */
    fun samples(ctx: Context, budget: Int = 1400): String {
        val mine = StringBuilder()
        try {
            for (chat in ChatStore.list(ctx)) {
                for (m in chat.messages) {
                    if (m.role == Role.USER && m.text.length in 10..220) {
                        mine.append(m.text).append('\n')
                        if (mine.length > budget) break
                    }
                }
                if (mine.length > budget) break
            }
        } catch (e: Exception) { }
        return if (mine.length < 300) "" else mine.toString()
    }

    /** A prompt that makes the model write [request] in the user's style, or
     *  null when there is not enough of the user's writing to learn from. */
    fun prompt(ctx: Context, request: String): String? {
        val mine = samples(ctx, 900)
        if (mine.isEmpty()) return null
        // v9.26.0 "Busy-reply fix": the TASK now comes first and the examples
        // are marked reference-only. The old order put ~1400 chars of the
        // user's own messages - mostly questions they had asked NOVA - BEFORE
        // the instruction, so a small model simply continued that list and
        // answered with questions instead of writing a busy reply.
        return "TASK: " + request + "\n\n" +
            "Write it in the user's own voice - match their tone, language mix " +
            "and habits, first person.\n" +
            "The lines below are ONLY a style reference (how the user writes). " +
            "Do NOT reply to them, do NOT continue them, and do NOT ask any " +
            "question unless the TASK above asks for one.\n" +
            "----- examples of the user's writing -----\n" + mine +
            "----- end of examples -----\n" +
            "Now do the TASK above, in that style. Output only the text."
    }

    /** True when a model is loaded, so generation can actually run. */
    fun ready(): Boolean = try { NovaEngine.isModelLoaded } catch (e: Exception) { false }

    /**
     * Ask the model for [n] short "I'm busy" lines in the user's style. Runs
     * off the main thread. Returns an empty list when no model is loaded or
     * there is not enough of the user's writing to learn from.
     */
    suspend fun generateBusyLines(ctx: Context, n: Int = 4): List<String> {
        if (!ready()) return emptyList()
        val req = "Write " + n + " short replies I can send when I am busy and " +
            "cannot talk right now. Each 3 to 8 words, casual, asking them to " +
            "say it fast (for example \"tell me fast, I am doing something\"). " +
            "Each must be a STATEMENT, never a question. " +
            "One per line, no numbering, no quotes."
        val p = prompt(ctx, req) ?: return emptyList()
        val out = try {
            withContext(Dispatchers.IO) { NovaEngineAdapter.generate(p, 200) }
        } catch (e: Exception) { return emptyList() }
        // v9.26.0: a busy line is never a question - drop any the model still
        // asks, so "What's up with this news?" can never reach a sender.
        return parseLines(out).filterNot { it.trimEnd().endsWith("?") }.take(n)
    }

    /** Trim a raw model reply into clean, short lines. */
    fun parseLines(raw: String): List<String> {
        val out = ArrayList<String>()
        for (line in raw.split('\n')) {
            var s = line.trim()
            // drop list markers ("1.", "-", "*", bullet) the model likes to add
            while (s.isNotEmpty() && (s[0].isDigit() || s[0] == '-' || s[0] == '*' ||
                        s[0] == '.' || s[0] == ')' || s[0] == '\u2022')) {
                s = s.substring(1).trim()
            }
            s = s.trim('"', '\'', ' ')
            if (s.length in 3..80) out.add(s)
        }
        return out.distinct()
    }

    /**
     * v9.23.0 "The model for the messages": write a reply to ONE incoming
     * message, in the user's style. Returns null when no model is loaded or
     * the generation fails, so the caller falls back to a canned line.
     */
    suspend fun busyReply(ctx: Context, incoming: String): String? {
        if (!ready()) return null
        val theirs = incoming.trim().take(300)
        if (theirs.isEmpty()) return null
        val req = "You are replying to a chat message while you are busy and " +
            "cannot talk right now. Their message: \"" + theirs + "\". " +
            "Write ONE short reply, 3 to 10 words, that fits what they said " +
            "and asks them to say it fast if it matters. First person, casual. " +
            "It must be a STATEMENT, not a question. " +
            "Reply with only the text, no quotes."
        val p = prompt(ctx, req) ?: return null
        val out = try {
            withContext(Dispatchers.IO) { NovaEngineAdapter.generate(p, 120) }
        } catch (e: Exception) { return null }
        return parseLines(out).firstOrNull()
    }

    /**
     * v9.24.0 "Wake for WhatsApp": load the model on demand - the last one
     * the user ran - and then write the reply, so a WhatsApp message can be
     * answered by the model even when NOVA is cold. Returns null when there
     * is nothing to load or the load fails, so the caller falls back to a
     * canned line. Loading takes seconds and real RAM; the caller runs this
     * off the notification thread with a long timeout.
     */
    suspend fun wakeAndReply(ctx: Context, incoming: String): String? {
        if (ready()) return busyReply(ctx, incoming)
        val s = Settings(ctx)
        val path = s.lastModelPath
        if (path.isNullOrBlank()) return null
        if (!java.io.File(path).exists()) return null
        try {
            withContext(Dispatchers.IO) {
                NovaEngine.load(ctx, path, s.lastModelLabel, s.systemPrompt)
            }
        } catch (e: Exception) { return null }
        if (!ready()) return null
        return busyReply(ctx, incoming)
    }
}
