package org.nova.ncie.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.nova.ChatStore
import org.nova.Exams
import org.nova.MainActivity
import org.nova.Msg
import org.nova.Role
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * v9.10.0 "Revision Planner": exam date tracking with a countdown, and a
 * deterministic revision plan generator (weak areas first, sources spread
 * over the remaining days).
 *
 *  - "my exam is on 15 feb" / "set exam date 2027-02-15" / "my boards
 *    start on march 5" / "boards on X": parse the date ("15 feb",
 *    "15 february", "feb 15", "2027-02-15"; no year means the next
 *    future occurrence), store it in filesDir/exam_date.txt (millis,
 *    single line), reply "Exam date set: <formatted>. N days to go."
 *    Future dates only - a past date gets an honest error.
 *  - "plan my revision" / "revision plan": build the plan fresh.
 *    Day-count N = days to the exam (capped at 30 days), day 1 is
 *    today. Due weak areas (tutor_miss.txt entries older than a day,
 *    deduped) are assigned FIRST - one per day in round-robin cycles
 *    over the days, so more areas than days simply packs a second
 *    area onto the earlier days; then the user's sources
 *    (NcieGround.docsList: Knowledge documents + offline wiki
 *    articles) are spread round-robin over the days that have no weak
 *    area yet. Every day's line lands in filesDir/revision_plan.txt as
 *    `dayIndex<TAB>dd-MMM<TAB>topic`, and the reply is the full
 *    monospace schedule plus a total line.
 *  - "show my revision plan": list the stored plan only.
 *  - "clear exam date" / "exam done" / "exam is over": delete both
 *    files.
 *  - "exam countdown" / "days to my exam": "N days to go".
 *
 * Like the tutor and the routines: fully local and deterministic, no
 * LLM call, no network, no new dependency - it works before any model
 * is loaded. The day-count math reuses Exams.daysLeft (the same
 * calendar-day difference in the local zone the exam screen uses).
 * Chat plumbing follows the NcieTutor pattern (user bubble, reply
 * bubble, chat save off-thread).
 */
object NcieExam {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private fun dateFile(ctx: Context) = File(ctx.filesDir, "exam_date.txt")
    private fun planFile(ctx: Context) = File(ctx.filesDir, "revision_plan.txt")

    // ------------------------------------------------------ date parsing

    /** Month name (full, 3-letter, "sept") -> 1..12, lowercase keys. */
    private val MONTHS = HashMap<String, Int>().apply {
        val names = arrayOf("january", "february", "march", "april", "may",
            "june", "july", "august", "september", "october", "november",
            "december")
        for ((i, n) in names.withIndex()) {
            put(n, i + 1)
            put(n.substring(0, 3), i + 1)
        }
        put("sept", 9)
    }

