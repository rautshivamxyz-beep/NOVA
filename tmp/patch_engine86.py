#!/usr/bin/env python3
"""NOVA one-shot patch for v9.33.83 "lookup quotes the line the user meant".

Deterministic, idempotent, self-verifying (ends by running verify_patches.py).
Usage: patch_engine86.py <NOVA repo root>

Audit finding (continuous audit stream, first pass over the v9.33.82 release):

  F2 "notes lookup" (v9.33.80) quotes the user's own line verbatim, so the
  ONLY thing that can go wrong is picking the wrong line. It picked on
  Tokens.contentWords(query) with no function-word filter, while the rest of
  the retrieval path filters through NcieGround.STOP. Two defects followed:

  A. "what is the boiling point of water" handed excerpt() the five words
     what/the/boiling/point/water, so a distractor line that merely said
     "what" and "the" outscored the line that said "boiling". The quoted line
     was often the wrong one.
  B. window() centred the 240-character slice on a plain indexOf, so "the"
     matched inside "There" and "art" matched inside "startup". The slice was
     then centred on a substring that had nothing to do with the question and
     frequently did not contain the word the user searched for.

What it does
  1. build.gradle      : versionCode 250 -> 251, versionName 9.33.82 -> 9.33.83
  2. Tokens.kt         : the function-word list moves here as Tokens.STOP -
                         ONE definition, in the pure file (the v9.33.76 rule:
                         one name per rule)
  3. NcieGround.kt     : STOP becomes Tokens.STOP (behaviour identical)
  4. NcieLookup.kt     : termsOf() filters the query through Tokens.STOP;
                         firstWord() centres the window on a whole word
  5. NcieLookupTest.kt : four tests pin both defects
  6. verify_patches.py : five checks, the version floor at 9.33.83/251
"""
import os
import re
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
    ("versionCode 250", "versionCode 251", 1),
    ("versionName '9.33.82'", "versionName '9.33.83'", 1),
])

# 2. Tokens.kt - the function-word list, one definition, in the pure file
TOKENS_ANCHOR = (
    "    fun contentWords(s: String, minLen: Int = 3): List<String> = words(s, minLen)\n"
    "\n"
    "    /** Space-padded normalized form for whole-word matching (\" term \"). */\n"
)
TOKENS_NEW = (
    "    fun contentWords(s: String, minLen: Int = 3): List<String> = words(s, minLen)\n"
    "\n"
    "    /**\n"
    "     * v9.33.83 \"Lookup ranking\": the ONE list of function words on the\n"
    "     * retrieval path - the words a question carries for grammar, not for\n"
    "     * meaning. Moved here verbatim from NcieGround (which now reads it as\n"
    "     * Tokens.STOP) because a second caller needs it: the notes lookup ranks\n"
    "     * its quoted line on the question's content words, and a private list\n"
    "     * two packages away is how the v9.33.76 drift happened in the first\n"
    "     * place. This file is pure Kotlin, so the list is reachable from a JVM\n"
    "     * unit test with no Android on the classpath.\n"
    "     *\n"
    "     * English first, then the Devanagari function words (v9.33.23) so a\n"
    "     * Hindi/Marathi question's content terms are not diluted by its\n"
    "     * grammar words.\n"
    "     */\n"
    "    val STOP: Set<String> = setOf(\"what\", \"who\", \"when\", \"where\", \"why\", \"how\", \"the\", \"and\",\n"
    "        \"for\", \"are\", \"was\", \"were\", \"is\", \"does\", \"did\", \"do\", \"with\", \"about\",\n"
    "        \"tell\", \"explain\", \"describe\", \"which\", \"that\", \"this\", \"from\", \"many\",\n"
    "        \"much\", \"some\", \"give\", \"list\", \"name\", \"then\", \"than\", \"into\", \"also\",\n"
    "        \"my\", \"notes\", \"note\", \"documents\", \"document\",\n"
    "        \"है\", \"हैं\", \"था\", \"थी\", \"थे\", \"का\", \"की\", \"के\", \"को\", \"में\", \"से\", \"पर\", \"और\", \"या\", \"यह\", \"ये\", \"वह\", \"वो\", \"कि\", \"क्या\", \"कौन\", \"कब\", \"कहाँ\", \"कहां\", \"क्यों\", \"कैसे\", \"ने\", \"भी\", \"तो\", \"ही\", \"नहीं\", \"न\", \"कर\", \"करें\", \"हो\", \"होता\", \"होती\", \"होते\", \"जो\", \"लिए\", \"कुछ\", \"कोई\", \"इस\", \"उस\", \"इन\", \"उन\", \"वाला\", \"वाली\", \"वाले\", \"मेरा\", \"मेरी\", \"मेरे\", \"तुम\", \"आप\", \"मैं\", \"हम\", \"अपना\", \"अपनी\", \"आहे\", \"आहेत\", \"चा\", \"ची\", \"चे\", \"ला\", \"मध्ये\", \"आणि\", \"किंवा\", \"नाही\", \"हा\", \"ती\", \"ते\")\n"
    "\n"
    "    /** Space-padded normalized form for whole-word matching (\" term \"). */\n"
)
edit(BASE + "ncie/Tokens.kt", [(TOKENS_ANCHOR, TOKENS_NEW, 1)])

