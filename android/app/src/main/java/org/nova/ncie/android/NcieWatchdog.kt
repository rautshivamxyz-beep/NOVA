package org.nova.ncie.android

import org.nova.NovaLog

import android.content.Context
import org.nova.NovaHealth
import org.nova.NovaListener
import org.nova.Reminder
import org.nova.ReminderStore

/**
 * A4 "Watchdog" (v9.33.11): "watchdog" / "system check" / "keep nova alive" -
 * a deterministic, fully offline self-heal for the two parts of NOVA that go
 * quiet on their own: the reminders (Android drops AlarmManager alarms on a
 * force-stop) and the notification listener (Android can switch it off). No
 * model call, no network, no new permission.
 *
 *  - re-arms every reminder still on disk, the same roll-forward the boot
 *    receiver does, so a force-stop no longer silently loses them
 *  - re-arms the nightly Dream alarm and confirms it is really set
 *  - reports whether the notification listener is still connected, and how
 *    to turn it back on if it is not
 *  - reports whether Android is allowed to keep NOVA's background work alive
 *
 * Everything is file + AlarmManager work over state the app already owns, so
 * it runs before any model is loaded.
 */
object NcieWatchdog {

    fun run(ctx: Context): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder("Watchdog\n")

        // ---- reminders: re-arm whatever is still on disk ----
        var pending = 0
        var rearmed = 0
        try {
            for ((at, t, rep) in ReminderStore.load(ctx)) {
                val next = when {
                    // v9.33.89: one-step roll-forward (NcieDeadlines.nextFire)
                    // - the old while-loop was a hang waiting for a tiny
                    // interval, and this runs on every watchdog sweep
                    rep > 0L -> {
                        var n = NcieDeadlines.nextFire(at, rep, now)
                        if (n <= now) n += rep
                        n
                    }
                    at > now -> at
                    else -> continue
                }
                pending++
                try { Reminder.schedule(ctx, next, t, rep); rearmed++ } catch (e: Exception) { NovaLog.quiet("NcieWatchdog", e) }
            }
        } catch (e: Exception) { NovaLog.quiet("NcieWatchdog", e) }
        sb.append("\nReminders\n")
        sb.append("  ").append(pending).append(" pending, ")
            .append(rearmed).append(" re-armed\n")

        // ---- the nightly Dream alarm (re-arm + confirm) ----
        try { NovaHealth.armAll(ctx) } catch (e: Exception) { NovaLog.quiet("NcieWatchdog", e) }
        val dream = try { NovaHealth.alarmsArmed(ctx) } catch (e: Exception) { false }
        sb.append("  dream alarm: ").append(if (dream) "armed" else "NOT armed").append("\n")

        // ---- the notification listener ----
        val listener = try { NovaListener.isEnabled(ctx) } catch (e: Exception) { false }
        sb.append("\nNotification listener\n")
        sb.append("  ").append(if (listener) "connected" else "NOT connected").append("\n")
        if (!listener)
            sb.append("  turn it on: Settings > Notification access > NOVA\n")

        // ---- background survival ----
        val batt = try { NovaHealth.batteryOk(ctx) } catch (e: Exception) { true }
        sb.append("\nBackground\n")
        sb.append("  battery: ")
            .append(if (batt) "unrestricted" else "optimised - Android may stop reminders")
            .append("\n")

        return "```\n" + sb.toString() + "```"
    }
}
