#!/usr/bin/env python3
"""v9.33.113 "Setting the exam date again does not add a second exam".

v9.16.3 connected the two exam stores: "my exam is on 15 feb" writes the
countdown file (exam_date.txt) AND the exam list (exams.json) so the Exams
screen and the model's exam line both know about it.

It connected them with Exams.add, which only ever appends. So the second
time the user said "my exam is on 20 march" - the ordinary way anyone
corrects a date - exams.json held BOTH rows:

    Exam  in 102 days (15 Feb)
    Exam  in 135 days (20 Mar)

and that line went into the prompt of every turn. The model, asked how long
until the exam, had two answers and picked one. The Exams screen showed a
row the user never added. And the chat countdown, which reads exam_date.txt,
disagreed with both.

One name is one exam: Exams.set replaces the row of that name instead of
adding to it, and the chat path uses it. A user who really has two exams
with the same name can still add both from the Exams screen.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933113.py .
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


EXAMS = BASE + "Exams.kt"
NCIEEXAM = BASE + "ncie/android/NcieExam.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")

exams = patch(EXAMS, [
    (
        r'''    fun add(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.add(Exam(name, dateMs))
        save(ctx, all)
    }
''',
        r'''    fun add(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.add(Exam(name, dateMs))
        save(ctx, all)
    }

    /**
     * v9.33.113 "Setting the exam date again does not add a second exam":
     * one name is one exam.
     *
     * The chat path used [add], which only appends, so correcting the date -
     * the ordinary thing to do when a date moves - left the OLD row in the
     * list for ever. "Exam in 102 days (15 Feb); Exam in 135 days (20 Mar)"
     * then went into the prompt of every turn: two answers to one question,
     * and the model picked one. The Exams screen showed a row the user never
     * added, and the chat countdown (exam_date.txt) disagreed with both.
     */
    fun set(ctx: Context, name: String, dateMs: Long) {
        val all = load(ctx)
        all.removeAll { it.name.equals(name, ignoreCase = true) }
        all.add(Exam(name, dateMs))
        save(ctx, all)
    }
'''),
])

ncieexam = patch(NCIEEXAM, [
    (
        r'''        // v9.16.3: also record it in the Exams store (exams.json) so the
        // Exams screen shows it AND the model's exam line (Exams.promptLine)
        // includes it - the two stores used to be disconnected.
        try { Exams.add(act, "Exam", ms) } catch (e: Exception) { NovaLog.quiet("NcieExam", e) }
''',
        r'''        // v9.16.3: also record it in the Exams store (exams.json) so the
        // Exams screen shows it AND the model's exam line (Exams.promptLine)
        // includes it - the two stores used to be disconnected.
        // v9.33.113: set, not add. add appended, so setting the date a second
        // time left the old one in the list and in every prompt.
        try { Exams.set(act, "Exam", ms) } catch (e: Exception) { NovaLog.quiet("NcieExam", e) }
'''),
])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 280") != 1 or gradle.count("versionName '9.33.112'") != 1:
    print("FATAL: build.gradle is not at 9.33.112 (280)")
    sys.exit(1)
gradle = gradle.replace("versionCode 280", "versionCode 281")
gradle = gradle.replace("versionName '9.33.112'", "versionName '9.33.113'")
write(GRADLE, gradle)

# ------------------------------------------------- teach the verifier
# v9.33.113: the v9.16.3 check pinned the call itself - "Exams.add(act, ...)"
# in NcieExam. The intent was "the chat exam also lands in the Exams store",
# not "the store is appended to", so the check now asks for the store write
# whatever the verb, and v9.33.113 asks separately that the verb is set().
OLD_V163 = '''check("v9.16.3: chat exam also lands in the Exams store (NcieExam)",
      _nex3.count("Exams.add(act, \\"Exam\\", ms)") == 1)
'''
NEW_V163 = '''# v9.33.113: was Exams.add - the intent is that the chat exam reaches the
# store, not that the store is appended to; set() replaces one name's row.
check("v9.16.3: chat exam also lands in the Exams store (NcieExam)",
      _nex3.count("Exams.set(act, \\"Exam\\", ms)") == 1)
'''
OLD_TAIL = '''check("v9.33.112: version is at least 9.33.112 (280)",
      version_at_least("9.33.112", 280))
'''
NEW_TAIL = '''# v9.33.113 "Setting the exam date again does not add a second exam": v9.16.3
# connected exam_date.txt to exams.json with Exams.add, which only appends, so
# correcting the date left the old row in the list for ever - and in the prompt
# of every turn, where the model had two answers to "how long until my exam".
check("v9.33.113: one name is one exam",
      _ex.count("fun set(ctx: Context, name: String, dateMs: Long)") == 1 and
      _ex.count("all.removeAll { it.name.equals(name, ignoreCase = true) }") == 1)
check("v9.33.113: the chat path replaces the date instead of adding one",
      ne.count('Exams.set(act, "Exam", ms)') == 1 and
      ne.count('Exams.add(act, "Exam", ms)') == 0)
check("v9.33.113: the manual add still adds",
      _ex.count("fun add(ctx: Context, name: String, dateMs: Long)") == 1)
check("v9.33.113: version is at least 9.33.113 (281)",
      version_at_least("9.33.113", 281))
'''
ver = read(VERIFY)
if ver.count(OLD_V163) != 1:
    print("FATAL: the v9.16.3 verifier check did not match exactly once")
    sys.exit(1)
ver = ver.replace(OLD_V163, NEW_V163)
if ver.count(OLD_TAIL) != 1:
    print("FATAL: the verifier tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_TAIL, NEW_TAIL))

# ---------------------------------------------------------------- verify
print("")
check("exams: set replaces the row of that name",
      "fun set(ctx: Context, name: String, dateMs: Long) {" in exams and
      "all.removeAll { it.name.equals(name, ignoreCase = true) }" in exams)
check("exams: add is untouched",
      "all.add(Exam(name, dateMs))" in exams)
check("ncieexam: the chat path uses set",
      'Exams.set(act, "Exam", ms)' in ncieexam and 'Exams.add(act, "Exam", ms)' not in ncieexam)
check("version is 9.33.113 (281)",
      "versionName '9.33.113'" in gradle and "versionCode 281" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.113", 281)' in read(VERIFY))
for name, text in [("Exams.kt", exams), ("NcieExam.kt", ncieexam)]:
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
print("PATCH OK: v9.33.113 applied and self-verified")
