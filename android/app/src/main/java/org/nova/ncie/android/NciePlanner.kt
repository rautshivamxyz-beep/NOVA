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
            "\nChoose \"web\" when the answer depends on live or recent information, " +
            "\"wiki\" for general facts, \"notes\"/\"files\" for the user's own material, " +
            "\"memory\" for things about the user, \"calculator\" for arithmetic, and " +
            "\"none\" only when no source is needed. Output only the JSON array.\n\n" +
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

    /** Asks the local model which tools to use. Call OFF the main thread. */
    fun choose(ctx: Context, text: String): List<String> = try {
        parse(NovaEngineAdapter.generate(prompt(text), 60))
    } catch (e: Exception) { emptyList() }
}
