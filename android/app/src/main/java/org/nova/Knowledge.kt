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
        "want", "whole", "full", "complete",
        // v7.8.1: "name" matched half the knowledge base ("my name is
        // shivam" injected random notes that happened to contain the
        // word "name") - it is never a discriminative keyword
        "name", "names"
    )

    private var cache: ArrayList<Chunk>? = null

    /** v5.4.6: documents excluded from search by the user's Notes filter. */
    private fun excluded(ctx: Context): Set<String> = Settings(ctx).knowledgeExcluded

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
            // v7.6: atomic write - a crash mid-write no longer wipes the file
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            cache = chunks
        } catch (e: Exception) { }
    }

    fun hasDocs(ctx: Context): Boolean = load(ctx).isNotEmpty()

    /** v7.6: warm the cache from a background thread at app start so the
     *  first message of a session never parses knowledge.json on the UI
     *  thread (Wikipedia got warmUp long ago - this is the notes twin). */
    fun warmUp(ctx: Context) { load(ctx) }

    /** Doc name -> chunk count, in insertion order. */
    fun docs(ctx: Context): List<Pair<String, Int>> {
        val seen = LinkedHashMap<String, Int>()
        for (c in load(ctx)) seen[c.doc] = (seen[c.doc] ?: 0) + 1
        return seen.map { it.key to it.value }
    }

    /** v5.4.9: full extracted text of one document (view / export). */
    fun docText(ctx: Context, name: String): String =
        load(ctx).filter { it.doc == name }.joinToString("\n\n") { it.text }

    @Synchronized
    fun addDoc(ctx: Context, name: String, text: String) {
        val chunks = ArrayList(load(ctx).filter { it.doc != name })
        for (piece in chunkText(text)) chunks.add(Chunk(name, piece, piece.lowercase(), normOf(piece)))
        save(ctx, chunks)
        clearQaCache(ctx)
    }

    /** v5.4.7: backup restore - replace the whole knowledge base. */
    fun restoreAll(ctx: Context, json: String) {
        try {
            val arr = JSONArray(json)
            val chunks = ArrayList<Chunk>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getString("t")
                chunks.add(Chunk(o.getString("d"), t, t.lowercase(), normOf(t)))
            }
            save(ctx, chunks)
        } catch (e: Exception) { }
    }

    fun removeDoc(ctx: Context, name: String) {
        val chunks = ArrayList(load(ctx).filter { it.doc != name })
        save(ctx, chunks)
        clearQaCache(ctx)
    }

    /** Old cached answers are invalid once the notes change. */
    fun clearQaCache(ctx: Context) {
        try {
            File(ctx.filesDir, "summary_cache").apply { mkdirs() }
                .listFiles { f: File -> f.name.startsWith("qa_") }
                ?.forEach { it.delete() }
        } catch (e: Exception) { }
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

    /** v6.2.3: rarity (IDF) weight of each query term - a word in a handful
     *  of chunks ("baker") identifies the right document far better than a
     *  word appearing in half the knowledge base ("india" all over the SST
     *  notes). log-scaled so common words still count, just less. */
    private fun termWeights(ctx: Context, terms: List<String>): Map<String, Double> {
        val chunks = load(ctx)
        val w = HashMap<String, Double>()
        for (t in terms) {
            var n = 0
            for (c in chunks) if (c.norm.contains(" " + t + " ")) ++n
            w[t] = if (n == 0) 1.0 else Math.log(1.0 + chunks.size.toDouble() / n)
        }
        return w
    }

    /** v6.2.3: best document for a query - a term COUNTS once per document
     *  (not once per chunk: that let a frequent word like "india" beat the
     *  rare word "baker" by piling up hundreds of chunk hits), weighted by
     *  term rarity, with a x2 bonus when the term is in the document NAME. */
    private fun bestDocFor(ctx: Context, terms: List<String>): String? {
        if (terms.isEmpty()) return null
        val chunks = load(ctx)
        if (chunks.isEmpty()) return null
        val skip = excluded(ctx)
        val nameDocs = HashMap<String, MutableSet<String>>()   // term -> docs named after it
        val textDocs = HashMap<String, MutableSet<String>>()   // term -> docs containing it
        for (c in chunks) {
            if (c.doc in skip) continue
            val dl = c.doc.lowercase()
            for (t in terms) {
                if (dl.contains(t)) nameDocs.getOrPut(t) { HashSet() }.add(c.doc)
                if (c.norm.contains(" " + t + " ")) textDocs.getOrPut(t) { HashSet() }.add(c.doc)
            }
        }
        val weights = termWeights(ctx, terms)
        val docScores = HashMap<String, Double>()
        for (t in terms) {
            val w = weights[t] ?: 1.0
            nameDocs[t]?.forEach { docScores[it] = (docScores[it] ?: 0.0) + 2.0 * w }
            textDocs[t]?.forEach { docScores[it] = (docScores[it] ?: 0.0) + w }
        }
        return docScores.maxByOrNull { it.value }?.key
    }

    /** Keyword search over all chunks; returns the best matches.
     *  v6.2.3: matches are rarity-weighted - hitting the rare word
     *  ("baker") counts far more than hitting a common one ("india").
     *  A chunk qualifies when it captures at least 55% of the query's
     *  total weight: a single rare-word hit now works, while "power
     *  sharing" style queries still need both words. */
    fun search(ctx: Context, query: String, maxResults: Int = 4): List<Chunk> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        val skip = excluded(ctx)
        val weights = termWeights(ctx, terms)
        val total = weights.values.sum()
        val scored = ArrayList<Pair<Double, Chunk>>()
        for (c in chunks) {
            if (c.doc in skip) continue
            val dl = c.doc.lowercase()
            var s = 0.0
            for (t in terms) {
                if (c.norm.contains(" " + t + " ")) s += weights[t] ?: 1.0
                if (dl.contains(t)) s += 2.0 * (weights[t] ?: 1.0)
            }
            if (s > 0.0 && s >= 0.55 * total) scored.add(s to c)
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
     * v6.2.3: rarity-weighted, one hit per document, so the rare word
     * ("baker" in the English notes) decides instead of the most frequent
     * word ("india" across the whole SST notes).
     */
    fun bestDocName(ctx: Context, query: String): String? =
        bestDocFor(ctx, tokenize(query))

    /** Chunks for a summary request: picks the ONE best document for the
     *  query (v6.2.3: rarity-weighted, see [bestDocFor]), then either the
     *  whole document (when the query names it, e.g. "sst") or the chunks
     *  around the user's topic - so a "power sharing" summary gets the
     *  whole chapter, not fragments, and never drags in unrelated
     *  chapters. Returned in document order. */
    fun bestChunks(ctx: Context, query: String, maxChunks: Int = 18): List<String> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val chunks = load(ctx)
        if (chunks.isEmpty()) return emptyList()
        val bestDoc = bestDocFor(ctx, terms) ?: return emptyList()
        val bestDl = bestDoc.lowercase()
        val chunkScores = IntArray(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            if (c.doc != bestDoc) continue
            var s = 0
            for (t in terms) {
                if (c.norm.contains(" " + t + " ")) s += 2
                if (bestDl.contains(t)) s += 3
            }
            chunkScores[i] = s
        }
        val nameHit = terms.any { bestDl.contains(it) }
        val idxs = chunks.indices.filter { chunks[it].doc == bestDoc && chunkScores[it] > 0 }
        val docIdx = chunks.indices.filter { chunks[it].doc == bestDoc }
        // the query names this chapter -> summarize the whole document
        if (nameHit || idxs.isEmpty())
            return docIdx.take(maxChunks).map { chunks[it].text }
        // topic inside a bigger document -> a CONTIGUOUS window around
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

    /** v7.6: public for the send()-side relevance gate. */
    fun tokenize(s: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in s.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (w.length >= 3 && w !in STOP) out.add(w)
        }
        return out.toList()
    }
}
