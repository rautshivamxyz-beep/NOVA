package org.nova.ncie.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.nova.MainActivity
import org.nova.NovaListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * v9.1.0 "Automation I": NOVA's deterministic routines - a morning
 * briefing and a one-phrase study timer starter.
 *
 *  - "good morning" / "briefing": a monospace briefing of the day -
 *    weekday and date, battery, the notifications logged since midnight
 *    (top apps by count), the weak areas due for revision (from
 *    NcieTutor's tutor_miss.txt) and the flashcard count. Everything is
 *    read from local state that other features already maintain - no
 *    model call, no network, nothing leaves the phone.
 *  - "start study": a 45-minute timer through the exact clock-app path
 *    the phone commands use (MainActivity.startClockTimer), so the
 *    mechanism and its failure handling stay identical.
 *
 * Like PROFILE_Q / MISSED_Q / the tutor intercepts: deterministic,
 * single-session helpers that return the reply text; NcieChat owns the
 * chat plumbing around them.
 */
object NcieRoutines {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Same line format separator NcieTutor uses for flashcards.txt
     *  (unit-separator symbol, Kotlin escape like NcieTutor.kt). */
    private const val SEP = '\u241F'

    /**
     * The morning briefing - every line from local state only:
     * date, battery, notifications since midnight (top 3 apps by
     * count), weak areas due, flashcard count.
     */
    fun morningBriefing(ctx: Context): String {
        val sb = StringBuilder("```\nGOOD MORNING\n\n")
        // v9.4.0 "Audit Fixes II": the date greets the user in THEIR
        // locale, not always US English
        sb.append(SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
            .format(Calendar.getInstance().time)).append('\n')
        // battery - the sticky ACTION_BATTERY_CHANGED intent the system
        // keeps broadcastable without waking anything
        try {
            val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            sb.append(if (level >= 0 && scale > 0)
                "battery: " + (level * 100 / scale) + "%" else "battery: unknown").append('\n')
        } catch (e: Exception) {
            sb.append("battery: unknown\n")
        }
        // notifications since midnight - NovaListener's local log
        if (!NovaListener.isEnabled(ctx)) {
            sb.append("notifications: access off\n")
        } else {
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val lines = NovaListener.readLog(ctx)
            val countBy = HashMap<String, Int>()
            var total = 0
            for (l in lines) {
                val p = l.split('\t', limit = 4)
                if (p.size < 3) continue
                val t = p[0].toLongOrNull() ?: continue
                if (t < midnight) continue
                val app = p[1].substringAfterLast('.')
                countBy[app] = (countBy[app] ?: 0) + 1
                total++
            }
            if (total == 0) sb.append("notifications: none yet\n")
            else {
                val top = countBy.entries.sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(3)
                sb.append("notifications: ").append(total)
                    .append(" since midnight - top: ")
                    .append(top.joinToString(", ") { it.key + " " + it.value })
                    .append('\n')
            }
        }
        // weak areas due - tutor_miss.txt lines are millis<TAB>topic<TAB>question;
        // due = a distinct topic with at least one miss older than a day
        try {
            val dueBy = HashMap<String, Int>()
            val now = System.currentTimeMillis()
            val f = File(ctx.filesDir, "tutor_miss.txt")
            if (f.exists()) {
                for (l in f.readLines()) {
                    val p = l.split('\t', limit = 3)
                    if (p.size < 3) continue
                    val t = p[0].toLongOrNull() ?: continue
                    val topic = p[1].trim()
                    if (topic.isEmpty()) continue
                    if (now - t >= DAY_MS) dueBy[topic] = (dueBy[topic] ?: 0) + 1
                }
            }
            if (dueBy.isEmpty()) sb.append("weak areas: none due\n")
            else {
                val topics = dueBy.keys.sortedWith(
                    compareByDescending<String> { dueBy[it] ?: 0 }.thenBy { it })
                sb.append("weak areas due: ").append(topics.size)
                    .append(" (").append(topics.joinToString(", ")).append(")\n")
            }
        } catch (e: Exception) {
            sb.append("weak areas: none due\n")
        }
        // flashcards - one line = one card, front<SEP>back like NcieTutor
        var cards = 0
        try {
            val f = File(ctx.filesDir, "flashcards.txt")
            if (f.exists()) cards = f.readLines().count { l ->
                val i = l.indexOf(SEP)
                i > 0 && l.substring(0, i).isNotBlank() && l.substring(i + 1).isNotBlank()
            }
        } catch (e: Exception) { }
        sb.append(if (cards > 0) "flashcards: $cards" else "flashcards: none yet").append('\n')
        // v9.10.0 "Revision Planner": the exam countdown and today's
        // revision topic, when an exam date is set. The topic is the
        // plan line whose dd-MMM date is TODAY (deterministic: first
        // match); a day with no plan line (or no plan at all) simply
        // omits the revision line.
        try {
            val examDays = NcieExam.daysLeft(ctx)
            if (examDays != null) {
                sb.append("days to exam: ").append(examDays).append('\n')
                NcieExam.todayTopic(ctx)?.let {
                    sb.append("revision today: ").append(it).append('\n')
                }
            }
        } catch (e: Exception) { }
        sb.append("```")
        return sb.toString()
    }

    /**
     * "start study" - a 45-minute timer through the SAME clock-app path
     * the phone commands use (MainActivity.startClockTimer), so it
     * behaves exactly like "timer 45 minutes".
     */
    fun startStudy(ctx: Context, act: MainActivity): String {
        val ok = try { act.startClockTimer(45 * 60) } catch (e: Exception) { false }
        return if (ok) "45-minute study timer started. Open your notes and go."
        else "No clock app found - set the 45-minute timer yourself and go."
    }
}
