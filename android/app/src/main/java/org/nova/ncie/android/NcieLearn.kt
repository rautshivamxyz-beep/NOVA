package org.nova.ncie.android

import org.nova.ChatStore
import org.nova.MainActivity
import org.nova.Msg
import org.nova.Role
import org.nova.SMALLTALK_REGEX
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
 */
object NcieLearn {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ncie-learn").apply { isDaemon = true }
    }

    @Volatile private var learner: PersistentLearner? = null
    @Volatile private var bootStarted = false

    /** The quality gate: the Verifier interface's ground-truth-free
     *  quality() defaults are the gate here — no right answer needed. */
    private val quality = object : Verifier {
        override fun verify(analysis: Analysis, plan: Plan, answer: String) =
            VerifyResult(false, 0.5, "unused — quality() is the actual gate here")
    }

    /** Smart Skip: an exact repeat served instantly. Renders exactly like
     *  the study-Q cache block in NcieChat — user bubble, reply bubble,
     *  toast, chat save, and a context carry so follow-ups ("explain that
     *  again") are answered with transcript context, not cold. */
    fun recall(act: MainActivity, text: String): Boolean {
        val hit = learner(act)?.recall(text) ?: return false
        val um = Msg(Role.USER, text)
        act.currentChat.messages.add(um)
        act.adapter.add(um)
        val reply = Msg(Role.ASSISTANT, hit.answer)
        act.currentChat.messages.add(reply)
        act.adapter.add(reply)
        act.scrollToEnd()
        act.toast("Answer (cached from last time)")
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
        learner(act)?.record(userText, NovaResponse(
            answer = clean,
            plan = Plan(Route.LLM, null, 0, 0, "app chat turn"),
            verify = verdict,
            repaired = false,
            cacheHit = false,
            llmUsed = true,
            trace = emptyList(),
        ))
    }

    /** Lazy background boot: captures filesDir on the caller's thread, then
     *  loads the cache off it. Returns null until loaded — a miss, never
     *  a block. All learner access afterwards is main-thread only. */
    private fun learner(act: MainActivity): PersistentLearner? {
        learner?.let { return it }
        if (!bootStarted) {
            synchronized(this) {
                if (!bootStarted) {
                    bootStarted = true
                    val dir = act.filesDir
                    io.execute {
                        val file = File(dir, "ncie_learn.txt")
                        val l = PersistentLearner(object : LearningStore {
                            override fun write(snapshot: String) {
                                try {
                                    file.parentFile?.mkdirs()
                                    file.writeText(snapshot)
                                } catch (e: Exception) { }
                            }
                            override fun read(): String? =
                                try { if (file.exists()) file.readText() else null }
                                catch (e: Exception) { null }
                        })
                        learner = l
                    }
                }
            }
        }
        return null
    }
}
