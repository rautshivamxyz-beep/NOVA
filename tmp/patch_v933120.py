#!/usr/bin/env python3
"""v9.33.120 "One exam date, one store".

The chat's exam countdown read filesDir/exam_date.txt. Nothing else in the app
writes that file: the Exams screen writes exams.json, the date picker writes
exams.json, and the model's exam line (Exams.promptLine) reads exams.json.

So the two halves of the app disagreed about whether an exam existed at all:

  - a date set on the Exams screen was invisible to "days to my exam", "plan my
    revision" and the exam countdown, which answered "Set your exam date
    first" - while the screen was showing the exam and the prompt of every
    turn was telling the model about it;
  - and an exam cleared on the screen left the chat counting down to an exam
    that no longer existed, for as long as that file survived.

v9.33.113 fixed the WRITE half of this - the chat now stores its date in
exams.json as well - and left the read half on its own file. v9.33.118 fixed
which days count as upcoming, in one of the two places that asked.

One store now: Exams. One selection rule: Exams.nearest - the exam whose day is
today or later, nearest first, which is the exam the screen lists first and the
one the prompt uses. "Exam done" removes that exam from the store, not just
from a file nobody else reads. The legacy file is still read, but only when
exams.json has nothing to say, so an install that set its date before this
release keeps its countdown.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933120.py .
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


EXAMS = BASE + "Exams.kt"
NCIE_EXAM = BASE + "ncie/android/NcieExam.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
NEWTEST = os.path.join(TESTDIR, "ExamsNearestTest.kt")

# ------------------------------------------------------ the selection rule
OLD_DAYSLEFT = '''    fun daysLeft(dateMs: Long): Int {
        // v7.6: calendar-day difference in the local zone - the old
        // millisecond math showed "TODAY" for an exam held yesterday
        val zone = java.util.TimeZone.getDefault()
        fun day(ms: Long) = (ms + zone.getOffset(ms)) / 86_400_000L
        return (day(dateMs) - day(System.currentTimeMillis())).toInt()
    }
'''

NEW_DAYSLEFT = '''    fun daysLeft(dateMs: Long): Int {
        // v7.6: calendar-day difference in the local zone - the old
        // millisecond math showed "TODAY" for an exam held yesterday
        val zone = java.util.TimeZone.getDefault()
        fun day(ms: Long) = (ms + zone.getOffset(ms)) / 86_400_000L
        return (day(dateMs) - day(System.currentTimeMillis())).toInt()
    }

    /**
     * v9.33.120 "One exam date, one store": which exam "my exam" means.
     *
     * The chat countdown, the revision plan, the deadline radar and the
     * weekly review each ask for ONE exam date, and they read a file of
     * their own that only the chat command ever wrote - so the screen and
     * the chat could disagree about whether there was an exam at all.
     *
     * The nearest exam whose DAY is today or later is the answer: the one
     * the Exams screen lists first and the one [promptLine] puts in the
     * prompt. With nothing upcoming the most recent past exam is answered
     * rather than null, so the chat says "your exam is today or already
     * past" instead of "set your exam date first" about an exam the screen
     * is still showing. Pure, so a unit test can pin it.
     */
    fun nearest(list: List<Exam>): Exam? =
        list.filter { daysLeft(it.dateMs) >= 0 }.minByOrNull { it.dateMs }
            ?: list.maxByOrNull { it.dateMs }
'''

# ------------------------------------------------------------ the removal
OLD_SET = '''    fun set(ctx: Context, name: String, dateMs: Long): Boolean {
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

NEW_SET = '''    fun set(ctx: Context, name: String, dateMs: Long): Boolean {
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

    /**
     * v9.33.120: removes the exam of that name - what "exam done" does now
     * that this store is the one place an exam lives. Read-modify-write, so
     * it refuses to save over a load that failed (the v9.33.119 rule), and
     * it writes nothing when the name was not there to begin with.
     */
    fun drop(ctx: Context, name: String): Boolean {
        val all = loadOrNull(ctx) ?: run {
            NovaLog.quiet("Exams", java.io.IOException("unreadable exam list - not overwriting it"))
            return false
        }
        val before = all.size
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        if (all.size != before) save(ctx, all)
        return true
    }
'''

exams = patch(EXAMS, [(OLD_DAYSLEFT, NEW_DAYSLEFT), (OLD_SET, NEW_SET)])

# ------------------------------------------------- the one read in the chat
OLD_READDATE = '''    /** The stored exam date in millis, or null when none is set. */
    private fun readDate(ctx: Context): Long? = try {
        val f = dateFile(ctx)
        if (!f.exists()) null
        else f.readLines().firstOrNull()?.trim()?.toLongOrNull()
    } catch (e: Exception) { null }
'''

NEW_READDATE = '''    /**
     * v9.33.120 "One exam date, one store": the exam the countdown, the
     * revision plan and "exam countdown" answer with.
     *
     * It came from [dateFile], a file only the chat command ever wrote. The
     * Exams screen writes exams.json and nothing else, so a date set on the
     * screen was invisible here - "days to my exam" answered "Set your exam
     * date first" while the screen showed the exam and the prompt of every
     * turn told the model about it - and an exam cleared on the screen left
     * this countdown running against an exam that no longer existed. Two
     * stores, two answers to one question.
     *
     * Exams is the one store now, and [Exams.nearest] is the one selection
     * rule: the exam the screen lists first and the one the prompt carries.
     * The legacy file is still read, but only when the store is empty, so an
     * install that set its date before this release keeps its countdown.
     */
    private fun storedExam(ctx: Context): Exams.Exam? = try {
        Exams.nearest(Exams.load(ctx))
    } catch (e: Exception) { NovaLog.quiet("NcieExam", e); null }

    /** The pre-v9.33.120 record - read only when the store is empty. */
    private fun legacyDate(ctx: Context): Long? = try {
        val f = dateFile(ctx)
        if (!f.exists()) null
        else f.readLines().firstOrNull()?.trim()?.toLongOrNull()
    } catch (e: Exception) { null }

    /** The stored exam date in millis, or null when none is set. */
    private fun readDate(ctx: Context): Long? =
        storedExam(ctx)?.dateMs ?: legacyDate(ctx)
'''

OLD_REPLY = '''        val fmt = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
        // v9.33.111: `?: 0` turned a failed round-trip into "0 days to go".
        // Null here means the date did not come back off disk; say only what
        // is true instead of inventing a countdown to today.
        val d = daysLeft(act)
        postReply(act, "Exam date set: $fmt." +
            (if (d == null) "" else " $d days to go."))
'''

NEW_REPLY = '''        val fmt = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
        // v9.33.111: `?: 0` turned a failed round-trip into "0 days to go".
        // v9.33.120: and the countdown is asked about the date that was just
        // stored, not about whichever exam the store answers with now - the
        // user typed this date, so the reply has to be about it.
        val d = Exams.daysLeft(ms)
        postReply(act, "Exam date set: $fmt. $d days to go.")
'''

OLD_CLEAR = '''    /** "clear exam date" / "exam done" / "exam is over". */
    fun clear(act: MainActivity) {
        try {
            dateFile(act).delete()
            planFile(act).delete()
        } catch (e: Exception) { NovaLog.quiet("NcieExam", e) }
        postReply(act, "Exam planner cleared.")
    }
'''

NEW_CLEAR = '''    /** "clear exam date" / "exam done" / "exam is over". */
    fun clear(act: MainActivity) {
        // v9.33.120: the exam the countdown was answering with is removed
        // from the one store too. Deleting only the legacy file left the
        // exam in exams.json, so "exam done" emptied this countdown while
        // the Exams screen and the prompt of every turn still had it.
        val gone = storedExam(act)
        val dropped = try {
            if (gone == null) true else Exams.drop(act, gone.name)
        } catch (e: Exception) { NovaLog.quiet("NcieExam", e); false }
        try {
            dateFile(act).delete()
            planFile(act).delete()
        } catch (e: Exception) { NovaLog.quiet("NcieExam", e) }
        postReply(act, if (dropped) "Exam planner cleared."
            else "Cleared the plan, but the exam list could not be read, so the exam is still there.")
    }
'''

ncie = patch(NCIE_EXAM, [
    (OLD_READDATE, NEW_READDATE),
    (OLD_REPLY, NEW_REPLY),
    (OLD_CLEAR, NEW_CLEAR),
])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 287") != 1 or gradle.count("versionName '9.33.119'") != 1:
    print("FATAL: build.gradle is not at 9.33.119 (287)")
    sys.exit(1)
gradle = gradle.replace("versionCode 287", "versionCode 288")
gradle = gradle.replace("versionName '9.33.119'", "versionName '9.33.120'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit test
NEW_TEST = '''package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.120 "One exam date, one store".
 *
 * The chat countdown, the revision plan, the deadline radar and the weekly
 * review all need ONE exam date, and they used to read a file of their own
 * that only the chat command wrote - so a date set on the Exams screen was
 * invisible to the chat, and an exam cleared on the screen kept the chat
 * counting down. [Exams.nearest] is the one selection rule now: the exam the
 * screen lists first and the one the model prompt carries.
 *
 * These pin the two boundaries it has to get right - which exam is "my exam"
 * when there are several, and what it answers when every exam is in the past.
 */
class ExamsNearestTest {

    private fun at(dayOffset: Int, hour: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun exam(name: String, dayOffset: Int, hour: Int = 9) =
        Exams.Exam(name, at(dayOffset, hour))

    @Test
    fun noExamsMeansNoExam() {
        assertNull(Exams.nearest(emptyList()))
    }

    @Test
    fun theSoonestUpcomingExamIsTheAnswer() {
        val nearest = Exams.nearest(listOf(
            exam("Physics", 12),
            exam("Chemistry", 3),
            exam("Maths", 40)
        ))
        assertEquals("Chemistry", nearest?.name)
    }

    @Test
    fun anExamThatHasPassedIsNotTheAnswerWhileOneIsUpcoming() {
        val nearest = Exams.nearest(listOf(
            exam("Physics", -2),
            exam("Chemistry", 5)
        ))
        assertEquals("Chemistry", nearest?.name)
    }

    @Test
    fun withNothingUpcomingTheMostRecentPastExamIsAnswered() {
        // the screen is still showing it, so the chat must not say "set your
        // exam date first" - it says the exam is today or already past
        val nearest = Exams.nearest(listOf(
            exam("Physics", -30),
            exam("Chemistry", -2)
        ))
        assertEquals("Chemistry", nearest?.name)
    }

    @Test
    fun anExamLaterTodayIsStillUpcoming() {
        // the day is what counts, not the hour: a 23:00 exam is upcoming at
        // 00:01 on its own day, which is the v9.33.118 rule this asks
        val nearest = Exams.nearest(listOf(exam("Physics", 0, 23)))
        assertEquals("Physics", nearest?.name)
        assertEquals(0, Exams.daysLeft(nearest!!.dateMs))
    }
}
'''

if os.path.exists(NEWTEST):
    print("FATAL: ExamsNearestTest.kt already exists")
    sys.exit(1)
write(NEWTEST, NEW_TEST)

# ------------------------------------------------- teach the verifier
OLD_V111 = '''check("v9.33.111: no countdown is invented for a date that did not stick",
      ne.count("val d = daysLeft(act) ?: 0") == 0 and
      ne.count('(if (d == null) "" else " $d days to go.")') == 1)
'''

NEW_V111 = '''check("v9.33.111: no countdown is invented for a date that did not stick",
      ne.count("val d = daysLeft(act) ?: 0") == 0 and
      # v9.33.120: the countdown is asked about the date just stored, so the
      # nullable round-trip is gone - the intent is still "never invent one".
      ne.count("val d = Exams.daysLeft(ms)") == 1)
'''

# v9.33.120 moves three counts, and each one is a real change of shape, not a
# loosening: the exam list now has TWO read-modify-write mutators that drop a
# row by name (set and drop), the "is it upcoming" rule is asked in TWO places
# in Exams.kt (the prompt and nearest), and THREE mutators refuse to save over
# a failed read (add, set, drop).
OLD_V113 = '''check("v9.33.113: one name is one exam",
      _ex.count("fun set(ctx: Context, name: String, dateMs: Long)") == 1 and
      _ex.count("all.removeAll { it.name.equals(name, ignoreCase = true) }") == 1)
'''

NEW_V113 = '''check("v9.33.113: one name is one exam",
      _ex.count("fun set(ctx: Context, name: String, dateMs: Long)") == 1 and
      # v9.33.120: drop() is the second caller of the one-name rule, not a
      # second rule - both ask the same comparison
      _ex.count("all.removeAll { it.name.equals(name, ignoreCase = true) }") == 2)
'''

OLD_V118F = '''check("v9.33.118: an exam is upcoming while its DAY is today or later",
      _ex118.count("filter { daysLeft(it.dateMs) >= 0 }") == 1 and
      _ex118.count("dateMs > System.currentTimeMillis()") == 0)
'''

NEW_V118F = '''check("v9.33.118: an exam is upcoming while its DAY is today or later",
      # v9.33.120: the prompt and Exams.nearest are the two askers
      _ex118.count("filter { daysLeft(it.dateMs) >= 0 }") == 2 and
      _ex118.count("dateMs > System.currentTimeMillis()") == 0)
'''

OLD_V119F = '''check("v9.33.119: the exam list refuses to save over a failed read",
      _ex119.count("internal fun loadOrNull(ctx: Context): MutableList<Exam>?") == 1 and
      _ex119.count("unreadable exam list - not overwriting it") == 2 and
      _ex119.count("f.readText()") == 0)
'''

NEW_V119F = '''check("v9.33.119: the exam list refuses to save over a failed read",
      _ex119.count("internal fun loadOrNull(ctx: Context): MutableList<Exam>?") == 1 and
      # v9.33.120: add, set and drop are all read-modify-write
      _ex119.count("unreadable exam list - not overwriting it") == 3 and
      _ex119.count("f.readText()") == 0)
'''

OLD_V119 = '''check("v9.33.119: version is at least 9.33.119 (287)",
      version_at_least("9.33.119", 287))
'''

NEW_V119 = '''check("v9.33.119: version is at least 9.33.119 (287)",
      version_at_least("9.33.119", 287))

# v9.33.120 "One exam date, one store": the chat countdown read a file only
# the chat command wrote, while the Exams screen and the model prompt read
# exams.json - so a date set on the screen was invisible to "days to my exam"
# and an exam cleared on the screen kept the chat counting down to it.
_ne120 = load("ncie/android/NcieExam.kt")
_ex120 = load("Exams.kt")
check("v9.33.120: the chat asks the one store, with one selection rule",
      _ne120.count("Exams.nearest(Exams.load(ctx))") == 1 and
      _ne120.count("storedExam(ctx)?.dateMs ?: legacyDate(ctx)") == 1 and
      _ex120.count("fun nearest(list: List<Exam>): Exam?") == 1)
check("v9.33.120: the legacy file is only a fallback",
      _ne120.count("private fun legacyDate(ctx: Context): Long?") == 1 and
      _ne120.count("exam_date.txt") == 2)
check("v9.33.120: the nearest exam is the one the screen lists first",
      _ex120.count("list.filter { daysLeft(it.dateMs) >= 0 }.minByOrNull { it.dateMs }") == 1 and
      _ex120.count("?: list.maxByOrNull { it.dateMs }") == 1)
check("v9.33.120: exam done removes the exam from the store",
      _ex120.count("fun drop(ctx: Context, name: String): Boolean") == 1 and
      _ne120.count("Exams.drop(act, gone.name)") == 1)
check("v9.33.120: the reply counts down to the date that was just set",
      _ne120.count("val d = Exams.daysLeft(ms)") == 1 and
      _ne120.count("daysLeft(act)") == 2)
check("v9.33.120: the selection rule is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ExamsNearestTest.kt"),
           encoding="utf-8").read().count("@Test") >= 5)
check("v9.33.120: version is at least 9.33.120 (288)",
      version_at_least("9.33.120", 288))
'''

ver = read(VERIFY)
for _old, _new, _name in [(OLD_V111, NEW_V111, "v9.33.111"),
                          (OLD_V113, NEW_V113, "v9.33.113"),
                          (OLD_V118F, NEW_V118F, "v9.33.118"),
                          (OLD_V119F, NEW_V119F, "v9.33.119")]:
    if ver.count(_old) != 1:
        print("FATAL: the %s anchor did not match exactly once" % _name)
        sys.exit(1)
    ver = ver.replace(_old, _new)
if ver.count(OLD_V119) != 1:
    print("FATAL: the v9.33.119 tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V119, NEW_V119))

# ---------------------------------------------------------------- verify
print("")
check("Exams: one selection rule", "fun nearest(list: List<Exam>): Exam?" in exams)
check("Exams: one removal", "fun drop(ctx: Context, name: String): Boolean" in exams)
check("NcieExam: the store is asked first",
      "storedExam(ctx)?.dateMs ?: legacyDate(ctx)" in ncie)
check("NcieExam: the legacy file is still a fallback", "legacyDate(ctx)" in ncie)
check("NcieExam: exam done drops the exam", "Exams.drop(act, gone.name)" in ncie)
check("NcieExam: the countdown is about the date just set",
      "val d = Exams.daysLeft(ms)" in ncie)
check("version is 9.33.120 (288)",
      "versionName '9.33.120'" in gradle and "versionCode 288" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.120", 288)' in read(VERIFY))
check("the unit test exists and pins the rule", os.path.exists(NEWTEST))


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


for name, text in [("Exams.kt", exams), ("NcieExam.kt", ncie),
                   ("ExamsNearestTest.kt", read(NEWTEST))]:
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
print("PATCH OK: v9.33.120 applied and self-verified")
