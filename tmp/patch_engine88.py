#!/usr/bin/env python3
"""NOVA one-shot patch for v9.33.85 "a deadline written for today is due today".

Deterministic, idempotent, self-verifying (ends by running verify_patches.py).
Usage: patch_engine88.py <NOVA repo root>

Audit finding (A2 deadline radar, third pass):

  radar() added a date found in the user's notes only when
  `at in now..horizon`. A date-only value resolves to MIDNIGHT, so a note
  reading "assignment due 29 Oct" compared as 00:00 against `now` = 14:30 and
  was dropped - for the whole of the 29th, the one day it mattered most. The
  formatter even has a "today" branch that a note date could never reach.

  Reminders keep the `now` floor on purpose: one that already fired is done,
  not due. Only the note dates move to the start of today.

What it does
  1. build.gradle        : versionCode 252 -> 253, versionName 9.33.84 -> 9.33.85
  2. NcieDeadlines.kt    : onRadar() - the note-date window, pure arithmetic;
                           the four note-date loops use it instead of now..horizon
  3. NcieDeadlinesTest.kt: one test pins the boundary
  4. verify_patches.py   : four checks, the version floor at 9.33.85/253
"""
import os
import sys
import subprocess

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = "android/app/src/main/java/org/nova/"
TEST = "android/app/src/test/java/org/nova/"


def read(rel):
    return open(os.path.join(ROOT, rel), encoding="utf-8").read()


def write(rel, s):
    open(os.path.join(ROOT, rel), "w", encoding="utf-8").write(s)
    print("patched", rel)


def edit(rel, pairs):
    s = read(rel)
    for old, new, n in pairs:
        got = s.count(old)
        if got != n:
            raise SystemExit("FATAL %s: expected %d of %r, found %d" % (rel, n, old[:70], got))
        s = s.replace(old, new)
    write(rel, s)


# 1. the version bump
edit("android/app/build.gradle", [
    ("versionCode 252", "versionCode 253", 1),
    ("versionName '9.33.84'", "versionName '9.33.85'", 1),
])

# 2. NcieDeadlines.kt
ONRADAR_OLD = "    private fun startOfToday(): Long {\n"
ONRADAR_NEW = (
    "    /**\n"
    "     * v9.33.85: a deadline WRITTEN IN A NOTE is due from the start of today,\n"
    "     * not from this instant. A date-only value resolves to midnight, so\n"
    "     * \"assignment due 29 Oct\" compared as `at in now..horizon` was dropped\n"
    "     * for the whole of the 29th - the one day it mattered most. Reminders\n"
    "     * keep the `now` floor: one that already fired is done, not due.\n"
    "     * Pure arithmetic, so a unit test can pin the boundary.\n"
    "     */\n"
    "    internal fun onRadar(today: Long, horizon: Long, at: Long): Boolean =\n"
    "        at >= today && at <= horizon\n"
    "\n"
    "    private fun startOfToday(): Long {\n"
)
edit(BASE + "ncie/android/NcieDeadlines.kt", [
    (ONRADAR_OLD, ONRADAR_NEW, 1),
    ("if (at in now..horizon)", "if (onRadar(today, horizon, at))", 4),
])

# 3. the unit test
TEST_IMPORT_OLD = "import org.junit.Assert.assertNull\n"
TEST_IMPORT_NEW = "import org.junit.Assert.assertNull\nimport org.junit.Assert.assertTrue\n"

TEST_ANCHOR = (
    "    @Test\n"
    "    fun impossibleDatesAreRefused() {\n"
    "        val today = midnight(2026, 6, 1)\n"
    "        assertNull(NcieDeadlines.dateAt(today, 31, 2, \"2026\"))\n"
    "        assertNull(NcieDeadlines.dateAt(today, 0, 5, null))\n"
    "        assertNull(NcieDeadlines.dateAt(today, 10, 13, null))\n"
    "    }\n"
    "}\n"
)
TEST_NEW = (
    "    @Test\n"
    "    fun impossibleDatesAreRefused() {\n"
    "        val today = midnight(2026, 6, 1)\n"
    "        assertNull(NcieDeadlines.dateAt(today, 31, 2, \"2026\"))\n"
    "        assertNull(NcieDeadlines.dateAt(today, 0, 5, null))\n"
    "        assertNull(NcieDeadlines.dateAt(today, 10, 13, null))\n"
    "    }\n"
    "\n"
    "    @Test\n"
    "    fun aDeadlineWrittenForTodayIsOnTheRadarAllDay() {\n"
    "        val today = midnight(2026, 6, 1)\n"
    "        val horizon = today + 60L * 24 * 60 * 60 * 1000\n"
    "        // a date-only value resolves to the start of today, so by 14:30 it\n"
    "        // is already 'in the past' - it must still be due, not dropped\n"
    "        assertTrue(NcieDeadlines.onRadar(today, horizon, today))\n"
    "        assertTrue(NcieDeadlines.onRadar(today, horizon, today + 1))\n"
    "        assertTrue(NcieDeadlines.onRadar(today, horizon, horizon))\n"
    "        // yesterday is not due, and neither is anything past the horizon\n"
    "        assertTrue(!NcieDeadlines.onRadar(today, horizon, today - 1))\n"
    "        assertTrue(!NcieDeadlines.onRadar(today, horizon, horizon + 1))\n"
    "    }\n"
    "}\n"
)
edit(TEST + "ncie/android/NcieDeadlinesTest.kt", [
    (TEST_IMPORT_OLD, TEST_IMPORT_NEW, 1),
    (TEST_ANCHOR, TEST_NEW, 1),
])

# 4. verify_patches
VERIFY_ANCHOR = (
    'check("v9.33.84: version is at least 9.33.84 (252)",\n'
    '      version_at_least("9.33.84", 252))\n'
)
VERIFY_NEW = VERIFY_ANCHOR + (
    '\n'
    '# ---- v9.33.85 "A deadline written for today is due today" ----\n'
    '# A note date resolves to midnight, so it must be measured from the start\n'
    '# of today; a reminder keeps the `now` floor because one that already fired\n'
    '# is done. The two must not drift into each other.\n'
    '_dl85 = load("ncie/android/NcieDeadlines.kt")\n'
    'check("v9.33.85: a note date is due from the start of today",\n'
    '      "internal fun onRadar(today: Long, horizon: Long, at: Long): Boolean" in _dl85\n'
    '      and _dl85.count("if (onRadar(today, horizon, at))") == 4)\n'
    'check("v9.33.85: a reminder keeps the now floor",\n'
    '      "if (fireAt in now..horizon)" in _dl85\n'
    '      and "if (at in now..horizon)" not in _dl85)\n'
    'check("v9.33.85: the boundary is pinned by a unit test",\n'
    '      "fun aDeadlineWrittenForTodayIsOnTheRadarAllDay()" in\n'
    '      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieDeadlinesTest.kt"),\n'
    '           encoding="utf-8").read())\n'
    'check("v9.33.85: version is at least 9.33.85 (253)",\n'
    '      version_at_least("9.33.85", 253))\n'
)
edit("tmp/verify_patches.py", [(VERIFY_ANCHOR, VERIFY_NEW, 1)])

# self-verify
print("running verify_patches ...")
rc = subprocess.call([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
if rc != 0:
    raise SystemExit("FATAL: verify_patches failed after patching")
print("patch_engine88 OK")
