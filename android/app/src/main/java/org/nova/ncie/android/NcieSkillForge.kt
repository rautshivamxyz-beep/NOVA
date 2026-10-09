package org.nova.ncie.android

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * v9.30.0 "Skill Forge": NOVA writes its own skills.
 *
 * The skill library (NcieSkills / SkillStore) was always data - but the
 * data was authored by hand and shipped. This is the missing half. When a
 * turn goes well and looks like a REPEATABLE request ("summarise X",
 * "translate X to Hindi", "make notes on X"), NOVA asks its own model to
 * generalise that one interaction into a reusable skill - a trigger plus
 * an instruction carrying {q} - validates it hard, and appends it to the
 * user skills file. NcieSkills picks it up on the next turn, so NOVA does
 * that KIND of request better from then on, without an app update.
 *
 * Everything is on-device. Validation is deliberately strict: a skill
 * whose trigger does not compile, duplicates an existing one, or reads
 * like a one-off answer is thrown away rather than saved.
 */
object NcieSkillForge {

    private const val MAX_FORGED = 20          // cap the library it grows
    private const val MIN_GAP_MS = 90_000L     // at most one forge per 90s
    private const val MAX_INSTRUCTION = 600

    /** NOVA's own skills live in the user file (it takes precedence). */
    fun file(ctx: Context): File = File(ctx.filesDir, "skills.txt")

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ncie-forge").apply { isDaemon = true }
    }

    @Volatile private var lastForge = 0L

    /** A procedure-ish request: a doing-verb plus a topic. */
    private val PROCEDURAL = Regex(
        "(?i)\\b(summaris|summariz|explain|translate|make (?:me )?(?:notes|a list|a table|a plan|flashcards)|" +
            "write (?:me )?(?:an? )?(?:essay|email|letter|paragraph|story|report)|" +
            "compare|list the|step by step|how (?:do|to)|convert|solve|" +
            "samjha|samjhao|likh|banao|bana do|taiyar|matlab)\\b"
    )

    /** ...but never for chit-chat. */
    private val CHITCHAT = Regex(
        "(?i)^\\s*(hi|hey|hello|thanks|thank you|ok|okay|hmm|bye|good morning|good night)\\b"
    )

    /** The names of the skills NOVA wrote itself (marked source=nova). */
    fun forged(ctx: Context): List<String> = try {
        val out = ArrayList<String>()
        var name: String? = null
        var mine = false
        for (raw in file(ctx).readText().split('\n')) {
            val line = raw.trimEnd('\r')
            if (line == "[skill]") {
                if (mine) name?.let { out.add(it) }
                name = null; mine = false; continue
            }
            if (line.startsWith("name=") && name == null) name = line.substring(5).trim()
            else if (line.startsWith("source=nova")) mine = true
        }
        if (mine) name?.let { out.add(it) }
        out
    } catch (e: Exception) { emptyList() }

    fun forgedCount(ctx: Context): Int = forged(ctx).size

    fun prompt(question: String, answer: String): String =
        "You are NOVA's skill forge. A request just went well. Turn it into a REUSABLE " +
            "skill so NOVA handles this KIND of request well in future.\n" +
            "Reply with EXACTLY three lines and nothing else:\n" +
            "name=<2 to 4 words>\n" +
            "trigger=(?i)\\b<regex that matches this KIND of request>\\b\n" +
            "instruction=<what NOVA should do, written as a general rule, with {q} where the " +
            "user's text goes>\n" +
            "The trigger must match the KIND of request (for example \"summarise X\"), NOT this " +
            "exact sentence, and must not name this specific topic. No markdown, no code fences.\n\n" +
            "Request: " + question + "\n" +
            "Answer NOVA gave: " + answer.take(400)

    /** Parse + validate a forge reply. Null when it is not worth saving. */
    fun parse(reply: String): Triple<String, String, String>? {
        var name: String? = null
        var trig: String? = null
        var ins: String? = null
        for (raw in reply.split('\n')) {
            val line = raw.trim().removePrefix("```").trim()
            if (line.startsWith("name=") && name == null) name = line.substring(5).trim()
            else if (line.startsWith("trigger=") && trig == null) trig = line.substring(8).trim()
            else if (line.startsWith("instruction=") && ins == null)
                ins = line.substring(12).replace("\\n", "\n").trim()
        }
        val n = name ?: return null
        val t = trig ?: return null
        val i = ins ?: return null
        if (n.isBlank() || n.length > 40) return null
        if (i.length < 25 || i.length > MAX_INSTRUCTION) return null
        if (!i.contains("{q}") && i.length < 60) return null   // must read as a rule
        if (t.length < 4 || t.length > 200) return null
        try { Regex(t) } catch (e: Exception) { return null }   // must compile
        return Triple(n, t, i)
    }

    /** Append a validated skill to the user file. */
    private fun append(ctx: Context, name: String, trigger: String, instruction: String): Boolean =
        try {
            file(ctx).appendText(
                "\n[skill]\nname=" + name + "\ntrigger=" + trigger +
                    "\ninstruction=" + instruction.replace("\n", "\\n") + "\nsource=nova\n"
            )
            true
        } catch (e: Exception) { false }

    /**
     * Called after a good turn. Decides whether to forge, asks the model,
     * validates, saves and reloads the library. Work runs on its own
     * background thread, so the reply is never held up.
     */
    fun maybeForge(ctx: Context, question: String?, answer: String?) {
        if (question == null || answer == null) return
        val q = question.trim()
        val a = answer.trim()
        if (q.length < 12 || q.length > 300) return
        if (a.length < 60) return
        if (CHITCHAT.containsMatchIn(q)) return
        if (!PROCEDURAL.containsMatchIn(q)) return
        NcieSkills.ensure(ctx)
        if (NcieSkills.match(q) != null) return          // a skill already covers this
        val now = System.currentTimeMillis()
        if (now - lastForge < MIN_GAP_MS) return
        if (forgedCount(ctx) >= MAX_FORGED) return
        lastForge = now
        io.execute {
            val reply = try { NovaEngineAdapter.generate(prompt(q, a), 220) } catch (e: Exception) { return@execute }
            val skill = parse(reply) ?: return@execute
            if (NcieSkills.triggerExists(skill.second)) return@execute
            if (append(ctx, skill.first, skill.second, skill.third)) {
                NcieSkills.reload(ctx)
                NcieEvolve.noteForged(ctx, skill.first)
            }
        }
    }
}
