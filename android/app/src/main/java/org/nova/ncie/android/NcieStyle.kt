package org.nova.ncie.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nova.ChatStore
import org.nova.NovaEngine
import org.nova.Role

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
        val mine = samples(ctx)
        if (mine.isEmpty()) return null
        return "The user writes like this (real examples of their messages):\n-----\n" +
            mine + "-----\nNow write the following IN THE SAME STYLE - same tone, same " +
            "language mix, same habits, first person. Reply with only the text:\n" + request
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
            "One per line, no numbering, no quotes."
        val p = prompt(ctx, req) ?: return emptyList()
        val out = try {
            withContext(Dispatchers.IO) { NovaEngineAdapter.generate(p, 200) }
        } catch (e: Exception) { return emptyList() }
        return parseLines(out).take(n)
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
}
