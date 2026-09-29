package org.nova.ncie.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.nova.ChatStore
import org.nova.Knowledge
import org.nova.MainActivity
import org.nova.Msg
import org.nova.Role
import org.nova.Settings
import org.nova.SMALLTALK_REGEX
import org.nova.ncie.knowledge.KnowledgeStore
import org.nova.ncie.learn.Distiller
import org.nova.ncie.learn.LearnedFact
import org.nova.ncie.learn.LearningStore
import org.nova.ncie.learn.PersistentLearner
import org.nova.ncie.model.Analysis
import org.nova.ncie.model.NovaResponse
import org.nova.ncie.model.Plan
import org.nova.ncie.model.Route
import org.nova.ncie.model.VerifyResult
import org.nova.ncie.verify.Verifier
import java.io.File
import java.util.concurrent.Executors

/**
 * NCIE v0.7.0 (#1): the LEARN phase, live in the app's chat.
 *
 * The kernel's PersistentLearner over a file in filesDir (ncie_learn.txt).
 * NcieChat asks [recall] right before the model would run — an exact
 * repeat of an already-answered turn is served instantly, zero tokens
 * (the study-Q cache's idea, generalized to every turn by the kernel) —
 * and startGeneration's completion hands the final answer to [record].
 *
 * Recording is quality-gated by the kernel's Verifier (blank replies,
 * leaked transcript headers and strict-mode boilerplate never enter the
 * cache), filtered to real user turns (internal prompts pass userText =
 * null; greetings are handled by the smalltalk branch and never recalled,
 * so they are not recorded either), written asynchronously on a daemon
 * thread, and capped by the learner's own LRU. Boot is lazy and
 * fail-soft: the first turn after a cold start is always a miss while
 * the cache loads in the background — nothing ever blocks the chat.
 *
 * v0.8.1: [invalidate] — the kernel learner's clear() seam, called when
 * the knowledge base changes (NcieKnowledge.addDoc/removeDoc). Answers
 * learned from notes that no longer exist must never come back as Smart
 * Skips. Records join the learner's own thread, so an invalidate can
 * never interleave with a persist.
 */
