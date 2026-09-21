package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Personal knowledge base: user documents split into chunks,
 *  keyword-searched and injected into prompts (offline RAG). */
object Knowledge {

    class Chunk(val doc: String, val text: String, val low: String)

    private val STOP = setOf(
        "the", "and", "for", "are", "this", "that", "with", "what", "when",
        "where", "who", "how", "why", "was", "were", "from", "have", "has",
        "had", "you", "your", "into", "about", "which", "their", "they",
        "will", "would", "there", "these", "those", "been", "being", "does",
        "each", "just", "also", "some", "such", "only", "very", "can", "did",
        "its", "his", "her", "him", "them", "our", "out", "get", "got", "any",
        "all", "not", "but", "she", "then", "than"
    )

    private var cache: ArrayList<Chunk>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "knowledge.json")

    private fun load(ctx: Context): ArrayList<Chunk> {
        cache?.let { return it }
        val list = ArrayList<Chunk>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = o.getString("t")
                    list.add(Chunk(o.getString("d"), t, t.lowercase()))
                }
            }
        } catch (e: Exception) { }
        cache = list
        return list
    }

    private fun save(ctx: Context, chunks: ArrayList<Chunk>) {
        try {
            val arr = JSONArray()
            for (c in chunks) arr.put(JSONObject().put("d", c.doc).put("t", c.text))
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { }
        cache = chunks
    }

    fun hasDocs(ctx: Context): Boolean = load(ctx).isNotEmpty()

    /** Doc name -> chunk count, in insertion order. */
    fun docs(ctx: Context): List<Pair<String, Int>> {
        val seen = LinkedHashMap<String, Int>()
        for (c in load(ctx)) seen[c.doc] = (seen[c.doc] ?: 0) + 1
        return seen.map { it.key to it.value }
    }

    fun addDoc(ctx: Context, name: String, text: String) {
        val chunks = ArrayList(load(ctx).filter { it.doc != name })
        for (piece in chunkText(text)) chunks.add(Chunk(name, piece, piece.lowercase()))
        save(ctx, chunks)
    }

    fun removeDoc(ctx: Context, name: String) {
        val chunks = ArrayList(load(ctx).filter { it.doc != name })
        save(ctx, chunks)
    }

    /** Splits text into ~700-char pieces, breaking at paragraphs/sentences. */
    private fun chunkText(text: String): List<String> {
        val paras = text.replace("\r", "").split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p in paras) {
            var para = p
            while (para.length > 900) {          // split huge paragraphs at sentence end
                var cut = para.lastIndexOf(". ", 900)
                if (cut < 300) cut = 800
                out.add(para.substring(0, cut + 1).trim())
                para = para.substring(cut + 1)
            }
            if (sb.length + para.length > 700 && sb.isNotEmpty()) {
                out.add(sb.toString().trim()); sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(para)
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim())
        return out.filter { it.length > 40 }      // skip headers/fragments
    }

    /** Keyword search over all chunks; returns the best matches. */
    fun search(ctx: Context, query: String, maxResults: Int = 4): List<Chunk> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        val need = if (terms.size >= 2) 2 else 1
        val scored = ArrayList<Pair<Int, Chunk>>()
        for (c in chunks) {
            var score = 0
            for (t in terms) if (c.low.contains(t)) score++
            if (score >= need) scored.add(score to c)
        }
        scored.sortByDescending { it.first }
        return scored.take(maxResults).map { it.second }
    }

    private fun tokenize(s: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in s.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (w.length >= 3 && w !in STOP) out.add(w)
        }
        return out.toList()
    }
}