    /** A validated calendar for (year, month 1..12, day); null when the
     *  date does not exist (Feb 30, month 13, ...). Non-lenient, so the
     *  calendar itself refuses to normalize garbage into a real date. */
    private fun cal(year: Int, month: Int, day: Int): Calendar? = try {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, 9, 0, 0)
        c.isLenient = false
        if (c.get(Calendar.MONTH) != month - 1 ||
            c.get(Calendar.DAY_OF_MONTH) != day) null
        else c
    } catch (e: Exception) { null }

    /** The start of today in the local zone. */
    private fun todayStart(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /**
     * Parse a user-typed date - "2027-02-15", "15 feb", "15 february",
     * "feb 15", "march 5" (ordinal suffixes "15th feb" tolerated).
     * Without a year the NEXT future occurrence is used. Null when the
     * string is not a readable date at all.
     */
    internal fun parseDate(s: String): Calendar? {
        val t = s.trim().lowercase(Locale.US).trim('.', '!', '?', ',', ';', ':')
        if (t.isEmpty()) return null
        // "2027-02-15" - explicit year, no future bump
        val iso = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").find(t)
        if (iso != null) {
            return cal(iso.groupValues[1].toInt(), iso.groupValues[2].toInt(),
                iso.groupValues[3].toInt())
        }
        val today = todayStart()
        // "15 feb" / "15th february" - day first, month name after
        var m = Regex("^(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)$").find(t)
        if (m != null) {
            val mon = MONTHS[m.groupValues[2]] ?: return null
            val day = m.groupValues[1].toInt()
            var c = cal(today.get(Calendar.YEAR), mon, day)
            if (c != null && c.before(today))
                c = cal(today.get(Calendar.YEAR) + 1, mon, day)
            return c
        }
        // "feb 15" / "march 5" - month name first, day after
        m = Regex("^([a-z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?$").find(t)
        if (m != null) {
            val mon = MONTHS[m.groupValues[1]] ?: return null
            val day = m.groupValues[2].toInt()
            var c = cal(today.get(Calendar.YEAR), mon, day)
            if (c != null && c.before(today))
                c = cal(today.get(Calendar.YEAR) + 1, mon, day)
            return c
        }
        return null
    }

    // --------------------------------------------------------- storage

    /** The stored exam date in millis, or null when none is set. */
    private fun readDate(ctx: Context): Long? = try {
        val f = dateFile(ctx)
        if (!f.exists()) null
        else f.readLines().firstOrNull()?.trim()?.toLongOrNull()
    } catch (e: Exception) { null }

    /** Days between today and the exam date (calendar-day difference in
     *  the local zone - Exams.daysLeft, the same math the exam screen
     *  uses). Null when no exam date is set. */
    fun daysLeft(ctx: Context): Int? = readDate(ctx)?.let { Exams.daysLeft(it) }

    // --------------------------------------------------- the commands

    /** Set the exam date from a chat command. [dateStr] is the captured
     *  remainder ("15 feb", "2027-02-15", ...). */
    fun setExamDate(act: MainActivity, text: String, dateStr: String) {
        showUser(act, text)
        val c = parseDate(dateStr)
        if (c == null) {
            postReply(act, "I couldn't read that date - try 'my exam is on " +
                "15 feb', 'feb 15' or '2027-02-15'.")
            return
        }
        if (c.before(todayStart())) {
            postReply(act, "That date is in the past - give me a future " +
                "one, like 'my exam is on 15 feb'.")
            return
        }
        val ms = c.timeInMillis
        try {
            dateFile(act).apply { parentFile?.mkdirs() }.writeText(ms.toString() + "\n")
        } catch (e: Exception) {
            postReply(act, "Could not save the exam date.")
            return
        }
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
        val d = daysLeft(act) ?: 0
        postReply(act, "Exam date set: $fmt. $d days to go.")
    }

    /** The distinct weak-area topics with at least one miss older than a
     *  day, most-missed first, ties alphabetical (deterministic). The
     *  same tutor_miss.txt walk the briefing and "study" use: legacy
     *  "flashcards" entries are skipped, case-spelling variants of one
     *  topic are one topic (first spelling kept). */
    private fun dueWeakTopics(ctx: Context): List<String> = try {
        val f = File(ctx.filesDir, "tutor_miss.txt")
        if (!f.exists()) emptyList()
        else {
            val now = System.currentTimeMillis()
            val counts = HashMap<String, Int>()      // normalized key -> due misses
            val display = HashMap<String, String>()  // key -> first-seen spelling
            for (l in f.readLines()) {
                val p = l.split('\t', limit = 3)
                if (p.size < 3) continue
                val t = p[0].toLongOrNull() ?: continue
                val topic = p[1].trim()
                if (topic.isEmpty() || topic.equals("flashcards", ignoreCase = true)) continue
                if (now - t < DAY_MS) continue
                val key = topic.lowercase()
                counts[key] = (counts[key] ?: 0) + 1
                if (!display.containsKey(key)) display[key] = topic
            }
            counts.keys.sortedWith(
                compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
                .map { display[it]!! }
        }
    } catch (e: Exception) { emptyList() }

    /** "plan my revision" / "revision plan" - always built FRESH (a
     *  stored plan is replaced, never appended). */
    fun buildPlan(act: MainActivity) {
        val days = daysLeft(act)
        if (days == null) {
            postReply(act, "Set your exam date first: my exam is on <date>.")
            return
        }
        if (days <= 0) {
            postReply(act, "Your exam is today or already past - set a new " +
                "exam date first: my exam is on <date>.")
            return
        }
        val weak = dueWeakTopics(act)
        val sources = NcieGround.docsList(act)
        if (weak.isEmpty() && sources.isEmpty()) {
            postReply(act, "Nothing to plan — add documents or quiz yourself first.")
            return
        }
        val n = minOf(days, 30)
        // day d (0-based) holds its topics; day 0 is today
        val topics = ArrayList<ArrayList<String>>()
        for (d in 0 until n) topics.add(ArrayList())
        val placed = HashSet<String>()   // lowercase topics already on a day
        // weak areas FIRST: one per day in round-robin cycles over the
        // days - a second cycle packs a second area per day when there
        // are more areas than days
        var di = 0
        for (w in weak) {
            if (!placed.add(w.lowercase())) continue
            topics[di % n].add(w)
            di++
        }
        // then the sources, round-robin over the days that hold no weak
        // area yet (over ALL days when every day already has one)
        val srcDays = (0 until n).filter { topics[it].isEmpty() }
        val rr = if (srcDays.isEmpty()) (0 until n).toList() else srcDays
        var si = 0
        for (s in sources) {
            if (!placed.add(s.lowercase())) continue
            topics[rr[si % rr.size]].add(s)
            si++
        }
        val examMs = readDate(act) ?: System.currentTimeMillis()
        val dayFmt = SimpleDateFormat("dd-MMM", Locale.US)
        val dispFmt = SimpleDateFormat("d MMM", Locale.US)
        val sb = StringBuilder("```\nRevision plan — ")
            .append(if (days == 1) "1 day" else "$days days")
            .append(" to go (")
            .append(SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(examMs)))
            .append("):\n\n")
        var written = 0
        try {
            val lines = StringBuilder()
            for (d in 0 until n) {
                if (topics[d].isEmpty()) continue
                val day = (Calendar.getInstance() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, d)
                }
                val topic = topics[d].joinToString(" + ")
                lines.append(d + 1).append('\t')
                    .append(dayFmt.format(day.time)).append('\t')
                    .append(topic.replace("\t", " ").replace("\n", " ")).append('\n')
                sb.append("DAY ").append(d + 1).append(" (")
                    .append(dispFmt.format(day.time)).append(") — ")
                    .append(topic).append('\n')
                written++
            }
            planFile(act).apply { parentFile?.mkdirs() }.writeText(lines.toString())
        } catch (e: Exception) {
            postReply(act, "Could not save the revision plan.")
            return
        }
        sb.append("\nTotal: ").append(written).append(" of ").append(n)
            .append(" days scheduled")
        if (days > 30) sb.append(" (first 30 of ").append(days).append(" days)")
        sb.append(", ").append(weak.size).append(" weak area")
        if (weak.size != 1) sb.append("s")
        sb.append(", ").append(sources.size).append(" source")
        if (sources.size != 1) sb.append("s")
        sb.append(".\n```")
        postReply(act, sb.toString())
    }

    /** "show my revision plan" - the stored plan, list only, never
     *  rebuilt. */
    fun showPlan(act: MainActivity) {
        try {
            val f = planFile(act)
            val lines = if (f.exists()) f.readLines() else emptyList()
            if (lines.none { it.split('\t', limit = 3).size == 3 }) {
                postReply(act, "No plan yet — say 'plan my revision' after " +
                    "setting your exam date.")
                return
            }
            val sb = StringBuilder("```\nYour revision plan:\n\n")
            for (l in lines) {
                val p = l.split('\t', limit = 3)
                if (p.size < 3) continue
                sb.append("DAY ").append(p[0]).append(" (").append(p[1])
                    .append(") — ").append(p[2]).append('\n')
            }
            sb.append("```")
            postReply(act, sb.toString())
        } catch (e: Exception) {
            postReply(act, "No plan yet — say 'plan my revision' after " +
                "setting your exam date.")
        }
    }

    /** "exam countdown" / "days to my exam". */
    fun countdown(act: MainActivity) {
        val d = daysLeft(act)
        postReply(act, if (d == null)
            "Set your exam date first: my exam is on <date>."
        else "$d days to go.")
    }

    /** "clear exam date" / "exam done" / "exam is over". */
    fun clear(act: MainActivity) {
        try {
            dateFile(act).delete()
            planFile(act).delete()
        } catch (e: Exception) { }
        postReply(act, "Exam planner cleared.")
    }

    /** Today's revision topic: the FIRST plan line whose dd-MMM date is
     *  today (the plan spans at most 30 days, so a date can appear at
     *  most once). Null when there is no plan or no line for today. */
    fun todayTopic(ctx: Context): String? = try {
        val f = planFile(ctx)
        if (!f.exists()) null
        else {
            val today = SimpleDateFormat("dd-MMM", Locale.US).format(Date())
            f.readLines().firstOrNull { l ->
                val p = l.split('\t', limit = 3)
                p.size == 3 && p[1] == today
            }?.let { it.split('\t', limit = 3)[2] }
        }
    } catch (e: Exception) { null }

    // -------------------------------------------------- chat plumbing

    private fun showUser(act: MainActivity, text: String) {
        val um = Msg(Role.USER, text)
        act.currentChat.messages.add(um)
        act.adapter.add(um)
        act.scrollToEnd()
    }

    private fun postReply(act: MainActivity, body: String) {
        val reply = Msg(Role.ASSISTANT, body)
        act.currentChat.messages.add(reply)
        act.adapter.add(reply)
        act.scrollToEnd()
        act.scope.launch(Dispatchers.IO) {
            try { ChatStore.save(act, act.currentChat) } catch (e: Exception) { }
        }
    }
}