object NcieLearn {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ncie-learn").apply { isDaemon = true }
    }

    /** v0.9.1 phase 2: memory-screen callbacks are delivered here,
     *  where a View can be updated. */
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var learner: PersistentLearner? = null
    @Volatile private var bootStarted = false
    @Volatile private var learnFile: java.io.File? = null
    /** v8.1.0 (opt #5): the debounced disk the learner persists through
     *  (null until boot). Held here so the memory screen's destructive
     *  operations can force an immediate flush. */
    @Volatile private var disk: DebouncedDisk? = null

    /** The quality gate: the Verifier interface's ground-truth-free
     *  quality() defaults are the gate here — no right answer needed. */
    private val quality = object : Verifier {
        override fun verify(analysis: Analysis, plan: Plan, answer: String) =
            VerifyResult(false, 0.5, "unused — quality() is the actual gate here")
    }

    /** Smart Skip: an exact repeat served instantly — and, since kernel
     *  v0.8.0, a differently-worded one too. The exact recall runs first;
     *  only a miss falls through to the kernel's fuzzy recall, whose
     *  covering rule guarantees the cached question was at least as
     *  specific as the ask ("explain federalism" finds the answer
     *  recorded under "what is federalism"; "explain federalism in
     *  india" stays a miss until india was actually covered). Renders
     *  exactly like the study-Q cache block in NcieChat — user bubble,
     *  reply bubble, toast, chat save, and a context carry so follow-ups
     *  ("explain that again") are answered with transcript context. */
    fun recall(act: MainActivity, text: String): Boolean {
        val l = learner(act) ?: return false
        val exact = l.recall(text)
        val fuzzy = if (exact == null) l.recallFuzzy(text) else null
        val answer = exact?.answer ?: fuzzy?.second?.answer ?: return false
        val um = Msg(Role.USER, text)
        act.currentChat.messages.add(um)
        act.adapter.add(um)
        val reply = Msg(Role.ASSISTANT, answer)
        act.currentChat.messages.add(reply)
        act.adapter.add(reply)
        act.scrollToEnd()
        act.toast(if (exact != null) "Answer (cached from last time)"
                  else "Answer (cached from a similar question)")
        // v8.1.0 (opt #7): the chat save is file I/O - it moves off the
        // caller's (main) thread, where it had no business sitting
        io.execute {
            try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { }
        }
        act.needsContextCarry = true
        return true
    }

    /** The completed turn. Internal prompts (userText == null) and greetings
     *  are never recorded; short or degenerate replies never enter the cache. */
    /** v8.2.0: [sources] are the note chunks that grounded the answer —
     *  the same texts the prompt was built from, so the kernel's quality
     *  gate can score the answer against them (continuous score + drift
     *  rejection, exactly like kernel turns). */
    fun record(act: MainActivity, userText: String?, reply: String,
               sources: List<String> = emptyList()) {
        if (userText == null) return
        if (SMALLTALK_REGEX.containsMatchIn(userText)) return
        val clean = reply.trim()
        if (clean.length < 30) return   // same bar as the study-Q cache
        val verdict = quality.quality(clean, sources)
        if (!verdict.passed) {
            // v8.4.0 (stages 2+4): a rejected answer is a signal now -
            // its topic's failure count grows (the Struggle Rule spends a
            // bigger budget there next time), and a turn with nothing
            // behind it (no sources, no skill) is a capability gap.
            learner(act)?.let { l -> io.execute { l.noteFailure(userText) } }
            if (sources.isEmpty() && act.lastSkillMatched == null) noteGap(act, userText)
            return
        }
        val l = learner(act) ?: return
        val response = NovaResponse(
            answer = clean,
            plan = Plan(Route.LLM, null, 0, 0, "app chat turn"),
            verify = verdict,
            repaired = false,
            cacheHit = false,
            llmUsed = true,
            trace = emptyList(),
        )
        // v0.8.1: records join the learner's own thread, so an
        // invalidate() can never interleave with a persist.
        io.execute { l.record(userText, response) }
        // v0.9.1 (phase 2): the same quality-gated turn is ALSO born as a
        // graded fact — the kernel's memory system gets what the answer
        // cache already got. verdict.qualityScore is a Double (see
        // VerifyResult in Types.kt); with this stub Verifier a clean
        // quality() pass scores 0.6 — the kernel's convention for a
        // quality pass with no ground truth — so the fact is born with
        // exactly the score of the gate that admitted it.
        io.execute { l.record(LearnedFact.fromChat(userText, clean, verdict.qualityScore)) }
    }

    /** Lazy background boot: captures filesDir on the caller's thread, then
     *  loads the cache off it. Returns null until loaded — a miss, never
     *  a block. All learner access afterwards is main-thread only. */
    private fun learner(ctx: Context): PersistentLearner? {
        learner?.let { return it }
        if (!bootStarted) {
            synchronized(this) {
                if (!bootStarted) {
                    bootStarted = true
                    val dir = ctx.filesDir
                    val file = File(dir, "ncie_learn.txt")
                    learnFile = file
                    io.execute {
                        val d = storeFor(file)
                        disk = d
                        val l = PersistentLearner(d)
                        learner = l
                        // v8.2.0 (#10 rules): the live learner upgrades the
                        // planner — the Mastery Rule applies from here on
                        NcieKnowledge.adoptAdaptivePlanner(l)
                    }
                }
            }
        }
        return null
    }

    /** v8.1.0 (opt #5): a DEBOUNCED file-backed LearningStore. The
     *  learner persists the FULL snapshot after every record, so a
     *  chatty session rewrote the whole file every turn. Writes now
     *  coalesce: the latest snapshot lands ~2s after the last record,
     *  and the explicit flushes (invalidate / clear / distill) write
     *  immediately so a wipe never sits unflushed on disk. */
    private class DebouncedDisk(
        private val file: File,
        private val io: java.util.concurrent.Executor,
        private val main: Handler,
    ) : LearningStore {
        @Volatile private var pending: String? = null
        private val flusher = Runnable {
            io.execute {
                val snap = pending ?: return@execute
                pending = null
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(snap)
                } catch (e: Exception) { }
            }
        }
        override fun write(snapshot: String) {
            pending = snapshot
            main.removeCallbacks(flusher)
            main.postDelayed(flusher, 2_000)
        }
        /** Write any pending snapshot now. */
        fun flushNow() {
            main.removeCallbacks(flusher)
            flusher.run()
        }
        override fun read(): String? =
            try { if (file.exists()) file.readText() else null }
            catch (e: Exception) { null }
    }

    /** The file-backed LearningStore for [file]. */
    private fun storeFor(file: File) = DebouncedDisk(file, io, main)

    /** v0.8.1: the knowledge base changed — cached answers may be built
     *  on notes that no longer exist, so the learned cache is dropped
     *  (the same treatment Knowledge.addDoc/removeDoc give the study-Q
     *  cache). The disk file is deleted, the in-memory learner runs
     *  clear() — wiping memory and writing the empty snapshot — and a
     *  boot that has not happened yet finds nothing to load. Runs on
     *  the learner's own thread, so it can never interleave with a
     *  record. */
    fun invalidate() {
        io.execute {
            val f = learnFile ?: return@execute
            try { f.delete() } catch (e: Exception) { }
            learner?.clear()
            disk?.flushNow()
        }
    }

    // ------------------------------------------------------------------
    // v0.9.1 phase 2 — the memory screen's API. Every call joins the
    // learner's own io thread (so it can never interleave with a record
    // or an invalidate), is fail-soft (a memory problem must never crash
    // the app), and hands its result to the caller on the main thread,
    // where a View can be updated.
    // ------------------------------------------------------------------

    /** The memory screen can be the app's entry point (it has its own
     *  launcher icon until phase 3 wires it into MainActivity), so it
     *  needs the same lazy boot the chat path gets. [ctx] is used only
     *  to locate filesDir, at call time — nothing of it is retained. */
    fun memoryBoot(ctx: Context) { learner(ctx) }

    /** The whole graded memory plus the learner's stats line, or an
     *  empty list / placeholder string while the learner still boots. */
    fun memorySnapshot(onReady: (facts: List<LearnedFact>, stats: String) -> Unit) {
        io.execute {
            val facts = try { learner?.learnedFacts() ?: emptyList() }
                        catch (e: Exception) { emptyList() }
            var stats = try { learner?.stats() ?: "memory still loading" }
                        catch (e: Exception) { "memory unavailable" }
            // v8.4.0 (stages 2+4): what NOVA knows it is bad at - the
            // weak topics and the asked-for-but-can't-do requests
            try {
                val weak = learner?.weakTopics().orEmpty().take(3)
                if (weak.isNotEmpty())
                    stats += "\nWeakest topics: " + weak.joinToString(", ") { "${it.topic} (${it.failures})" }
            } catch (e: Exception) { }
            try {
                val gaps = gapStore?.top(3).orEmpty()
                if (gaps.isNotEmpty())
                    stats += "\nAsked for, can't do: " + gaps.joinToString(", ") { g -> "'" + g.question + "' x" + g.count }
            } catch (e: Exception) { }
            post { onReady(facts, stats) }
        }
    }

    // v8.4.0 (stage 4): the capability-gap log - loaded with the
    // learner, same io thread, same filesDir
    @Volatile private var gapStore: org.nova.ncie.learn.GapStore? = null
    private fun gapFile(ctx: android.content.Context) =
        java.io.File(ctx.filesDir, "capability_gaps.txt")

    /** v8.4.0 (stage 4): note a request NOVA structurally could not
     *  answer - no tool, no skill, no sources behind it. */
    fun noteGap(ctx: android.content.Context, question: String) {
        io.execute {
            val g = gapStore ?: org.nova.ncie.learn.GapStore.parse(
                try { gapFile(ctx).readText() } catch (e: Exception) { "" }
            ).also { gapStore = it }
            g.note(question, System.currentTimeMillis())
            try { gapFile(ctx).writeText(g.serialize()) } catch (e: Exception) { }
        }
    }

    /** Forget ONE question — fact and cached answer, memory and disk.
     *  Everything else stays (the surgical opposite of [memoryClear]). */
    fun memoryForget(question: String) {
        io.execute {
            try { learner?.forget(question) } catch (e: Exception) { }
        }
    }

    /** Wipe the whole learned world — graded facts, cached answers,
     *  counters — the same clear() the kernel's invalidation uses. */
    fun memoryClear() {
        io.execute {
            try { learner?.clear() } catch (e: Exception) { }
            try { disk?.flushNow() } catch (e: Exception) { }
        }
    }

    /**
     * One consolidation pass — the graduation ceremony. A fact with
     * score >= Distiller.GRADUATION_SCORE and at least
     * Distiller.GRADUATION_INTERACTIONS confirmations leaves the graded
     * memory and becomes a knowledge-base document (its name the
     * question, its content the answer), so the app's offline RAG
     * serves it to every later answer.
     *
     * Persistence choice (deliberate, and the reason this does NOT call
     * NcieKnowledge.addDoc): that forward persists a doc AND calls
     * NcieLearn.invalidate() — which would wipe the not-yet-graduated
     * facts still on probation, exactly what a graduation must never
     * do. So the Distiller runs against a rebuilt KnowledgeStore (the
     * same boot NcieKnowledge itself uses: KnowledgeAdapter.loadChunks
     * + rebuildChunks + the user's Notes-filter exclusions), and each
     * promoted doc is then persisted through Knowledge.addDoc — the
     * app's single writer of knowledge.json, with NO learner
     * invalidation. NcieKnowledge picks the change up the usual way:
     * it stats knowledge.json per call and re-parses on mtime change.
     *
     * A promotion whose doc failed to land in knowledge.json is put
     * back into the graded memory exactly as it was — nothing is lost.
     */
    fun memoryDistill(ctx: Context, onDone: (report: String) -> Unit) {
        io.execute {
            val l = learner
            if (l == null) {
                post { onDone("Memory is still loading — try again in a moment.") }
                return@execute
            }
            try {
                val before = l.learnedFacts()

                // The same store NcieKnowledge boots: the app's chunks,
                // verbatim, plus the user's Notes-filter exclusions.
                val store = KnowledgeStore()
                store.rebuildChunks(KnowledgeAdapter.loadChunks(ctx))
                store.setExcluded(Settings(ctx).knowledgeExcluded)

                val distiller = Distiller(l, store)
                val report = distiller.distill()

                // The Distiller promoted facts into the throwaway store
                // above (the live one is NcieKnowledge's private field);
                // now make each promotion real on disk. The promoted are
                // the ones distill() forgot from the graded memory.
                val remaining = l.learnedFacts().map { it.question }.toSet()
                var unpersisted = 0
                for (f in before) {
                    if (f.question in remaining) continue
                    val q = f.question.trim()
                    // v8.1.0 (opt #8): the kernel's own name builder - same
                    // 64-char cap, but with a hash tail so two questions
                    // sharing their first characters can never overwrite
                    // each other's documents again
                    val name = distiller.docNameOf(f)
                    try {
                        Knowledge.addDoc(ctx, name, q + "\n\n" + f.answer.trim())
                        if (Knowledge.docText(ctx, name).isNotEmpty()) continue
                    } catch (e: Exception) { }
                    // The doc did NOT land in knowledge.json — the fact
                    // goes back on probation, exactly as it was.
                    try { l.record(f) } catch (e: Exception) { }
                    unpersisted++
                }
                // v9.6.0 "Engine Pack": the promotions above changed
                // knowledge.json outside the NcieKnowledge boundary - reset
                // the grounding index so the next retrieve re-reads fresh.
                NcieGround.indexReset(ctx)
                try { disk?.flushNow() } catch (e: Exception) { }
                val tail = if (unpersisted > 0)
                    " — $unpersisted promotion(s) failed to persist and were kept in memory"
                else ""
                post { onDone(report.toString() + tail) }
            } catch (e: Exception) {
                post { onDone("Consolidation failed: " + (e.message ?: "unknown error")) }
            }
        }
    }

    /** Deliver [r] on the main thread, fail-soft at every step. */
    private fun post(r: () -> Unit) {
        try {
            main.post {
                try { r() } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
    }
}
