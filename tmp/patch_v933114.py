#!/usr/bin/env python3
"""v9.33.114 "One card rule".

v9.33.109 "One flashcard count" put the definition of a card in NcieDeck,
because the briefing and the dream report counted the same deck two different
ways and printed two numbers on one screen.

The COUNT was centralised. The PARSE was not: NcieTutor still carries its own
copy of the rule - where the separator is, what counts as an empty front or
back - and its own copy of the separator character. So the deck the user is
told they have (NcieDeck.count, used by both reports) and the deck the quiz
can actually read (NcieTutor.readCards) are decided by two separate pieces of
code that happen to agree today.

That is exactly the shape of the bug v9.33.109 fixed, one layer down: change
one side and the app is back to reporting five cards and quizzing three, with
nothing saying why. The quiz now asks NcieDeck whether a line is a card, and
takes the separator from NcieDeck, so there is one rule in one place.

No behaviour change is intended: NcieDeck.isCard requires a separator with
something on both sides, which is what the tutor's copy required too. The old
filter that dropped empty fronts and backs is gone because isCard already
guarantees both sides are filled.

Self-verifying: every anchor must match exactly once, and the finished tree is
checked for the new markers before the script exits 0. Run from the repo root
(or pass it): python3 tmp/patch_v933114.py .
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


TUTOR = BASE + "ncie/android/NcieTutor.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")

tutor = patch(TUTOR, [
    (
        r'''    /** WikiCore's line format separator (unit-separator symbol (Kotlin escape \u241F, like WikiCore.kt)). */
    private const val SEP = '\u241F'
''',
        r'''    /** v9.33.114: the separator comes from NcieDeck, where the card rule
     *  lives - a second copy of the character is a second way for the deck to
     *  be read, which is what v9.33.109 set out to end. */
    private const val SEP = NcieDeck.SEP
'''),
    (
        r'''    private fun readCards(ctx: Context): List<Pair<String, String>> = try {
        val f = flashFile(ctx)
        if (!f.exists()) emptyList()
        else f.readLines().mapNotNull { l ->
            val i = l.indexOf(SEP)
            if (i < 1) null
            else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
    } catch (e: Exception) { emptyList() }
''',
        r'''    private fun readCards(ctx: Context): List<Pair<String, String>> = try {
        val f = flashFile(ctx)
        if (!f.exists()) emptyList()
        // v9.33.114: ask NcieDeck whether a line is a card. v9.33.109 gave
        // the deck ONE count; the rule that decides what a card is was still
        // written twice, here and there. Two copies agree until one changes,
        // and then the briefing reports a deck the quiz cannot read.
        else f.readLines().mapNotNull { l ->
            if (!NcieDeck.isCard(l)) return@mapNotNull null
            val i = l.indexOf(SEP)
            l.substring(0, i).trim() to l.substring(i + 1).trim()
        }
    } catch (e: Exception) { emptyList() }
'''),
])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 281") != 1 or gradle.count("versionName '9.33.113'") != 1:
    print("FATAL: build.gradle is not at 9.33.113 (281)")
    sys.exit(1)
gradle = gradle.replace("versionCode 281", "versionCode 282")
gradle = gradle.replace("versionName '9.33.113'", "versionName '9.33.114'")
write(GRADLE, gradle)

# ------------------------------------------------- teach the verifier
OLD_TAIL = '''check("v9.33.113: version is at least 9.33.113 (281)",
      version_at_least("9.33.113", 281))
'''
NEW_TAIL = '''# v9.33.114 "One card rule": v9.33.109 centralised the flashcard COUNT in
# NcieDeck but left the PARSE - where the separator is and what counts as an
# empty side - written twice, once in NcieDeck and once in NcieTutor. Two
# copies agree until one changes, and then the briefing reports a deck the
# quiz cannot read: the same bug v9.33.109 fixed, one layer down.
check("v9.33.114: the quiz asks NcieDeck what a card is",
      load("ncie/android/NcieTutor.kt").count("if (!NcieDeck.isCard(l)) return@mapNotNull null") == 1 and
      load("ncie/android/NcieTutor.kt").count("l.indexOf(SEP)") == 1)
check("v9.33.114: the quiz takes the separator from NcieDeck",
      load("ncie/android/NcieTutor.kt").count("private const val SEP = NcieDeck.SEP") == 1 and
      load("ncie/android/NcieTutor.kt").count("const val SEP = '") == 0)
check("v9.33.114: the one card rule is still the one in NcieDeck",
      _deck.count("fun isCard(line: String): Boolean") == 1 and
      _deck.count("const val SEP = '") == 1)
check("v9.33.114: version is at least 9.33.114 (282)",
      version_at_least("9.33.114", 282))
'''
ver = read(VERIFY)
if ver.count(OLD_TAIL) != 1:
    print("FATAL: the verifier tail anchor did not match exactly once")
    sys.exit(1)
write(VERIFY, ver.replace(OLD_TAIL, NEW_TAIL))

# ---------------------------------------------------------------- verify
print("")
check("tutor: the parse asks NcieDeck",
      "if (!NcieDeck.isCard(l)) return@mapNotNull null" in tutor)
check("tutor: the separator is NcieDeck's",
      "private const val SEP = NcieDeck.SEP" in tutor and "const val SEP = '" not in tutor)
check("tutor: the old duplicate rule is gone",
      "if (i < 1) null" not in tutor and
      ".filter { it.first.isNotEmpty() && it.second.isNotEmpty() }" not in tutor)
check("version is 9.33.114 (282)",
      "versionName '9.33.114'" in gradle and "versionCode 282" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.114", 282)' in read(VERIFY))
check("NcieTutor.kt: braces balance", tutor.count("{") == tutor.count("}"))
check("NcieTutor.kt: parens balance", tutor.count("(") == tutor.count(")"))
bad = []
for m in re.finditer(r'"(?:[^"\\]|\\.)*"', tutor):
    for e in re.finditer(r"\\(.)", m.group(0)):
        if e.group(1) not in "tbnr\"'\\$su":
            bad.append(e.group(1))
check("NcieTutor.kt: no invalid string escapes", not bad)

print("")
if fails:
    print("PATCH FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("PATCH OK: v9.33.114 applied and self-verified")
