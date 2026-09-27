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
     * Finds the most relevant articles for a question.
     *
     * @param index the host's title index: (title, byte offset of its line)
     * @param readLine reads the full article line at a byte offset, or null
     */
    fun search(
        index: List<Pair<String, Long>>,
        query: String,
        maxResults: Int = 2,
        readLine: (Long) -> String?,
    ): List<Hit> {
        val qw = words(query)
        if (qw.isEmpty()) return emptyList()
        val ql = query.lowercase()
        val scored = index.mapNotNull { (title, off) ->
            val tw = words(title)
            val score = qw.count { it in tw } +
                (if (title.lowercase() in ql) 2 else 0)
            if (score > 0) Triple(score, title, off) else null
        }.sortedWith(compareByDescending<Triple<Int, String, Long>> { it.first }
            .thenBy { it.second })
        if (scored.isEmpty()) return emptyList()
        val out = mutableListOf<Hit>()
        for ((_, title, off) in scored.take(maxResults)) {
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
