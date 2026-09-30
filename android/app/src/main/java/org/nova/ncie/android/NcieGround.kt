package org.nova.ncie.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * v9.9.0 "Semantic RAG" adds hybrid retrieval: every source also gets
 * pre-computed chunk embeddings at index time (NcieEmbed - the bundled
 * on-device all-MiniLM-L6-v2 int8 ONNX model, fully local), and
 * [retrieve] combines semantic cosine scores with the keyword scores
 * (final = max of the two, each normalized to 0..1). A chunk whose
 * WORDING differs from the question ("photosynthesis" vs "how do
 * plants make food") now scores. The embedder is best-effort: when the
 * model is missing or fails, every source simply uses keyword-only
 * scoring exactly as before - graceful fallback, never a crash.
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

    /** v9.12.1 "Context Diet": the retrieval STRENGTH gate shared by the
     *  chat turn's injection points (the attached-document window and
     *  the notes RAG in NcieChat). A match is STRONG when at least 3 of
     *  the question's significant terms appear in the candidate
     *  (whole-word), or the semantic cosine between the question and the
     *  candidate (NcieEmbed, when the embedder is alive) is >= 0.45.
     *  Weak matches inject nothing at all - the model answers from
     *  general knowledge honestly instead of drifting into an unrelated
     *  chunk. The explicit "from my notes:" path (retrieve) is NOT gated
     *  by this - the user asked for it. */
    fun strongMatch(ctx: Context, question: String, candidate: String): Boolean {
        val terms = questionTerms(question)
        val norm = " " + candidate.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
        var matched = 0
        for (t in terms) if (norm.contains(" " + t + " ")) matched++
        if (matched >= 3) return true
        val qv = NcieEmbed.embed(ctx, question) ?: return false
        val cv = NcieEmbed.embed(ctx, candidate.take(1500)) ?: return false
        return NcieEmbed.cosine(qv, cv) >= 0.45f
    }

    /** v9.13.0 "Audit Fixes" (HIGH 4): the chat turn's strength gate, OFF
     *  the main thread. ncieSend suspends here (Dispatchers.IO) so the
     *  ONNX embedder never runs - and its 23 MB session is never lazily
     *  initialized - on the main thread; a UI freeze on the first gated
     *  send after a cold start. Same result, different dispatcher. */
    suspend fun strongMatchIo(ctx: Context, question: String, candidate: String): Boolean =
        withContext(Dispatchers.IO) { strongMatch(ctx, question, candidate) }

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
     *  question - top 2 matching chunks per source, top 4 across
     *  sources, best score first. Empty when nothing matches at all.
     *
     *  v9.6.0 FlashMap: the in-memory name->chunks cache is validated
     *  against ground_index.txt, so unchanged sources are not re-read
     *  or re-split each query; a missing/stale index is rebuilt here.
     *
     *  v9.9.0 Semantic RAG: the question is embedded ONCE (NcieEmbed -
     *  null when the embedder is broken, then everything below is
     *  keyword-only as before) and scored by cosine against each
     *  source's pre-computed chunk embeddings; final score per chunk is
     *  max(semantic, keyword/termCount) - a simple, robust union. A
     *  semantic-only hit above the 0.30 noise floor counts even when
     *  NO keyword matches (different wording, same meaning). Sources
     *  with more than 40 chunks skip the full semantic pass and embed
     *  only their top keyword candidates, to bound latency. */
    /** v9.13.2 "Small Model Honesty": the best KNOWLEDGE document for a
     *  summarise request whose topic lives in the CONTENT, not the name
     *  ("summarise anne frank" -> the PDF holding the Anne Frank
     *  chapter). Reuses the same retrieval scoring "from my notes"
     *  runs (keyword + semantic, top chunks per source); the document
     *  owning the most of the top retrieved chunks wins. A weak best
     *  match (fewer than 2 of the top chunks) is a miss: null, and the
     *  caller keeps its current behavior. Wiki articles do not count -
     *  the summarizers read knowledge.json only. */
    fun bestSummaryDoc(ctx: Context, question: String): String? {
        val terms = questionTerms(question)
        if (terms.isEmpty()) return null
        val kdocs = NcieKnowledge.docs(ctx).map { it.first }.toHashSet()
        if (kdocs.isEmpty()) return null
        val hits = retrieve(ctx, question)
        val byDoc = HashMap<String, Int>()
        for ((name, _) in hits) if (name in kdocs) byDoc[name] = (byDoc[name] ?: 0) + 1
        val best = byDoc.maxByOrNull { it.value } ?: return null
        if (best.value < 2) return null
        return best.key
    }

    fun retrieve(ctx: Context, question: String): List<Pair<String, String>> {
        val terms = questionTerms(question)
        if (terms.isEmpty()) return emptyList()
        val names = docsList(ctx)
        val idx = readIndex(ctx)
        var dirty = idx == null || idx.size != names.size
        // the question's semantic vector, computed once per query;
        // null = embedder broken/failed -> keyword-only for everything
        val qVec = NcieEmbed.embed(ctx, question)
        val best = ArrayList<Triple<Float, String, String>>()   // score, source, chunk
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
                // v9.9.0: keep this source's embedding index in step with
                // the chunks too (fire-and-forget, stale-guarded).
                reindexEmbeddings(ctx, name, chunks)
            }
            if (chunks.isEmpty()) continue
            // keyword scores first (whole-word, the notes search's
            // normalization, verbatim as before)
            val kw = IntArray(chunks.size)
            for (i in chunks.indices) {
                val norm = " " + chunks[i].lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
                var score = 0
                for (t in terms) if (norm.contains(" " + t + " ")) score++
                kw[i] = score
            }
            // semantic scores per chunk index. Only when the question
            // embedded AND the source has an .emb index; sources with
            // >40 chunks embed only their top keyword candidates.
            val sem = HashMap<Int, Float>()
            if (qVec != null) {
                if (chunks.size <= 40 && NcieEmbed.hasIndex(ctx, name)) {
                    for ((i, s) in NcieEmbed.search(ctx, name, qVec, chunks.size))
                        if (s > 0f) sem[i] = s
                } else if (chunks.size > 40) {
                    val topKw = chunks.indices.sortedByDescending { kw[it] }.take(5)
                    for (i in topKw) {
                        if (kw[i] <= 0) continue
                        val cv = NcieEmbed.embed(ctx, chunks[i]) ?: continue
                        val s = NcieEmbed.cosine(qVec, cv)
                        if (s > 0f) sem[i] = s
                    }
                }
            }
            val perSource = ArrayList<Pair<Float, String>>()
            val denom = maxOf(1, terms.size).toFloat()
            for (i in chunks.indices) {
                val s = sem[i] ?: 0f
                val f = maxOf(kw[i] / denom, s)
                // a keyword hit counts as before; a semantic-only hit
                // counts above the noise floor (wording differs from
                // the question, meaning is the same)
                if (kw[i] > 0 || s >= 0.30f) perSource.add(f to chunks[i])
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
        // v9.9.0: keep the semantic chunk embeddings in step with this
        // write (fire-and-forget; deleteIndex when the source is gone).
        reindexEmbeddings(ctx, name, if (text == null) null else flashChunks[name])
    }

    /** The whole wiki store was replaced (WikiCore download): drop the
     *  index and the caches; the next retrieve rebuilds from the
     *  sources as they now are. */
    fun indexReset(ctx: Context) {
        graphTerms = null
        flashChunks.clear()
        try { indexFile(ctx).delete() } catch (e: Exception) { }
        // v9.9.0: the whole source set changed - drop every embedding
        // index too; retrieve rebuilds them as sources come back.
        NcieEmbed.resetIndexes(ctx)
    }

    // --------------------------------------------- v9.9.0: semantic index

    /** The per-source embedding re-index workers in flight - the simple
     *  guard so one source is never embedded twice concurrently. */
    private val embeddingInFlight = HashSet<String>()

    /** Fire-and-forget: bring a source's chunk embeddings (NcieEmbed's
     *  filesDir/embeddings/<name>.emb) in step with its chunks. Runs on
     *  a plain background thread, skips when the index is already
     *  current (chunk count matches), deletes the index when the source
     *  is gone. Failures are logged, never fatal - that source simply
     *  stays keyword-only. */
    private fun reindexEmbeddings(ctx: Context, name: String, chunks: List<String>?) {
        val appCtx = ctx.applicationContext
        synchronized(embeddingInFlight) {
            if (embeddingInFlight.contains(name)) return
            embeddingInFlight.add(name)
        }
        Thread {
            try {
                if (chunks == null || chunks.isEmpty())
                    NcieEmbed.deleteIndex(appCtx, name)
                else if (NcieEmbed.indexSize(appCtx, name) != chunks.size)
                    NcieEmbed.indexChunks(appCtx, name, chunks)
            } catch (e: Exception) {
                android.util.Log.w("NcieGround", "embedding index failed for " + name, e)
            } finally {
                synchronized(embeddingInFlight) { embeddingInFlight.remove(name) }
            }
        }.start()
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
