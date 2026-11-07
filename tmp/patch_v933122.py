#!/usr/bin/env python3
"""v9.33.122 "The exam line carries the three nearest".

Exams.promptLine is injected into the prompt of EVERY chat turn. It joined
every upcoming exam the user had into one line:

    Physics in 12 days (14 May); Chemistry in 3 days (5 May);
    Maths in 40 days (2 Jun); Biology in 41 days (3 Jun); ...

A student who adds their whole term - which is the point of the Exams screen -
pays for all of it on every turn, in the one part of the prompt that is never
retrievable: the exam line is context, not a retrieval hit, so it cannot be
trimmed by the relevance gate or the document budget. And the model does not
answer better for knowing about the fourth exam: "what is coming up" is about
the next one.

So the line carries the three nearest upcoming exams. Nothing is lost from the
app - the Exams screen, the deadline radar and the weekly review still list
every exam - and the prompt shrinks on every turn for anyone with more than
three. That is the one direction the speed rule allows without a measured
quality gain, so it is the one direction this takes.

The formatting moves into a pure function ([Exams.lineFor]) so the rule can be
pinned by a JVM test instead of a screen.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933122.py .
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
NEWTEST = os.path.join(TESTDIR, "ExamsLineTest.kt")

OLD_LINE = '''    /** "Physics in 12 days (14 May)" - nearest first, or null. */
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
        if (upcoming.isEmpty()) return null
        return upcoming.joinToString("; ") { e ->
            val d = daysLeft(e.dateMs)
            (if (d <= 0) "${e.name} TODAY " else "${e.name} in $d days ") +
                "(${SimpleDateFormat("d MMM", Locale.US).format(Date(e.dateMs))})"
        }
    }
}
'''

NEW_LINE = '''    /** How many upcoming exams the model's line carries. */
    const val PROMPT_LIMIT = 3

    /** "Physics in 12 days (14 May)" - nearest first, or null. */
    fun promptLine(ctx: Context): String? = lineFor(load(ctx))

    /**
     * v9.33.122 "The exam line carries the three nearest": the exam line is
     * injected into the prompt of EVERY turn, and it used to join every
     * upcoming exam the user had. A student who adds their whole term - which
     * is the point of the Exams screen - paid for all of it on every turn, in
     * the one part of the prompt nothing can trim: the line is context, not a
     * retrieval hit, so neither the relevance gate nor the document budget
     * touches it.
     *
     * The three nearest is what the model needs - "what is coming up" is
     * about the next one - and nothing is lost from the app: the Exams
     * screen, the deadline radar and the weekly review still list every
     * exam. Pure, so a JVM test can pin the cap and the order.
     *
     * v9.33.118 "The exam is not over at nine in the morning" still holds
     * here: this filtered on `dateMs > now`, i.e. the exam's exact TIMESTAMP,
     * but an exam date is a calendar DAY stored at 09:00 by the chat parser
     * and at midnight by the date picker - so on the morning of the exam the
     * model was told nothing about it. The day is what counts.
     */
    fun lineFor(all: List<Exam>, limit: Int = PROMPT_LIMIT): String? {
        val upcoming = all
            .filter { daysLeft(it.dateMs) >= 0 }
            .sortedBy { it.dateMs }
        if (upcoming.isEmpty()) return null
        return upcoming.take(limit).joinToString("; ") { e ->
            val d = daysLeft(e.dateMs)
            (if (d <= 0) "${e.name} TODAY " else "${e.name} in $d days ") +
                "(${SimpleDateFormat("d MMM", Locale.US).format(Date(e.dateMs))})"
        }
    }
}
'''

exams = patch(EXAMS, [(OLD_LINE, NEW_LINE)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 289") != 1 or gradle.count("versionName '9.33.121'") != 1:
    print("FATAL: build.gradle is not at 9.33.121 (289)")
    sys.exit(1)
gradle = gradle.replace("versionCode 289", "versionCode 290")
gradle = gradle.replace("versionName '9.33.121'", "versionName '9.33.122'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit test
NEW_TEST = '''package org.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.122 "The exam line carries the three nearest".
 *
 * [Exams.lineFor] is the exam line that goes into the prompt of every chat
 * turn. It is the one part of the prompt nothing can trim - it is context, not
 * a retrieval hit - so its size is paid on every turn, and the model only ever
 * needs the next exam.
 *
 * These pin the cap, the order, and that nothing upcoming still means no line
 * at all (an empty line would be a prompt token spent on nothing).
 */
class ExamsLineTest {

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
    fun noUpcomingExamMeansNoLine() {
        assertNull(Exams.lineFor(emptyList()))
        assertNull(Exams.lineFor(listOf(exam("Physics", -3))))
    }

    @Test
    fun theNearestIsFirst() {
        val line = Exams.lineFor(listOf(exam("Physics", 12), exam("Chemistry", 3)))
        assertTrue(line!!.startsWith("Chemistry"))
        assertTrue(line.indexOf("Chemistry") < line.indexOf("Physics"))
    }

    @Test
    fun onlyTheThreeNearestAreCarried() {
        // full names, not letters: a single "D" would match the month "Dec"
        val line = Exams.lineFor(listOf(
            exam("Physics", 1), exam("Chemistry", 2), exam("Maths", 3),
            exam("Biology", 4), exam("History", 5)
        ))!!
        assertTrue(line.contains("Physics"))
        assertTrue(line.contains("Maths"))
        assertEquals("the fourth exam is not in the line", false, line.contains("Biology"))
        assertEquals("the fifth exam is not in the line", false, line.contains("History"))
    }

    @Test
    fun anExamThatHasPassedIsNotInTheLine() {
        val line = Exams.lineFor(listOf(exam("Old", -2), exam("Next", 4)))!!
        assertEquals(false, line.contains("Old"))
        assertTrue(line.contains("Next"))
    }

    @Test
    fun theExamItselfSaysToday() {
        // the day is what counts, not the hour - the v9.33.118 rule
        val line = Exams.lineFor(listOf(exam("Physics", 0, 23)))!!
        assertTrue("got: " + line, line.contains("Physics TODAY"))
    }

    @Test
    fun theCapIsThree() {
        assertEquals(3, Exams.PROMPT_LIMIT)
    }
}
'''

if os.path.exists(NEWTEST):
    print("FATAL: ExamsLineTest.kt already exists")
    sys.exit(1)
write(NEWTEST, NEW_TEST)

# ------------------------------------------------- teach the verifier
OLD_V121 = '''check("v9.33.121: version is at least 9.33.121 (289)",
      version_at_least("9.33.121", 289))
'''

NEW_V121 = '''check("v9.33.121: version is at least 9.33.121 (289)",
      version_at_least("9.33.121", 289))

# v9.33.122 "The exam line carries the three nearest": the exam line goes into
# the prompt of every turn and joined EVERY upcoming exam, so a term of exams
# was paid for on every turn in the one part of the prompt nothing can trim.
_ex122 = load("Exams.kt")
check("v9.33.122: the exam line is capped, and the cap is one constant",
      _ex122.count("const val PROMPT_LIMIT = 3") == 1 and
      _ex122.count("upcoming.take(limit)") == 1)
check("v9.33.122: the line is built by a pure function",
      _ex122.count("fun lineFor(all: List<Exam>, limit: Int = PROMPT_LIMIT): String?") == 1 and
      _ex122.count("fun promptLine(ctx: Context): String? = lineFor(load(ctx))") == 1)
check("v9.33.122: the cap is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ExamsLineTest.kt"),
           encoding="utf-8").read().count("@Test") >= 6)
check("v9.33.122: version is at least 9.33.122 (290)",
      version_at_least("9.33.122", 290))
'''

ver = read(VERIFY)
if ver.count(OLD_V121) != 1:
    print("FATAL: the v9.33.121 tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_V121, NEW_V121))

# ---------------------------------------------------------------- verify
print("")
check("Exams: one cap constant", "const val PROMPT_LIMIT = 3" in exams)
check("Exams: the line is a pure function", "fun lineFor(all: List<Exam>" in exams)
check("Exams: promptLine asks it", "fun promptLine(ctx: Context): String? = lineFor(load(ctx))" in exams)
check("Exams: the line takes only the nearest", "upcoming.take(limit)" in exams)
check("version is 9.33.122 (290)",
      "versionName '9.33.122'" in gradle and "versionCode 290" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.122", 290)' in read(VERIFY))
check("the unit test exists and pins the cap", os.path.exists(NEWTEST))


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


for name, text in [("Exams.kt", exams), ("ExamsLineTest.kt", read(NEWTEST))]:
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
print("PATCH OK: v9.33.122 applied and self-verified")
