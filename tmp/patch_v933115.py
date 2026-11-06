#!/usr/bin/env python3
"""v9.33.115 "A re-arm is not a new alarm".

Three copies of the reminder re-arm loop existed: the launch path in
MainActivity, the boot receiver, and the A4 watchdog. Every one of them
called Reminder.schedule, and Reminder.schedule minted a FRESH alarm id
(ReminderStore.nextId) on every call.

An AlarmManager PendingIntent is identified by (receiver, request code). A
new request code is therefore a different alarm, and nothing cancels the old
one - so arming the same reminder again did not replace its alarm, it added
a second one. MainActivity re-armed every stored reminder on EVERY launch,
so opening NOVA twice meant two notifications for one reminder, three
launches meant three, and each press of "system check" (the watchdog) added
one more. The id counter climbed with every launch for the same reason.

v9.33.89 also replaced the add-the-interval roll-forward loop with
NcieDeadlines.nextFire "everywhere it happens" - but its sweep listed four
files and MainActivity.kt was not one of them, so the hang it removed was
still sitting on the launch path, waiting for a tiny or corrupt repeat
interval.

A reminder now owns its alarm id from the moment it is stored. Every re-arm
reuses that id, so arming the same reminder again REPLACES its alarm
(FLAG_UPDATE_CURRENT) instead of adding another, and the pass is idempotent -
which is what all three callers always claimed it was. One re-arm function,
one arming rule, no loop.

Self-verifying: every anchor must match exactly once, and the finished tree
is checked for the new markers before the script exits 0. Run from the repo
root (or pass it): python3 tmp/patch_v933115.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")

fails = []


def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        fails.append(name)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def patch(path, pairs):
    text = read(path)
    for old, new in pairs:
        n = text.count(old)
        if n != 1:
            print("FATAL: anchor matched %d times in %s:\n%s" % (n, path, old))
            sys.exit(1)
        text = text.replace(old, new)
    write(path, text)
    return text


STORE = BASE + "ReminderStore.kt"
RECV = BASE + "ReminderReceiver.kt"
MAIN = BASE + "MainActivity.kt"
WATCH = BASE + "ncie/android/NcieWatchdog.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
TEST = os.path.join(ROOT, "android/app/src/test/java/org/nova/ReminderStoreTest.kt")

# ------------------------------------------------------------- the store
OLD_STORE = '''    fun add(ctx: Context, atMillis: Long, text: String, repeatMs: Long) {
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
'''

NEW_STORE = '''    /**
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
'''

OLD_TAIL = '''        val base = (now / 1000L) % 1_000_000_000L
        return if (base < 1L) 1 else base.toInt()
    }
}
'''

NEW_TAIL = '''        val base = (now / 1000L) % 1_000_000_000L
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
'''

OLD_BOOT = '''        if (intent.action != android.content.Intent.ACTION_BOOT_COMPLETED) return
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
'''

NEW_BOOT = '''        if (intent.action != android.content.Intent.ACTION_BOOT_COMPLETED) return
        // v9.33.115: one re-arm pass for the launch path, the boot receiver
        // and the watchdog. It reuses each reminder's own alarm id, so a
        // reboot re-arms what is on disk instead of adding a second copy of
        // every reminder beside the one it just restored.
        try { ReminderStore.rearm(context) } catch (e: Exception) { NovaLog.quiet("ReminderStore", e) }
'''

store = patch(STORE, [(OLD_STORE, NEW_STORE), (OLD_TAIL, NEW_TAIL), (OLD_BOOT, NEW_BOOT)])

# ------------------------------------------------------- the scheduler
recv = patch(RECV, [
    (
        '''    fun schedule(context: Context, atMillis: Long, text: String, repeatMs: Long = 0L) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // v7.6: unique ID from the store - hash collisions silently
        // overwrote alarms
        val id = ReminderStore.nextId(context)
        val intent = Intent(context, ReminderReceiver::class.java)''',
        '''    fun schedule(context: Context, atMillis: Long, text: String, repeatMs: Long, id: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // v9.33.115 "A re-arm is not a new alarm": the id is the reminder's
        // OWN (minted once by ReminderStore.add), never a fresh one. A new
        // request code is a new alarm, so minting one here meant every
        // re-arm ADDED a copy of the reminder instead of replacing the alarm
        // already set - one reminder, several notifications at the same
        // minute. v7.6's unique ids are still unique: they are just handed
        // out once per reminder now, not once per arming.
        val intent = Intent(context, ReminderReceiver::class.java)'''),
    (
        '''                var next = org.nova.ncie.android.NcieDeadlines.nextFire(base, repeatMs, now)
                if (next <= now) next += repeatMs
                Reminder.schedule(context, next, text, repeatMs)''',
        '''                var next = org.nova.ncie.android.NcieDeadlines.nextFire(base, repeatMs, now)
                if (next <= now) next += repeatMs
                // v9.33.115: the same id, so the next occurrence replaces
                // this alarm and its notification rather than stacking on top
                Reminder.schedule(context, next, text, repeatMs, id)'''),
])

# --------------------------------------------------------- the launch path
main = patch(MAIN, [
    (
        '''        scope.launch(Dispatchers.IO) {
            try {
                val nowMs = System.currentTimeMillis()
                for ((at, t, rep) in ReminderStore.load(this@MainActivity)) {
                    val next = when {
                        rep > 0 -> { var n = at; while (n <= nowMs) n += rep; n }
                        at > nowMs -> at
                        else -> continue
                    }
                    Reminder.schedule(this@MainActivity, next, t, rep)
                }
            } catch (e: Exception) { NovaLog.quiet("MainActivity", e) }''',
        '''        scope.launch(Dispatchers.IO) {
            try {
                // v9.33.115 "A re-arm is not a new alarm": this was a THIRD
                // copy of the boot re-arm, and it ran on every launch.
                // Reminder.schedule minted a fresh request code each time, so
                // every launch armed another alarm for the same reminder and
                // the user was notified once per launch. It also still
                // carried the add-the-interval loop that v9.33.89 replaced
                // "everywhere" - MainActivity was not on that sweep's list.
                ReminderStore.rearm(this@MainActivity)
            } catch (e: Exception) { NovaLog.quiet("MainActivity", e) }'''),
    (
        '''                Reminder.schedule(this, whenMs, task, repeatMs)
                ReminderStore.add(this, whenMs, task, repeatMs)''',
        '''                // v9.33.115: the reminder owns its alarm id from the
                // moment it is stored, so the alarm armed here is the one
                // every later re-arm replaces. Store first: a reminder that
                // reached the disk is re-armed at the next launch even if
                // arming it now fails.
                val rid = ReminderStore.add(this, whenMs, task, repeatMs)
                Reminder.schedule(this, whenMs, task, repeatMs, rid)'''),
])

# ------------------------------------------------------------ the watchdog
watch = patch(WATCH, [
    # the watchdog no longer arms anything itself, so its Reminder import goes
    ("import org.nova.Reminder\nimport org.nova.ReminderStore\n",
     "import org.nova.ReminderStore\n"),
    (
        '''        val now = System.currentTimeMillis()
        val sb = StringBuilder("Watchdog\\n")

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
''',
        '''        val sb = StringBuilder("Watchdog\\n")

        // ---- reminders: re-arm whatever is still on disk ----
        // v9.33.115: the re-arm rule has one home now (ReminderStore.rearm).
        // This copy was the one that also minted a new alarm id on every
        // sweep, so each "system check" added another copy of every reminder
        // and the user got one notification per press.
        val pending = try { ReminderStore.load(ctx).size } catch (e: Exception) { 0 }
        val rearmed = try { ReminderStore.rearm(ctx) } catch (e: Exception) {
            NovaLog.quiet("NcieWatchdog", e)
            0
        }
'''),
])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 282") != 1 or gradle.count("versionName '9.33.114'") != 1:
    print("FATAL: build.gradle is not at 9.33.114 (282)")
    sys.exit(1)
gradle = gradle.replace("versionCode 282", "versionCode 283")
gradle = gradle.replace("versionName '9.33.114'", "versionName '9.33.115'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit tests
test = read(TEST)
OLD_LAST = '''    @Test
    fun theClockFallbackIsStableWithinOneSecond() {
        val a = ReminderStore.nextIdFrom(null, now)
        val b = ReminderStore.nextIdFrom(null, now + 500L)
        assertEquals("same second, same base", a, b)
        assertNotEquals("the next second is a new id", a, ReminderStore.nextIdFrom(null, now + 1000L))
    }
}
'''
NEW_LAST = '''    @Test
    fun theClockFallbackIsStableWithinOneSecond() {
        val a = ReminderStore.nextIdFrom(null, now)
        val b = ReminderStore.nextIdFrom(null, now + 500L)
        assertEquals("same second, same base", a, b)
        assertNotEquals("the next second is a new id", a, ReminderStore.nextIdFrom(null, now + 1000L))
    }

    // v9.33.115 "A re-arm is not a new alarm": the one arming rule. Every
    // re-arm site (launch, boot, watchdog) asks this instead of writing its
    // own `when`, so the answer has to be right once.

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun aOneShotInTheFutureKeepsItsOwnTime() {
        assertEquals(now + 5 * day, ReminderStore.nextArm(now + 5 * day, 0L, now))
    }

    @Test
    fun aOneShotThatAlreadyFiredIsNotArmedAgain() {
        assertNull("a fired one-shot must not be re-armed", ReminderStore.nextArm(now - day, 0L, now))
    }

    @Test
    fun aRepeatStillInTheFutureKeepsItsFirstFire() {
        assertEquals(now + 5 * day, ReminderStore.nextArm(now + 5 * day, day, now))
    }

    @Test
    fun aRepeatIsAlwaysArmedStrictlyAfterNow() {
        // an alarm set for the instant it is armed is a race: the OS fires it
        // at once, so the re-arm has to land after now even when the interval
        // divides the time behind us exactly
        assertEquals(now + day, ReminderStore.nextArm(now - 3 * day, day, now))
        assertEquals(now + day, ReminderStore.nextArm(now, day, now))
    }

    @Test
    fun aTinyRepeatIntervalIsOneStepAndNotAHang() {
        // v9.33.89 replaced the add-the-interval loop because a 1ms interval
        // could hang the thread arming it. One step now, and it is a step.
        assertEquals(now + 1L, ReminderStore.nextArm(now - 1L, 1L, now))
    }

    @Test
    fun aCorruptRepeatIntervalIsNeverArmed() {
        assertNull("a decade is not a repeat interval",
            ReminderStore.nextArm(now - day, Long.MAX_VALUE, now))
        assertNull("a negative interval is not a schedule",
            ReminderStore.nextArm(now - day, -day, now))
    }
}
'''
if test.count(OLD_LAST) != 1:
    print("FATAL: the ReminderStoreTest tail anchor did not match exactly once")
    sys.exit(1)
test = test.replace(OLD_LAST, NEW_LAST)
test = test.replace(
    "import org.junit.Assert.assertNotEquals\n",
    "import org.junit.Assert.assertNotEquals\nimport org.junit.Assert.assertNull\n")
write(TEST, test)

# ------------------------------------------------- teach the verifier
OLD_89 = '''check("v9.33.89: the roll-forward is one step, everywhere it happens",
      _dl89.count("internal fun nextFire(") == 1 and
      load("ncie/android/NcieWeekly.kt").count("NcieDeadlines.nextFire(") == 1 and
      load("ncie/android/NcieWatchdog.kt").count("NcieDeadlines.nextFire(") == 1 and
      load("ReminderStore.kt").count("org.nova.ncie.android.NcieDeadlines.nextFire(") == 1)
check("v9.33.89: no add-the-interval loop survives in the app",
      sum(load(f).count("while (n <= now)") + load(f).count("while (fireAt < now)")
          for f in ["ncie/android/NcieDeadlines.kt", "ncie/android/NcieWeekly.kt",
                    "ncie/android/NcieWatchdog.kt", "ReminderStore.kt"]) == 0)
'''
NEW_89 = '''check("v9.33.89: the roll-forward is one step, everywhere it happens",
      _dl89.count("internal fun nextFire(") == 1 and
      load("ncie/android/NcieWeekly.kt").count("NcieDeadlines.nextFire(") == 1 and
      # v9.33.115 moved the watchdog's arming into ReminderStore.rearm, so
      # the watchdog no longer rolls anything forward itself
      load("ncie/android/NcieWatchdog.kt").count("NcieDeadlines.nextFire(") == 0 and
      load("ReminderStore.kt").count("org.nova.ncie.android.NcieDeadlines.nextFire(") == 1)
check("v9.33.89: no add-the-interval loop survives in the app",
      # v9.33.115: MainActivity.kt was missing from this sweep and still
      # carried the loop on the launch path. It is on the list now.
      sum(load(f).count("while (n <= now)") + load(f).count("while (fireAt < now)")
          for f in ["ncie/android/NcieDeadlines.kt", "ncie/android/NcieWeekly.kt",
                    "ncie/android/NcieWatchdog.kt", "ReminderStore.kt",
                    "MainActivity.kt", "ReminderReceiver.kt"]) == 0)
'''

OLD_V114 = '''check("v9.33.114: version is at least 9.33.114 (282)",
      version_at_least("9.33.114", 282))
'''
NEW_V114 = '''check("v9.33.114: version is at least 9.33.114 (282)",
      version_at_least("9.33.114", 282))

# v9.33.115 "A re-arm is not a new alarm": three copies of the reminder
# re-arm loop, and every one of them armed through Reminder.schedule, which
# minted a fresh alarm id per call. A new request code is a new alarm, so a
# re-arm ADDED a copy instead of replacing the one already set - one reminder
# rang once per launch of the app, and once more per "system check". One
# re-arm pass now, and the id belongs to the reminder, not to the arming.
_rs115 = load("ReminderStore.kt")
_rr115 = load("ReminderReceiver.kt")
check("v9.33.115: a reminder owns its alarm id, written once",
      _rs115.count("data class Row(val at: Long, val text: String, val rep: Long, val id: Int)") == 1 and
      _rs115.count('o.optInt("id", 0)') == 1 and
      _rs115.count('.put("id", r.id)') == 1)
check("v9.33.115: arming never mints an id",
      _rs115.count("nextId(ctx)") == 2 and
      _rr115.count("ReminderStore.nextId(") == 0 and
      load("MainActivity.kt").count("ReminderStore.nextId(") == 0 and
      _rr115.count("fun schedule(context: Context, atMillis: Long, text: String, repeatMs: Long, id: Int)") == 1)
check("v9.33.115: the re-arm pass has one definition and three callers",
      _rs115.count("fun rearm(ctx: Context): Int") == 1 and
      _rs115.count("ReminderStore.rearm(context)") == 1 and
      load("MainActivity.kt").count("ReminderStore.rearm(") == 1 and
      load("ncie/android/NcieWatchdog.kt").count("ReminderStore.rearm(ctx)") == 1 and
      load("ncie/android/NcieWatchdog.kt").count("Reminder.schedule(") == 0)
check("v9.33.115: the launch path no longer rolls a loop forward",
      load("MainActivity.kt").count("while (n <= nowMs)") == 0 and
      load("MainActivity.kt").count("var n = at; while") == 0)
check("v9.33.115: the arming rule is pure and pinned by a unit test",
      _rs115.count("internal fun nextArm(at: Long, rep: Long, now: Long): Long?") == 1 and
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ReminderStoreTest.kt"),
           encoding="utf-8").read().count("@Test") >= 13)
check("v9.33.115: version is at least 9.33.115 (283)",
      version_at_least("9.33.115", 283))
'''

ver = read(VERIFY)
if ver.count(OLD_89) != 1 or ver.count(OLD_V114) != 1:
    print("FATAL: a verifier anchor did not match exactly once")
    sys.exit(1)
ver = ver.replace(OLD_89, NEW_89).replace(OLD_V114, NEW_V114)
write(VERIFY, ver)

# ---------------------------------------------------------------- verify
print("")
check("store: a reminder carries its alarm id", "data class Row(" in store and
      'o.optInt("id", 0)' in store and '.put("id", r.id)' in store)
check("store: the id is minted once, not per arming",
      store.count("nextId(ctx)") == 2)
check("store: one re-arm pass", store.count("fun rearm(ctx: Context): Int") == 1)
check("store: one arming rule", store.count("internal fun nextArm(") == 1)
check("store: the boot receiver calls the one pass",
      "try { ReminderStore.rearm(context) }" in store and "while (n <= now)" not in store)
check("store: the load view the reports read is unchanged",
      "fun load(ctx: Context): MutableList<Triple<Long, String, Long>> =" in store)
check("scheduler: the id is a parameter", "repeatMs: Long, id: Int" in recv and
      "ReminderStore.nextId(" not in recv)
check("scheduler: a repeat re-arms with its own id",
      "Reminder.schedule(context, next, text, repeatMs, id)" in recv)
check("launch: one call, no loop", "ReminderStore.rearm(this@MainActivity)" in main and
      "while (n <= nowMs)" not in main and "Reminder.schedule(this@MainActivity" not in main)
check("launch: the new reminder arms with the id it stored",
      "val rid = ReminderStore.add(this, whenMs, task, repeatMs)" in main and
      "Reminder.schedule(this, whenMs, task, repeatMs, rid)" in main)
check("watchdog: one call, no loop", "ReminderStore.rearm(ctx)" in watch and
      "Reminder.schedule(ctx" not in watch and "val now = System" not in watch)
check("version is 9.33.115 (283)",
      "versionName '9.33.115'" in gradle and "versionCode 283" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.115", 283)' in read(VERIFY))
check("ReminderStoreTest pins the arming rule", "nextArm(" in read(TEST))
def balanced(text):
    """Braces and parens outside comments and string literals - the same scan
    verify_patches.py runs, because a naive count is fooled by both."""
    i, n = 0, len(text)
    braces = parens = 0
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        elif c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
        else:
            if c == "{":
                braces += 1
            elif c == "}":
                braces -= 1
            elif c == "(":
                parens += 1
            elif c == ")":
                parens -= 1
            i += 1
    return braces == 0 and parens == 0


for name, text in [("ReminderStore.kt", store), ("ReminderReceiver.kt", recv),
                   ("MainActivity.kt", main), ("NcieWatchdog.kt", watch),
                   ("ReminderStoreTest.kt", read(TEST))]:
    check(name + ": structure is balanced", balanced(text))
    bad = []
    for m in re.finditer(r'"(?:[^"\\]|\\.)*"', text):
        for e in re.finditer(r"\\(.)", m.group(0)):
            if e.group(1) not in "tbnr\"'\\$su":
                bad.append(e.group(1))
    check(name + ": no invalid string escapes", not bad)

print("")
if fails:
    print("PATCH FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("PATCH OK: v9.33.115 applied and self-verified")
