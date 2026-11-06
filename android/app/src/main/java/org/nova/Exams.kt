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

    fun load(ctx: Context): MutableList<Exam> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val out = mutableListOf<Exam>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                // v9.33.101: getJSONObject/getString throw on one bad row and
                // the catch below then returns an EMPTY list - every exam the
                // user had, gone, and the next add() saves that empty list back.
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                out.add(Exam(name, o.optLong("date", 0L)))
            }
        } catch (e: Exception) { NovaLog.quiet("Exams", e) }
        return out
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

    fun add(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.add(Exam(name, dateMs))
        save(ctx, all)
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
    fun set(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        all.add(Exam(name, dateMs))
        save(ctx, all)
    }

    fun daysLeft(dateMs: Long): Int {
        // v7.6: calendar-day difference in the local zone - the old
        // millisecond math showed "TODAY" for an exam held yesterday
        val zone = java.util.TimeZone.getDefault()
        fun day(ms: Long) = (ms + zone.getOffset(ms)) / 86_400_000L
        return (day(dateMs) - day(System.currentTimeMillis())).toInt()
    }

    /** "Physics in 12 days (14 May)" - nearest first, or null. */
    fun promptLine(ctx: Context): String? {
        // v9.33.118 "The exam is not over at nine in the morning": this
        // filtered on `dateMs > now`, i.e. the exam's exact TIMESTAMP - but
        // an exam date is a calendar DAY, stored at 09:00 by the chat parser
        // and at midnight by the date picker. From that instant the exam
        // stopped being "upcoming", so on the morning of the exam the model
        // was told nothing about it, while the Exams screen, the planner and
        // the chat countdown - which ask daysLeft - still said TODAY. One
        // rule now, the same one Planner already used.
        val upcoming = load(ctx)
            .filter { daysLeft(it.dateMs) >= 0 }
            .sortedBy { it.dateMs }
        if (upcoming.isEmpty()) return null
        return upcoming.joinToString("; ") { e ->
            val d = daysLeft(e.dateMs)
            (if (d <= 0) "${e.name} TODAY " else "${e.name} in $d days ") +
                "(${SimpleDateFormat("d MMM", Locale.US).format(Date(e.dateMs))})"
        }
    }
}
