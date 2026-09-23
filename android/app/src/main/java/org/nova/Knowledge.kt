package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Personal knowledge base: user documents split into chunks,
 *  keyword-searched and injected into prompts (offline RAG). */
object Knowledge {

    class Chunk(val doc: String, val text: String, val low: String, val norm: String)

    private val STOP = setOf(
        "the", "and", "for", "are", "this", "that", "with", "what", "when",
        "where", "who", "how", "why", "was", "were", "from", "have", "has",
        "had", "you", "your", "into", "about", "which", "their", "they",
        "will", "would", "there", "these", "those", "been", "being", "does",
        "each", "just", "also", "some", "such", "only", "very", "can", "did",
        "its", "his", "her", "him", "them", "our", "out", "get", "got", "any",
        "all", "not", "but", "she", "then", "than",
        "notes", "note", "summarise", "summarize", "summary", "material",
        "give", "show", "tell", "read", "topic", "chapter", "gimme",
        "want", "whole", "full", "complete"
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
                    list.add(Chunk(o.getString("d"), t, t.lowercase(), normOf(t)))
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
            cache = chunks
        } catch (e: Exception) { }
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
        for (piece in chunkText(text)) chunks.add(Chunk(name, piece, piece.lowercase(), normOf(piece)))
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

    /** Keyword search over all chunks; returns the best matches.
     *  The document NAME counts double, so "sst" finds "SST notes". */
    fun search(ctx: Context, query: String, maxResults: Int = 4): List<Chunk> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        val need = if (terms.size >= 2) 2 else 1
        val scored = ArrayList<Pair<Int, Chunk>>()
        for (c in chunks) {
            var score = 0
            val dl = c.doc.lowercase()
            for (t in terms) {
                if (c.norm.contains(" " + t + " ")) score++
                // the document NAME matters too: "sst" must find "SST notes"
                if (dl.contains(t)) score += 2
            }
            if (score >= need) scored.add(score to c)
        }
        scored.sortByDescending { it.first }
        return scored.take(maxResults).map { it.second }
    }

    /** All chunks of one document, in stored order. */
    fun docChunks(ctx: Context, name: String): List<String> =
        load(ctx).filter { it.doc == name }.map { it.text }

    /** True when every query term hits the document NAME (e.g. "summarise
     *  sst notes" naming "SST_Notes_Detailed.pdf") - the user means the
     *  whole document, not one topic inside it. */
    fun nameOnlyQuery(query: String, doc: String): Boolean {
        val terms = tokenize(query)
        if (terms.isEmpty()) return false
        val dl = doc.lowercase()
        return terms.all { dl.contains(it) }
    }

    /**
     * Best document for a summary request, matching ANY query term - used
     * for routing "summarise power sharing" to the right notes even when
     * no single chunk contains every word. null when nothing matches.
     */
    fun bestDocName(ctx: Context, query: String): String? {
        val terms = tokenize(query)
        if (terms.isEmpty()) return null
        val chunks = load(ctx)
        if (chunks.isEmpty()) return null
        val docScores = HashMap<String, Int>()
        for (c in chunks) {
            val dl = c.doc.lowercase()
            var s = 0
            for (t in terms) {
                if (c.norm.contains(" " + t + " ")) s += 2
                if (dl.contains(t)) s += 3
            }
            if (s > 0) docScores[c.doc] = (docScores[c.doc] ?: 0) + s
        }
        return docScores.maxByOrNull { it.value }?.key
    }

    /** Chunks for a summary request: picks the ONE best document for the
     *  query, then either the whole document (when the query names it, e.g.
     *  "sst") or the chunks around the user's topic - so a "power sharing"
     *  summary gets the whole chapter, not fragments, and never drags in
     *  unrelated chapters. Returned in document order. */
    fun bestChunks(ctx: Context, query: String, maxChunks: Int = 18): List<String> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        // 1) pick the single best document for this query
        val docScores = HashMap<String, Int>()
        val chunkScores = IntArray(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            val dl = c.doc.lowercase()
            var docHit = 0
            var textHit = 0
            for (t in terms) {
                if (c.norm.contains(" " + t + " ")) {
                    textHit += 2
                    docScores[c.doc] = (docScores[c.doc] ?: 0) + 2
                }
                if (dl.contains(t)) docHit += 3
            }
            docScores[c.doc] = (docScores[c.doc] ?: 0) + docHit
            chunkScores[i] = textHit + docHit
        }
        val bestDoc = docScores.maxByOrNull { it.value }?.key ?: return emptyList()
        val nameHit = terms.any { bestDoc.lowercase().contains(it) }
        val idxs = chunks.indices.filter { chunks[it].doc == bestDoc && chunkScores[it] > 0 }
        val docIdx = chunks.indices.filter { chunks[it].doc == bestDoc }
        // 2a) the query names this chapter -> summarize the whole document
        if (nameHit || idxs.isEmpty())
            return docIdx.take(maxChunks).map { chunks[it].text }
        // 2b) topic inside a bigger document -> a CONTIGUOUS window around
        // the best match: the whole topic/chapter comes along, never a mix
        // of matching fragments from different chapters
        val center = idxs.maxByOrNull { chunkScores[it] } ?: docIdx.first()
        val pos = docIdx.indexOf(center).coerceAtLeast(0)
        val from = maxOf(0, pos - 2)
        val to = minOf(docIdx.size, from + maxChunks)
        return docIdx.subList(from, to).map { chunks[it].text }
    }

        /** v5.4: lowercase with non-alphanumeric runs collapsed to single
     *  spaces, padded at both ends - " term " matching then hits whole
     *  words only ("art" no longer matches "start"). */
    private fun normOf(t: String): String =
        " " + t.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "

    private fun tokenize(s: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in s.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (w.length >= 3 && w !in STOP) out.add(w)
        }
        return out.toList()
    }
}
