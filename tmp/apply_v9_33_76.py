#!/usr/bin/env python3
"""NOVA v9.33.76 "Every-script retrieval terms" - one-shot patch.

Applied by .github/workflows/apply-one-shot.yml, then deleted.

WHY. v9.33.19 gave every script a Unicode tokenizer (Tokens), but four
retrieval call sites kept the old hand-rolled rule

    Tokens.split(x).filter { it.length > 2 }

That rule counts CHARACTERS, and a Devanagari content word is short in
characters because its vowel signs (matras) are separate code points: "घर"
(house) and "मन" (mind) are two characters. They were dropped before
anything matched, so for a Hindi/Marathi question

  - NcieTools.score saw no terms at all, so file/recipe search scored 0,
  - OnlineFetch.hits counted zero keyword hits, leaving only the Coverage
    ratio to accept a page,
  - ChatText's document excerpt ranking had nothing to rank on and fell
    through to a blind head slice,
  - NcieChat's document-window overlap test read the question as unrelated
    to the attached document and stopped injecting it.

Exactly the failure v9.33.19 set out to end, surviving in the four places
that were missed. The rule now has ONE name, Tokens.contentWords, so it
cannot drift again; verify_patches asserts both the four call sites and the
absence of any bare ASCII-length filter on the retrieval path.

SAFETY. Every target file is checked against the blob SHA it had when this
patch was written; a mismatch aborts before anything is written. Every
replacement must match exactly once. verify_patches.py runs at the end and
a failure aborts. Nothing here touches secrets, data or git history.
"""
import json
import os
import subprocess
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."

# The blob SHA each file had when this patch was written (git hash-object).
EXPECTED = {
    "android/app/src/main/java/org/nova/ncie/android/NcieTools.kt": "149ef1d1ecd5778f1d443f80e90819802fca5723",
    "android/app/src/main/java/org/nova/ncie/android/OnlineFetch.kt": "db588bb6965eb4936ff4b8249ee66f30d05dff76",
    "android/app/src/main/java/org/nova/ChatText.kt": "a8f1210f87023f970e743ea40da1cbb73def7b6a",
    "android/app/src/main/java/org/nova/ncie/android/NcieChat.kt": "d93a1bbcc2488e86fc59d88ea3959d733f3e9d24",
    "android/app/src/main/java/org/nova/ncie/Tokens.kt": "64e019f15141728b4837c561adcb8427e05cd59f",
    "android/app/build.gradle": "f49e35cd1967be31e25a161350c914b01ba50ffc",
    "tmp/verify_patches.py": "ac984a3e50e38b633d8d6daa8589d20264ff23c5",
    "android/app/src/test/java/org/nova/ncie/TokensTest.kt": "5f87d82d5c80b22459c24ec0cbc2a37f985b4e0e"
}

