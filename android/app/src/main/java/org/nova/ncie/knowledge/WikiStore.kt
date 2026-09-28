package org.nova.ncie.knowledge

/**
 * v0.7.0 — the search half of the NOVA app's WikiCore (WikiCore.search),
 * ported kernel-side. The app keeps the I/O — the download, the
 * articles.txt file, the byte-offset title index — and calls in here
 * for scoring and selection, so the offline-Wikipedia ranking lives
 * behind the NCIE boundary like every other piece of intelligence.
 *
 * Ported VERBATIM: the same stopword list, the same score (query-word
 * hits in the title, plus a 2-point bonus when the title appears in the
 * query), the same title tie-break, the same top-3 paragraph selection
 * and the same 1100-char cap. A host that feeds the same index and line
 * reader gets byte-identical results to the app's WikiCore.search.
 */
class WikiStore {

    data class Hit(val title: String, val text: String)

    private val STOP = setOf("what", "who", "when", "where", "why", "how", "the", "and",
        "for", "are", "was", "were", "is", "does", "did", "do", "with", "about",
        "tell", "explain", "describe", "which", "that", "this", "from", "many",
        "much", "some", "give", "list", "name", "then", "than", "into", "also")

    fun words(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOP }.toSet()

    /**
     * v0.9.2 (opt #3): the title index PRE-TOKENIZED once, instead of
     * re-tokenizing every title (regex split, stopword filter, padded
     * normalize) on every query. Hosts that hold one index call
     * [prepare] once and pass the result to the prepared [search]
     * overload; the per-query work drops to integer counting.
     */
    class PreparedIndex internal constructor(
        internal val titles: Array<String>,
        internal val offsets: LongArray,
        internal val titleWords: Array<Set<String>>,
        internal val titleNorms: Array<String>,
        internal val byToken: Map<String, IntArray>,
    )

    /** Tokenize the whole title index ONCE - call after the index is
     *  built or rebuilt, keep the result beside it. */
    fun prepare(index: List<Pair<String, Long>>): PreparedIndex {
        val tw = Array(index.size) { words(index[it].first) }
        val norms = Array(index.size) {
            val t = index[it].first.lowercase()
            " " + t.replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        }
        val tokenMap = HashMap<String, ArrayList<Int>>()
        for (i in index.indices) {
            for (t in tw[i]) tokenMap.getOrPut(t) { ArrayList() }.add(i)
        }
        val byToken = HashMap<String, IntArray>(tokenMap.size)
        for ((t, ids) in tokenMap) byToken[t] = ids.toIntArray()
        return PreparedIndex(
            Array(index.size) { index[it].first },
            LongArray(index.size) { index[it].second },
            tw, norms, byToken,
        )
    }

    /**
     * Finds the most relevant articles for a question - the compat entry
     * point that prepares the index first. Hosts that search repeatedly
     * should call [prepare] once and use the other [search] overload.
     *
     * @param index the host's title index: (title, byte offset of its line)
     * @param readLine reads the full article line at a byte offset, or null
     */
    fun search(
        index: List<Pair<String, Long>>,
        query: String,
        maxResults: Int = 2,
        readLine: (Long) -> String?,
    ): List<Hit> = search(prepare(index), query, maxResults, readLine)

    /**
     * The same search over a [PreparedIndex]: identical results to the
     * compat overload, without re-tokenizing every title per query.
     */
    fun search(
        prepared: PreparedIndex,
        query: String,
        maxResults: Int = 2,
        readLine: (Long) -> String?,
    ): List<Hit> {
        val qw = words(query)
        if (qw.isEmpty()) return emptyList()
        // v7.8.1: the title bonus used raw substring containment, so an
        // article title could match INSIDE a longer word - "hiv" matched
        // inside "shivam", and "my name is shivam" pulled the HIV article
        // in as background. Both sides are normalised to space-padded
        // whole words now: "nelson mandela" still earns the bonus inside
        // "who is nelson mandela", "hiv" no longer matches "shivam".
        val qWords = " " + query.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        val scored = ArrayList<Triple<Int, String, Long>>()
        // v0.9.2 (opt #3): candidates are the titles a query token hits
        // (from the token map built once by prepare) plus titles earning
        // the whole-word title bonus. Iterated in index order so ties
        // rank exactly as the full scan did.
        val candidates = HashSet<Int>()
        for (t in qw) prepared.byToken[t]?.forEach { candidates.add(it) }
        for (i in prepared.titleNorms.indices) {
            if (prepared.titleNorms[i].length > 2 &&
                qWords.contains(prepared.titleNorms[i])
            ) candidates.add(i)
        }
        for (i in candidates.sorted()) {
            val score = qw.count { it in prepared.titleWords[i] } +
                (if (prepared.titleNorms[i].length > 2 &&
                    qWords.contains(prepared.titleNorms[i])) 2 else 0)
            if (score > 0) {
                scored.add(Triple(score, prepared.titles[i], prepared.offsets[i]))
            }
        }
        val ranked = scored.sortedWith(compareByDescending<Triple<Int, String, Long>> { it.first }
            .thenBy { it.second })
        if (scored.isEmpty()) return emptyList()
        val out = mutableListOf<Hit>()
        for ((_, title, off) in ranked.take(maxResults)) {
            val line = readLine(off) ?: continue
            val parts = line.split('\u241F')
            val paras = parts.drop(1).filter { it.isNotBlank() }
            if (paras.isEmpty()) continue
            val best = paras.sortedByDescending { p -> qw.count { p.lowercase().contains(it) } }
                .take(3).joinToString(" ")
            val text = if (best.length > 1100) best.substring(0, 1100) + "…" else best
            out.add(Hit(title, text))
        }
        return out
    }
}
