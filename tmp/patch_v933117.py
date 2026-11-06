#!/usr/bin/env python3
"""v9.33.117 "Aa and BB are not the same question".

The cached-answer key was built with String.hashCode:

    "qa_" + Integer.toHexString(question.lowercase(Locale.ROOT).hashCode()) +
        "_" + Integer.toHexString(model.hashCode()) + "_" + length

A 32-bit hash is not an identity. Java's String.hashCode collides on the
simplest possible pair - "Aa" and "BB" both hash to 2112 - and a study
session is full of short questions of the same shape ("what is a cell",
"what is a cell wall", "define osmosis"). Two questions that collide share
one cache entry, so the second one is answered with the first one's answer,
instantly, marked "(cached)": the exact shape of the v9.33.102 and v9.33.105
bugs, one layer down. The model label was hashed the same way, so switching
to a model whose name collided served answers the other model gave.

NOVA already has the right primitive: NcieFingerprint, a 64-bit FNV-1a over
the exact text (v9.33.102), which is what the embedding index and the summary
caches are keyed on. The answer cache uses it too now - one identity rule in
the app, not two.

Old entries are simply never read again (the family ceiling prunes them).

Self-verifying: every anchor must match exactly once, and the finished tree
is checked for the new markers before the script exits 0. Run from the repo
root (or pass it): python3 tmp/patch_v933117.py .
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


CACHE = BASE + "ncie/android/NcieCache.kt"
GRADLE = os.path.join(ROOT, "android/app/build.gradle")
VERIFY = os.path.join(ROOT, "tmp/verify_patches.py")
TEST = os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieCacheTest.kt")

# ------------------------------------------------------------- the one key
OLD_KEY = '''    fun qaKey(question: String, model: String, length: Int): String =
        "qa_" + Integer.toHexString(question.lowercase(java.util.Locale.ROOT).hashCode()) +
            "_" + Integer.toHexString(model.hashCode()) + "_" + length
'''

NEW_KEY = '''    fun qaKey(question: String, model: String, length: Int): String =
        "qa_" + java.lang.Long.toHexString(
            NcieFingerprint.ofText(question.lowercase(java.util.Locale.ROOT))) +
            "_" + java.lang.Long.toHexString(NcieFingerprint.ofText(model)) +
            "_" + length
'''

OLD_DOC = '''     * One function, one rule: the question in Locale.ROOT, the model label,
     * and the generation length that shaped the answer. Pure string work, so
     * a unit test can pin it.
     */'''

NEW_DOC = '''     * One function, one rule: the question in Locale.ROOT, the model label,
     * and the generation length that shaped the answer. Pure string work, so
     * a unit test can pin it.
     *
     * v9.33.117 "Aa and BB are not the same question": the question and the
     * model label were folded in with String.hashCode - 32 bits, and Java's
     * own collision pair ("Aa" and "BB" both hash to 2112) is two plausible
     * study questions. Two questions that collided shared one cache entry, so
     * the second was answered with the first one's answer, instantly and
     * marked "(cached)": the v9.33.102 / v9.33.105 shape one layer down. They
     * go through NcieFingerprint (64-bit FNV-1a over the exact text) now, the
     * same identity rule the embedding index and the summary caches use.
     */'''

cache = patch(CACHE, [(OLD_KEY, NEW_KEY), (OLD_DOC, NEW_DOC)])

# ---------------------------------------------------------------- version
gradle = read(GRADLE)
if gradle.count("versionCode 284") != 1 or gradle.count("versionName '9.33.116'") != 1:
    print("FATAL: build.gradle is not at 9.33.116 (284)")
    sys.exit(1)
gradle = gradle.replace("versionCode 284", "versionCode 285")
gradle = gradle.replace("versionName '9.33.116'", "versionName '9.33.117'")
write(GRADLE, gradle)

# ------------------------------------------------------------- unit tests
test = read(TEST)
OLD_TAIL = '''    @Test
    fun aPutLandsTheAnswer() {
        val filesDir = Files.createTempDirectory("novafiles").toFile()
        try {
            assertTrue(NcieCache.put(filesDir, "qa_x", "the answer"))
            assertEquals("the answer", File(NcieCache.dir(filesDir), "qa_x").readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
'''

NEW_TAIL = '''    @Test
    fun aPutLandsTheAnswer() {
        val filesDir = Files.createTempDirectory("novafiles").toFile()
        try {
            assertTrue(NcieCache.put(filesDir, "qa_x", "the answer"))
            assertEquals("the answer", File(NcieCache.dir(filesDir), "qa_x").readText())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    // v9.33.117 "Aa and BB are not the same question": the cached-answer key
    // was a 32-bit String.hashCode, and "Aa" / "BB" is Java's own collision
    // pair. Two questions that collide share one entry, so one is answered
    // with the other's answer, marked "(cached)".

    @Test
    fun theCanonicalHashCollisionIsNotOneCacheEntry() {
        assertEquals("the premise: these two strings really do collide",
            "Aa".hashCode(), "BB".hashCode())
        assertNotEquals("two questions, two entries",
            NcieCache.qaKey("Aa", "Qwen3 0.6B", 512),
            NcieCache.qaKey("BB", "Qwen3 0.6B", 512))
    }

    @Test
    fun twoModelLabelsThatCollideAreNotOneEntry() {
        assertEquals("the premise: these two labels really do collide",
            "Aa".hashCode(), "BB".hashCode())
        assertNotEquals("two models, two entries",
            NcieCache.qaKey("what is a cell", "Aa", 512),
            NcieCache.qaKey("what is a cell", "BB", 512))
    }

    @Test
    fun theSameQuestionIsStillOneEntry() {
        assertEquals(NcieCache.qaKey("What is a cell?", "Qwen3 0.6B", 512),
            NcieCache.qaKey("what is a cell?", "Qwen3 0.6B", 512))
        assertNotEquals("the answer length still shapes the key",
            NcieCache.qaKey("what is a cell?", "Qwen3 0.6B", 512),
            NcieCache.qaKey("what is a cell?", "Qwen3 0.6B", 900))
    }
}
'''

if test.count(OLD_TAIL) != 1:
    print("FATAL: the NcieCacheTest tail anchor did not match exactly once")
    sys.exit(1)
write(TEST, test.replace(OLD_TAIL, NEW_TAIL))

# ------------------------------------------------- teach the verifier
OLD_V116 = '''check("v9.33.116: version is at least 9.33.116 (284)",
      version_at_least("9.33.116", 284))
'''

NEW_V116 = '''check("v9.33.116: version is at least 9.33.116 (284)",
      version_at_least("9.33.116", 284))

# v9.33.117 "Aa and BB are not the same question": the cached-answer key was a
# 32-bit String.hashCode on the question and on the model label, and "Aa" /
# "BB" is Java's own collision pair - two questions, one entry, one of them
# answered with the other's answer, marked "(cached)".
_nc117 = load("ncie/android/NcieCache.kt")
check("v9.33.117: the answer cache key is an identity, not a 32-bit hash",
      _nc117.count("NcieFingerprint.ofText(") == 2 and
      _nc117.count(".hashCode()") == 0 and
      _nc117.count("Integer.toHexString(") == 0)
check("v9.33.117: one identity rule in the app",
      _nc117.count("java.lang.Long.toHexString(") == 2 and
      load("ncie/android/NcieFingerprint.kt").count("fun ofText(text: String): Long") == 1)
check("v9.33.117: the collision is pinned by a unit test",
      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieCacheTest.kt"),
           encoding="utf-8").read().count("@Test") >= 17)
check("v9.33.117: version is at least 9.33.117 (285)",
      version_at_least("9.33.117", 285))
'''

# v9.33.97 pinned the two facts that made its key correct - the locale the
# question is folded in, and the length that shaped the answer. Both survive
# the change of hash; they were written against the old spelling of the key,
# so they are rewritten to pin the INTENT (v9.33.113 did the same for its own
# check) rather than the verb that happened to be there.
OLD_97 = """check("v9.33.97: the key lowercases in Locale.ROOT, not the device locale",
      _nc.count("question.lowercase(java.util.Locale.ROOT).hashCode()") == 1)
