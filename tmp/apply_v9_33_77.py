#!/usr/bin/env python3
"""NOVA v9.33.77 "Answer-first window" - one-shot patch.

Applied by .github/workflows/apply-one-shot.yml, then deleted.

WHY. docSearchIn builds the excerpt NOVA injects when you ask about your
notes or an attached document. It scored every paragraph, took the top 12,
put them back into document order and STOPPED at the first one that did not
fit the character budget. Two failures, both measured on a 450-character note
whose answering sentence is its last paragraph:

  - an early, mediocre paragraph ate the whole budget, so the paragraph
    holding the answer was never emitted (300-char budget: 194 characters of
    filler returned, the answer absent);
  - when the FIRST selected paragraph alone was larger than the budget, the
    loop broke before appending anything and the function returned an EMPTY
    string. The notes silently vanished, the model answered from imagination,
    and nothing anywhere reported a failure.

The budget is now spent on the highest-scoring paragraphs (a paragraph that
does not fit is skipped rather than fatal), the chosen ones are restored to
document order so the excerpt still reads naturally, and the result is capped
at the budget - so this can never grow the prompt. Four unit tests pin it,
including the empty-window regression.

SAFETY. Every target file is checked against the blob SHA it had when this
patch was written; a mismatch aborts before anything is written. Every
replacement must match exactly once. verify_patches.py runs at the end and a
failure aborts. Nothing here touches secrets, data or git history.
"""
import json
import os
import subprocess
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."

EXPECTED = {
    "android/app/src/main/java/org/nova/ChatText.kt": "1fece9a5204ecaced797a45c0b481d126037522b",
    "android/app/src/test/java/org/nova/ChatTextTest.kt": "d096195a11e55cdc460dd850888a51a758f78b66",
    "android/app/build.gradle": "85e1d9225ef3be5aa62729805b368d71bdb8db97",
    "tmp/verify_patches.py": "f410f00b7cfffc524b3f019170cd6806aa8ec81d"
}

