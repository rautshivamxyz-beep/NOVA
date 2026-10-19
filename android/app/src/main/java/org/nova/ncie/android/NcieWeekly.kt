package org.nova.ncie.android

import org.nova.NovaLog

import android.content.Context
import org.nova.ReminderStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * A3 "Weekly review" (v9.33.10): "weekly review" / "week ahead" /
 * "sunday review" - a deterministic, fully offline digest of the week
 * ahead plus a snapshot of the user's own material. No model call, no
 * network, no new permission.
 *
 *  - the week ahead: scheduled reminders ([ReminderStore]) and the exam
 *    countdown ([NcieExam.daysLeft]) within the next 7 days
 *  - the material: how many knowledge documents and offline wiki
 *    articles NOVA is holding
 *
 * Everything is pure file + calendar work over data the app already
 * owns, so it works before any model is loaded.
 */
object NcieWeekly {

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun review(ctx: Context): String {
        val now = System.currentTimeMillis()
        val today = startOfToday()
        val weekEnd = today + 7 * DAY_MS
        val sb = StringBuilder("Weekly review\n")

        // ---- the week ahead ----
        val ahead = ArrayList<Pair<Long, String>>()
        try {
            for ((at, t, rep) in ReminderStore.load(ctx)) {
                var fireAt = at
                if (rep > 0L) { while (fireAt < now) fireAt += rep }
                if (fireAt in now..weekEnd) ahead.add(fireAt to t)
            }
        } catch (e: Exception) { NovaLog.quiet("NcieWeekly", e) }
        try {
            val dl = NcieExam.daysLeft(ctx)
            if (dl != null && dl in 0..7) ahead.add((today + dl * DAY_MS) to "exam")
        } catch (e: Exception) { NovaLog.quiet("NcieWeekly", e) }

        sb.append("\nComing up (next 7 days):\n")
        if (ahead.isEmpty()) {
            sb.append("  nothing scheduled\n")
        } else {
            ahead.sortBy { it.first }
            for ((at, label) in ahead) {
                val d = ((at - today) / DAY_MS).toInt()
                val whenTxt = if (d <= 0) "today" else if (d == 1) "tomorrow" else "in " + d + " days"
                sb.append("  ").append(whenTxt).append(" (").append(fmtDate(at))
                    .append(") \u2014 ").append(label).append("\n")
            }
        }

        // ---- the material ----
        val docs = try { NcieKnowledge.docs(ctx).size } catch (e: Exception) { 0 }
        val wiki = try { NcieGround.wikiNames(ctx).size } catch (e: Exception) { 0 }
        sb.append("\nYour material:\n")
        sb.append("  ").append(docs)
            .append(if (docs == 1) " knowledge document" else " knowledge documents").append("\n")
        sb.append("  ").append(wiki)
            .append(if (wiki == 1) " wiki article" else " wiki articles").append("\n")
        return "```\n" + sb.toString() + "```"
    }

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
}
