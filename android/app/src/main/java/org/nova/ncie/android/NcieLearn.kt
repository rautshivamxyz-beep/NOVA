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
        try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { }
        act.needsContextCarry = true
        return true
    }

    /** The completed turn. Internal prompts (userText == null) and greetings
     *  are never recorded; short or degenerate replies never enter the cache. */
    fun record(act: MainActivity, userText: String?, reply: String) {
        if (userText == null) return
        if (SMALLTALK_REGEX.containsMatchIn(userText)) return
        val clean = reply.trim()
        if (clean.length < 30) return   // same bar as the study-Q cache
        val verdict = quality.quality(clean)
        if (!verdict.passed) return
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
                    io.execute { learner = PersistentLearner(storeFor(file)) }
                }
            }
        }
        return null
    }

    /** The file-backed LearningStore for [file]. */
    private fun storeFor(file: File) = object : LearningStore {
        override fun write(snapshot: String) {
            try {
                file.parentFile?.mkdirs()
                file.writeText(snapshot)
            } catch (e: Exception) { }
        }
        override fun read(): String? =
            try { if (file.exists()) file.readText() else null }
            catch (e: Exception) { null }
    }

    /** v0.8.1: the knowledge base changed — cached answers may be built
     *  on notes that no longer exist, so the learned cache is dropped
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                "
                 continue
                    val q = f.question.trim()
                    val name = if (q.length <= Distiller.MAX_NAME_CHARS) q
                               else q.substring(0, Distiller.MAX_NAME_CHARS) + "…"
                    try {
                        Knowledge.addDoc(ctx, name, q + "\n\n" + f.answer.trim())
                        if (Knowledge.docText(ctx, name).isNotEmpty()) continue
                    } catch (e: Exception) { }
                    // The doc did NOT land in knowledge.json — the fact
                    // goes back on probation, exactly as it was.
                    try { l.record(f) } catch (e: Exception) { }
                    unpersisted++
                }
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
