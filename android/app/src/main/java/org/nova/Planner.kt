package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v8.3.0: the study planner.
 *
 * Turns what NOVA already knows - exam dates (Exams), the flashcard
 * deck (Study) and the user's own weak topics - into a concrete plan
 * for today. Pure logic, no model involved, so it is instant and
 * never wrong. Also owns the doubt journal: questions captured on the
 * fly that the user promises to clear the next day.
 *
 * Storage follows the house rules: plain JSON files, atomic
 * tmp-then-rename writes (v7.6 pattern), never trusted blindly on
 * read - a corrupt file just means "empty planner", not a crash.
 */
object Planner {

    /** A topic the user finds hard. weak: 1 shaky .. 3 cannot do it yet. */
    data class Topic(val subject: String, val name: String, var weak: Int)

    /** A question captured on the fly, to be cleared later. */
    data class Doubt(val text: String, val ts: Long, var done: Boolean)

    /** One line of today's plan. daysLeft is -1 when no exam backs it. */
    data class PlanEntry(
        val subject: String,
        val minutes: Int,
        val daysLeft: Int,
        val urgency: String
    )

    data class Today(
        val entries: List<PlanEntry>,
        val cardsDue: Int,
        val cardsTotal: Int,
        val openDoubts: List<Doubt>,
        val weakTopics: List<Topic>,
        val budget: Int
    )

    private fun plannerFile(ctx: Context): File = File(ctx.filesDir, "planner.json")
    private fun doubtsFile(ctx: Context): File = File(ctx.filesDir, "doubts.json")

    private fun writeAtomic(f: File, text: String) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------ daily budget (minutes)

    fun minutes(ctx: Context): Int {
        return try {
            val f = plannerFile(ctx)
            if (!f.exists()) 60
            else JSONObject(f.readText()).optInt("minutes", 60)
        } catch (e: Exception) {
            60
        }
    }

    fun setMinutes(ctx: Context, m: Int) {
        try {
            val f = plannerFile(ctx)
            val o = if (f.exists()) {
                try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
            } else JSONObject()
            o.put("minutes", m)
            writeAtomic(f, o.toString())
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------ weak topics

    fun topics(ctx: Context): MutableList<Topic> {
        val out = mutableListOf<Topic>()
        val f = plannerFile(ctx)
        if (!f.exists()) return out
        try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("topics") ?: return out
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                out.add(
                    Topic(
                        t.optString("subject", ""),
                        t.optString("name", ""),
                        t.optInt("weak", 2).coerceIn(1, 3)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun saveTopics(ctx: Context, list: List<Topic>) {
        try {
            val f = plannerFile(ctx)
            val o = if (f.exists()) {
                try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
            } else JSONObject()
            val arr = JSONArray()
            for (t in list) {
                arr.put(
                    JSONObject()
                        .put("subject", t.subject)
                        .put("name", t.name)
                        .put("weak", t.weak)
                )
            }
            o.put("topics", arr)
            writeAtomic(f, o.toString())
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------ doubt journal

    fun doubts(ctx: Context): MutableList<Doubt> {
        val out = mutableListOf<Doubt>()
        val f = doubtsFile(ctx)
        if (!f.exists()) return out
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                out.add(Doubt(d.optString("text", ""), d.optLong("ts", 0L), d.optBoolean("done", false)))
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun saveDoubts(ctx: Context, list: List<Doubt>) {
        try {
            val arr = JSONArray()
            for (d in list) {
                arr.put(
                    JSONObject()
                        .put("text", d.text)
                        .put("ts", d.ts)
                        .put("done", d.done)
                )
            }
            writeAtomic(doubtsFile(ctx), arr.toString())
        } catch (e: Exception) {
        }
    }

    // ------------------------------------------------ today's plan

    /**
     * Minutes go where the urgency is: each upcoming exam gets a share
     * of the daily budget weighted by 1/(daysLeft + 1), doubled if the
     * user marked weak topics under that subject. With no exams at all,
     * subjects that have weak topics split the budget evenly. Rounded
     * to 5-minute steps, minimum 5, so the numbers stay believable.
     */
    fun today(ctx: Context): Today {
        val budget = minutes(ctx)
        val exams = Exams.load(ctx)
            .filter { Exams.daysLeft(it.dateMs) >= 0 }
            .sortedBy { it.dateMs }
        val topics = topics(ctx)
        val open = doubts(ctx).filter { !it.done && it.text.isNotBlank() }

        val entries: List<PlanEntry>
        if (exams.isEmpty()) {
            val subs = topics.map { it.subject.trim().lowercase() }
                .filter { it.isNotEmpty() }.distinct()
            entries = subs.map { s ->
                val pretty = s.substring(0, 1).uppercase() + s.substring(1)
                PlanEntry(pretty, ((budget / subs.size) / 5 * 5).coerceAtLeast(5), -1, "")
            }
        } else {
            val lowerToSubj = topics.filter { it.subject.isNotBlank() }
                .associate { it.subject.trim().lowercase() to true }
            data class W(val name: String, val d: Int, val w: Double)
            val ws = exams.map { e ->
                val d = Exams.daysLeft(e.dateMs)
                val n = e.name.trim().lowercase()
                val hasWeak = lowerToSubj.containsKey(n) ||
                    lowerToSubj.keys.any { n.contains(it) }
                W(e.name, d, (1.0 / (d + 1).toDouble()) * (if (hasWeak) 2.0 else 1.0))
            }
            val sum = ws.fold(0.0) { acc, w -> acc + w.w }
            entries = ws.map { w ->
                val m = ((budget * w.w / sum).toInt() / 5 * 5).coerceAtLeast(5)
                val urg = when {
                    w.d <= 7 -> "HIGH"
                    w.d <= 21 -> "MEDIUM"
                    else -> "low"
                }
                PlanEntry(w.name, m, w.d, urg)
            }
        }

        val cards = Study.load(ctx)
        return Today(
            entries,
            cards.count { it.due <= System.currentTimeMillis() },
            cards.size,
            open,
            topics,
            budget
        )
    }
}