# 3. NcieGround.kt - STOP is now the shared list, same contents
g = read(BASE + "ncie/android/NcieGround.kt")
g2, n = re.subn(
    r'    private val STOP = setOf\("what".*?"ती", "ते"\)\n',
    '    // v9.33.83: the list itself moved to Tokens.STOP (one definition on\n'
    '    // the retrieval path). The contents are unchanged, so questionTerms()\n'
    '    // behaves exactly as before.\n'
    '    private val STOP = Tokens.STOP\n',
    g, count=1, flags=re.S)
if n != 1:
    raise SystemExit("FATAL ncie/android/NcieGround.kt: the STOP literal was not found exactly once")
write(BASE + "ncie/android/NcieGround.kt", g2)

# 4. NcieLookup.kt - rank and centre on content words only
LOOK_TERMS_OLD = "        val terms = Tokens.contentWords(query)\n"
LOOK_TERMS_NEW = "        val terms = termsOf(query)\n"

LOOK_HELPERS_OLD = "    /** A flat, bounded slice of one line, centred on the first matched word. */\n"
LOOK_HELPERS_NEW = (
    "    /**\n"
    "     * v9.33.83: the query's content words, with the shared function-word list\n"
    "     * removed. \"what is the boiling point of water\" used to hand [excerpt]\n"
    "     * the five words what/the/boiling/point/water, so a distractor line that\n"
    "     * merely said \"what\" and \"the\" outscored the line that said \"boiling\"\n"
    "     * and the user was quoted the wrong line. A query made only of function\n"
    "     * words keeps its words, so the excerpt still has something to look for.\n"
    "     */\n"
    "    internal fun termsOf(query: String): List<String> {\n"
    "        val all = Tokens.contentWords(query)\n"
    "        val content = all.filter { it !in Tokens.STOP }\n"
    "        return if (content.isEmpty()) all else content\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v9.33.83: the first WHOLE-WORD occurrence of [term] in [lower], or -1.\n"
    "     * A plain indexOf found \"the\" inside \"There\" and \"art\" inside\n"
    "     * \"startup\", so a long line was sliced around a substring that had\n"
    "     * nothing to do with the question and the quoted window often did not\n"
    "     * contain the word the user searched for. The boundaries are checked by\n"
    "     * hand because Java's word-boundary anchor is ASCII-only and would never\n"
    "     * match a Devanagari word at all.\n"
    "     */\n"
    "    private fun firstWord(lower: String, term: String): Int {\n"
    "        if (term.isEmpty()) return -1\n"
    "        var i = lower.indexOf(term)\n"
    "        while (i >= 0) {\n"
    "            val end = i + term.length\n"
    "            val before = i <= 0 || !isWordChar(lower[i - 1])\n"
    "            val after = end >= lower.length || !isWordChar(lower[end])\n"
    "            if (before && after) return i\n"
    "            i = lower.indexOf(term, i + 1)\n"
    "        }\n"
    "        return -1\n"
    "    }\n"
    "\n"
    "    /** Letters, digits and combining marks of any script are one word. */\n"
    "    private fun isWordChar(c: Char): Boolean {\n"
    "        if (Character.isLetterOrDigit(c)) return true\n"
    "        val t = Character.getType(c)\n"
    "        return t == Character.NON_SPACING_MARK.toInt() ||\n"
    "            t == Character.COMBINING_SPACING_MARK.toInt()\n"
    "    }\n"
    "\n"
    "    /** A flat, bounded slice of one line, centred on the first matched word. */\n"
)
edit(BASE + "ncie/android/NcieLookup.kt", [
    (LOOK_TERMS_OLD, LOOK_TERMS_NEW, 1),
    ("        val lower = flat.lowercase()\n",
     "        // v9.33.83: keep the indices usable - a few code points lowercase to\n"
     "        // two characters, which would shift every index in this function.\n"
     "        val lower = flat.lowercase().let { if (it.length == flat.length) it else flat }\n", 1),
    ("            val i = lower.indexOf(t)\n", "            val i = firstWord(lower, t)\n", 1),
    (LOOK_HELPERS_OLD, LOOK_HELPERS_NEW, 1),
])

