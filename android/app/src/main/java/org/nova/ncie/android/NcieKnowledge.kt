package org.nova.ncie.android

import android.content.Context
import org.nova.Knowledge
import org.nova.Settings
import org.nova.ncie.analyze.RuleBasedAnalyzer
import org.nova.ncie.execute.ToolRegistry
import org.nova.ncie.knowledge.KnowledgeStore
import org.nova.ncie.learn.Learner
import org.nova.ncie.plan.AdaptiveKernel
import org.nova.ncie.plan.DecisionKernel
import org.nova.ncie.plan.Planner
import java.io.File

/**
 * NCIE Stage 3–4 (#1): the notes layer behind send() — the kernel now owns
 * the whole RAG surface for chat, not just retrieval. The v0.7.0 polish
 * finishes the boundary: every knowledge access in the app — chat search,
 * the summarizers' chunk pulls, the notes filter, the import/export flow
 * in KnowledgeActivity, the save-to-knowledge share action — goes through
 * this object. MainActivity and KnowledgeActivity make no direct
 * Knowledge.* calls anymore; Knowledge.kt is the app's storage
 * implementation, exactly as NovaEngine is the engine behind
 * NovaEngineAdapter. The calls are [search] / [hasDocs] / [bestDocName] /
 * [bestChunks] / [docs] / [nameOnlyQuery] / [leanContext], plus the
 * storage forwards at the bottom; inside, the kernel runs ① ANALYZE (study-question /
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
    /** v8.2.0: the base planner until the learner boots, the adaptive
     *  one after — see [adoptAdaptivePlanner]. */
    @Volatile private var planner: Planner = DecisionKernel(ToolRegistry(emptyList()))
    private val store = KnowledgeStore()

    @Volatile private var lastMtime = -1L

    /** v9.10.0 hardening: KnowledgeStore's rebuilds are internally
     *  synchronized, but its READS (search / bestDocName / bestChunks /
     *  docs / chunkCount) are not - a refresh() rebuild on one thread
     *  while another thread is mid-search could hit a chunk list that
     *  is being swapped underneath it. This lock makes every
     *  refresh+read pair below atomic: rebuild and search never
     *  interleave. */
    private val storeLock = Any()

    /** Parse knowledge.json once, off the main thread (send() would
     *  otherwise pay for it on the first message). Call alongside
     *  Knowledge.warmUp at startup. */
    fun warmUpNotes(ctx: Context) {
        refresh(ctx)
    }

    /** v8.2.0 (#10 rules): swap the base planner for the adaptive one
     *  once the learner is live — the Mastery Rule takes over from there.
     *  Idempotent; before the first boot the base budgets apply exactly
     *  as before. */
    @Volatile private var adaptivePlanner: Planner? = null
    fun adoptAdaptivePlanner(l: Learner) {
        if (adaptivePlanner != null) return
        val p = AdaptiveKernel(planner, l)
        adaptivePlanner = p
        this.planner = p
    }

    /** v8.4.0 (stage 1): the question's significant terms - the kernel
     *  analyzer's keywords. The ONLY thing the online fetch ever sends. */
    fun keyTerms(text: String): List<String> = analyzer.analyze(text).keywords

    /** The kernel-routed notes search for chat: analyze → plan → search,
     *  then the relevance gate. Returns Knowledge.Chunk so every existing
     *  consumer (follow-up carry, citations, study rewrap) is unchanged. */
    fun search(ctx: Context, text: String): List<Knowledge.Chunk> {
        // ① ANALYZE ② PLAN — the request's context budget.
        val analysis = analyzer.analyze(text)
        val budget = planner.plan(analysis, cacheHit = false).contextBudgetChars
        // v9.10.0 hardening: this was a throwing assertion on the budget
        // (budget >= 0), which crashed the whole chat turn the moment a
        // planner returned a negative budget. A bad budget now logs and
        // the turn continues (the relevance gate and the caller's own
        // caps still bound what gets injected) - the answer still comes
        // out, it is never a crash.
        if (budget < 0)
            android.util.Log.w("NcieKnowledge",
                "planner budget " + budget + " < 0 - soft-fail, continuing")
        synchronized(storeLock) {
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
    }

    /** The kernel's context-budget verdict for a chat turn: true when the
     *  planner assigns the lean tier (plain short chat, complexity below
     *  the study threshold — budget 0; study questions and keyword pulls
     *  get 1500+). Combined with the app's tiny-model flag it picks the
     *  injection profile: lean turns get the 1200/900 caps, everything
     *  else the full 2400/1200 — the same two profiles the app has
     *  always shipped, now chosen per request by the kernel. */
    fun leanContext(text: String): Boolean =
        planner.plan(analyzer.analyze(text), cacheHit = false).contextBudgetChars < 1500

    /** v0.8.1 (#1): the kernel's thinking-budget verdict for a chat turn.
     *  The user's predictLength setting stays the master cap; the kernel
     *  only tightens the leash — lean turns (the same tier leanContext
     *  reports) get half the cap with a floor of 384 tokens, everything
     *  else — study questions, notes, documents — keeps the full cap.
     *  Internal prompts (text == null) keep the full cap too.
     *  v9.3.0 "Audit Fixes I" (audit: casual-question halving cut
     *  answers short): the halving floor is 384, not 192 - a halved
     *  casual answer still has room to actually answer. */
    fun generationBudget(text: String?, userCap: Int): Int {
        if (text == null) return userCap
        return if (leanContext(text)) (userCap / 2).coerceAtLeast(384) else userCap
    }

    /** Does the knowledge base have any documents? (Parity with
     *  Knowledge.hasDocs — no exclusion filtering.) */
    fun hasDocs(ctx: Context): Boolean {
        synchronized(storeLock) {
            refresh(ctx)
            return store.chunkCount > 0
        }
    }

    /** The best-matching document name for a query, or null. (Parity with
     *  Knowledge.bestDocName — exclusion-filtered, x2 name bonus.) */
    fun bestDocName(ctx: Context, query: String): String? {
        synchronized(storeLock) {
            refresh(ctx)
            store.setExcluded(Settings(ctx).knowledgeExcluded)
            return store.bestDocName(query)
        }
    }

    /** Contiguous chapter chunks for a summary request. (Parity with
     *  Knowledge.bestChunks — the whole topic, never mixed fragments.) */
    fun bestChunks(ctx: Context, query: String, maxChunks: Int = 18): List<String> {
        synchronized(storeLock) {
            refresh(ctx)
            store.setExcluded(Settings(ctx).knowledgeExcluded)
            return store.bestChunks(query, maxChunks)
        }
    }

    /** Doc name -> chunk count, in insertion order. (Parity with
     *  Knowledge.docs — no exclusion filtering.) */
    fun docs(ctx: Context): List<Pair<String, Int>> {
        synchronized(storeLock) {
            refresh(ctx)
            return store.docs()
        }
    }

    /** True when every query term hits the document NAME — the user means
     *  the whole document, not one topic inside it. */
    fun nameOnlyQuery(query: String, doc: String): Boolean =
        store.nameOnlyQuery(query, doc)

    // ------------------------------------------------------------------
    // Storage, exposed on the kernel boundary (v0.7.0 polish). The
    // functions below are thin forwards to Knowledge.kt — the app's
    // storage layer. Behavior is exactly Knowledge's; the point is the
    // architecture: after this, no app file calls Knowledge.* directly.
    // Knowledge remains the single writer of knowledge.json (and its
    // addDoc/removeDoc still invalidate the study-Q cache), while the
    // kernel adapter stays the single reader for chat and summarizers.
    // ------------------------------------------------------------------

    /** Parse knowledge.json once, off the main thread. (Forward.) */
    fun warmUp(c: Context) = Knowledge.warmUp(c)

    /** All chunks of one document, in order. (Forward — used by the
     *  whole-doc summarizer.) */
    fun docChunks(c: Context, name: String): List<String> =
        Knowledge.docChunks(c, name)

    /** The full text of one document. (Forward — KnowledgeActivity's
     *  export/share.) */
    fun docText(c: Context, name: String): String =
        Knowledge.docText(c, name)

    /** Index a document: replaces any older copy, invalidates the
     *  study-Q cache — and, v0.8.1, the learned cache too (answers
     *  built on the old notes must not come back as Smart Skips).
     *  (Forward — KnowledgeActivity import and the save-to-knowledge
     *  share action.) */
    fun addDoc(c: Context, name: String, text: String) {
        Knowledge.addDoc(c, name, text)
        NcieLearn.invalidate()
        // v9.6.0 "Engine Pack": the FlashMap index - refresh this
        // source's chunk-count line as the grounding layer sees it
        // (callers are already off the main thread, like the write above).
        NcieGround.indexUpdate(c, name, text)
    }

    /** Delete a document and its caches — the study-Q cache and the
     *  learned one. (Forward.) */
    fun removeDoc(c: Context, name: String) {
        Knowledge.removeDoc(c, name)
        NcieLearn.invalidate()
        // v9.6.0 "Engine Pack": the FlashMap index drops the source's line.
        NcieGround.indexUpdate(c, name, null)
    }

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