HUNKS = json.loads(r"""
[
 {
  "path": "android/app/src/main/java/org/nova/ncie/android/NcieTools.kt",
  "old": "        val terms = Tokens.split(query).filter { it.length > 2 }",
  "new": "        // v9.33.76: every-script content words (a 2-character Devanagari\n        // word is a real term) - see Tokens.contentWords.\n        val terms = Tokens.contentWords(query)"
 },
 {
  "path": "android/app/src/main/java/org/nova/ncie/android/OnlineFetch.kt",
  "old": "        val terms = Tokens.split(q).filter { it.length > 2 }.distinct()",
  "new": "        // v9.33.76: every-script content words. The ASCII-length filter\n        // dropped a Hindi query's two-character words, so the keyword side\n        // of the acceptance gate counted zero hits for a Hindi question.\n        val terms = Tokens.contentWords(q).distinct()"
 },
 {
  "path": "android/app/src/main/java/org/nova/ChatText.kt",
  "old": "    val qw = Tokens.split(ql)\n        .filter { it.length > 2 && it !in docStop }.toSet()",
  "new": "    // v9.33.76: every-script content words, so a Hindi question ranks the\n    // document's paragraphs instead of falling through to a blind head slice.\n    val qw = Tokens.contentWords(ql)\n        .filter { it !in docStop }.toSet()"
 },
 {
  "path": "android/app/src/main/java/org/nova/ncie/android/NcieChat.kt",
  "old": "            val qWords = Tokens.split(text)\n                .filter { it.length > 2 && it !in docStop }",
  "new": "            // v9.33.76: every-script content words - a Hindi question about\n            // an attached document now registers overlap instead of looking\n            // unrelated and losing the document.\n            val qWords = Tokens.contentWords(text)\n                .filter { it !in docStop }"
 },
 {
  "path": "android/app/src/main/java/org/nova/ncie/Tokens.kt",
  "old": "    /** Space-padded normalized form for whole-word matching (\" term \"). */",
  "new": "    /**\n     * v9.33.76 \"Every-script retrieval terms\": the ONE definition of a\n     * content word on the retrieval path - the words of a query or a page\n     * that are worth matching on.\n     *\n     * v9.33.19 gave every script a tokenizer, but four retrieval call sites\n     * kept the old hand-rolled rule `Tokens.split(x).filter { it.length > 2 }`.\n     * That rule counts CHARACTERS, and a Devanagari content word is short in\n     * characters because its vowel signs are separate code points: \"घर\"\n     * (house) and \"मन\" (mind) are two characters and were silently dropped.\n     * A Hindi question therefore lost its real content words before matching\n     * - the tool scorer saw no terms at all, the search acceptance gate\n     * counted zero keyword hits, and the document window had nothing to rank\n     * on. Exactly the failure v9.33.19 set out to end, surviving in the four\n     * places that were missed.\n     *\n     * [words] already carries the rule (ASCII keeps \">= minLen\", a 2-character\n     * word qualifies when it holds a non-ASCII letter), so this is a NAME for\n     * it rather than a second implementation. English behaviour is identical\n     * to the old filter; every other script now behaves like English.\n     */\n    fun contentWords(s: String, minLen: Int = 3): List<String> = words(s, minLen)\n\n    /** Space-padded normalized form for whole-word matching (\" term \"). */"
 },
 {
  "path": "android/app/build.gradle",
  "old": "        versionCode 243\n        versionName '9.33.75'",
  "new": "        // v9.33.76 \"Every-script retrieval terms\": the four retrieval call\n        //   sites that still filtered query words by ASCII character count\n        //   now use Tokens.contentWords, so a Hindi/Marathi question keeps\n        //   its two-character content words in the tool scorer, the search\n        //   acceptance gate and the document window. English unchanged.\n        versionCode 244\n        versionName '9.33.76'"
 },
 {
  "path": "tmp/verify_patches.py",
  "old": "for f, text in [(\"MainActivity.kt\", ma), (\"ncie/android/NcieChat.kt\", nc), (\"ncie/android/NcieTune.kt\", tun), (\"ncie/android/NcieLearn.kt\", nl), (\"ncie/android/NcieKnowledge.kt\", nk), (\"ncie/android/NcieExam.kt\", ne), (\"ncie/learn/Learner.kt\", lr), (\"ncie/learn/PersistentLearner.kt\", pl), (\"ncie/learn/LearnedFact.kt\", lf), (\"KnowledgeActivity.kt\", ka), (\"ncie/android/NcieLeftovers.kt\", lo), (\"LeftoverReceiver.kt\", lr2), (\"NovaUi.kt\", load(\"NovaUi.kt\")), (\"NovaHealth.kt\", load(\"NovaHealth.kt\")), (\"SetupActivity.kt\", load(\"SetupActivity.kt\")), (\"HealthActivity.kt\", load(\"HealthActivity.kt\")), (\"HelpActivity.kt\", load(\"HelpActivity.kt\")), (\"ncie/android/NcieStyle.kt\", load(\"ncie/android/NcieStyle.kt\")), (\"NovaListener.kt\", load(\"NovaListener.kt\")), (\"ncie/android/NcieDeadlines.kt\", load(\"ncie/android/NcieDeadlines.kt\")), (\"ncie/android/NcieWeekly.kt\", load(\"ncie/android/NcieWeekly.kt\")), (\"ncie/android/NcieWatchdog.kt\", load(\"ncie/android/NcieWatchdog.kt\")), (\"ncie/android/NcieFocus.kt\", load(\"ncie/android/NcieFocus.kt\")), (\"ncie/android/NovaJarvis.kt\", load(\"ncie/android/NovaJarvis.kt\")), (\"NovaLog.kt\", load(\"NovaLog.kt\"))]:",
  "new": "# ---- v9.33.76 \"Every-script retrieval terms\" ----\n# v9.33.19 gave every script a tokenizer, but four retrieval call sites kept\n# the old hand-rolled rule `Tokens.split(x).filter { it.length > 2 }`. That\n# counts CHARACTERS, and a Devanagari content word is short in characters\n# because its vowel signs are separate code points, so \"ghar\" and \"man\" were\n# dropped before anything matched: a Hindi question lost its content words in\n# the tool scorer, the search acceptance gate and the document window.\n# The rule now has one name (Tokens.contentWords) and these checks keep it.\nntools = load(\"ncie/android/NcieTools.kt\")\nnof = load(\"ncie/android/OnlineFetch.kt\")\ncheck(\"v9.33.76: Tokens defines contentWords (the one content-word rule)\",\n      \"fun contentWords(s: String, minLen: Int = 3): List<String> = words(s, minLen)\"\n      in load(\"ncie/Tokens.kt\"))\ncheck(\"v9.33.76: tool-result scoring uses every-script terms\",\n      \"Tokens.contentWords(query)\" in ntools)\ncheck(\"v9.33.76: the search acceptance gate uses every-script terms\",\n      \"Tokens.contentWords(q).distinct()\" in nof)\ncheck(\"v9.33.76: document excerpt ranking uses every-script terms\",\n      \"Tokens.contentWords(ql)\" in ma)\ncheck(\"v9.33.76: document-window overlap uses every-script terms\",\n      \"Tokens.contentWords(text)\" in nc)\n# and the ASCII-length rule cannot come back on the retrieval path\n_BARE_ASCII_TERMS = re.compile(\n    r\"Tokens\\.split\\([^)]*\\)\\s*\\.filter\\s*\\{\\s*it\\.length\\s*>\\s*2\")\ncheck(\"v9.33.76: no ASCII-length query filter left on the retrieval path\",\n      not any(_BARE_ASCII_TERMS.search(t) for t in\n              [ntools, nof, ma, nc, ka, lo, load(\"ncie/android/NcieKnowledge.kt\")]))\n\nfor f, text in [(\"MainActivity.kt\", ma), (\"ncie/android/NcieChat.kt\", nc), (\"ncie/android/NcieTune.kt\", tun), (\"ncie/android/NcieLearn.kt\", nl), (\"ncie/android/NcieKnowledge.kt\", nk), (\"ncie/android/NcieExam.kt\", ne), (\"ncie/learn/Learner.kt\", lr), (\"ncie/learn/PersistentLearner.kt\", pl), (\"ncie/learn/LearnedFact.kt\", lf), (\"KnowledgeActivity.kt\", ka), (\"ncie/android/NcieLeftovers.kt\", lo), (\"LeftoverReceiver.kt\", lr2), (\"NovaUi.kt\", load(\"NovaUi.kt\")), (\"NovaHealth.kt\", load(\"NovaHealth.kt\")), (\"SetupActivity.kt\", load(\"SetupActivity.kt\")), (\"HealthActivity.kt\", load(\"HealthActivity.kt\")), (\"HelpActivity.kt\", load(\"HelpActivity.kt\")), (\"ncie/android/NcieStyle.kt\", load(\"ncie/android/NcieStyle.kt\")), (\"NovaListener.kt\", load(\"NovaListener.kt\")), (\"ncie/android/NcieDeadlines.kt\", load(\"ncie/android/NcieDeadlines.kt\")), (\"ncie/android/NcieWeekly.kt\", load(\"ncie/android/NcieWeekly.kt\")), (\"ncie/android/NcieWatchdog.kt\", load(\"ncie/android/NcieWatchdog.kt\")), (\"ncie/android/NcieFocus.kt\", load(\"ncie/android/NcieFocus.kt\")), (\"ncie/android/NovaJarvis.kt\", load(\"ncie/android/NovaJarvis.kt\")), (\"NovaLog.kt\", load(\"NovaLog.kt\")), (\"ncie/android/NcieTools.kt\", ntools), (\"ncie/android/OnlineFetch.kt\", nof), (\"ncie/Tokens.kt\", load(\"ncie/Tokens.kt\"))]:"
 },
 {
  "path": "android/app/src/test/java/org/nova/ncie/TokensTest.kt",
  "old": "    fun punctuationAndDigitsSurviveAsWords() {",
  "new": "    fun contentWordsKeepShortDevanagari() {\n        // v9.33.76: \"घर\" (house) and \"मन\" (mind) are two CHARACTERS because\n        // the vowel signs are separate code points. The old\n        // `Tokens.split(x).filter { it.length > 2 }` rule dropped them, so a\n        // Hindi query lost its content words before anything matched.\n        assertEquals(listOf(\"घर\", \"कहाँ\", \"है\"), Tokens.contentWords(\"घर कहाँ है?\"))\n        // English behaviour is exactly the old filter: two letters still go.\n        assertEquals(listOf(\"the\", \"cat\"), Tokens.contentWords(\"the cat is in\"))\n    }\n\n    @Test\n    fun punctuationAndDigitsSurviveAsWords() {"
 }
]
""")

