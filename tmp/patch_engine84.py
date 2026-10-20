#!/usr/bin/env python3
"""NOVA one-shot patch for v9.33.16 "the deterministic core, proven + honest".

Deterministic, idempotent, and self-verifying: it applies every edit, then
runs tmp/verify_patches.py and exits non-zero if anything is missing, so a
silent no-op can never ship. Usage: patch_engine84.py <NOVA repo root>

What it does
  1. build.gradle   : versionCode 183 -> 184, versionName 9.33.15 -> 9.33.16
  2. NcieDeadlines  : dateAt() private -> internal (no behaviour change; the
                      pure calendar parser becomes unit-testable)
  3. NcieFocus      : start() no longer reports "focus mode on" when the
                      session could not be saved - it says so instead
  4. NcieDeadlinesTest.kt : new unit tests for the deadline date parser
  5. verify_patches.py    : the version pin moves to 9.33.16 + two new checks
"""
import os
import sys
import subprocess

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = "android/app/src/main/java/org/nova/"


def edit(rel, pairs):
    p = os.path.join(ROOT, rel)
    s = open(p, encoding="utf-8").read()
    for old, new, n in pairs:
        got = s.count(old)
        if got != n:
            raise SystemExit("FATAL %s: expected %d of %r, found %d" % (rel, n, old[:50], got))
        s = s.replace(old, new)
    open(p, "w", encoding="utf-8").write(s)
    print("patched", rel)


# 1. the version bump
edit("android/app/build.gradle", [
    ("versionCode 183", "versionCode 184", 1),
    ("versionName '9.33.15'", "versionName '9.33.16'", 1),
])

# 2. the deadline date parser becomes testable
edit(BASE + "ncie/android/NcieDeadlines.kt", [
    ("    private fun dateAt(", "    internal fun dateAt(", 1),
])

# 3. focus mode is honest about a failed save
edit(BASE + "ncie/android/NcieFocus.kt", [
    (
        '        try { file(ctx).writeText(end.toString()) } catch (e: Exception) { NovaLog.quiet("NcieFocus", e) }\n'
        '        return "Focus mode on for " + mins +',
        '        // v9.33.16: a swallowed write failure used to be reported as\n'
        '        // "focus mode on" anyway - the session was never saved, so the next\n'
        '        // turn was not actually silent. Say so instead of lying.\n'
        '        val saved = try {\n'
        '            file(ctx).writeText(end.toString())\n'
        '            true\n'
        '        } catch (e: Exception) {\n'
        '            NovaLog.quiet("NcieFocus", e)\n'
        '            false\n'
        '        }\n'
        '        if (!saved) return "Couldn\'t start focus mode - NOVA could not save the session. Try again."\n'
        '        return "Focus mode on for " + mins +',
        1,
    ),
])

# 4. the new unit tests
TEST_REL = "android/app/src/test/java/org/nova/ncie/android/NcieDeadlinesTest.kt"
TEST_SRC = '''package org.nova.ncie.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * v9.33.16: the deadline radar's date parser ([NcieDeadlines.dateAt]) is the
 * one piece of A2 that is pure calendar math and needs no Android, so it can
 * be pinned by a real unit test. These lock the two rules the radar depends
 * on: a date already past rolls forward to next year ONLY when it carried no
 * explicit year, and an impossible date (31 February) is refused rather than
 * silently shifted to 3 March.
 *
 * Run with `gradle testDebugUnitTest` (CI runs it on every pull request).
 */
class NcieDeadlinesTest {

    private fun midnight(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(y, m - 1, d, 0, 0, 0)
        return c.timeInMillis
    }

    private fun ymd(ms: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun explicitYearIsKept() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2026, 11, 5), ymd(NcieDeadlines.dateAt(today, 5, 11, "2026")!!))
    }

    @Test
    fun aTwoDigitYearIsThisCentury() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2027, 3, 15), ymd(NcieDeadlines.dateAt(today, 15, 3, "27")!!))
    }

    @Test
    fun noYearFutureThisYearStays() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2026, 6, 20), ymd(NcieDeadlines.dateAt(today, 20, 6, null)!!))
    }

    @Test
    fun noYearAlreadyPastRollsForward() {
        val today = midnight(2026, 6, 1)
        assertEquals(Triple(2027, 1, 1), ymd(NcieDeadlines.dateAt(today, 1, 1, null)!!))
    }

    @Test
    fun impossibleDatesAreRefused() {
        val today = midnight(2026, 6, 1)
        assertNull(NcieDeadlines.dateAt(today, 31, 2, "2026"))
        assertNull(NcieDeadlines.dateAt(today, 0, 5, null))
        assertNull(NcieDeadlines.dateAt(today, 10, 13, null))
    }
}
'''
tp = os.path.join(ROOT, TEST_REL)
os.makedirs(os.path.dirname(tp), exist_ok=True)
open(tp, "w", encoding="utf-8").write(TEST_SRC)
print("wrote", TEST_REL)

# 5. verify_patches: the version pin + the new checks
edit("tmp/verify_patches.py", [
    (
        'check("v9.33.15: version bumped for the nothing-fails-silently pass", '
        'bg.count("versionName \'9.33.15\'") == 1 and bg.count("versionCode 183") == 1)',
        'check("v9.33.16: version bumped for the proven-core pass", '
        'bg.count("versionName \'9.33.16\'") == 1 and bg.count("versionCode 184") == 1)\n'
        '\n'
        '# ---- v9.33.16 "the deterministic core, proven + honest" ----\n'
        'check("v9.33.16: focus mode does not claim success when the write fails",\n'
        '      load("ncie/android/NcieFocus.kt").count("Couldn\'t start focus mode") == 1)\n'
        'check("v9.33.16: the deadline date parser is unit-tested",\n'
        '      load("ncie/android/NcieDeadlines.kt").count("internal fun dateAt(") == 1 and\n'
        '      os.path.exists(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieDeadlinesTest.kt")))',
        1,
    ),
])

# self-verify
print("running verify_patches ...")
rc = subprocess.call([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
if rc != 0:
    raise SystemExit("FATAL: verify_patches failed after patching")
print("patch_engine84 OK")
