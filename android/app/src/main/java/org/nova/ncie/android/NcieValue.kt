package org.nova.ncie.android

import android.content.Context
import java.io.File

/**
 * v9.14.0 "Sharp Memory": adaptive context value.
 *
 * Every turn NOVA injects several context sources - the user's notes, the
 * offline Wikipedia background, the experience line, the rolling summary.
 * Some of them almost never actually help a given user's questions, yet
 * they still cost prompt tokens (prefill) on every turn.
 *
 * This tracker learns, per source, how often it was injected and how often
 * the finished answer actually used it (measured deterministically by
 * [org.nova.ncie.verify.Coverage] - the answer's significant terms must
 * appear in the injected text). A source that has been injected at least
 * [MIN_INJECT] times and NEVER helped is skipped, so the prompt shrinks and
 * the first word arrives sooner - with the answers unchanged, because a
 * source that never contributed is, by definition, not contributing.
 *
 * Safety: evidence is required before anything is skipped ([MIN_INJECT]); a
 * skipped source is re-tested one turn in [RETEST_EVERY], so it can always
 * earn its way back (no permanent lock-out); the whole thing is off with the
 * "Adaptive context" setting; and a missing or corrupt file simply means
 * "inject everything" - a performance loss, never a crash.
 */
object NcieValue {

    /** Injections required before a source may be skipped. */
    private const val MIN_INJECT = 15

    /** Re-test a skipped source one turn in this many. */
    private const val RETEST_EVERY = 8

    // source -> [injected, used]
    @Volatile private var stats: MutableMap<String, IntArray>? = null
    private var turns = 0

    private fun file(ctx: Context) = File(ctx.filesDir, "context_value.txt")

    @Synchronized
    private fun load(ctx: Context): MutableMap<String, IntArray> {
        stats?.let { return it }
        val m = HashMap<String, IntArray>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                for (line in f.readLines()) {
                    val p = line.split('\t')
                    if (p.size == 3) {
                        val inj = p[1].toIntOrNull() ?: continue
                        val use = p[2].toIntOrNull() ?: continue
                        if (inj >= 0 && use >= 0 && use <= inj) m[p[0]] = intArrayOf(inj, use)
                    }
                }
            }
        } catch (e: Exception) { }
        stats = m
        return m
    }

    @Synchronized
    private fun save(ctx: Context, m: Map<String, IntArray>) {
        try {
            val sb = StringBuilder()
            for ((k, v) in m) sb.append(k).append('\t').append(v[0]).append('\t').append(v[1]).append('\n')
            file(ctx).writeText(sb.toString())
        } catch (e: Exception) { }
    }

    /** True when [source] may be injected this turn. A source with enough
     *  evidence and no contribution is skipped, except on a re-test turn. */
    @Synchronized
    fun allow(ctx: Context, source: String): Boolean {
        val v = load(ctx)[source] ?: return true
        if (v[0] < MIN_INJECT || v[1] > 0) return true
        turns++
        return turns % RETEST_EVERY == 0
    }

    /** Record that [source] was injected this turn and whether it was used. */
    @Synchronized
    fun note(ctx: Context, source: String, used: Boolean) {
        val m = load(ctx)
        val v = m[source] ?: intArrayOf(0, 0)
        v[0] += 1
        if (used) v[1] += 1
        m[source] = v
        save(ctx, m)
    }

    /** Human-readable value summary for the Memory screen. */
    @Synchronized
    fun summary(ctx: Context): String {
        val m = load(ctx)
        if (m.isEmpty()) return "No context history yet."
        return m.entries.sortedBy { it.key }.joinToString("\n") { (k, v) ->
            "$k: injected ${v[0]}, helped ${v[1]}"
        }
    }

    /** Forget all learned values - every source is injected again. */
    @Synchronized
    fun reset(ctx: Context) {
        stats = HashMap()
        turns = 0
        try { file(ctx).delete() } catch (e: Exception) { }
    }
}
