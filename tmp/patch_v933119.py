#!/usr/bin/env python3
"""v9.33.119 "A store that could not be read is not an empty store".

Every store in NOVA parses its file defensively, so a file it cannot read
comes back as "nothing there" - an empty list. That is the right answer for a
screen that only displays: a corrupt exam list shows no exams instead of
crashing the app.

It is the wrong answer for a store that WRITES BACK what it just read. Four of
them are read-modify-write:

    ReminderStore.add      load the rows, append one, write all of them
    ReminderStore.remove   load the rows, drop one, write all of them
    Exams.add / Exams.set  load the exams, append/replace one, write them all
    Planner.setMinutes     read planner.json, set a key, write the object back
    Planner.saveTopics     same file, same shape

Give any of those an unreadable file and the load hands it an empty list, the
mutation adds the one new item to that emptiness, and the save writes the
result over the whole file. The user adds ONE reminder and loses every other
reminder they had. The reminder store's own comment calls a reminder "the one
thing here the user cannot re-derive" - and v9.33.96 hardened it against a bad
ROW while leaving the bad FILE free to do exactly that.

v9.33.112 fixed this shape once, in the recipe library, with an allOrNull()
that keeps "could not read" distinct from "nothing there". It fixed the one
store it was looking at. This is the same rule, given one home and applied to
every store that rewrites what it read:

    NcieFiles.readOrNull(f)   "" = not there yet, null = there and unreadable
    <store>OrNull(ctx)        null = could not read; the mutator writes nothing

The readers are unchanged - load() still flattens both cases to an empty list,
because showing nothing is still the right thing to do with a file that cannot
be read. Only the writers learn the difference.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933119.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/")
TESTDIR = os.path.join(ROOT, "android/app/src/test/java/org/nova/")

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


FILES_KT = BASE + "ncie/android/NcieFiles.kt"
REMINDERS = BASE + "ReminderStore.kt"
EXAMS = BASE + "Exams.kt"
PLANNER = BASE + "Planner.kt"
MAIN = BASE + "MainActivity.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
NEWTEST = os.path.join(TESTDIR, "ncie/android/NcieReadOrNullTest.kt")

# ------------------------------------------------- the one read (NcieFiles)
OLD_QUIETLY = '''    /**
     * The same write, appended to the list of best-effort saves that only
     * care that it happened. Kept for the call sites that already swallow.
     */
    fun writeQuietly(f: File, text: String) {
        write(f, text)
    }
}
'''

NEW_QUIETLY = '''    /**
     * The same write, appended to the list of best-effort saves that only
     * care that it happened. Kept for the call sites that already swallow.
     */
    fun writeQuietly(f: File, text: String) {
        write(f, text)
    }

    /**
     * v9.33.119 "A store that could not be read is not an empty store": the
     * one read, the other half of [write].
     *
     * Every store parses its file defensively, so a file it cannot read comes
     * back as "nothing there". Two answers are needed, not one:
     *
     *   ""    the file is not there yet - a fresh install, nothing to lose
     *   null  the file is there and could not be read - something to lose
     *
     * A reader may flatten both to an empty list; a store that is about to
     * rewrite the file from what it read may not. The stores that write back
     * what they read (reminders, exams, planner.json) ask this, and refuse to
     * save when the answer is null - otherwise adding one item rewrites the
     * file from an empty list and deletes everything else the user had.
     *
     * Never throws: an unreadable file is recorded and answered with null.
     */
    fun readOrNull(f: File): String? {
        if (!f.exists()) return ""
        return try {
            f.readText()
        } catch (e: Exception) {
            NovaLog.quiet("NcieFiles", e)
            null
        }
    }
}
'''

files_kt = patch(FILES_KT, [(OLD_QUIETLY, NEW_QUIETLY)])

# ------------------------------------------------------------- reminders
OLD_CONST = '''    /** A repeat interval longer than this is corrupt, not a schedule. */
    private const val MAX_REPEAT = 10L * 366L * 24L * 60L * 60L * 1000L
'''

NEW_CONST = '''    /** A repeat interval longer than this is corrupt, not a schedule. */
    private const val MAX_REPEAT = 10L * 366L * 24L * 60L * 60L * 1000L

    /**
     * v9.33.119: the alarm id a reminder that could NOT be stored is armed
     * with. [add] answers this when the reminder list exists and could not be
     * read - nothing was written over it, so there is no stored id to use.
     * It is never written to disk, so it cannot collide with a stored one;
     * the alarm still rings, it just cannot survive a reboot.
     */
    const val TRANSIENT_ID = 0
'''

OLD_LOADROWS = '''    fun loadRows(ctx: Context): MutableList<Row> {
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
'''

NEW_LOADROWS = '''    fun loadRows(ctx: Context): MutableList<Row> =
        loadRowsOrNull(ctx) ?: mutableListOf()

    /**
     * v9.33.119 "A store that could not be read is not an empty store": the
     * rows on disk, or null when the file exists but could not be read.
     *
     * v9.33.96 made one malformed ROW harmless. This makes an unreadable
     * FILE harmless too - and that is the case that actually deletes data,
     * because [add] and [remove] are read-modify-write: with an empty list
     * from a failed read they save that emptiness back, so adding one
     * reminder erased every other reminder the user had. A reminder is the
     * one thing here the user cannot re-derive.
     */
    internal fun loadRowsOrNull(ctx: Context): MutableList<Row>? {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val text = NcieFiles.readOrNull(f) ?: return null
        return try {
            val arr = JSONArray(text)
            val out = mutableListOf<Row>()
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
            out
        } catch (e: Exception) {
            NovaLog.quiet("ReminderStore", e)
            null
        }
    }
'''

OLD_ADD = '''    fun add(ctx: Context, atMillis: Long, text: String, repeatMs: Long): Int {
        val all = loadRows(ctx)
        val existing = all.firstOrNull { it.at == atMillis && it.text == text }
        if (existing != null) return existing.id
        val id = nextId(ctx)
        all.add(Row(atMillis, text, repeatMs, id))
        write(ctx, all)
        return id
    }
'''

NEW_ADD = '''    fun add(ctx: Context, atMillis: Long, text: String, repeatMs: Long): Int {
        // v9.33.119: read-modify-write. A load that FAILED is not an empty
        // list - saving over it would erase every other reminder. Nothing is
        // written and the caller is told, so it can say so instead of
        // quietly losing the user's reminders.
        val all = loadRowsOrNull(ctx) ?: run {
            NovaLog.quiet("ReminderStore",
                java.io.IOException("unreadable reminder list - not overwriting it"))
            return TRANSIENT_ID
        }
        val existing = all.firstOrNull { it.at == atMillis && it.text == text }
        // a row written before v9.33.115 owns no alarm id yet - give it one
        if (existing != null && existing.id > 0) return existing.id
        val id = nextId(ctx)
        all.removeAll { it.at == atMillis && it.text == text }
        all.add(Row(atMillis, text, repeatMs, id))
        write(ctx, all)
        return id
    }
'''

OLD_REMOVE = '''    fun remove(ctx: Context, atMillis: Long, text: String) {
        val all = loadRows(ctx)
        all.removeAll { it.at == atMillis && it.text == text }
        write(ctx, all)
    }
'''

NEW_REMOVE = '''    fun remove(ctx: Context, atMillis: Long, text: String): Boolean {
        // v9.33.119: same read-modify-write as add - and this one runs on
        // EVERY one-shot fire, from a broadcast receiver, so a corrupt list
        // was wiped by simply letting a reminder ring.
        val all = loadRowsOrNull(ctx) ?: run {
            NovaLog.quiet("ReminderStore",
                java.io.IOException("unreadable reminder list - not overwriting it"))
            return false
        }
        all.removeAll { it.at == atMillis && it.text == text }
        write(ctx, all)
        return true
    }
'''

OLD_REARM = '''    fun rearm(ctx: Context): Int {
        val now = System.currentTimeMillis()
        val rows = loadRows(ctx)
'''

NEW_REARM = '''    fun rearm(ctx: Context): Int {
        val now = System.currentTimeMillis()
        // v9.33.119: re-arm only reuses ids that are already on disk, and it
        // saves the rows back when it mints one. On a list it could not read
        // that save would replace every reminder with nothing, at every
        // launch. Nothing to re-arm is the honest answer here.
        val rows = loadRowsOrNull(ctx) ?: run {
            NovaLog.quiet("ReminderStore",
                java.io.IOException("unreadable reminder list - not re-arming or saving"))
            return 0
        }
'''

reminders = patch(REMINDERS, [
    (OLD_CONST, NEW_CONST),
    (OLD_LOADROWS, NEW_LOADROWS),
    (OLD_ADD, NEW_ADD),
    (OLD_REMOVE, NEW_REMOVE),
    (OLD_REARM, NEW_REARM),
])

# ----------------------------------------------------------------- exams
OLD_EXAM_LOAD = '''    fun load(ctx: Context): MutableList<Exam> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val out = mutableListOf<Exam>()
        try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                // v9.33.101: getJSONObject/getString throw on one bad row and
                // the catch below then returns an EMPTY list - every exam the
                // user had, gone, and the next add() saves that empty list back.
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                out.add(Exam(name, o.optLong("date", 0L)))
            }
        } catch (e: Exception) { NovaLog.quiet("Exams", e) }
        return out
    }
'''

NEW_EXAM_LOAD = '''    fun load(ctx: Context): MutableList<Exam> =
        loadOrNull(ctx) ?: mutableListOf()

    /**
     * v9.33.119 "A store that could not be read is not an empty store": the
     * exams on disk, or null when the file exists but could not be read.
     *
     * v9.33.101 stopped one bad ROW from emptying the list; the bad FILE was
     * still flattened to an empty list, and [add] and [set] are
     * read-modify-write - so adding one exam to an unreadable list saved that
     * one exam over all the others. The exam dates are the user's own, and
     * nothing in the app can re-derive them.
     */
    internal fun loadOrNull(ctx: Context): MutableList<Exam>? {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        val text = NcieFiles.readOrNull(f) ?: return null
        return try {
            val arr = JSONArray(text)
            val out = mutableListOf<Exam>()
            for (i in 0 until arr.length()) {
                // v9.33.101: getJSONObject/getString throw on one bad row and
                // the catch below then returns an EMPTY list - every exam the
                // user had, gone, and the next add() saves that empty list back.
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                out.add(Exam(name, o.optLong("date", 0L)))
            }
            out
        } catch (e: Exception) {
            NovaLog.quiet("Exams", e)
            null
        }
    }
'''

OLD_EXAM_ADD = '''    fun add(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.add(Exam(name, dateMs))
        save(ctx, all)
    }
'''

NEW_EXAM_ADD = '''    fun add(ctx: Context, name: String, dateMs: Long): Boolean {
        // v9.33.119: read-modify-write - never save over a failed read
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Exams", java.io.IOException("unreadable exam list - not overwriting it"))
            return false
        }
        all.add(Exam(name, dateMs))
        save(ctx, all)
        return true
    }
'''

OLD_EXAM_SET = '''    fun set(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        all.add(Exam(name, dateMs))
        save(ctx, all)
    }
'''

NEW_EXAM_SET = '''    fun set(ctx: Context, name: String, dateMs: Long): Boolean {
        // v9.33.119: read-modify-write, same rule as add
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Exams", java.io.IOException("unreadable exam list - not overwriting it"))
            return false
        }
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        all.add(Exam(name, dateMs))
        save(ctx, all)
        return true
    }
'''

exams = patch(EXAMS, [
    (OLD_EXAM_LOAD, NEW_EXAM_LOAD),
    (OLD_EXAM_ADD, NEW_EXAM_ADD),
    (OLD_EXAM_SET, NEW_EXAM_SET),
])

# --------------------------------------------------------------- planner
OLD_WRITEATOMIC = '''    private fun writeAtomic(f: File, text: String) {
        try {
            NcieFiles.write(f, text)
        } catch (e: Exception) { NovaLog.quiet("Planner", e) }
    }
'''

NEW_WRITEATOMIC = '''    private fun writeAtomic(f: File, text: String): Boolean {
        return try {
            NcieFiles.write(f, text)
        } catch (e: Exception) { NovaLog.quiet("Planner", e); false }
    }

    /**
     * v9.33.119 "A store that could not be read is not an empty store":
     * planner.json holds the daily budget AND the weak topics in ONE object,
     * and both mutators below are read-modify-write over it. A file NOVA
     * could not parse used to come back as a fresh empty JSONObject, so
     * changing the budget - or adding one weak topic - rewrote the file from
     * nothing and took the other half with it. null is "could not read"; a
     * mutator that gets null writes nothing.
     */
    private fun planOrNull(ctx: Context): JSONObject? {
        val f = plannerFile(ctx)
        if (!f.exists()) return JSONObject()
        val text = NcieFiles.readOrNull(f) ?: return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            NovaLog.quiet("Planner", e)
            null
        }
    }
'''

OLD_MINUTES = '''    fun minutes(ctx: Context): Int {
        return try {
            val f = plannerFile(ctx)
            if (!f.exists()) 60
            else JSONObject(f.readText()).optInt("minutes", 60)
        } catch (e: Exception) {
            60
        }
    }
'''

NEW_MINUTES = '''    fun minutes(ctx: Context): Int {
        val o = planOrNull(ctx) ?: return 60
        return o.optInt("minutes", 60)
    }
'''

OLD_SETMINUTES = '''    fun setMinutes(ctx: Context, m: Int) {
        try {
            val f = plannerFile(ctx)
            val o = if (f.exists()) {
                try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
            } else JSONObject()
            o.put("minutes", m)
            writeAtomic(f, o.toString())
        } catch (e: Exception) { NovaLog.quiet("Planner", e) }
    }
'''

NEW_SETMINUTES = '''    fun setMinutes(ctx: Context, m: Int): Boolean {
        val o = planOrNull(ctx) ?: return false
        return try {
            o.put("minutes", m)
            writeAtomic(plannerFile(ctx), o.toString())
        } catch (e: Exception) { NovaLog.quiet("Planner", e); false }
    }
'''

OLD_TOPICS_READ = '''        try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("topics") ?: return out
'''

NEW_TOPICS_READ = '''        try {
            val o = planOrNull(ctx) ?: return out
            val arr = o.optJSONArray("topics") ?: return out
'''

OLD_SAVEDOUBTS = '''    fun saveDoubts(ctx: Context, list: List<Doubt>) {
        try {
            val arr = JSONArray()
            for (d in list) {
                arr.put(
                    JSONObject()
                        .put("text", d.text)
                        .put("ts", d.ts)
                        .put("done", d.done)
                )
            }
            writeAtomic(doubtsFile(ctx), arr.toString())
        } catch (e: Exception) { NovaLog.quiet("Planner", e) }
    }
'''

NEW_SAVEDOUBTS = '''    fun saveDoubts(ctx: Context, list: List<Doubt>): Boolean {
        // v9.33.119: this list was built by doubts(), so a journal that could
        // not be read arrives here as "the user has no doubts" - and saving
        // that would delete the ones they do have. The doubt journal is a
        // read-modify-write by proxy, and the rule is the same one.
        val f = doubtsFile(ctx)
        if (f.exists() && NcieFiles.readOrNull(f) == null) {
            NovaLog.quiet("Planner",
                java.io.IOException("unreadable doubt journal - not overwriting it"))
            return false
        }
        return try {
            val arr = JSONArray()
            for (d in list) {
                arr.put(
                    JSONObject()
                        .put("text", d.text)
                        .put("ts", d.ts)
                        .put("done", d.done)
                )
            }
            writeAtomic(f, arr.toString())
        } catch (e: Exception) { NovaLog.quiet("Planner", e); false }
    }
'''

OLD_SAVETOPICS = '''    fun saveTopics(ctx: Context, list: List<Topic>) {
        try {
            val f = plannerFile(ctx)
            val o = if (f.exists()) {
                try { JSONObject(f.readText()) } catch (e: Exception) { JSONObject() }
            } else JSONObject()
            val arr = JSONArray()
            for (t in list) {
                arr.put(
                    JSONObject()
                        .put("subject", t.subject)
                        .put("name", t.name)
                        .put("weak", t.weak)
                )
            }
            o.put("topics", arr)
            writeAtomic(f, o.toString())
        } catch (e: Exception) { NovaLog.quiet("Planner", e) }
    }
'''

NEW_SAVETOPICS = '''    fun saveTopics(ctx: Context, list: List<Topic>): Boolean {
        val o = planOrNull(ctx) ?: return false
        return try {
            val arr = JSONArray()
            for (t in list) {
                arr.put(
                    JSONObject()
                        .put("subject", t.subject)
                        .put("name", t.name)
                        .put("weak", t.weak)
                )
            }
            o.put("topics", arr)
            writeAtomic(plannerFile(ctx), o.toString())
        } catch (e: Exception) { NovaLog.quiet("Planner", e); false }
    }
'''

planner = patch(PLANNER, [
    (OLD_WRITEATOMIC, NEW_WRITEATOMIC),
    (OLD_MINUTES, NEW_MINUTES),
    (OLD_SETMINUTES, NEW_SETMINUTES),
    (OLD_TOPICS_READ, NEW_TOPICS_READ),
    (OLD_SAVETOPICS, NEW_SAVETOPICS),
    (OLD_SAVEDOUBTS, NEW_SAVEDOUBTS),
])

# ------------------------------------------------------------ the caller
OLD_CALL = '''                val rid = ReminderStore.add(this, whenMs, task, repeatMs)
                Reminder.schedule(this, whenMs, task, repeatMs, rid)
                toast(if (repeatMs > 0) "Reminder set ($repeatLabel): $human"
                else "Reminder set: $human")
'''

NEW_CALL = '''                val rid = ReminderStore.add(this, whenMs, task, repeatMs)
                Reminder.schedule(this, whenMs, task, repeatMs, rid)
                if (rid == ReminderStore.TRANSIENT_ID) {
                    // v9.33.119: the reminder list exists and could not be
                    // read, so nothing was written over it. The alarm still
                    // rings - it just cannot survive a reboot, and the user
                    // is told rather than shown a reminder that is not saved.
                    toast("NOVA could not read its reminder list, so this one rings but is not saved")
                } else {
                    toast(if (repeatMs > 0) "Reminder set ($repeatLabel): $human"
                    else "Reminder set: $human")
                }
'''

main = patch(MAIN, [(OLD_CALL, NEW_CALL)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 286") != 1 or gradle.count("versionName '9.33.118'") != 1:
    print("FATAL: build.gradle is not at 9.33.118 (286)")
    sys.exit(1)
gradle = gradle.replace("versionCode 286", "versionCode 287")
gradle = gradle.replace("versionName '9.33.118'", "versionName '9.33.119'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit test
NEW_TEST = '''package org.nova.ncie.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * v9.33.119 "A store that could not be read is not an empty store".
 *
 * Every store in NOVA parses its file defensively, so a file it cannot read
 * comes back as "nothing there". For a screen that only displays that is the
 * right answer. For a store that WRITES BACK what it just read it is data
 * loss: the reminder store, the exam list and planner.json all load, change
 * one item and save the whole list, so an empty list from a failed read means
 * the next save deletes everything else the user had.
 *
 * [NcieFiles.readOrNull] is the one place that keeps the two cases apart.
 * These pin the distinction the mutators depend on: "" is "not there yet"
 * (nothing to lose, safe to create), null is "there and unreadable" (something
 * to lose, write nothing).
 */
class NcieReadOrNullTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("novaread").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aMissingFileIsNotAReadFailure() {
        // a fresh install: nothing on disk, and the first save may create it
        assertEquals("", NcieFiles.readOrNull(File(dir, "never-written.json")))
    }

    @Test
    fun aFileWithTextComesBackWhole() {
        val f = File(dir, "reminders.json")
        f.writeText("[{\\"at\\":1,\\"t\\":\\"drink water\\"}]")
        assertEquals("[{\\"at\\":1,\\"t\\":\\"drink water\\"}]", NcieFiles.readOrNull(f))
    }

    @Test
    fun anEmptyFileIsEmptyTextNotAFailure() {
        // a file that is really empty is still a file NOVA wrote - it is not
        // the same answer as one that could not be opened at all
        val f = File(dir, "empty.json")
        f.writeText("")
        assertEquals("", NcieFiles.readOrNull(f))
    }

    @Test
    fun aPathThatCannotBeReadIsNull() {
        // the path exists but is a directory: exists() is true and readText()
        // throws, which is exactly the corrupt-state-file shape the stores
        // must refuse to overwrite. (A chmod-000 file cannot be used here -
        // the test runner may be root, and root ignores the mode.)
        val f = File(dir, "unreadable.json")
        assertTrue("could not make the unreadable path", f.mkdirs())
        assertNull(NcieFiles.readOrNull(f))
    }
}
'''

if os.path.exists(NEWTEST):
    print("FATAL: NcieReadOrNullTest.kt already exists")
    sys.exit(1)
os.makedirs(os.path.dirname(NEWTEST), exist_ok=True)
write(NEWTEST, NEW_TEST)

# ------------------------------------------------- teach the verifier
OLD_V118 = '''check("v9.33.118: version is at least 9.33.118 (286)",
      version_at_least("9.33.118", 286))
'''

NEW_V118 = '''check("v9.33.118: version is at least 9.33.118 (286)",
      version_at_least("9.33.118", 286))

# v9.33.119 "A store that could not be read is not an empty store": the
# reminder store, the exam list and planner.json all load, change one item and
# save the whole list - so an empty list from a FAILED read meant the next save
# deleted everything else the user had. One read tells the two cases apart.
_nf119 = load("ncie/android/NcieFiles.kt")
_rs119 = load("ReminderStore.kt")
_ex119 = load("Exams.kt")
_pl119 = load("Planner.kt")
check("v9.33.119: NcieFiles has the one read, and it keeps the two cases apart",
      _nf119.count("fun readOrNull(f: File): String?") == 1 and
      _nf119.count("if (!f.exists()) return \\"\\"") == 1)
check("v9.33.119: the reminder store refuses to save over a failed read",
      _rs119.count("internal fun loadRowsOrNull(ctx: Context): MutableList<Row>?") == 1 and
      _rs119.count("unreadable reminder list - not overwriting it") == 2 and
      _rs119.count("f.readText()") == 1)
check("v9.33.119: add and remove are told apart from a successful save",
      _rs119.count("const val TRANSIENT_ID = 0") == 1 and
      _rs119.count("repeatMs: Long): Int {") == 1 and
      _rs119.count("text: String): Boolean {") == 1)
check("v9.33.119: the exam list refuses to save over a failed read",
      _ex119.count("internal fun loadOrNull(ctx: Context): MutableList<Exam>?") == 1 and
      _ex119.count("unreadable exam list - not overwriting it") == 2 and
      _ex119.count("f.readText()") == 0)
check("v9.33.119: planner.json is read once, through the same rule",
      _pl119.count("private fun planOrNull(ctx: Context): JSONObject?") == 1 and
      _pl119.count("f.readText()") == 1)
check("v9.33.119: the doubt journal refuses to save over a failed read",
      _pl119.count("unreadable doubt journal - not overwriting it") == 1)
check("v9.33.119: the caller says so instead of losing the reminder",
      load("MainActivity.kt").count("if (rid == ReminderStore.TRANSIENT_ID) {") == 1)
check("v9.33.119: the two answers are pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieReadOrNullTest.kt"),
           encoding="utf-8").read().count("@Test") >= 4)
check("v9.33.119: version is at least 9.33.119 (287)",
      version_at_least("9.33.119", 287))
'''

ver = read(VERIFY)
if ver.count(OLD_V118) != 1:
    print("FATAL: the verifier tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V118, NEW_V118))

# ---------------------------------------------------------------- verify
print("")
check("NcieFiles: the one read exists", "fun readOrNull(f: File): String?" in files_kt)
check("NcieFiles: a missing file is not a failure", 'if (!f.exists()) return ""' in files_kt)
check("reminders: the loader can say it failed",
      "internal fun loadRowsOrNull" in reminders)
check("reminders: the list is read once, through the one rule",
      reminders.count("f.readText()") == 1)
check("reminders: add answers TRANSIENT_ID instead of saving",
      "return TRANSIENT_ID" in reminders)
check("exams: the loader can say it failed", "internal fun loadOrNull" in exams)
check("exams: no bare readText left", "f.readText()" not in exams)
check("planner: one read for the one object", "private fun planOrNull" in planner)
check("planner: the plan object is read once, through the one rule",
      planner.count("f.readText()") == 1)
check("the caller handles an unsaved reminder",
      "if (rid == ReminderStore.TRANSIENT_ID) {" in main)
check("version is 9.33.119 (287)",
      "versionName '9.33.119'" in gradle and "versionCode 287" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.119", 287)' in read(VERIFY))
check("the unit test exists and pins the four cases", os.path.exists(NEWTEST))


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


for name, text in [("NcieFiles.kt", files_kt), ("ReminderStore.kt", reminders),
                   ("Exams.kt", exams), ("Planner.kt", planner),
                   ("MainActivity.kt", main), ("NcieReadOrNullTest.kt", read(NEWTEST))]:
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
print("PATCH OK: v9.33.119 applied and self-verified")
