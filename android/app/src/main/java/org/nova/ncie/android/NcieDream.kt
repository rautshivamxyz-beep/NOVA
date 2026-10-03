package org.nova.ncie.android

import android.content.Context
import org.nova.NovaListener
import org.nova.ScheduledSends
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * v9.16.11 "Dream mode": NOVA's overnight consolidation. It reads only the
 * local state other features already keep - the notification log, the
 * pending scheduled sends, the tutor weak-area log, the flashcard deck and
 * the exam planner - and writes a short "dream report"
 * (filesDir/dream_report.txt). The morning briefing shows it, so NOVA has
 * tomorrow's picture ready before you wake. Deterministic, no model call,
 * no network, nothing leaves the phone.
 */
object NcieDream {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val DREAM_REQ = 7301

    fun reportPath(ctx: Context): File = File(ctx.filesDir, "dream_report.txt")

    /**
     * Runs the consolidation now: reads the day's local state, writes the
     * report to disk and returns it as plain text (the caller wraps it).
     */
    fun run(ctx: Context): String {
        val sb = StringBuilder("DREAM REPORT - ")
        sb.append(SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            .format(Calendar.getInstance().time)).append('\n')

        // notifications since midnight - NovaListener's local log
        var notifTotal = 0
        val byApp = HashMap<String, Int>()
        if (NovaListener.isEnabled(ctx)) {
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            for (l in NovaListener.readLog(ctx)) {
                val p = l.split('\t', limit = 4)
                if (p.size < 3) continue
                val t = p[0].toLongOrNull() ?: continue
                if (t < midnight) continue
                val app = p[1].substringAfterLast('.')
                byApp[app] = (byApp[app] ?: 0) + 1
                notifTotal++
            }
        }
        sb.append(if (notifTotal == 0) "notifications today: none\n"
            else "notifications today: " + notifTotal + " - top: " +
                byApp.entries.sortedByDescending { it.value }.take(3)
                    .joinToString(", ") { it.key + " " + it.value } + "\n")

        // pending scheduled sends
        val pending = try { ScheduledSends.load(ctx).size } catch (e: Exception) { 0 }
        sb.append("scheduled sends pending: ").append(pending).append('\n')

        // weak areas due for revision (a miss at least a day old)
        var due = 0
        try {
            val now = System.currentTimeMillis()
            val topics = HashSet<String>()
            val f = File(ctx.filesDir, "tutor_miss.txt")
            if (f.exists()) for (l in f.readLines()) {
                val p = l.split('\t', limit = 3)
                if (p.size < 3) continue
                val t = p[0].toLongOrNull() ?: continue
                val topic = p[1].trim()
                if (topic.isNotEmpty() && now - t >= DAY_MS) topics.add(topic.lowercase())
            }
            due = topics.size
        } catch (e: Exception) { }
        sb.append("weak areas due: ").append(due).append('\n')

        // flashcard deck
        var cards = 0
        try {
            val f = File(ctx.filesDir, "flashcards.txt")
            if (f.exists()) cards = f.readLines().count { it.isNotBlank() }
        } catch (e: Exception) { }
        sb.append("flashcards: ").append(cards).append('\n')

        // exam countdown + today's revision topic
        try {
            val d = NcieExam.daysLeft(ctx)
            if (d != null) {
                sb.append("days to exam: ").append(d).append('\n')
                NcieExam.todayTopic(ctx)?.let {
                    sb.append("revision today: ").append(it).append('\n')
                }
            }
        } catch (e: Exception) { }

        // v9.19.0 "Polish": the leftover state (expiring docs, reminders,
        // promises, guardian) joins the dream report.
        try {
            val digest = NcieLeftovers.dailyDigest(ctx)
            if (digest.isNotBlank()) sb.append(digest)
        } catch (e: Exception) { }
        sb.append("tomorrow: review the weak areas above, then say 'study'.")
        val text = sb.toString()
        try { reportPath(ctx).writeText(text) } catch (e: Exception) { }
        // v9.16.12: keep the nightly alarm armed (idempotent).
        schedule(ctx)
        return text
    }

    /** v9.16.12: arms the daily overnight consolidation (~03:00), so the
     *  report is ready before the morning briefing. Re-armed after each
     *  fire; the exact-alarm grant is best-effort (falls back to inexact). */
    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(ctx, DREAM_REQ,
                android.content.Intent(ctx, org.nova.DreamReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 3); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            try {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP,
                    cal.timeInMillis, pi)
            } catch (e: SecurityException) {
                am.set(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: Exception) { }
    }

    /** The last written report, or "" when none exists yet. */
    fun lastReport(ctx: Context): String = try {
        val f = reportPath(ctx)
        if (f.exists()) f.readText() else ""
    } catch (e: Exception) { "" }
}
