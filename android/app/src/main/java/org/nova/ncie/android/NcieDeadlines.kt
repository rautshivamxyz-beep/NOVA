package org.nova.ncie.android

import org.nova.NovaLog

import android.content.Context
import org.nova.ReminderStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A2 "Deadline radar" (v9.33.9): "what's due" / "deadlines" / "what's
 * coming up" - a deterministic, fully offline radar over the user's OWN
 * material. No model call, no network, no new permission:
 *
 *  - scheduled reminders ([ReminderStore]) - future occurrences only;
 *    a repeating reminder is rolled forward to its next fire
 *  - the exam countdown ([NcieExam.daysLeft])
 *  - explicit dates written inside the user's knowledge notes
 *
 * Everything is pure string + calendar math over files the app already
 * owns, so it works before any model is loaded and never touches the
 * network. The output is a monospace block, like the other deterministic
 * commands ("my documents", "what have you learned").
 */
object NcieDeadlines {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HORIZON_DAYS = 60L

    /** Month name stem (3-letter or "sept") -> 0-based month. */
    private val MONTHS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3,
        "may" to 4, "jun" to 5, "jul" to 6, "aug" to 7,
        "sep" to 8, "sept" to 8, "oct" to 9, "nov" to 10, "dec" to 11)

    // 2026-06-12
    private val ISO_DATE = Regex("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")
    // 12/06, 12-06, 12.06 (optional 2- or 4-digit year)
    private val NUM_DATE = Regex("\\b(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?\\b")
    // 12 june, 12 Jun, 12th june
    private val D_MON = Regex("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec)[a-z]*\\b")
    // june 12, jun 12
    private val MON_D = Regex("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec)[a-z]*\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b")

    /**
     * v9.33.89: a bare "d/m" with NO year is only a date when the note is
     * talking about one. "8.5" is an average, "1/2" is a fraction, "3-5" is
     * a range and "10.30" is a time - every one of them is a valid day and
     * month to [NUM_DATE], so the radar used to report a study note's
     * arithmetic as a deadline. The year-carrying forms are unambiguous and
     * are never gated; a bare one has to sit next to a scheduling word.
     */
    private val SCHEDULE_HINT = Regex(
        "(?i)\\b(?:due|deadline|deadlines|submit|submission|submitting|exam|exams" +
            "|test|tests|quiz|assignment|assignments|project|viva|practical|holiday" +
            "|birthday|anniversary|meeting|appointment|interview|event|events" +
            "|result|results|fee|fees|payment|admit card|last date|by|before|until|till)\\b")

    /** How far from a bare date a scheduling word still counts, in chars. */
    private const val HINT_WINDOW = 40

    /** The radar body: a monospace list of what is due in the next
     *  [HORIZON_DAYS] days, soonest first. Never throws. */
    fun radar(ctx: Context): String {
        val now = System.currentTimeMillis()
        val today = startOfToday()
        val horizon = today + HORIZON_DAYS * DAY_MS
        val items = ArrayList<Pair<Long, String>>()

        // 1. scheduled reminders
        try {
            for ((at, t, rep) in ReminderStore.load(ctx)) {
                val fireAt = nextFire(at, rep, now)
                if (fireAt in now..horizon)
                    items.add(fireAt to ("reminder: " + t + (if (rep > 0L) " (repeats)" else "")))
            }
        } catch (e: Exception) { NovaLog.quiet("NcieDeadlines", e) }

        // 2. the exam countdown
        try {
            val dl = NcieExam.daysLeft(ctx)
            if (dl != null && dl >= 0 && dl <= HORIZON_DAYS)
                items.add((today + dl * DAY_MS) to
                    ("exam in " + dl + (if (dl == 1) " day" else " days")))
        } catch (e: Exception) { NovaLog.quiet("NcieDeadlines", e) }

        // 3. explicit dates inside the knowledge notes
        try {
            for (d in NcieKnowledge.docs(ctx)) {
                val text = try { NcieKnowledge.docText(ctx, d.first) } catch (e: Exception) { "" }
                if (text.isEmpty()) continue
                for (m in ISO_DATE.findAll(text)) {
                    val at = dateAt(today, m.groupValues[3].toIntOrNull(),
                        m.groupValues[2].toIntOrNull(), m.groupValues[1]) ?: continue
                    if (onRadar(today, horizon, at)) items.add(at to (m.value.trim() + " \u2014 in \"" + d.first + "\""))
                }
                // scan the loose formats on the text with the ISO dates
                // blanked, so "2026-11-05" is not also read as "11-05"
                val plain = ISO_DATE.replace(text, " ")
                // v9.33.89: the numeric forms go through one gated pass - a
                // bare "d/m" needs a scheduling word beside it, so a note's
                // "8.5" / "1/2" / "3-5" is no longer reported as a deadline
                for ((at, label) in numericDates(today, plain)) {
                    if (onRadar(today, horizon, at))
                        items.add(at to (label + " \u2014 in \"" + d.first + "\""))
                }
                for (m in D_MON.findAll(plain)) {
                    val mo = MONTHS[m.groupValues[2].lowercase()]?.plus(1) ?: continue
                    val at = dateAt(today, m.groupValues[1].toIntOrNull(), mo, null) ?: continue
                    if (onRadar(today, horizon, at)) items.add(at to (m.value.trim() + " \u2014 in \"" + d.first + "\""))
                }
                for (m in MON_D.findAll(plain)) {
                    val mo = MONTHS[m.groupValues[1].lowercase()]?.plus(1) ?: continue
                    val at = dateAt(today, m.groupValues[2].toIntOrNull(), mo, null) ?: continue
                    if (onRadar(today, horizon, at)) items.add(at to (m.value.trim() + " \u2014 in \"" + d.first + "\""))
                }
            }
        } catch (e: Exception) { NovaLog.quiet("NcieDeadlines", e) }

        if (items.isEmpty()) return "Nothing due in the next " + HORIZON_DAYS + " days."
        items.sortBy { it.first }
        val seen = HashSet<String>()
        val sb = StringBuilder("```\nDeadlines (next " + HORIZON_DAYS + " days):\n")
        for ((at, label) in items) {
            if (!seen.add(label.lowercase())) continue
            val days = ((at - today) / DAY_MS).toInt()
            val whenTxt = when {
                days <= 0 -> "today"
                days == 1 -> "tomorrow"
                else -> "in " + days + " days"
            }
            sb.append("  ").append(whenTxt).append(" (").append(fmtDate(at))
                .append(") \u2014 ").append(label).append("\n")
        }
        sb.append("```")
        return sb.toString()
    }

    /**
     * v9.33.85: a deadline WRITTEN IN A NOTE is due from the start of today,
     * not from this instant. A date-only value resolves to midnight, so
     * "assignment due 29 Oct" compared as `at in now..horizon` was dropped
     * for the whole of the 29th - the one day it mattered most. Reminders
     * keep the `now` floor: one that already fired is done, not due.
     * Pure arithmetic, so a unit test can pin the boundary.
     */
    internal fun onRadar(today: Long, horizon: Long, at: Long): Boolean =
        at >= today && at <= horizon

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun fmtDate(ms: Long): String =
        SimpleDateFormat("d MMM", Locale.US).format(Date(ms))

    /**
     * v9.33.89: the numeric dates inside [text] the radar will trust.
     *
     * The ISO form is blanked first, so "2026-11-05" is never also read as
     * "11-05". A match that carries its own year is unambiguous and always
     * accepted; a bare day/month must sit within [HINT_WINDOW] characters of
     * a scheduling word, because on its own it is far more likely to be a
     * decimal, a fraction, a range or a time than a date.
     *
     * Pure string + calendar work over [today], so a unit test can pin it.
     */
    internal fun numericDates(today: Long, text: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val plain = ISO_DATE.replace(text, " ")
        for (m in NUM_DATE.findAll(plain)) {
            val at = dateAt(today, m.groupValues[1].toIntOrNull(),
                m.groupValues[2].toIntOrNull(), m.groupValues[3].ifBlank { null }) ?: continue
            if (m.groupValues[3].isBlank() && !nearScheduleWord(plain, m.range)) continue
            out.add(at to m.value.trim())
        }
        return out
    }

    /** True when a scheduling word sits within [HINT_WINDOW] chars of [r]. */
    internal fun nearScheduleWord(text: String, r: IntRange): Boolean {
        val from = if (r.first > HINT_WINDOW) r.first - HINT_WINDOW else 0
        val to = if (r.last + 1 + HINT_WINDOW < text.length) r.last + 1 + HINT_WINDOW else text.length
        if (to <= from) return false
        return SCHEDULE_HINT.containsMatchIn(text.substring(from, to))
    }

    /**
     * v9.33.89: the next fire time of a reminder at or after [now].
     *
     * The roll-forward used to add the interval in a loop until it passed
     * now. It is the same arithmetic, but done in one step: a stored repeat
     * interval that is tiny (or corrupt) turned that loop into a hang, and
     * this runs on the UI thread behind "what's due" and the weekly review.
     *
     * A one-shot reminder keeps its own time. Pure arithmetic, so it is
     * unit-tested.
     */
    internal fun nextFire(at: Long, rep: Long, now: Long): Long {
        if (rep <= 0L) return at
        if (at >= now) return at
        val behind = now - at
        // ceil(behind / rep): the first fire time at or after now
        val steps = if (behind % rep == 0L) behind / rep else behind / rep + 1
        return at + steps * rep
    }

    /** A date as millis, or null when the parts are not a real date. A
     *  date already past rolls to next year only when it carried no
     *  explicit year; a 2-digit year is 20xx. */
    internal fun dateAt(today: Long, day: Int?, month: Int?, year: String?): Long? {
        if (day == null || month == null) return null
        if (day !in 1..31 || month !in 1..12) return null
        val c = Calendar.getInstance()
        c.timeInMillis = today
        var y = c.get(Calendar.YEAR)
        if (year != null) {
            val yv = year.toIntOrNull() ?: return null
            y = if (year.length <= 2) 2000 + yv else yv
        }
        c.set(Calendar.YEAR, y)
        c.set(Calendar.MONTH, month - 1)
        c.set(Calendar.DAY_OF_MONTH, day)
        // reject overflow (31 feb -> 3 mar)
        if (c.get(Calendar.DAY_OF_MONTH) != day || c.get(Calendar.MONTH) != month - 1) return null
        if (year == null && c.timeInMillis < today) c.add(Calendar.YEAR, 1)
        return c.timeInMillis
    }
}