check("v9.33.97: the key carries the generation length",
      _nc.count('"_" + Integer.toHexString(model.hashCode()) + "_" + length') == 1)"""
NEW_97 = """check("v9.33.97: the key lowercases in Locale.ROOT, not the device locale",
      # v9.33.117 changed how the question is folded in, not the locale
      _nc.count("question.lowercase(java.util.Locale.ROOT)") == 1)
check("v9.33.97: the key carries the generation length",
      _nc.count('"_" + length') == 1)"""

ver = read(VERIFY)
if ver.count(OLD_V116) != 1 or ver.count(OLD_97) != 1:
    print("FATAL: a verifier anchor did not match exactly once")
    sys.exit(1)
ver = ver.replace(OLD_V116, NEW_V116).replace(OLD_97, NEW_97)
write(VERIFY, ver)

# ---------------------------------------------------------------- verify
print("")
check("cache: the question goes through the fingerprint",
      "NcieFingerprint.ofText(question.lowercase(java.util.Locale.ROOT))" in cache)
check("cache: the model label goes through the fingerprint",
      "NcieFingerprint.ofText(model)" in cache)
check("cache: no 32-bit hash is left in the key",
      ".hashCode()" not in cache and "Integer.toHexString(" not in cache)
check("cache: the family prefix is unchanged", '"qa_" +' in cache)
check("version is 9.33.117 (285)",
      "versionName '9.33.117'" in gradle and "versionCode 285" in gradle)
check("the verifier knows this version",
      'version_at_least("9.33.117", 285)' in read(VERIFY))
check("NcieCacheTest pins the collision (source)", "theCanonicalHashCollisionIsNotOneCacheEntry" in read(TEST))


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


for name, text in [("NcieCache.kt", cache), ("NcieCacheTest.kt", read(TEST))]:
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
print("PATCH OK: v9.33.117 applied and self-verified")
