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

    /**
     * v9.33.115 "A re-arm is not a new alarm": one reminder on disk, with
     * the AlarmManager request code it OWNS.
     *
     * The id used to be minted inside Reminder.schedule, i.e. once per
     * ARMING rather than once per reminder - and a PendingIntent with a new
     * request code is a different alarm, so arming again added a copy
     * instead of replacing the one already set. It is minted here, when the
     * reminder is created, and every later re-arm reuses it.
     */
    data class Row(val at: Long, val text: String, val rep: Long, val id: Int)

    /** A repeat interval longer than this is corrupt, not a schedule. */
    private const val MAX_REPEAT = 10L * 366L * 24L * 60L * 60L * 1000L

    /** Stores one reminder and answers the alarm id it owns. */
    fun add(ctx: Context, atMillis: Long, text: String, repeatMs: Long): Int {
        val all = loadRows(ctx)
        val existing = all.firstOrNull { it.at == atMillis && it.text == text }
        if (existing != null) return existing.id
        val id = nextId(ctx)
        all.add(Row(atMillis, text, repeatMs, id))
        write(ctx, all)
        return id
    }

    fun remove(ctx: Context, atMillis: Long, text: String) {
        val all = loadRows(ctx)
        all.removeAll { it.at == atMillis && it.text == text }
        write(ctx, all)
    }

    /**
     * Every reminder on disk with the alarm id it owns. An id of 0 means the
     * row was written before v9.33.115; [rearm] mints one for it and saves.
     */
    fun loadRows(ctx: Context): MutableList<Row> {
        val out = mutableListOf<Row>()
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
                    out.add(Row(at, t, o.optLong("rep", 0L), o.optInt("id", 0)))
                }
            }
        } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
        return out
    }

    /** The reporting view of the store - (at, text, repeat) - what the
     *  deadline radar, the weekly review and the spoken nudge read. */
    fun load(ctx: Context): MutableList<Triple<Long, String, Long>> =
        loadRows(ctx).map { Triple(it.at, it.text, it.rep) }.toMutableList()

    private fun write(ctx: Context, list: List<Row>) {
        try {
            val arr = JSONArray()
            val now = System.currentTimeMillis()
            for (r in list) {
                // drop one-shots that already fired and were never removed
                if (r.rep <= 0L && r.at < now - 60_000L) continue
                arr.put(JSONObject().put("at", r.at).put("t", r.text)
                    .put("rep", r.rep).put("id", r.id))
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

    /**
     * v9.33.115: when a stored reminder should be armed next, or null when
     * there is nothing left to arm (a one-shot that already fired).
     *
     * The roll-forward itself stays in NcieDeadlines.nextFire - one step,
     * never a loop, so a tiny or corrupt repeat interval cannot hang the
     * thread that is arming. This adds the one thing every re-arm site needs
     * and each of the three copies wrote out by hand: the fire has to be
     * STRICTLY after now, because an alarm set for the instant it is armed
     * is a race the OS settles by firing at once. Pure arithmetic, so a JVM
     * unit test can pin it.
     */
    internal fun nextArm(at: Long, rep: Long, now: Long): Long? {
        if (rep <= 0L) return if (at > now) at else null
        if (rep > MAX_REPEAT) return null          // corrupt interval - never armed
        val first = org.nova.ncie.android.NcieDeadlines.nextFire(at, rep, now)
        if (first > now) return first
        return first + rep                          // strictly after now, one step
    }

    /**
     * v9.33.115 "A re-arm is not a new alarm": the ONE re-arm pass.
     *
     * Three copies of this loop existed - the launch path in MainActivity,
     * the boot receiver and the A4 watchdog - and every one of them went
     * through Reminder.schedule, which minted a fresh request code per call.
     * A PendingIntent with a new request code is a DIFFERENT alarm and
     * nothing cancelled the old one, so arming a reminder that was already
     * armed added a second alarm for it: one reminder rang twice after two
     * launches, three times after three, and once more for every press of
     * "system check". Each row owns its id now, so a re-arm REPLACES its
     * alarm (FLAG_UPDATE_CURRENT) instead of adding another, and this pass
     * is idempotent - safe at launch, on boot and from the watchdog, which
     * is exactly what all three callers already claimed.
     *
     * Returns how many alarms were armed.
     */
    fun rearm(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val rows = loadRows(ctx)
        var armed = 0
        var minted = false
        val keep = ArrayList<Row>(rows.size)
        for (r in rows) {
            val next = nextArm(r.at, r.rep, now)
            val id = if (r.id > 0) r.id else { minted = true; nextId(ctx) }
            keep.add(Row(r.at, r.text, r.rep, id))
            if (next == null) continue              // a one-shot that already fired
            try {
                Reminder.schedule(ctx, next, r.text, r.rep, id)
                armed++
            } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
        }
        // rows written before v9.33.115 get their id exactly once
        if (minted) write(ctx, keep)
        return armed
    }
}

/** Re-arms all stored reminders after the phone reboots. */
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        if (intent.action != android.content.Intent.ACTION_BOOT_COMPLETED) return
        // v9.33.115: one re-arm pass for the launch path, the boot receiver
        // and the watchdog. It reuses each reminder's own alarm id, so a
        // reboot re-arms what is on disk instead of adding a second copy of
        // every reminder beside the one it just restored.
        try { ReminderStore.rearm(context) } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
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
