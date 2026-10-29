#!/usr/bin/env python3
"""NOVA one-shot patch for v9.33.84 "the lookup never quotes an unrelated line".

Deterministic, idempotent, self-verifying (ends by running verify_patches.py).
Usage: patch_engine87.py <NOVA repo root>

Audit finding (second pass, now over the v9.33.83 lookup):

  NcieGround.retrieve returns a chunk when it matched only SEMANTICALLY
  (cosine >= 0.30, zero keyword overlap). NcieLookup.body quoted the first
  line of whatever it was handed, so a semantic-only hit was printed under
  "Your notes on X" with "Quoted straight from your notes - nothing added".
  That line shares not one word with the question: a paraphrase dressed up as
  a quote, which is the exact failure the lookup exists to prevent. It also
  burned a quote slot (MAX_QUOTES is 4), so a real hit could be pushed out by
  an unrelated one.

What it does
  1. build.gradle      : versionCode 251 -> 252, versionName 9.33.83 -> 9.33.84
  2. NcieLookup.kt     : bestLine() exposes the whole line the quote came
                         from; body() skips a hit whose line holds none of the
                         question's content words, and says "nothing found"
                         when no line survives
  3. NcieLookupTest.kt : two tests pin it
  4. verify_patches.py : four checks, the version floor at 9.33.84/252
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
    ("versionCode 251", "versionCode 252", 1),
    ("versionName '9.33.83'", "versionName '9.33.84'", 1),
])

# 2. NcieLookup.kt
EXCERPT_OLD = (
    "        if (best.isEmpty()) best = chunk.trim()\n"
    "        return window(best, terms, maxChars)\n"
    "    }\n"
)
EXCERPT_NEW = (
    "        if (best.isEmpty()) best = chunk.trim()\n"
    "        return window(best, terms, maxChars)\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v9.33.84: the whole line [excerpt] would quote, unclipped. The caller\n"
    "     * needs it to decide whether that line shares a word with the question\n"
    "     * at all before spending one of the [MAX_QUOTES] slots on it.\n"
    "     */\n"
    "    internal fun bestLine(chunk: String, query: String): String =\n"
    "        excerpt(chunk, query, Int.MAX_VALUE)\n"
)

BODY_OLD = (
    "        if (hits.isEmpty()) {\n"
    "            val what = if (sources == 1) \"1 source\" else sources.toString() + \" sources\"\n"
    "            return \"Nothing in your notes mentions \\\"\" + query + "
    "\"\\\". I looked in \" + what + \".\"\n"
    "        }\n"
    "        val sb = StringBuilder(\"```\\nYour notes on \\\"\").append(query).append(\"\\\":\\n\")\n"
    "        var n = 0\n"
    "        for (h in hits) {\n"
    "            if (n >= MAX_QUOTES) break\n"
    "            n++\n"
    "            sb.append(\"\\n\").append(n).append(\". \").append(h.first).append(\" - \")\n"
    "            sb.append(excerpt(h.second, query).replace(\"```\", \"'''\")).append(\"\\n\")\n"
    "        }\n"
)
BODY_NEW = (
    "        val terms = termsOf(query)\n"
    "        val quotes = ArrayList<Pair<String, String>>()\n"
    "        for (h in hits) {\n"
    "            if (quotes.size >= MAX_QUOTES) break\n"
    "            val line = bestLine(h.second, query)\n"
    "            if (!holdsTerm(line, terms)) continue\n"
    "            quotes.add(h.first to window(line, terms, 240))\n"
    "        }\n"
    "        if (quotes.isEmpty()) return nothingFound(query, sources)\n"
    "        val sb = StringBuilder(\"```\\nYour notes on \\\"\").append(query).append(\"\\\":\\n\")\n"
    "        for (i in quotes.indices) {\n"
    "            sb.append(\"\\n\").append(i + 1).append(\". \").append(quotes[i].first).append(\" - \")\n"
    "            sb.append(quotes[i].second.replace(\"```\", \"'''\")).append(\"\\n\")\n"
    "        }\n"
)

HELPERS_OLD = (
    "        sb.append(\"```\\n\")\n"
    "        sb.append(\"Quoted straight from your notes - nothing added, no model used.\")\n"
    "        return sb.toString()\n"
    "    }\n"
)
HELPERS_NEW = (
    "        sb.append(\"```\\n\")\n"
    "        sb.append(\"Quoted straight from your notes - nothing added, no model used.\")\n"
    "        return sb.toString()\n"
    "    }\n"
    "\n"
    "    /** v9.33.84: the honest reply when not one line shares a word. */\n"
    "    private fun nothingFound(query: String, sources: Int): String {\n"
    "        val what = if (sources == 1) \"1 source\" else sources.toString() + \" sources\"\n"
    "        return \"Nothing in your notes mentions \\\"\" + query + "
    "\"\\\". I looked in \" + what + \".\"\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * v9.33.84: true when [line] holds at least one of the query's content\n"
    "     * words. Retrieval can hand back a chunk that matched only semantically,\n"
    "     * with no word in common with the question; quoting its first line as\n"
    "     * \"your notes on X\" would be a paraphrase dressed up as a quote.\n"
    "     */\n"
    "    private fun holdsTerm(line: String, terms: List<String>): Boolean {\n"
    "        if (terms.isEmpty()) return false\n"
    "        val norm = Tokens.norm(line)\n"
    "        return terms.any { norm.contains(\" \" + it + \" \") }\n"
    "    }\n"
)
edit(BASE + "ncie/android/NcieLookup.kt", [
    (EXCERPT_OLD, EXCERPT_NEW, 1),
    (BODY_OLD, BODY_NEW, 1),
    (HELPERS_OLD, HELPERS_NEW, 1),
])

# 3. the unit tests
TEST_ANCHOR = (
    "    @Test\n"
    "    fun aQueryOfOnlyFunctionWordsStillQuotesALine() {\n"
    "        assertEquals(\"First line here.\",\n"
    "            NcieLookup.excerpt(\"First line here.\\nSecond line there.\", \"what is it\"))\n"
    "    }\n"
    "}\n"
)
TEST_NEW = (
    "    @Test\n"
    "    fun aQueryOfOnlyFunctionWordsStillQuotesALine() {\n"
    "        assertEquals(\"First line here.\",\n"
    "            NcieLookup.excerpt(\"First line here.\\nSecond line there.\", \"what is it\"))\n"
    "    }\n"
    "\n"
    "    @Test\n"
    "    fun aHitThatSharesNoWordWithTheQuestionIsNotQuoted() {\n"
    "        // retrieval can return a semantic-only hit - no literal overlap at\n"
    "        // all. Quoting its first line as \"your notes on tensor\" would be a\n"
    "        // paraphrase dressed up as a quote.\n"
    "        val hits = listOf(\"Physics\" to \"Kinetic energy depends on mass and speed.\")\n"
    "        val body = NcieLookup.body(\"tensor\", hits, 1)\n"
    "        assertTrue(body.contains(\"Nothing in your notes mentions \\\"tensor\\\"\"))\n"
    "        assertTrue(!body.contains(\"Kinetic energy\"))\n"
    "    }\n"
    "\n"
    "    @Test\n"
    "    fun anUnrelatedHitDoesNotConsumeAQuoteSlot() {\n"
    "        val hits = listOf(\n"
    "            \"Physics\" to \"Kinetic energy depends on mass.\",\n"
    "            \"ML Notes\" to \"A tensor is a multi-dimensional array.\")\n"
    "        val body = NcieLookup.body(\"tensor\", hits, 2)\n"
    "        assertTrue(body.contains(\"1. ML Notes - A tensor is a multi-dimensional array.\"))\n"
    "        assertTrue(!body.contains(\"Physics\"))\n"
    "    }\n"
    "}\n"
)
edit(TEST + "ncie/android/NcieLookupTest.kt", [(TEST_ANCHOR, TEST_NEW, 1)])

# 4. verify_patches - the new checks + the version floor
VERIFY_ANCHOR = (
    'check("v9.33.83: version is at least 9.33.83 (251)",\n'
    '      version_at_least("9.33.83", 251))\n'
)
VERIFY_NEW = VERIFY_ANCHOR + (
    '\n'
    '# ---- v9.33.84 "The lookup never quotes an unrelated line" ----\n'
    '# A quote with no word in common with the question is a paraphrase wearing\n'
    '# the word "quoted" - the one thing this command must never print.\n'
    'check("v9.33.84: a line that shares no word with the question is not quoted",\n'
    '      "if (!holdsTerm(line, terms)) continue" in _look\n'
    '      and "internal fun bestLine(chunk: String, query: String): String" in _look)\n'
    'check("v9.33.84: an empty quote list falls back to the honest reply",\n'
    '      "if (quotes.isEmpty()) return nothingFound(query, sources)" in _look)\n'
    'check("v9.33.84: the rule is pinned by unit tests",\n'
    '      "fun aHitThatSharesNoWordWithTheQuestionIsNotQuoted()" in\n'
    '      open(os.path.join(ROOT, "android/app/src/test/java/org/nova/ncie/android/NcieLookupTest.kt"),\n'
    '           encoding="utf-8").read())\n'
    'check("v9.33.84: version is at least 9.33.84 (252)",\n'
    '      version_at_least("9.33.84", 252))\n'
)
edit("tmp/verify_patches.py", [(VERIFY_ANCHOR, VERIFY_NEW, 1)])

# self-verify
print("running verify_patches ...")
rc = subprocess.call([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
if rc != 0:
    raise SystemExit("FATAL: verify_patches failed after patching")
print("patch_engine87 OK")
