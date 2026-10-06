package org.nova.ncie.android

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream

/**
 * v9.29.3 "Tool recipes": NOVA's safe, OFFLINE tool primitives.
 *
 * A recipe is data - a list of steps, each naming a primitive and its
 * arguments. [run] interprets it over a small pipeline. Everything here
 * is bounded and offline:
 *
 *  - reading happens ONLY inside a folder the user granted (SAF tree);
 *  - writing creates NEW files in NOVA's OWN private folder, atomically
 *    (tmp + rename) - it can never modify or delete a file of the user's;
 *  - there is NO network primitive at all;
 *  - a hard step cap, item cap and read cap.
 *
 * Nothing here executes code the model wrote - only these fixed primitives.
 */
object NcieTools {

    const val MAX_STEPS = 20
    const val MAX_ITEMS = 40
    const val MAX_READ_CHARS = 20000
    const val MAX_OUT = 6000

    /** One pipeline item: a file (name) and its text. */
    class Item(val name: String, val text: String)

    data class Step(val op: String, val args: Map<String, String> = emptyMap())

    /** The catalogue handed to the model when it authors a recipe. */
    val CATALOGUE: String = listOf(
        "list [ext]     -> files in the granted folder, optionally by extension (e.g. .pdf)",
        "read           -> read each item's text",
        "filter [text]  -> keep items whose name or text contains text",
        "search [text]  -> lines of the combined text matching the regex text",
        "take [n]       -> keep the first n items",
        "count          -> number of items",
        "summarise      -> summarise the combined text with the local model",
        "match [query]  -> items scored by relevance to query, highest first",
        "answer [query] -> answer query from the combined text",
        "write [name]   -> write the combined text to a NEW file in NOVA's folder"
    ).joinToString("\n")

    // ------------------------------------------------------------ folder

    fun grantedFolder(ctx: Context): Uri? = try {
        val s = org.nova.Settings(ctx).grantedFolder
        if (s.isBlank()) null else Uri.parse(s)
    } catch (e: Exception) { null }

    private fun root(ctx: Context): DocumentFile? =
        grantedFolder(ctx)?.let { DocumentFile.fromTreeUri(ctx, it) }

    private fun list(ctx: Context, ext: String?): List<Item> {
        val r = root(ctx) ?: return emptyList()
        val out = ArrayList<Item>()
        try {
            for (f in r.listFiles()) {
                if (!f.isFile) continue
                val n = f.name ?: continue
                if (!ext.isNullOrBlank() && !n.lowercase().endsWith(ext.lowercase())) continue
                out.add(Item(n, ""))
                if (out.size >= MAX_ITEMS) break
            }
        } catch (e: Exception) { }
        return out
    }

    private fun readItem(ctx: Context, it: Item): Item {
        if (it.text.isNotEmpty()) return it
        val f = root(ctx)?.findFile(it.name) ?: return it
        val t = try {
            ctx.contentResolver.openInputStream(f.uri)?.use { ins ->
                ins.bufferedReader().readText().take(MAX_READ_CHARS)
            } ?: ""
        } catch (e: Exception) { "" }
        return Item(it.name, t)
    }

    // ------------------------------------------------------------ run

    /**
     * Runs [steps] and returns the final text. [progress] is called with a
     * short label per step. Offline and bounded throughout; any step that
     * throws is skipped, so one bad step can never abort the whole recipe.
     */
    fun run(ctx: Context, steps: List<Step>, progress: (String) -> Unit = {}): String {
        var items = listOf<Item>()
        var text = ""
        val use = steps.take(MAX_STEPS)
        for ((i, s) in use.withIndex()) {
            progress("${i + 1}/${use.size}  ${s.op}")
            try {
                when (s.op.lowercase()) {
                    "list" -> items = list(ctx, s.args["ext"])
                    "read" -> items = items.map { readItem(ctx, it) }
                    "filter" -> {
                        val t = s.args["text"] ?: ""
                        items = items.filter { it.name.contains(t, true) || it.text.contains(t, true) }
                    }
                    "take" -> items = items.take((s.args["n"]?.toIntOrNull() ?: 10).coerceIn(1, MAX_ITEMS))
                    "count" -> text = items.size.toString()
                    "search" -> {
                        val re = try { Regex(s.args["text"] ?: "", RegexOption.IGNORE_CASE) }
                            catch (e: Exception) { null }
                        val src = text.ifBlank { items.joinToString("\n") { it.name + "\n" + it.text } }
                        text = if (re == null) src
                        else src.lines().filter { re.containsMatchIn(it) }.joinToString("\n")
                    }
                    "summarise" -> {
                        val src = text.ifBlank { items.joinToString("\n\n") { it.name + "\n" + it.text } }
                            .take(MAX_READ_CHARS)
                        text = NovaEngineAdapter.generate(
                            "Summarise the following in 5 short bullet points, one line each:\n\n" + src, 300)
                    }
                    "match" -> {
                        val q = s.args["query"] ?: ""
                        items = items.map { it to score(it.text, q) }
                            .sortedByDescending { it.second }.map { it.first }
                    }
                    "answer" -> {
                        val q = s.args["query"] ?: ""
                        val src = text.ifBlank { items.joinToString("\n\n") { it.name + "\n" + it.text } }
                            .take(MAX_READ_CHARS)
                        text = NovaEngineAdapter.generate(
                            "Answer this question using ONLY the context below. If the context " +
                                "does not contain the answer, say so.\n\nQuestion: $q\n\nContext:\n" + src, 400)
                    }
                    "write" -> {
                        val name = (s.args["name"] ?: "nova-output.txt")
                            .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        val body = text.ifBlank { items.joinToString("\n\n") { it.name + "\n" + it.text } }
                        writeNew(ctx, name, body)
                        text = "Wrote $name (${body.length} chars)."
                    }
                    else -> { }
                }
            } catch (e: Exception) { }
        }
        val out = text.ifBlank {
            items.joinToString("\n") {
                it.name + (if (it.text.isBlank()) "" else ": " + it.text.take(300))
            }
        }
        return out.take(MAX_OUT)
    }

    private fun score(text: String, query: String): Int {
        if (query.isBlank() || text.isBlank()) return 0
        val t = text.lowercase()
        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        if (terms.isEmpty()) return 0
        return terms.count { t.contains(it) } * 100 / terms.size
    }

    /**
     * Writes a NEW file in NOVA's own private folder. It never touches the
     * user's originals, and it is written atomically (tmp + rename), so a
     * crash cannot leave a half-written file.
     */
    private fun writeNew(ctx: Context, name: String, body: String) {
        try {
            val dir = File(ctx.filesDir, "recipes-out").apply { mkdirs() }
            val dest = File(dir, name)
            val tmp = File(dir, name + ".tmp")
            FileOutputStream(tmp).use { it.write(body.toByteArray()) }
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        } catch (e: Exception) { }
    }
}