# 5. the unit tests
TEST_ANCHOR = (
    "    @Test\n"
    "    fun aCodeFenceInTheNotesCannotBreakTheReply() {\n"
    "        val hits = listOf(\"Notes\" to \"here is code ```python\\nx = 1\\n``` done\")\n"
    "        val body = NcieLookup.body(\"code\", hits, 1)\n"
    "        assertEquals(2, Regex(\"```\").findAll(body).count())\n"
    "    }\n"
    "}\n"
)
TEST_NEW = (
    "    @Test\n"
    "    fun aCodeFenceInTheNotesCannotBreakTheReply() {\n"
    "        val hits = listOf(\"Notes\" to \"here is code ```python\\nx = 1\\n``` done\")\n"
    "        val body = NcieLookup.body(\"code\", hits, 1)\n"
    "        assertEquals(2, Regex(\"```\").findAll(body).count())\n"
    "    }\n"
    "\n"
    "    @Test\n"
    "    fun aFunctionWordDoesNotOutrankTheContentWord() {\n"
    "        // \"what is the boiling point of water\" - the first line holds what\n"
    "        // and the and point, the second holds boiling and water. Ranking on\n"
    "        // every query word quoted the first line; ranking on content words\n"
    "        // quotes the one the user asked about.\n"
    "        val chunk = \"What is the point of this? The whole thing is what it is.\\n\" +\n"
    "            \"Boiling water at sea level reaches 100 degrees.\"\n"
    "        assertEquals(\"Boiling water at sea level reaches 100 degrees.\",\n"
    "            NcieLookup.excerpt(chunk, \"what is the boiling point of water\"))\n"
    "    }\n"
    "\n"
    "    @Test\n"
    "    fun theWindowIsCentredOnTheWholeWordNotASubstring() {\n"
    "        // \"the\" first occurs inside \"There\", so the old slice started at\n"
    "        // character 0 and stopped before the word the user searched for.\n"
    "        val line = \"There is a lot of background here about many unrelated things, \" +\n"
    "            \"and the paragraph rambles on about cooking, storage and other matters \" +\n"
    "            \"before it finally mentions the boiling point of water at altitude.\"\n"
    "        val ex = NcieLookup.excerpt(line, \"what is the boiling point of water\", 120)\n"
    "        assertTrue(ex.contains(\"boiling\"))\n"
    "    }\n"
    "\n"
    "    @Test\n"
    "    fun aShortWordIsNotFoundInsideALongerOne() {\n"
    "        // \"art\" only ever appears as the start of \"startup\" and \"part\"\n"
    "        // early in the line - the quote must be the standalone word.\n"
    "        val line = \"The startup of the machine takes a long time, and this part \" +\n"
    "            \"of the process is slow, but the art of tuning it is knowing where \" +\n"
    "            \"to begin and the rest is practice and patience over many weeks.\"\n"
    "        val ex = NcieLookup.excerpt(line, \"art\", 90)\n"
    "        assertTrue(ex.contains(\" art \"))\n"
    "    }\n"
    "\n"
    "    @Test\n"
    "    fun aQueryOfOnlyFunctionWordsStillQuotesALine() {\n"
    "        assertEquals(\"First line here.\",\n"
    "            NcieLookup.excerpt(\"First line here.\\nSecond line there.\", \"what is it\"))\n"
    "    }\n"
    "}\n"
)
edit(TEST + "ncie/android/NcieLookupTest.kt", [(TEST_ANCHOR, TEST_NEW, 1)])

# 6. verify_patches - the new checks + the version floor
VERIFY_ANCHOR = (
    'check("v9.33.82: the notes still win the study rewrap",\n'
    '      "if (studyQ && docPart.isEmpty() && knowledgePart.isNotEmpty()) {" in nc)\n'
)
VERIFY_NEW = VERIFY_ANCHOR + (
    '\n'
    '# ---- v9.33.83 "Lookup quotes the line the user meant" ----\n'
    '# The lookup answers with the user\'s own line and no model, so the only\n'
    '# failure mode is the WRONG line. It must rank on content words (the shared\n'
    '# function-word list, not a private copy) and must centre the quote on a\n'
    '# whole word rather than on a substring that merely contains one.\n'
    '_tok83 = load("ncie/Tokens.kt")\n'
    '_ground83 = load("ncie/android/NcieGround.kt")\n'
    'check("v9.33.83: the function-word list has ONE definition (Tokens.STOP)",\n'
    '      "val STOP: Set<String> = setOf(" in _tok83\n'
    '      and "private val STOP = Tokens.STOP" in _ground83)\n'
    'check("v9.33.83: the lookup ranks on content words, not on function words",\n'
    '      "val content = all.filter { it !in Tokens.STOP }" in _look\n'
    '      and "val terms = termsOf(query)" in _look)\n'
    'check("v9.33.83: the quoted window is centred on a whole word",\n'
    '      "private fun firstWord(lower: String, term: String): Int" in _look\n'
    '      and "val i = firstWord(lower, t)" in _look)\n'
    'check("v9.33.83: the lookup ranking is pinned by unit tests",\n'
    '      "fun aFunctionWordDoesNotOutrankTheContentWord()" in\n'
    '      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieLookupTest.kt"),\n'
    '           encoding="utf-8").read()\n'
    '      and "fun theWindowIsCentredOnTheWholeWordNotASubstring()" in\n'
    '      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieLookupTest.kt"),\n'
    '           encoding="utf-8").read())\n'
    'check("v9.33.83: version is at least 9.33.83 (251)",\n'
    '      version_at_least("9.33.83", 251))\n'
)
edit("tmp/verify_patches.py", [(VERIFY_ANCHOR, VERIFY_NEW, 1)])

# self-verify
print("running verify_patches ...")
rc = subprocess.call([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
if rc != 0:
    raise SystemExit("FATAL: verify_patches failed after patching")
print("patch_engine86 OK")
