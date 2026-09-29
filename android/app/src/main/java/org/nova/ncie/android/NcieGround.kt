package org.nova.ncie.android

import android.content.Context
import org.nova.WikiCore
import java.io.File

/**
 * v9.5.0 "Document Grounding": strict answers from the user's OWN
 * material, with sources. "from my notes: <question>" triggers a
 * deep-read retrieval over every stored source - Knowledge documents
 * plus offline wiki articles - and grounds the model's answer in the
 * best-matching chunks, so nothing is invented:
 *
 *  - [retrieve] splits each source into ~1500-char chunks (paragraph
 *    boundaries preferred), scores every chunk by case-insensitive
 *    keyword overlap with the question (the kernel analyzer's keywords,
 *    the same normalization the notes search uses), keeps the top 2
 *    chunks per source and the top 4 across sources. Only chunks that
 *    actually match a keyword come back - an empty list means the
 *    user's material says nothing about the question.
 *  - [groundPrompt] builds the deterministic prompt: answer ONLY from
 *    the retrieved text, or reply exactly "Not in your notes", and
 *    mention the source.
 *  - [docsList] / [wikiNames] list what the user has, names only.
 *
 * v9.6.0 "Engine Pack" adds three deterministic engines here, all pure
 * string work over files the app already owns:
 *
 *  - Truth check ([truthCheck]): after a grounded answer is produced,
 *    the answer's content words are verified against the retrieved
 *    chunks - see the function.
 *  - FlashMap index (filesDir/ground_index.txt): one `name\tchunkCount`
 *    line per source, written at the store write points (NcieKnowledge
 *    add/remove, WikiCore's article store). In [retrieve] the index
 *    validates the session's in-memory name->chunks cache, so a source
 *    whose chunk count is unchanged is never re-read or re-split; a
 *    missing or stale index is rebuilt.
 *  - Knowledge Graph ([connections] / [related]): deterministic
 *    cross-document links - source pairs sharing at least 3 key terms.
 *
 * Fully local and deterministic, no new dependency: retrieval and the
 * engines are pure string work over files the app already owns, and the
 * LLM (already loaded in-app) is only used for the final grounded
 * answer - the NcieTutor pattern (NovaEngineAdapter.generate on
 * Dispatchers.IO).
 */
object NcieGround {

    /** The question's own stopwords - the same words the wiki search
     *  drops, so scoring counts content terms only ("what is the
     *  ozone layer" scores on ozone and layer, not on what and the). */
    private val STOP = setOf("what", "who", "when", "where", "why", "how", "the", "and",
        "for", "are", "was", "were", "is", "does", "did", "do", "with", "about",
        "tell", "explain", "describe", "which", "that", "this", "from", "many",
        "much", "some", "give", "list", "name", "then", "than", "into", "also",
        "my", "notes", "note", "documents", "document")

    /** The question's significant terms: the kernel analyzer's keywords
     *  (NcieKnowledge.keyTerms - the retrieval layer's existing helper)
     *  minus the question stopwords above. */
    internal fun questionTerms(question: String): List<String> =
        NcieKnowledge.keyTerms(question).filter { it !in STOP }

    /** All knowledge documents + wiki articles the user has, names only,
     *  in store order (Knowledge first, then wiki). */
    fun docsList(ctx: Context): List<String> {
        val names = ArrayList<String>()
        for (d in NcieKnowledge.docs(ctx)) names.add(d.first)
        names.addAll(wikiNames(ctx))
        return names
    }

    /** The stored wiki article titles (done.txt, the same list
     *  NcieTutor.findSource walks), or empty when wiki is not ready. */
    fun wikiNames(ctx: Context): List<String> = try {
        val done = File(ctx.filesDir, "wiki").resolve("done.txt")
        if (done.exists()) done.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        else emptyList()
    } catch (e: Exception) { emptyList() }

