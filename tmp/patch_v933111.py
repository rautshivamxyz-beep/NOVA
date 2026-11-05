#!/usr/bin/env python3
"""v9.33.111 "A save that cannot throw is not a save that succeeded, part 2".

v9.33.90 replaced every bare state write with NcieFiles.write, which NEVER
throws: a failed save returns false and is counted in the swallowed-save log.
v9.33.108 then fixed the focus session, where a `try { write() } catch {
"couldn't save" }` had become dead code that reported success anyway.

These three call sites still carried that dead catch:

  * NcieExam.setExamDate - the exam date. This one was the worst: the reply
    reads the day count straight back off disk, so a date that never reached
    storage was answered with "Exam date set: 15 Feb 2027. 0 days to go."
    The user is told the countdown started, and it did not.
  * NcieExam "plan my revision" - the saved plan. The plan was shown in chat
    as if it had been stored, and "show my revision plan" then said there was
    none, with nothing anywhere explaining the difference.
  * NcieTune.setCommand - a sampling override. "temperature set to 0.5" was
    said for a value the next "model settings" listing showed unchanged.

Each now asks the one save function for its answer, and NcieExam no longer
invents "0 days to go" for a date that did not come back off disk.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933111.py .
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = os.path.join(ROOT, "android/app/src/main/java/org/nova/ncie/android/")

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
    """Apply (old, new) pairs to [path], each exactly once."""
    text = read(path)
    for old, new in pairs:
        n = text.count(old)
        if n != 1:
            print("FATAL: anchor matched %d times in %s:\n%s" % (n, path, old))
            sys.exit(1)
        text = text.replace(old, new)
    write(path, text)
    return text


EXAM = BASE + "NcieExam.kt"
TUNE = BASE + "NcieTune.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")

# ---------------------------------------------------------------- NcieExam
exam = patch(EXAM, [
    (
        r'''        val ms = c.timeInMillis
        try {
            NcieFiles.write(dateFile(act), ms.toString() + "\n")
        } catch (e: Exception) {
            postReply(act, "Could not save the exam date.")
            return
        }
''',
        r'''        val ms = c.timeInMillis
        // v9.33.111: NcieFiles.write has not thrown since v9.33.90 - it
        // RETURNS false when the save fails - so this try/catch was dead and
        // a save that never reached the disk was reported as "Exam date
        // set". The countdown below reads the date straight back off disk,
        // so the user was then told "0 days to go" about a date that was
        // never stored. Ask the save for its answer (the v9.33.108 rule).
        if (!NcieFiles.write(dateFile(act), ms.toString() + "\n")) {
            postReply(act, "Could not save the exam date - NOVA could not write it to storage.")
            return
        }
'''),
    (
        r'''        val fmt = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
        val d = daysLeft(act) ?: 0
        postReply(act, "Exam date set: $fmt. $d days to go.")
''',
        r'''        val fmt = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(ms))
        // v9.33.111: `?: 0` turned a failed round-trip into "0 days to go".
        // Null here means the date did not come back off disk; say only what
        // is true instead of inventing a countdown to today.
        val d = daysLeft(act)
        postReply(act, "Exam date set: $fmt." +
            (if (d == null) "" else " $d days to go."))
'''),
    (
        r'''        var written = 0
        try {
            val lines = StringBuilder()
''',
        r'''        var written = 0
        // v9.33.111: the write no longer throws (v9.33.90), so the catch
        // below never reported a failed save - the plan was shown as if it
        // had been stored, and "show my revision plan" then said there was
        // none. Keep the catch for the calendar work and read the save's own
        // answer instead.
        val saved = try {
            val lines = StringBuilder()
'''),
    (
        r'''            NcieFiles.write(planFile(act), lines.toString())
        } catch (e: Exception) {
            postReply(act, "Could not save the revision plan.")
            return
        }
''',
        r'''            NcieFiles.write(planFile(act), lines.toString())
        } catch (e: Exception) {
            NovaLog.quiet("NcieExam", e)
            false
        }
'''),
    (
        r'''        sb.append(".\n```")
        postReply(act, sb.toString())
''',
        r'''        sb.append(".\n```")
        if (!saved) sb.append("\n\nNot saved - NOVA could not write the plan to storage, " +
            "so \"show my revision plan\" will not find it.")
        postReply(act, sb.toString())
'''),
])

# ---------------------------------------------------------------- NcieTune
tune = patch(TUNE, [
    (
        r'''        try {
            val sb = StringBuilder()
            for (k in KEYS) if (k in vals) sb.append(k).append('=').append(fmt(vals.getValue(k))).append('\n')
            NcieFiles.write(fileFor(c, m.first), sb.toString())
        } catch (e: Exception) {
            return "Could not save the setting (storage error)."
        }
''',
        r'''        // v9.33.111: NcieFiles.write returns false instead of throwing
        // (v9.33.90), so this catch was dead and an unsaved setting was
        // reported as set - the next "model settings" then showed the old
        // value with no explanation. Keep the catch for the formatting and
        // read the save's own answer.
        val saved = try {
            val sb = StringBuilder()
            for (k in KEYS) if (k in vals) sb.append(k).append('=').append(fmt(vals.getValue(k))).append('\n')
            NcieFiles.write(fileFor(c, m.first), sb.toString())
        } catch (e: Exception) {
            NovaLog.quiet("NcieTune", e)
            false
        }
        if (!saved) return "Could not save the setting (storage error)."
'''),
])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 278") != 1 or gradle.count("versionName '9.33.110'") != 1:
    print("FATAL: build.gradle is not at 9.33.110 (278)")
    sys.exit(1)
gradle = gradle.replace("versionCode 278", "versionCode 279")
gradle = gradle.replace("versionName '9.33.110'", "versionName '9.33.111'")
write(GRADLE, gradle)

# ------------------------------------------------- teach the verifier
OLD_TAIL = '''check("v9.33.110: version is at least 9.33.110 (278)",
      version_at_least("9.33.110", 278))
'''
NEW_TAIL = '''# v9.33.111 "A save that cannot throw is not a save that succeeded, part 2":
# v9.33.90 made NcieFiles.write RETURN false instead of throwing, so every
# try/catch that used its catch to report a failed save became dead code that
# reported success instead. v9.33.108 fixed the focus session; these three
# call sites still lied - the exam date, the revision plan and the tuning
# override - and the exam one was the worst: the countdown is read back off
# disk, so a date that never stuck was answered with "0 days to go".
check("v9.33.111: the exam date asks the save for its answer",
      ne.count("if (!NcieFiles.write(dateFile(act), ms.toString()") == 1 and
      ne.count('"Could not save the exam date."') == 0)
check("v9.33.111: no countdown is invented for a date that did not stick",
      ne.count("val d = daysLeft(act) ?: 0") == 0 and
      ne.count('(if (d == null) "" else " $d days to go.")') == 1)
check("v9.33.111: the revision plan asks the save for its answer",
      ne.count("val saved = try {") == 1 and
      ne.count('"Could not save the revision plan."') == 0 and
      ne.count("if (!saved) sb.append(") == 1)
check("v9.33.111: the tuning override asks the save for its answer",
      tun.count("val saved = try {") == 1 and
      tun.count('if (!saved) return "Could not save the setting (storage error)."') == 1)
check("v9.33.111: version is at least 9.33.111 (279)",
      version_at_least("9.33.111", 279))
'''
ver = read(VERIFY)
if ver.count(OLD_TAIL) != 1:
    print("FATAL: the verifier tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_TAIL, NEW_TAIL))

# ---------------------------------------------------------------- verify
print("")
check("exam: the save is asked for its answer",
      exam.count("if (!NcieFiles.write(dateFile(act), ms.toString()") == 1)
check("exam: the dead catch is gone", '"Could not save the exam date."' not in exam)
check("exam: no invented countdown", "val d = daysLeft(act) ?: 0" not in exam)
check("exam: the plan reports a failed save",
      'if (!saved) sb.append(' in exam and '"Could not save the revision plan."' not in exam)
check("tune: the save is asked for its answer",
      'if (!saved) return "Could not save the setting (storage error)."' in tune)
check("version is 9.33.111 (279)",
      "versionName '9.33.111'" in gradle and "versionCode 279" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.111", 279)' in read(VERIFY))
for name, text in [("NcieExam.kt", exam), ("NcieTune.kt", tune)]:
    check("%s: braces balance" % name, text.count("{") == text.count("}"))
    check("%s: parens balance" % name, text.count("(") == text.count(")"))
    bad = []
    for m in re.finditer(r'"(?:[^"\\]|\\.)*"', text):
        for e in re.finditer(r"\\(.)", m.group(0)):
            if e.group(1) not in "tbnr\"'\\$su":
                bad.append(e.group(1))
    check("%s: no invalid string escapes" % name, not bad)

print("")
if fails:
    print("PATCH FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("PATCH OK: v9.33.111 applied and self-verified")
