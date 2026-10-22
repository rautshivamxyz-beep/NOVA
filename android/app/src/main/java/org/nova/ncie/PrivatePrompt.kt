package org.nova.ncie

/**
 * v9.33.45 "Nothing private leaves".
 *
 * A server you do not own — a free cloud VM above all — must never receive the
 * user's own material. NOVA assembles a prompt that mixes three things: its own
 * generic instructions, the user's private material (notes, wiki hits, the
 * attached document, the facts they asked NOVA to remember, their exams, the
 * earlier conversation), and the question they typed. Only the first and third
 * may leave.
 *
 * This is pure string logic with no Android dependency, so it is unit-tested in
 * android/app/src/test/java — a privacy guarantee that nothing verifies is a
 * guarantee that quietly stops being true.
 *
 * THE FAILURE DIRECTION IS THE WHOLE POINT. [strip] returns null whenever it
 * cannot be CERTAIN the result is clean, and the caller then answers on the
 * phone instead. Never "send it anyway": a privacy feature that leaks on a
 * format change is worse than no feature at all.
 */
object PrivatePrompt {

    /**
     * The markers that OPEN a private region. Each one is a label NOVA itself
     * writes when it injects the material.
     */
    val OPEN = listOf(
        "CONTEXT (reference data",
        "(The user shared a document titled",
        "(Facts about the user, always remember:",
        "(The user's upcoming exams:",
        "(Summary of earlier conversation:",
        "(Earlier conversation for context:",
    )

    /**
     * The markers that CLOSE one. A leftover means the cut did not land where
     * it was supposed to, and that is exactly when we must not send.
     */
    val LEFTOVER = listOf(
        "END CONTEXT",
        "End of document.",
        "\u2014 end)",
        "end of earlier conversation",
        "Recent messages:",
    )

    /**
     * v9.33.50: the ONLY system prompt a machine we do not own may see.
     *
     * The user's own instructions are free text THEY wrote, and people put
     * personal facts in them ("I live in Pune, I bank with X"). Filtering the
     * question while forwarding that unchanged would have been a hole in the
     * guarantee, so on a remote machine the user's custom prompt is not sent
     * at all - NOVA's own generic prompt goes instead.
     */
    val REMOTE_SYSTEM_PROMPT =
        "You are NOVA, a helpful assistant. Answer clearly and concisely. " +
            "If you do not know something, say so instead of guessing."

    /** Which system prompt may leave the phone. */
    fun systemPromptFor(userPrompt: String, privateOnly: Boolean): String =
        if (privateOnly) REMOTE_SYSTEM_PROMPT else userPrompt

    /** Everything except NOVA's instructions and the user's question. */
    fun strip(prompt: String): String? {
        var out = prompt
        // the delimited blocks first: retrieved context, then the document
        out = cutBetween(out, "CONTEXT (reference data", "END CONTEXT")
        out = cutBetween(out, "(The user shared a document titled", "End of document.)")
        // the one-paragraph lines NOVA prepends
        out = cutLine(out, "(Facts about the user, always remember:")
        out = cutLine(out, "(The user's upcoming exams:")
        // the two earlier-conversation forms
        out = cutBetween(out, "(Summary of earlier conversation:", "\u2014 end)")
        out = cutBetween(out, "(Earlier conversation for context:", "end of earlier conversation)")
        // the safety net: anything still standing is something unrecognised
        for (m in OPEN) if (out.contains(m)) return null
        for (m in LEFTOVER) if (out.contains(m)) return null
        return out.trim().ifBlank { null }
    }

    /** Removes [from]..[to] inclusive; a missing [to] removes to the end. */
    private fun cutBetween(s: String, from: String, to: String): String {
        val a = s.indexOf(from)
        if (a < 0) return s
        val b = s.indexOf(to, a)
        return if (b < 0) s.substring(0, a) else s.substring(0, a) + s.substring(b + to.length)
    }

    /** Removes the one-paragraph line that [startsWith] opens. */
    private fun cutLine(s: String, startsWith: String): String {
        val a = s.indexOf(startsWith)
        if (a < 0) return s
        val b = s.indexOf("\n\n", a)
        return if (b < 0) s.substring(0, a) else s.substring(0, a) + s.substring(b + 2)
    }
}