HUNKS = json.loads(r"""
[
 {
  "path": "android/app/src/main/java/org/nova/ChatText.kt",
  "old": "    }\n    if (scored.isEmpty()) return doc.take(maxChars)\n    val best = scored.sortedByDescending { it.first }.take(12).map { it.second }.sorted()\n    val out = StringBuilder()\n    var last = -2\n    for (i in best) {\n        if (out.isNotEmpty() && i != last + 1) out.append(\"[...]\\n\")\n        val p = paras[i]\n        if (out.length + p.length > maxChars) break\n        out.append(p).append(\"\\n\\n\")\n        last = i\n    }\n    return out.toString().trim()\n}\n",
  "new": "    }\n    if (scored.isEmpty()) return doc.take(maxChars)\n    // v9.33.77 \"Answer-first window\": the budget is spent on the paragraphs\n    // that score BEST, not on the ones that merely come first.\n    //\n    // The old form took the top 12 by score, put them back into document\n    // order and STOPPED at the first one that did not fit. Two consequences,\n    // both measured on a 450-character note whose answering sentence is its\n    // last paragraph:\n    //  - an early, mediocre paragraph could eat the whole budget, so the\n    //    paragraph holding the answer was never emitted;\n    //  - when the FIRST selected paragraph alone was larger than the budget,\n    //    the loop broke before appending anything and this function returned\n    //    an EMPTY string. The notes were silently dropped, the model answered\n    //    from imagination, and nothing anywhere reported a failure.\n    //\n    // Now the highest-scoring paragraphs are taken while they fit (a paragraph\n    // that does not fit is skipped, not fatal - a smaller one may still fit),\n    // and the chosen ones are put back into document order afterwards so the\n    // excerpt still reads naturally. The character budget is unchanged, and\n    // the result is capped at it, so this can never grow the prompt.\n    val ranked = scored.sortedByDescending { it.first }.map { it.second }\n    val chosen = ArrayList<Int>(minOf(ranked.size, 12))\n    var used = 0\n    for (i in ranked) {\n        if (chosen.size >= 12) break\n        val p = paras[i]\n        if (used + p.length > maxChars) {\n            if (chosen.isNotEmpty()) continue   // a smaller paragraph may fit\n            chosen.add(i)                       // nothing fits: keep the best one\n            break\n        }\n        chosen.add(i)\n        used += p.length\n        if (used >= maxChars) break\n    }\n    if (chosen.isEmpty()) return doc.take(maxChars)\n    chosen.sort()\n    val out = StringBuilder()\n    var last = -2\n    for (i in chosen) {\n        if (out.isNotEmpty() && i != last + 1) out.append(\"[...]\\n\")\n        out.append(paras[i]).append(\"\\n\\n\")\n        last = i\n    }\n    val window = out.toString().trim()\n    return if (window.length > maxChars) window.take(maxChars) else window\n}\n"
 },
 {
  "path": "android/app/src/test/java/org/nova/ChatTextTest.kt",
  "old": "\nimport org.junit.Assert.assertEquals\nimport org.junit.Test\n",
  "new": "\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n"
 },
 {
  "path": "android/app/src/test/java/org/nova/ChatTextTest.kt",
  "old": " */\nclass ChatTextTest {\n\n    // ---- dropRepeatedBlocks: drop runs the continuation re-emits ----",
  "new": " */\nclass ChatTextTest {\n\n    // ---- v9.33.77 docSearchIn: the answer must survive the budget ----\n    //\n    // docSearchIn builds the \"from my notes\" window. Both failures below were\n    // real: a 450-character note whose answering sentence is its LAST\n    // paragraph returned 194 characters of filler at a 300-character budget\n    // and 0 characters at a smaller one - the notes were dropped and the\n    // model answered from imagination with nothing reported anywhere.\n\n    private val overview =\n        \"The chapter opens with a general overview of the topic and its history. \"\n\n    @Test\n    fun theAnsweringParagraphIsNotDroppedForEarlierFiller() {\n        val p0 = \"Photosynthesis appears again in the summary below. \" + overview.trim()\n        val p1 = \"Photosynthesis is also mentioned in the review questions. \" + overview.trim()\n        val answer = \"Photosynthesis converts light into chemical energy.\"\n        val doc = listOf(p0, p1, answer).joinToString(\"\\n\\n\")\n        val win = docSearchIn(doc, \"photosynthesis\", 300)\n        assertTrue(\"the window dropped the answering paragraph: \" + win,\n            win.contains(\"chemical energy\"))\n    }\n\n    @Test\n    fun aWindowIsNeverSilentlyEmpty() {\n        // one long scoring paragraph, then the answer: the old code broke out\n        // of the loop on the first paragraph and returned \"\"\n        val long = (\"Photosynthesis is covered later in this chapter. \" +\n            \"Background context that the chapter mentions once. \".repeat(8)).trim()\n        val doc = long + \"\\n\\n\" + \"Photosynthesis converts light into chemical energy.\"\n        assertTrue(docSearchIn(doc, \"photosynthesis\", 200).isNotEmpty())\n    }\n\n    @Test\n    fun chosenParagraphsStayInDocumentOrder() {\n        val first = \"Photosynthesis converts light into chemical energy.\"\n        val middle = \"This paragraph is about something else entirely.\"\n        val last = \"Photosynthesis is also reviewed in the questions at the end.\"\n        val doc = listOf(first, middle, last).joinToString(\"\\n\\n\")\n        // a budget that holds the two matching paragraphs but not the middle\n        val win = docSearchIn(doc, \"photosynthesis\", 120)\n        assertTrue(win.contains(\"chemical energy\"))\n        assertTrue(win.contains(\"questions at the end\"))\n        // document order, with the gap marked\n        assertTrue(win.indexOf(\"chemical energy\") < win.indexOf(\"questions at the end\"))\n        assertTrue(win.contains(\"[...]\"))\n    }\n\n    @Test\n    fun theWindowNeverExceedsItsBudget() {\n        val doc = \"Photosynthesis. \" + \"padding words that go on and on. \".repeat(200)\n        val win = docSearchIn(doc, \"photosynthesis\", 400)\n        assertTrue(\"window is \" + win.length + \" chars\", win.length <= 400)\n        assertTrue(win.isNotEmpty())\n    }\n\n    // ---- dropRepeatedBlocks: drop runs the continuation re-emits ----"
 },
 {
  "path": "android/app/build.gradle",
  "old": "        //   its two-character content words in the tool scorer, the search\n        //   acceptance gate and the document window. English unchanged.\n        versionCode 244\n        versionName '9.33.76'\n        // v6.0.0: arm64 only by default. CI also builds a universal APK\n        // (arm64 + x86_64, for emulators/Chromebooks) with:",
  "new": "        //   its two-character content words in the tool scorer, the search\n        //   acceptance gate and the document window. English unchanged.\n        // v9.33.77 \"Answer-first window\": the document/notes excerpt spends\n        //   its character budget on the paragraphs that score best, not on the\n        //   ones that come first, and is capped at the budget. Fixes an empty\n        //   \"from my notes\" window and an answer paragraph being dropped.\n        versionCode 245\n        versionName '9.33.77'\n        // v6.0.0: arm64 only by default. CI also builds a universal APK\n        // (arm64 + x86_64, for emulators/Chromebooks) with:"
 },
 {
  "path": "tmp/verify_patches.py",
  "old": "              [ntools, nof, ma, nc, ka, lo, load(\"ncie/android/NcieKnowledge.kt\")]))\n\nfor f, text in [(\"MainActivity.kt\", ma), (\"ncie/android/NcieChat.kt\", nc), (\"ncie/android/NcieTune.kt\", tun), (\"ncie/android/NcieLearn.kt\", nl), (\"ncie/android/NcieKnowledge.kt\", nk), (\"ncie/android/NcieExam.kt\", ne), (\"ncie/learn/Learner.kt\", lr), (\"ncie/learn/PersistentLearner.kt\", pl), (\"ncie/learn/LearnedFact.kt\", lf), (\"KnowledgeActivity.kt\", ka), (\"ncie/android/NcieLeftovers.kt\", lo), (\"LeftoverReceiver.kt\", lr2), (\"NovaUi.kt\", load(\"NovaUi.kt\")), (\"NovaHealth.kt\", load(\"NovaHealth.kt\")), (\"SetupActivity.kt\", load(\"SetupActivity.kt\")), (\"HealthActivity.kt\", load(\"HealthActivity.kt\")), (\"HelpActivity.kt\", load(\"HelpActivity.kt\")), (\"ncie/android/NcieStyle.kt\", load(\"ncie/android/NcieStyle.kt\")), (\"NovaListener.kt\", load(\"NovaListener.kt\")), (\"ncie/android/NcieDeadlines.kt\", load(\"ncie/android/NcieDeadlines.kt\")), (\"ncie/android/NcieWeekly.kt\", load(\"ncie/android/NcieWeekly.kt\")), (\"ncie/android/NcieWatchdog.kt\", load(\"ncie/android/NcieWatchdog.kt\")), (\"ncie/android/NcieFocus.kt\", load(\"ncie/android/NcieFocus.kt\")), (\"ncie/android/NovaJarvis.kt\", load(\"ncie/android/NovaJarvis.kt\")), (\"NovaLog.kt\", load(\"NovaLog.kt\")), (\"ncie/android/NcieTools.kt\", ntools), (\"ncie/android/OnlineFetch.kt\", nof), (\"ncie/Tokens.kt\", load(\"ncie/Tokens.kt\"))]:\n    structural(f, text)",
  "new": "              [ntools, nof, ma, nc, ka, lo, load(\"ncie/android/NcieKnowledge.kt\")]))\n\n# ---- v9.33.77 \"Answer-first window\" ----\n# docSearchIn builds the \"from my notes\" excerpt. It used to take the top 12\n# paragraphs by score, restore document order and STOP at the first one that\n# did not fit - so early filler could consume the budget and drop the\n# paragraph holding the answer, and when the first selected paragraph alone\n# was larger than the budget it returned an EMPTY string (the notes silently\n# vanished and the model answered from imagination). These checks keep the\n# relevance-first form and its unit tests in place.\ncheck(\"v9.33.77: the document window ranks by relevance first\",\n      \"val ranked = scored.sortedByDescending { it.first }.map { it.second }\" in ma)\ncheck(\"v9.33.77: a paragraph that does not fit is skipped, not fatal\",\n      \"if (chosen.isNotEmpty()) continue   // a smaller paragraph may fit\" in ma)\ncheck(\"v9.33.77: the window is capped at its character budget\",\n      \"if (window.length > maxChars) window.take(maxChars) else window\" in ma)\ncheck(\"v9.33.77: the old document-order-then-stop selection is gone\",\n      \"take(12).map { it.second }.sorted()\" not in ma)\ncheck(\"v9.33.77: the empty-window regression is pinned by a unit test\",\n      \"fun aWindowIsNeverSilentlyEmpty()\" in\n      open(os.path.join(ROOT, \"android/app/src/test/java/org/nova/ChatTextTest.kt\"),\n           encoding=\"utf-8\").read())\n\nfor f, text in [(\"MainActivity.kt\", ma), (\"ncie/android/NcieChat.kt\", nc), (\"ncie/android/NcieTune.kt\", tun), (\"ncie/android/NcieLearn.kt\", nl), (\"ncie/android/NcieKnowledge.kt\", nk), (\"ncie/android/NcieExam.kt\", ne), (\"ncie/learn/Learner.kt\", lr), (\"ncie/learn/PersistentLearner.kt\", pl), (\"ncie/learn/LearnedFact.kt\", lf), (\"KnowledgeActivity.kt\", ka), (\"ncie/android/NcieLeftovers.kt\", lo), (\"LeftoverReceiver.kt\", lr2), (\"NovaUi.kt\", load(\"NovaUi.kt\")), (\"NovaHealth.kt\", load(\"NovaHealth.kt\")), (\"SetupActivity.kt\", load(\"SetupActivity.kt\")), (\"HealthActivity.kt\", load(\"HealthActivity.kt\")), (\"HelpActivity.kt\", load(\"HelpActivity.kt\")), (\"ncie/android/NcieStyle.kt\", load(\"ncie/android/NcieStyle.kt\")), (\"NovaListener.kt\", load(\"NovaListener.kt\")), (\"ncie/android/NcieDeadlines.kt\", load(\"ncie/android/NcieDeadlines.kt\")), (\"ncie/android/NcieWeekly.kt\", load(\"ncie/android/NcieWeekly.kt\")), (\"ncie/android/NcieWatchdog.kt\", load(\"ncie/android/NcieWatchdog.kt\")), (\"ncie/android/NcieFocus.kt\", load(\"ncie/android/NcieFocus.kt\")), (\"ncie/android/NovaJarvis.kt\", load(\"ncie/android/NovaJarvis.kt\")), (\"NovaLog.kt\", load(\"NovaLog.kt\")), (\"ncie/android/NcieTools.kt\", ntools), (\"ncie/android/OnlineFetch.kt\", nof), (\"ncie/Tokens.kt\", load(\"ncie/Tokens.kt\"))]:\n    structural(f, text)"
 }
]""")


def sha(path):
    return subprocess.run(["git", "hash-object", "--", path],
                          capture_output=True, text=True,
                          check=True).stdout.strip()


def main():
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

    r = subprocess.run([sys.executable, os.path.join(ROOT, "tmp/verify_patches.py"), ROOT])
    if r.returncode != 0:
        print("ABORT: verify_patches.py failed after patching")
        return 1
    print("PATCH OK: v9.33.77 applied and verified")
    return 0


sys.exit(main())
