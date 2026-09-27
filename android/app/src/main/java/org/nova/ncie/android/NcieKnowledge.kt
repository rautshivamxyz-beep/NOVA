package org.nova.ncie.android

import android.content.Context
import org.nova.Knowledge
import org.nova.Settings
import org.nova.ncie.analyze.RuleBasedAnalyzer
import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.knowledge.KnowledgeStore
import org.nova.ncie.plan.DecisionKernel
import java.io.File

/**
 * NCIE Stage 3–4 (#1): the notes layer behind send() — the kernel now owns
 * the whole RAG surface for chat, not just retrieval. MainActivity calls
 * [search] / [hasDocs] / [bestDocName] / [bestChunks] / [docs] /
 * [nameOnlyQuery]; inside, the kernel runs ① ANALYZE (study-question /
 * notes intent) → ② PLAN (the context budget this request deserves) →
 * ③ EXECUTE (KnowledgeStore search — the ported Knowledge.kt logic — over
 * the app's knowledge.json chunks verbatim), then the v7.6 relevance gate
 * (significant query terms must appear in the matched chunks, so one
 * shared word like "bose" no longer pulls junk notes into a citation).
 *
 * Retrieval is byte-identical to Knowledge.search/bestDocName/bestChunks:
 * same chunk list (no re-chunking), same IDF weighting, same 55%
 * qualification, same defaults (maxResults=4, maxChunks=18), same live
 * Settings exclusion. The app's prompt assembly, model-aware caps and
 * study-question rewrap are unchanged — they consume the results exactly
 * as before. The Plan's context budget is wired but not enforced yet: the
 * app's tuned caps (tiny ? 1200 : 2400) still bound injection.
 */
object NcieKnowledge {

    private val analyzer = RuleBasedAnalyzer()
    private val planner = DecisionKernel(ToolRegistry(emptyList()))
    private val store = KnowledgeStore()

    @Volatile private var lastMtime = -1L

    /** Parse knowledge.json once, off the main thread (send() would
     *  otherwise pay for it on the first message). Call alongside
     *  Knowledge.warmUp at startup. */
    fun warmUpNotes(ctx: Context) {
        refresh(ctx)
    }

    /** The kernel-routed notes search for chat: analyze → plan → search,
     *  then the relevance gate. Returns Knowledge.Chunk so every existing
     *  consumer (follow-up carry, citations, study rewrap) is unchanged. */
    fun search(ctx: Context, text: String): List<Knowledge.Chunk> {
        // ① ANALYZE ② PLAN — the request's context budget.
        val analysis = analyzer.analyze(text)
        val budget = planner.plan(analysis, cacheHit = false).contextBudgetChars
        check(budget >= 0)
        refresh(ctx)
        store.setExcluded(Settings(ctx).knowledgeExcluded)
        var hits = store.search(text, maxResults = 4)
        // v7.6 relevance gate, verbatim: significant query terms must
        // appear in the matched chunks.
        if (hits.isNotEmpty()) {
            val sigTerms = store.tokenize(text).filter { it.length > 3 }.distinct()
            val hitText = hits.joinToString(" ") { it.text }.lowercase()
            val matched = sigTerms.count { hitText.contains(it) }
            if (matched == 0 || (sigTerms.size >= 2 && matched < 2)) hits = emptyList()
        }
        return hits.map { Knowledge.Chunk(it.doc, it.text, it.text.lowercase(), normOf(it.text)) }
    }

    /** Does the knowledge base have any documents? (Parity with
     *  Knowledge.hasDocs — no exclusion filtering.) */
    fun hasDocs(ctx: Context): Boolean {
        refresh(ctx)
        return store.chunkCount > 0
    }

    /** The best-matching document name for a query, or null. (Parity with
     *  Knowledge.bestDocName — exclusion-filtered, x2 name bonus.) */
    fun bestDocName(ctx: Context, query: String): String? {
        refresh(ctx)
        store.setExcluded(Settings(ctx).knowledgeExcluded)
        return store.bestDocName(query)
    }

    /** Contiguous chapter chunks for a summary request. (Parity with
     *  Knowledge.bestChunks — the whole topic, never mixed fragments.) */
    fun bestChunks(ctx: Context, query: String, maxChunks: Int = 18): List<String> {
        refresh(ctx)
        store.setExcluded(Settings(ctx).knowledgeExcluded)
        return store.bestChunks(query, maxChunks)
    }

    /** Doc name -> chunk count, in insertion order. (Parity with
     *  Knowledge.docs — no exclusion filtering.) */
    fun docs(ctx: Context): List<Pair<String, Int>> {
        refresh(ctx)
        return store.docs()
    }

    /** True when every query term hits the document NAME — the user means
     *  the whole document, not one topic inside it. */
    fun nameOnlyQuery(query: String, doc: String): Boolean =
        store.nameOnlyQuery(query, doc)

    /** Rebuild the store when knowledge.json changed (KnowledgeActivity
     *  add/delete). A stat per message; a parse only on change. */
    private fun refresh(ctx: Context) {
        val f = File(ctx.filesDir, "knowledge.json")
        val m = if (f.exists()) f.lastModified() else -1L
        if (m == lastMtime) return
        store.rebuildChunks(KnowledgeAdapter.loadChunks(ctx))
        lastMtime = m
    }

    private fun normOf(t: String): String =
        " " + t.lowercase().replace(Regex("[^a-z0-9]+"), " ") + " "
}