    /** Deep-read retrieval: the top (sourceName, chunkText) pairs for a
     *  question - top 2 keyword-matching chunks per source, top 4 across
     *  sources, best score first. Empty when nothing matches at all.
     *
     *  v9.6.0 FlashMap: the in-memory name->chunks cache is validated
     *  against ground_index.txt, so unchanged sources are not re-read
     *  or re-split each query; a missing/stale index is rebuilt here. */
    fun retrieve(ctx: Context, question: String): List<Pair<String, String>> {
        val terms = questionTerms(question)
        if (terms.isEmpty()) return emptyList()
        val names = docsList(ctx)
        val idx = readIndex(ctx)
        var dirty = idx == null || idx.size != names.size
        val best = ArrayList<Triple<Int, String, String>>()   // score, source, chunk
        for (name in names) {
            val cached = flashChunks[name]
            val chunks: List<String>
            if (cached != null && idx != null && idx[name] == cached.size) {
                // FlashMap hit: the index says this source's chunk count
                // is unchanged - reuse the session's chunks, no file
                // read, no re-split.
                chunks = cached
            } else {
                // FlashMap miss: new or edited source, or a missing/stale
                // index - read and split once, then cache for the session.
                val t = sourceText(ctx, name)
                chunks = if (t.isBlank()) emptyList() else chunksOf(t)
                flashChunks[name] = chunks
                dirty = true
            }
            if (chunks.isEmpty()) continue
            val perSource = ArrayList<Pair<Int, String>>()
            for (chunk in chunks) {
                // the notes search's normalization, verbatim: lowercase,
                // non-alphanumerics collapsed to spaces, whole-word match
                val norm = " " + chunk.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
                var score = 0
                for (t in terms) if (norm.contains(" " + t + " ")) score++
                if (score > 0) perSource.add(score to chunk)
            }
            perSource.sortByDescending { it.first }
            for (i in 0 until minOf(2, perSource.size))
                best.add(Triple(perSource[i].first, name, perSource[i].second))
        }
        best.sortByDescending { it.first }
        if (dirty) writeIndex(ctx, names)
        return best.take(4).map { it.second to it.third }
    }

    /** The deterministic grounded prompt: the model sees the question and
     *  ONLY the retrieved chunks, and must own up when they do not
     *  contain the answer. */
    fun groundPrompt(question: String, chunks: List<Pair<String, String>>): String =
        "Answer using ONLY the TEXT below. If the answer is not in the " +
            "text, reply exactly: Not in your notes. Otherwise answer " +
            "briefly and mention which source it came from. TEXT: " +
            chunks.joinToString("\n") { it.second + " (Source: " + it.first + ")" } +
            "\n\nQuestion: " + question

