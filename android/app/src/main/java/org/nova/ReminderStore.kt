package org.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
                    val o = arr.getJSONObject(i)
                    out.add(Triple(o.optLong("at"), o.getString("t"), o.optLong("rep")))
                }
            }
        } catch (e: Exception) { }
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
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { }
    }

    /** v7.6: stable unique reminder IDs - the old hashCode() ids could
     *  collide, silently overwriting an alarm with another. */
    fun nextId(ctx: Context): Int {
        val f = File(ctx.filesDir, "reminder_id")
        var n = 1
        try { if (f.exists()) n = f.readText().trim().toInt() + 1 } catch (e: Exception) { }
        try { f.writeText(n.toString()) } catch (e: Exception) { }
        return n
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
                    var n = at
                    while (n <= now) n += rep
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
        try { ScheduledSends.fireDue(context) } catch (e: Exception) { }
    }
}
