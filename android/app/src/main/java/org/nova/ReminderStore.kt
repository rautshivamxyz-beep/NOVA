package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.nova.ncie.android.NcieFiles
import java.io.File

/**
 * v5.4.2: reminders now survive a reboot. Reminder.schedule() only arms an
 * AlarmManager alarm, which Android discards on restart - every reminder was
 * silently lost. This store keeps them on disk; BootReceiver re-arms them
 * after the phone boots.
 */
object ReminderStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "reminders.json")

    fun add(ctx: Context, atMillis: Long, text: String, repeatMs: Long) {
        val all = load(ctx)
        if (all.none { it.first == atMillis && it.second == text }) {
            all.add(Triple(atMillis, text, repeatMs))
            save(ctx, all)
        }
    }

    fun remove(ctx: Context, atMillis: Long, text: String) {
        val all = load(ctx)
        all.removeAll { it.first == atMillis && it.second == text }
        save(ctx, all)
    }

    fun load(ctx: Context): MutableList<Triple<Long, String, Long>> {
        val out = mutableListOf<Triple<Long, String, Long>>()
        try {
            val f = file(ctx)
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    // v9.33.96: one malformed row used to throw straight out
                    // of this loop, so every reminder AFTER it disappeared
                    // from the list - and the next save() wrote that short
                    // list back to disk, deleting them for good. A reminder
                    // is the one thing here the user cannot re-derive. Each
                    // row now stands on its own: a bad one is skipped, the
                    // rest survive.
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optString("t")
                    val at = o.optLong("at", 0L)
                    if (t.isBlank() || at <= 0L) continue
                    out.add(Triple(at, t, o.optLong("rep", 0L)))
                }
            }
        } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
        return out
    }

    fun save(ctx: Context, list: List<Triple<Long, String, Long>>) {
        try {
            val arr = JSONArray()
            val now = System.currentTimeMillis()
            for ((at, t, rep) in list) {
                // drop one-shots that already fired and were never removed
                if (rep <= 0L && at < now - 60_000L) continue
                arr.put(JSONObject().put("at", at).put("t", t).put("rep", rep))
            }
            // v9.33.96: the hand-rolled save deleted the old file BEFORE it
            // retried the rename, so a second failed rename lost the whole
            // reminder list - old and new. NcieFiles.write (v9.33.90) is the
            // one atomic save in the app: it never removes the old file
            // until the new one is really in place.
            NcieFiles.write(file(ctx), arr.toString())
        } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
    }

    /** v7.6: stable unique reminder IDs - the old hashCode() ids could
     *  collide, silently overwriting an alarm with another.
     *
     *  v9.33.96: a corrupt or empty counter file used to fall back to 1, so
     *  the very next reminder reused an id an already-armed alarm was
     *  holding and FLAG_UPDATE_CURRENT replaced it - the same silent
     *  overwrite v7.6 fixed, reachable again from one bad byte. The counter
     *  is also read and written under one lock now: two reminders added in
     *  the same moment used to read the same number. */
    @Synchronized
    fun nextId(ctx: Context): Int {
        val f = File(ctx.filesDir, "reminder_id")
        val raw = try { if (f.exists()) f.readText() else null } catch (e: Exception) {
            NovaLog.quiet("ReminderStore", e); null
        }
        val n = nextIdFrom(raw, System.currentTimeMillis())
        // v9.33.103: the counter is a state file too - a truncated one is read
        // back as "no counter" and the clock-derived base takes over
        NcieFiles.write(f, n.toString())
        return n
    }

    /**
     * v9.33.96: the next reminder id, from the stored counter text.
     *
     * A counter that parses keeps counting. One that does not - missing,
     * blank, truncated, overflowed - starts from a base taken from the
     * clock instead of from 1, so a new alarm can never land on an id an
     * older, still-armed alarm is using. Pure arithmetic over [now], so a
     * JVM unit test can pin it.
     */
    internal fun nextIdFrom(raw: String?, now: Long): Int {
        val prev = raw?.trim()?.toIntOrNull()
        if (prev != null && prev >= 1 && prev < Int.MAX_VALUE) return prev + 1
        val base = (now / 1000L) % 1_000_000_000L
        return if (base < 1L) 1 else base.toInt()
    }
}

/** Re-arms all stored reminders after the phone reboots. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_BOOT_COMPLETED) return
        val now = System.currentTimeMillis()
        for ((at, t, rep) in ReminderStore.load(context)) {
            val next = when {
                rep > 0 -> {
                    // v9.33.89: one-step roll-forward - the old while-loop
                    // was a hang waiting for a tiny or corrupt interval.
                    // The boot re-arm wants the first fire strictly after
                    // now, hence the extra step at exact equality.
                    var n = org.nova.ncie.android.NcieDeadlines.nextFire(at, rep, now)
                    if (n <= now) n += rep
                    n
                }
                at > now -> at
                else -> continue
            }
            Reminder.schedule(context, next, t, rep)
        }
        // v9.8.0: pending scheduled texts survive the reboot too - fire
        // anything that came due while the phone was off, then re-arm
        // the nearest future one
        try { ScheduledSends.fireDue(context) } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
        // v9.21.0 "Reliable": re-arm the rest of NOVA's alarms too - the
        // nightly Dream consolidation and the daily recurring reminders
        // were armed only when the morning briefing ran, so a reboot
        // silently stopped them.
        try { NovaHealth.armAll(context) } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
        // v9.31.2 "Jarvis": re-arm the spoken-nudge / trigger tick too, so a
        // reboot no longer silently stops them.
        try { org.nova.ncie.android.NovaNudge.schedule(context) } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
    }
}