fails = []


def sha(path):
    return subprocess.run(["git", "hash-object", "--", path],
                          capture_output=True, text=True,
                          check=True).stdout.strip()


def main():
    # 1. refuse to touch a tree that moved under us
    for path, want in EXPECTED.items():
        full = os.path.join(ROOT, path)
        if not os.path.exists(full):
            print("ABORT: missing " + path)
            return 1
        got = sha(full)
        if got != want:
            print("ABORT: " + path + " changed since this patch was written")
            print("  expected blob " + want)
            print("  actual   blob " + got)
            return 1
    print("source integrity OK: every target file matches its recorded blob")

    # 2. apply, each replacement exactly once
    for h in HUNKS:
        full = os.path.join(ROOT, h["path"])
        with open(full, encoding="utf-8") as f:
            text = f.read()
        n = text.count(h["old"])
        if n != 1:
            print("ABORT: " + h["path"] + ": anchor matched " + str(n) + " times")
            return 1
        with open(full, "w", encoding="utf-8") as f:
            f.write(text.replace(h["old"], h["new"]))
        print("patched " + h["path"])

    # 3. the repo's own verifier must agree
    r = subprocess.run([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
    if r.returncode != 0:
        print("ABORT: verify_patches.py failed after patching")
        return 1
    print("PATCH OK: v9.33.76 applied and verified")
    return 0


sys.exit(main())
