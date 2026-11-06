#!/usr/bin/env python3
"""v9.33.118 "The exam is not over at nine in the morning".

Exams.promptLine is what puts the user's exams into the model's prompt:

    val upcoming = load(ctx)
        .filter { it.dateMs > System.currentTimeMillis() }
        .sortedBy { it.dateMs }

`dateMs` is the exam's exact TIMESTAMP, but an exam date is a calendar DAY.
The chat parser stores it at 09:00 on that day (NcieExam.cal), the Exams
screen's date picker at midnight. So from that instant the exam stopped being
"upcoming": at 09:00 on the day of the exam, the model was told nothing about
it - on the one morning it mattered - while the Exams screen, the planner and
the chat countdown, which all ask Exams.daysLeft, still said "TODAY".

Two of those three already used the right rule. Planner filters
`Exams.daysLeft(it.dateMs) >= 0`. The prompt did not, so the same question
("what's coming up") had two answers depending on which screen asked it.

One rule now: an exam is upcoming while its day is today or later, asked of
Exams.daysLeft in both places.

Self-verifying: every anchor must match exactly once, and the finished tree
is checked for the new markers before the script exits 0. Run from the repo
root (or pass it): python3 tmp/patch_v933118.py .
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
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
NEWTEST = os.path.join(TESTDIR, "ExamsTest.kt")

# ------------------------------------------------------------- the one rule
OLD_PROMPT = '''    /** "Physics in 12 days (14 May)" - nearest first, or null. */
    fun promptLine(ctx: Context): String? {
        val upcoming = load(ctx)
            .filter { it.dateMs > System.currentTimeMillis() }
            .sortedBy { it.dateMs }
'''

NEW_PROMPT = '''    /** "Physics in 12 days (14 May)" - nearest first, or null. */
    fun promptLine(ctx: Context): String? {
        // v9.33.118 "The exam is not over at nine in the morning": this
        // filtered on `dateMs > now`, i.e. the exam's exact TIMESTAMP - but
        // an exam date is a calendar DAY, stored at 09:00 by the chat parser
        // and at midnight by the date picker. From that instant the exam
        // stopped being "upcoming", so on the morning of the exam the model
        // was told nothing about it, while the Exams screen, the planner and
        // the chat countdown - which ask daysLeft - still said TODAY. One
        // rule now, the same one Planner already used.
        val upcoming = load(ctx)
            .filter { daysLeft(it.dateMs) >= 0 }
            .sortedBy { it.dateMs }
'''

exams = patch(EXAMS, [(OLD_PROMPT, NEW_PROMPT)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 285") != 1 or gradle.count("versionName '9.33.117'") != 1:
    print("FATAL: build.gradle is not at 9.33.117 (285)")
    sys.exit(1)
gradle = gradle.replace("versionCode 285", "versionCode 286")
gradle = gradle.replace("versionName '9.33.117'", "versionName '9.33.118'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit test
NEW_TEST = '''package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.118 "The exam is not over at nine in the morning".
 *
 * [Exams.daysLeft] is the one rule that decides whether an exam is today,
 * tomorrow or done, and three screens ask it: the Exams list, the planner and
 * the line that goes into the model's prompt. The prompt used to filter on the
 * exam's exact timestamp instead, so from 09:00 on the day of the exam the
 * model was told nothing about it.
 *
 * The math is calendar days in the local zone, so the boundaries are pinned
 * here: today is 0 (not -1 and not 1), tomorrow is 1, yesterday is -1.
 */
class ExamsTest {

    private fun at(dayOffset: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, dayOffset)
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun todayIsZeroWhateverTheTimeOfDay() {
        assertEquals("midnight today", 0, Exams.daysLeft(at(0, 0, 0)))
        assertEquals("nine in the morning today", 0, Exams.daysLeft(at(0, 9, 0)))
        assertEquals("one minute to midnight today", 0, Exams.daysLeft(at(0, 23, 59)))
    }

    @Test
    fun tomorrowIsOneAndYesterdayIsMinusOne() {
        assertEquals(1, Exams.daysLeft(at(1, 0, 0)))
        assertEquals(1, Exams.daysLeft(at(1, 23, 0)))
        assertEquals(-1, Exams.daysLeft(at(-1, 12, 0)))
    }

    @Test
    fun aDateOnlyExamIsUpcomingOnItsOwnDay() {
        // the rule the prompt asks now: today or later is upcoming. An exam
        // stored at midnight or at 09:00 is still upcoming all day.
        for (hour in listOf(0, 9, 18, 23)) {
            assertTrue("hour $hour on the day must be upcoming",
                Exams.daysLeft(at(0, hour, 0)) >= 0)
        }
        assertTrue("yesterday is over", Exams.daysLeft(at(-1, 23, 0)) < 0)
    }

    @Test
    fun theDifferenceIsCalendarDaysNotMilliseconds() {
        // 23:59 today to 00:01 tomorrow is two minutes, and one day
        val late = Exams.daysLeft(at(0, 23, 59))
        val early = Exams.daysLeft(at(1, 0, 1))
        assertEquals(0, late)
        assertEquals(1, early)
    }
}
'''

if os.path.exists(NEWTEST):
    print("FATAL: ExamsTest.kt already exists")
    sys.exit(1)
write(NEWTEST, NEW_TEST)

# ------------------------------------------------- teach the verifier
OLD_V117 = '''check("v9.33.117: version is at least 9.33.117 (285)",
      version_at_least("9.33.117", 285))
'''

NEW_V117 = '''check("v9.33.117: version is at least 9.33.117 (285)",
      version_at_least("9.33.117", 285))

# v9.33.118 "The exam is not over at nine in the morning": the prompt filtered
# exams on their exact TIMESTAMP, and an exam date is a calendar day stored at
# 09:00 (chat) or midnight (picker) - so on the morning of the exam the model
# was told nothing about it while every screen said TODAY.
_ex118 = load("Exams.kt")
check("v9.33.118: an exam is upcoming while its DAY is today or later",
      _ex118.count("filter { daysLeft(it.dateMs) >= 0 }") == 1 and
      _ex118.count("dateMs > System.currentTimeMillis()") == 0)
check("v9.33.118: the prompt and the planner ask the same rule",
      load("Planner.kt").count("Exams.daysLeft(it.dateMs) >= 0") == 1 and
      _ex118.count("fun daysLeft(dateMs: Long): Int") == 1)
check("v9.33.118: the day boundary is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ExamsTest.kt"),
           encoding="utf-8").read().count("@Test") >= 4)
check("v9.33.118: version is at least 9.33.118 (286)",
      version_at_least("9.33.118", 286))
'''

ver = read(VERIFY)
if ver.count(OLD_V117) != 1:
    print("FATAL: the verifier tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V117, NEW_V117))

# ---------------------------------------------------------------- verify
print("")
check("exams: the prompt asks daysLeft", "filter { daysLeft(it.dateMs) >= 0 }" in exams)
check("exams: the timestamp comparison is gone",
      "dateMs > System.currentTimeMillis()" not in exams)
check("exams: daysLeft is still the one day rule",
      exams.count("fun daysLeft(dateMs: Long): Int") == 1)
check("version is 9.33.118 (286)",
      "versionName '9.33.118'" in gradle and "versionCode 286" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.118", 286)' in read(VERIFY))
check("ExamsTest exists and pins the boundary", os.path.exists(NEWTEST))


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


for name, text in [("Exams.kt", exams), ("ExamsTest.kt", read(NEWTEST))]:
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
print("PATCH OK: v9.33.118 applied and self-verified")