    /** ~1500-char chunks, cut at paragraph boundaries where possible -
     *  a single paragraph longer than two chunks is still split so no
     *  chunk grows unbounded. */
    internal fun chunksOf(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (para in text.split(Regex("\n+"))) {
            val p = para.trim()
            if (p.isEmpty()) continue
            if (sb.isNotEmpty() && sb.length + p.length + 1 > 1500) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(p)
            while (sb.length > 3000) {
                out.add(sb.substring(0, 1500))
                sb.delete(0, 1500)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    // --------------------------------------------- v9.6.0: truth check

    /** The truth check on grounded answers - deterministic answer-vs-
     *  source keyword verification, no second model pass. The answer's
     *  content words (NcieKnowledge's term normalization, the same one
     *  retrieval uses) are matched whole-word against the retrieved
     *  chunks: when fewer than 30% of them appear there, the answer did
     *  not come from the user's material and the caller appends the
     *  warning line. Never blocks the answer, only flags it. */
    fun truthCheck(answer: String, chunks: List<Pair<String, String>>): Boolean {
        val terms = NcieKnowledge.keyTerms(answer)
        if (terms.isEmpty()) return true
        val norm = " " + chunks.joinToString(" ") { it.second }
            .lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
        var matched = 0
        for (t in terms) if (norm.contains(" " + t + " ")) matched++
        return matched * 10 >= terms.size * 3
    }

    // --------------------------------------------- v9.6.0: FlashMap index

    /** The session's in-memory chunk cache: source name -> chunks.
     *  Validated against the ground_index.txt counts, so an edit at any
     *  store write point (Knowledge add/remove, wiki article store) is
     *  picked up here. */
    private val flashChunks = HashMap<String, List<String>>()

    private fun indexFile(ctx: Context) = File(ctx.filesDir, "ground_index.txt")

    /** The parsed FlashMap index (name -> chunkCount), or null when the
     *  file is missing or unreadable - null means: rebuild. */
    private fun readIndex(ctx: Context): HashMap<String, Int>? {
        val f = indexFile(ctx)
        if (!f.exists()) return null
        val out = HashMap<String, Int>()
        try {
            for (l in f.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size == 2) out[p[0]] = p[1].trim().toIntOrNull() ?: -1
            }
        } catch (e: Exception) { return null }
        return out
    }

    /** Rewrite the index for the current source list: one
     *  `name\tchunkCount` line per source that has chunks. */
    private fun writeIndex(ctx: Context, names: List<String>) {
        try {
            indexFile(ctx).writeText(names.filter { flashChunks[it]?.isNotEmpty() == true }
                .joinToString("") { it + "\t" + flashChunks[it]!!.size + "\n" })
        } catch (e: Exception) { }
    }

    /** Write point for ONE changed source: refresh (or, with text == null,
     *  drop) its index line and its in-memory chunks. Called from the
     *  store write points - NcieKnowledge add/remove and WikiCore's
     *  article store - which are already off the main thread. */
    fun indexUpdate(ctx: Context, name: String, text: String?) {
        graphTerms = null
        try {
            if (text == null) flashChunks.remove(name)
            else flashChunks[name] = chunksOf(text)
            val f = indexFile(ctx)
            val map = LinkedHashMap<String, Int>()
            if (f.exists()) for (l in f.readLines()) {
                val p = l.split('\t', limit = 2)
                if (p.size == 2) map[p[0]] = p[1].trim().toIntOrNull() ?: -1
            }
            if (text == null) map.remove(name)
            else map[name] = flashChunks[name]!!.size
            f.writeText(map.entries.joinToString("") { it.key + "\t" + it.value + "\n" })
        } catch (e: Exception) { }
    }

    /** The whole wiki store was replaced (WikiCore download): drop the
     *  index and the caches; the next retrieve rebuilds from the
     *  sources as they now are. */
    fun indexReset(ctx: Context) {
        graphTerms = null
        flashChunks.clear()
        try { indexFile(ctx).delete() } catch (e: Exception) { }
    }

    // --------------------------------------------- v9.6.0: knowledge graph

    /** Name -> key terms of every source, computed on demand and cached
     *  in memory for the session (invalidated whenever a source changes
     *  - see [indexUpdate]). Deterministic: NcieKnowledge's term
     *  normalization over each source's full text. */
    @Volatile private var graphTerms: Map<String, Set<String>>? = null

    fun sourceTermSets(ctx: Context): Map<String, Set<String>> {
        graphTerms?.let { return it }
        val m = HashMap<String, Set<String>>()
        for (name in docsList(ctx)) {
            val t = sourceText(ctx, name)
            if (t.isNotBlank()) m[name] = NcieKnowledge.keyTerms(t).toSet()
        }
        graphTerms = m
        return m
    }

    /** The deterministic cross-links: every pair of the user's sources
     *  (Knowledge documents + wiki articles) sharing at least 3 key
     *  terms, names sorted and shared terms sorted for stable output. */
    fun connections(ctx: Context): List<Triple<String, String, List<String>>> {
        val m = sourceTermSets(ctx)
        val names = m.keys.sorted()
        val out = ArrayList<Triple<String, String, List<String>>>()
        for (i in names.indices)
            for (j in i + 1 until names.size) {
                val shared = m[names[i]]!!.intersect(m[names[j]]!!).sorted()
                if (shared.size >= 3) out.add(Triple(names[i], names[j], shared))
            }
        return out
    }

    /** The OTHER sources sharing at least 3 key terms with [name] - the
     *  "Related:" line after the Sources line of a grounded answer. */
    fun related(ctx: Context, name: String): List<String> {
        val m = sourceTermSets(ctx)
        val mine = m[name] ?: return emptyList()
        return m.keys.filter { !it.equals(name, ignoreCase = true) }
            .filter { mine.intersect(m[it]!!).size >= 3 }.sorted()
    }

    // ------------------------------------------------------------- sources

    /** One source's full text: the Knowledge document of that name first,
     *  then the wiki article with that title (the same two stores, in
     *  the same order, retrieve always walked). File work only - call
     *  from a background thread. */
    private fun sourceText(ctx: Context, name: String): String = try {
        val t = NcieKnowledge.docText(ctx, name)
        if (t.isNotBlank()) t
        else if (WikiCore.isReady(ctx)) {
            WikiCore.warmUp(ctx)
            WikiCore.articleText(ctx, name) ?: ""
        } else ""
    } catch (e: Exception) { "" }
}
