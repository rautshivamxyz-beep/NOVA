package org.nova.ncie.android

import android.content.Context

/**
 * v9.29.6 "Tool router": the local MODEL decides which source NOVA should
 * gather from, instead of the regex heuristics deciding alone. Given the
 * question and a catalogue of NOVA's information tools, it returns the
 * tools to use, best first.
 *
 * Best-effort: any failure returns an empty list and the caller keeps its
 * own heuristics. Offline - the same on-device engine, no network, nothing
 * leaves the phone.
 */
object NciePlanner {

    /** name -> what it is. Order is the catalogue order. */
    val TOOLS: List<Pair<String, String>> = listOf(
        "notes" to "the user's own saved notes and documents",
        "memory" to "facts the user asked NOVA to remember",
        "wiki" to "offline Wikipedia articles already stored on the phone",
        "web" to "look it up online (Wikipedia and the open web)",
        "files" to "files in the folder the user granted NOVA",
        "notifications" to "the user's recent phone notifications",
        "calculator" to "arithmetic - a sum, an equation, a percentage",
        "skills" to "a repeatable procedure NOVA has a skill for",
        "none" to "no source - answer from general knowledge"
    )

    fun catalogue(): String = TOOLS.joinToString("\n") { "  - ${it.first}: ${it.second}" }

    fun prompt(question: String): String =
        "You route NOVA's questions to the right source. Think about what the question " +
            "actually needs, then choose. Which source(s) are needed to answer the question " +
            "below? Reply with ONLY a JSON array of source names, best first, at most 2. " +
            "Available sources:\n" + catalogue() +
            "\nReply with ONLY the JSON array.\n\n" +
            "Question: " + question

    /** Parses the model's reply into known tool names (best effort). */
    fun parse(reply: String): List<String> {
        val known = TOOLS.map { it.first }.toSet()
        val m = Regex("\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(reply) ?: return emptyList()
        return m.groupValues[1].split(',')
            .map { it.trim().trim('"', '\'', '.', ' ', '`', '*') }
            .filter { it.lowercase() in known }
            .map { it.lowercase() }
            .distinct()
    }

    /**
     * v9.31.1 "Fast route": the OBVIOUS cases never need a model call. The
     * router used to generate up to 60 tokens before every answer, which
     * pushed the first word out by seconds. These cheap checks settle the
     * common questions instantly and only fall through to the model when
     * the question really is ambiguous. Same routing, no model cost.
     */
    private val FAST: List<Pair<Regex, String>> = listOf(
        Regex("(?i)\\b(search|google|look ?it ?up|online|internet|latest|news|today|current|right now|price|weather|score|match|kya hua|aaj|abhi)\\b") to "web",
        Regex("(?i)\\b(calculate|compute|what(?:'s| is) [0-9]|[0-9]+\\s*[+\\-*/x]\\s*[0-9]+)\\b") to "calculator",
        Regex("(?i)\\b(my notes|my documents|my pdf|my book|mere notes|mere documents)\\b") to "notes",
        Regex("(?i)\\b(do you remember|my memory|what do you remember|yaad)\\b") to "memory",
        Regex("(?i)\\b(who|what|when|where|which|kaun|kya|kab|kahan)\\b") to "wiki"
    )

    /** Asks the local model which tools to use. Call OFF the main thread. */
    fun choose(ctx: Context, text: String): List<String> {
        for ((re, tool) in FAST) if (re.containsMatchIn(text)) return listOf(tool)
        return try {
            parse(NovaEngineAdapter.generate(prompt(text), 24))
        } catch (e: Exception) { emptyList() }
    }
}
