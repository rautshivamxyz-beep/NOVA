package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.nova.ncie.android.NcieFiles

/**
 * Exam countdown: exam names + dates stored locally, shown in a screen
 * and injected into the model's prompts so NOVA always knows what's coming.
 */
object Exams {

    data class Exam(val name: String, val dateMs: Long)

    private fun file(ctx: Context): File = File(ctx.filesDir, "exams.json")

    fun load(ctx: Context): MutableList<Exam> =
        loadOrNull(ctx) ?: mutableListOf()

    /**
     * v9.33.119 "A store that could not be read is not an empty store": the
     * exams on disk, or null when the file exists but could not be read.
     *
     * v9.33.101 stopped one bad ROW from emptying the list; the bad FILE was
     * still flattened to an empty list, and [add] and [set] are
     * read-modify-write - so adding one exam to an unreadable list saved that
     * one exam over all the others. The exam dates are the user's own, and
     * nothing in the app can re-derive them.
     */
    internal fun loadOrNull(ctx: Context): MutableList<Exam>? {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val text = NcieFiles.readOrNull(f) ?: return null
        return try {
            val arr = JSONArray(text)
            val out = mutableListOf<Exam>()
            for (i in 0 until arr.length()) {
                // v9.33.101: getJSONObject/getString throw on one bad row and
                // the catch below then returns an EMPTY list - every exam the
                // user had, gone, and the next add() saves that empty list back.
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                out.add(Exam(name, o.optLong("date", 0L)))
            }
            out
        } catch (e: Exception) {
            NovaLog.quiet("Exams", e)
            null
        }
    }

    fun save(ctx: Context, exams: List<Exam>) {
        try {
            val arr = JSONArray()
            for (e in exams) arr.put(JSONObject().put("name", e.name).put("date", e.dateMs))
            // v7.6: atomic write - a crash mid-write no longer wipes the file
            // v9.33.98: the one atomic save. The hand-rolled copy below used
            // to delete the old file BEFORE retrying the rename, so a second
            // failed rename left no exam list at all.
            NcieFiles.write(file(ctx), arr.toString())
        } catch (e: Exception) { NovaLog.quiet("Exams", e) }
    }

    fun add(ctx: Context, name: String, dateMs: Long): Boolean {
        // v9.33.119: read-modify-write - never save over a failed read
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Exams", java.io.IOException("unreadable exam list - not overwriting it"))
            return false
        }
        all.add(Exam(name, dateMs))
        save(ctx, all)
        return true
    }

    /**
     * v9.33.113 "Setting the exam date again does not add a second exam":
     * one name is one exam.
     *
     * The chat path used [add], which only appends, so correcting the date -
     * the ordinary thing to do when a date moves - left the OLD row in the
     * list for ever. "Exam in 102 days (15 Feb); Exam in 135 days (20 Mar)"
     * then went into the prompt of every turn: two answers to one question,
     * and the model picked one. The Exams screen showed a row the user never
     * added, and the chat countdown (exam_date.txt) disagreed with both.
     */
    fun set(ctx: Context, name: String, dateMs: Long): Boolean {
        // v9.33.119: read-modify-write, same rule as add
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Exams", java.io.IOException("unreadable exam list - not overwriting it"))
            return false
        }
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        all.add(Exam(name, dateMs))
        save(ctx, all)
        return true
    }

    /**
     * v9.33.120: removes the exam of that name - what "exam done" does now
     * that this store is the one place an exam lives. Read-modify-write, so
     * it refuses to save over a load that failed (the v9.33.119 rule), and
     * it writes nothing when the name was not there to begin with.
     */
    fun drop(ctx: Context, name: String): Boolean {
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Exams", java.io.IOException("unreadable exam list - not overwriting it"))
            return false
        }
        val before = all.size
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        if (all.size != before) save(ctx, all)
        return true
    }

    fun daysLeft(dateMs: Long): Int {
        // v7.6: calendar-day difference in the local zone - the old
        // millisecond math showed "TODAY" for an exam held yesterday
        val zone = java.util.TimeZone.getDefault()
        fun day(ms: Long) = (ms + zone.getOffset(ms)) / 86_400_000L
        return (day(dateMs) - day(System.currentTimeMillis())).toInt()
    }

    /**
     * v9.33.120 "One exam date, one store": which exam "my exam" means.
     *
     * The chat countdown, the revision plan, the deadline radar and the
     * weekly review each ask for ONE exam date, and they read a file of
     * their own that only the chat command ever wrote - so the screen and
     * the chat could disagree about whether there was an exam at all.
     *
     * The nearest exam whose DAY is today or later is the answer: the one
     * the Exams screen lists first and the one [promptLine] puts in the
     * prompt. With nothing upcoming the most recent past exam is answered
     * rather than null, so the chat says "your exam is today or already
     * past" instead of "set your exam date first" about an exam the screen
     * is still showing. Pure, so a unit test can pin it.
     */
    fun nearest(list: List<Exam>): Exam? =
        list.filter { daysLeft(it.dateMs) >= 0 }.minByOrNull { it.dateMs }
            ?: list.maxByOrNull { it.dateMs }

    /** How many upcoming exams the model's line carries. */
    const val PROMPT_LIMIT = 3

    /** "Physics in 12 days (14 May)" - nearest first, or null. */
    fun promptLine(ctx: Context): String? = lineFor(load(ctx))

    /**
     * v9.33.122 "The exam line carries the three nearest": the exam line is
     * injected into the prompt of EVERY turn, and it used to join every
     * upcoming exam the user had. A student who adds their whole term - which
     * is the point of the Exams screen - paid for all of it on every turn, in
     * the one part of the prompt nothing can trim: the line is context, not a
     * retrieval hit, so neither the relevance gate nor the document budget
     * touches it.
     *
     * The three nearest is what the model needs - "what is coming up" is
     * about the next one - and nothing is lost from the app: the Exams
     * screen, the deadline radar and the weekly review still list every
     * exam. Pure, so a JVM test can pin the cap and the order.
     *
     * v9.33.118 "The exam is not over at nine in the morning" still holds
     * here: this filtered on `dateMs > now`, i.e. the exam's exact TIMESTAMP,
     * but an exam date is a calendar DAY stored at 09:00 by the chat parser
     * and at midnight by the date picker - so on the morning of the exam the
     * model was told nothing about it. The day is what counts.
     */
    fun lineFor(all: List<Exam>, limit: Int = PROMPT_LIMIT): String? {
        val upcoming = all
            .filter { daysLeft(it.dateMs) >= 0 }
            .sortedBy { it.dateMs }
        if (upcoming.isEmpty()) return null
        return upcoming.take(limit).joinToString("; ") { e ->
            val d = daysLeft(e.dateMs)
            (if (d <= 0) "${e.name} TODAY " else "${e.name} in $d days ") +
                "(${SimpleDateFormat("d MMM", Locale.US).format(Date(e.dateMs))})"
        }
    }
}
