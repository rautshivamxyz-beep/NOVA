package org.nova.ncie.android

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * v9.30.0 "Growth": NOVA's self-evolution loop.
 *
 * Three signals feed one loop, all on-device:
 *
 *   facts  - a quality-gated turn becomes a graded fact (NcieLearn); a
 *            twice-confirmed, high-scoring fact graduates into the
 *            knowledge base (Distiller), so the app's offline RAG serves
 *            it to every later answer
 *   skills - a repeatable request NOVA generalises into a skill of its
 *            own (NcieSkillForge); the next such request matches it
 *   gaps   - a request nothing could answer (GapStore) plus the weak
 *            topics the memory keeps failing (Learner.weakTopics)
 *
 * [note] records each answered turn, [grow] runs a consolidation pass on
 * idle and writes a growth report, and [report] is what the "growth"
 * command shows. Best-effort throughout: a failure here is never fatal
 * and never touches the user's turn.
 */
object NcieEvolve {

    private const val FILE = "nova_growth.txt"
    private const val MAX_LINES = 400

    fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ncie-grow").apply { isDaemon = true }
    }

    /** One ledger line per turn NOVA actually answered. */
    fun note(ctx: Context, question: String?, answer: String?) {
        if (question == null || answer == null) return
        val q = question.trim()
        if (q.length < 8 || q.length > 300) return
        NcieSkills.ensure(ctx)
        val kind = if (NcieSkills.match(q) != null) "skill" else "answer"
        io.execute {
            try {
                val f = file(ctx)
                f.appendText(
                    System.currentTimeMillis().toString() + "\t" + kind + "\t" +
                        q.replace('\n', ' ').take(140) + "\n"
                )
                val lines = f.readLines()
                if (lines.size > MAX_LINES) f.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
            } catch (e: Exception) { }
        }
    }

    /** One forged skill (called by the forge). */
    fun noteForged(ctx: Context, name: String) {
        io.execute {
            try {
                file(ctx).appendText(System.currentTimeMillis().toString() + "\tskill+\t" + name + "\n")
            } catch (e: Exception) { }
        }
    }

    /**
     * A consolidation pass - the "sleep on it" step. Distils the graded
     * memory (facts graduate into the knowledge base), then writes a
     * growth report. Safe to call from any thread; [onReport] is
     * delivered on the main thread.
     */
    fun grow(ctx: Context, onReport: ((String) -> Unit)? = null) {
        try {
            NcieLearn.memoryDistill(ctx) { distill ->
                val body = report(ctx, distill)
                try { File(ctx.filesDir, "growth_report.txt").writeText(body) } catch (e: Exception) { }
                onReport?.invoke(body)
            }
        } catch (e: Exception) {
            onReport?.invoke("Growth pass unavailable right now.")
        }
    }

    /** The growth report text. */
    fun report(ctx: Context, distill: String = ""): String {
        val sb = StringBuilder()
        sb.append("GROWTH REPORT - ")
        sb.append(SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())).append('\n')
        val skills = try { NcieSkillForge.forged(ctx) } catch (e: Exception) { emptyList() }
        val ledger = try { file(ctx).readLines() } catch (e: Exception) { emptyList() }
        val answered = ledger.count { it.contains("\tanswer\t") || it.contains("\tskill\t") }
        val recent = ledger.filter { it.contains("\tskill+\t") }
            .takeLast(5)
            .map { it.substringAfter("\tskill+\t") }
        sb.append("turns answered: ").append(answered).append('\n')
        sb.append("skills I have written myself: ").append(skills.size).append('\n')
        if (skills.isNotEmpty()) for (s in skills) sb.append("  - ").append(s).append('\n')
        if (recent.isNotEmpty()) {
            sb.append("newest: ").append(recent.joinToString(", ")).append('\n')
        }
        if (distill.isNotBlank()) sb.append('\n').append(distill.trim()).append('\n')
        return sb.toString()
    }
}
