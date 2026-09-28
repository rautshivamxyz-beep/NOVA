package org.nova.ncie.knowledge

/**
 * Personal knowledge base — a faithful pure-JVM port of NOVA-android's
 * `Knowledge.kt` (v7.6.3): user documents split into chunks, keyword-searched
 * with rarity (IDF) weighting, and injected into prompts (offline RAG).
 *
 * What was kept, byte-for-byte in logic:
 *  - the ~700-char chunker that breaks at paragraph/sentence boundaries
 *  - the stop-word list and the whole-word " padded " matching trick
 *  - IDF term weights so a rare word ("baker") outranks a common one
 *    ("india") that appears in half the corpus
 *  - one-hit-per-document best-doc scoring with a x2 name bonus
 *  - the 55%-of-query-weight chunk qualification threshold
 *  - the contiguous-chapter window for topic queries (so "power sharing"
 *    gets the whole chapter, never fragments of several chapters)
 *
 * What was removed: Android `Context`, `org.json` and `Settings` — the
 * storage layer stays outside the core. Feed documents in with [rebuild] /
 * [addDoc] / [rebuildChunks]; in the NOVA app, an adapter parses
 * knowledge.json and calls in (see README).
 */
class KnowledgeStore(
    /** Doc names excluded from search (the app's Notes filter). */
    @Volatile private var excluded: Set<String> = emptySet(),
) {

    // v0.9.2 (opt #6): the never-read `low` copy of every chunk is gone
    // (a third of chunk memory back), and each chunk precomputes its
    // document's lowercase name - search no longer lowercases it per
    // chunk per query.
    class Chunk(val doc: String, val text: String, val norm: String, val docLow: String)

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

    private val chunks = ArrayList<Chunk>()

    // v0.9.2 (opt #2): term -> chunk ids holding that term, and
    // doc name -> chunk ids, both rebuilt whenever the chunk list
    // changes. Search scores only the chunks a query term actually
    // hits instead of scanning every chunk's norm string per term.
    private val index = HashMap<String, LinkedHashSet<Int>>()
    private val docIds = LinkedHashMap<String, MutableList<Int>>()

    // ------------------------------------------------------------ contents

    fun isEmpty(): Boolean = chunks.isEmpty()

    val chunkCount: Int get() = chunks.size

    /** Doc name -> chunk count, in insertion order. */
    fun docs(): List<Pair<String, Int>> {
        val seen = LinkedHashMap<String, Int>()
        for (c in chunks) seen[c.doc] = (seen[c.doc] ?: 0) + 1
        return seen.map { it.key to it.value }
    }

    /** Replace the whole store from (docName, fullText) pairs. */
    fun rebuild(docs: List<Pair<String, String>>) {
        synchronized(this) {
            chunks.clear()
            for ((name, text) in docs) addDocLocked(name, text)
            reindexLocked()
        }
    }

    /** Ingest PRE-CHUNKED documents — (docName, chunkText) pairs in the
     *  exact shape the NOVA app's knowledge.json stores ([{"d": ..., "t": ...}]).
     *  No re-chunking: chunks are wrapped as-is, so retrieval is
     *  byte-identical to the app's Knowledge.kt reading the same file. */
    @Synchronized
    fun rebuildChunks(chunks: List<Pair<String, String>>) {
        this.chunks.clear()
        for ((doc, text) in chunks) {
            this.chunks.add(Chunk(doc, text, normOf(text), doc.lowercase()))
        }
        reindexLocked()
    }

    @Synchronized
    fun addDoc(name: String, text: String) {
        addDocLocked(name, text)
        reindexLocked()
    }

    private fun addDocLocked(name: String, text: String) {
        chunks.removeAll { it.doc == name }
        for (piece in chunkText(text)) {
            chunks.add(Chunk(name, piece, normOf(piece), name.lowercase()))
        }
    }

    @Synchronized
    fun removeDoc(name: String) {
        chunks.removeAll { it.doc == name }
        reindexLocked()
    }

    /** Update the exclusion filter (the app's Notes filter). */
    fun setExcluded(names: Set<String>) {
        excluded = names
    }

    // ------------------------------------------------------------- search

    /** Keyword search over all chunks; the best rarity-weighted matches.
     *  A chunk qualifies when it captures at least 55% of the query's
     *  total weight, so a single rare-word hit works while "power
     *  sharing"-style queries still need both words. */
    fun search(query: String, maxResults: Int = 4): List<Chunk> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        if (chunks.isEmpty()) return emptyList()
        val skip = excluded
        val weights = termWeights(terms)
        val total = weights.values.sum()
        val scored = ArrayList<Pair<Double, Chunk>>()
        // v0.9.2 (opt #2): the candidate set is the union of the chunks a
        // query term hits plus the chunks of any document whose NAME
        // matches a term (the x2 name bonus) - every chunk the old full
        // scan could have scored, and no others. Iterated in chunk order
        // so equal scores rank exactly as before.
        val candidates = HashSet<Int>()
        for (t in terms) index[t]?.let { candidates.addAll(it) }
        for ((doc, ids) in docIds) {
            if (terms.any { doc.lowercase().contains(it) }) candidates.addAll(ids)
        }
        for (i in candidates.sorted()) {
            val c = chunks[i]
            if (c.doc in skip) continue
            val dl = c.docLow
            var s = 0.0
            for (t in terms) {
                if (index[t]?.contains(i) == true) s += weights[t] ?: 1.0
                if (dl.contains(t)) s += 2.0 * (weights[t] ?: 1.0)
            }
            if (s > 0.0 && s >= 0.55 * total) scored.add(s to c)
        }
        scored.sortByDescending { it.first }
        return scored.take(maxResults).map { it.second }
    }

    /** Best document for a query: a term COUNTS once per document (not once
     *  per chunk), weighted by term rarity, with a x2 bonus when the term
     *  is in the document NAME. null when nothing matches. */
    fun bestDocName(query: String): String? = bestDocFor(tokenize(query))

    private fun bestDocFor(terms: List<String>): String? {
        if (terms.isEmpty()) return null
        if (chunks.isEmpty()) return null
        val skip = excluded
        val nameDocs = HashMap<String, MutableSet<String>>()   // term -> docs named after it
        val textDocs = HashMap<String, MutableSet<String>>()   // term -> docs containing it
        // v0.9.2 (opt #2): text hits come straight from the inverted
        // index, name hits from the per-doc chunk map - the same sets
        // the full scan built, without walking every chunk
        for (t in terms) {
            val docs = HashSet<String>()
            index[t]?.forEach { docs.add(chunks[it].doc) }
            textDocs[t] = docs
        }
        for ((doc, _) in docIds) {
            if (doc in skip) continue
            val dl = doc.lowercase()
            for (t in terms) {
                if (dl.contains(t)) nameDocs.getOrPut(t) { HashSet() }.add(doc)
            }
        }
        val weights = termWeights(terms)
        val docScores = HashMap<String, Double>()
        for (t in terms) {
            val w = weights[t] ?: 1.0
            nameDocs[t]?.forEach { docScores[it] = (docScores[it] ?: 0.0) + 2.0 * w }
            textDocs[t]?.forEach { docScores[it] = (docScores[it] ?: 0.0) + w }
        }
        return docScores.maxByOrNull { it.value }?.key
    }

    /** Chunks for a summary request: the ONE best document, then either the
     *  whole document (when the query names it) or a CONTIGUOUS window
     *  around the best match — the whole topic comes along, never a mix of
     *  fragments from different chapters. Returned in document order. */
    fun bestChunks(query: String, maxChunks: Int = 18): List<String> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        if (chunks.isEmpty()) return emptyList()
        val bestDoc = bestDocFor(terms) ?: return emptyList()
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
        if (nameHit || idxs.isEmpty()) {
            return docIdx.take(maxChunks).map { chunks[it].text }
        }
        // topic inside a bigger document -> a CONTIGUOUS window around
        // the best match
        val center = idxs.maxByOrNull { chunkScores[it] } ?: docIdx.first()
        val pos = docIdx.indexOf(center).coerceAtLeast(0)
        val from = maxOf(0, pos - 2)
        val to = minOf(docIdx.size, from + maxChunks)
        return docIdx.subList(from, to).map { chunks[it].text }
    }

    /** True when every query term hits the document NAME — the user means
     *  the whole document, not one topic inside it. */
    fun nameOnlyQuery(query: String, doc: String): Boolean {
        val terms = tokenize(query)
        if (terms.isEmpty()) return false
        val dl = doc.lowercase()
        return terms.all { dl.contains(it) }
    }

    // ----------------------------------------------------------- internals

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

    /** Rarity (IDF) weight of each query term — a word in a handful of
     *  chunks identifies the right document far better than a word
     *  appearing in half the knowledge base. log-scaled so common words
     *  still count, just less. */
    private fun termWeights(terms: List<String>): Map<String, Double> {
        val w = HashMap<String, Double>()
        for (t in terms) {
            val n = index[t]?.size ?: 0
            w[t] = if (n == 0) 1.0 else Math.log(1.0 + chunks.size.toDouble() / n)
        }
        return w
    }

    /** v0.9.2 (opt #2): rebuild the inverted index and the per-doc chunk
     *  map. Called with the store's monitor held, after any mutation. */
    private fun reindexLocked() {
        index.clear()
        docIds.clear()
        for (i in chunks.indices) {
            for (t in chunks[i].norm.split(' ')) {
                if (t.isNotEmpty()) index.getOrPut(t) { LinkedHashSet() }.add(i)
            }
            docIds.getOrPut(chunks[i].doc) { ArrayList() }.add(i)
        }
    }

    /** Lowercase with non-alphanumeric runs collapsed to single spaces,
     *  padded at both ends — " term " matching then hits whole words only
     *  ("art" no longer matches "start"). */
    private fun normOf(t: String): String =
        " " + t.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "

    /** Query terms: >= 3 chars, stop words dropped, deduplicated. */
    fun tokenize(s: String): List<String> {
        val out = LinkedHashSet<String>()
        for (w in s.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (w.length >= 3 && w !in STOP) out.add(w)
        }
        return out.toList()
    }
}
