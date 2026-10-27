#!/usr/bin/env python3
"""NOVA v9.33.78 "Storage robustness" - one-shot patch.

Applied by .github/workflows/apply-one-shot.yml, then deleted.

WHY. Two ways data or a multi-GB download could be lost with nothing
reported anywhere:

  - A .part left behind by an earlier download attempt that is already
    complete, or longer than the object the server now has, makes every
    retry answer 416 Range Not Satisfiable. The download was stuck for good:
    the same "HTTP 416 from server" on every tap, and no way out but
    clearing app data. Bytes on disk plus a 416 means the partial cannot be
    appended to, so it is dropped and the failure says so - the next tap
    starts clean.
  - The fetch log (the list of everything NOVA ever looked up online) was
    rewritten with a direct writeText, which truncates first. A crash, or the
    process being killed while an entry was being deleted, left an empty or
    half-written log: the whole lookup history, gone. It is now written to a
    .tmp file and renamed, so the log is either the old one or the new one,
    never a fragment.

SAFETY. Every target file is checked against the blob SHA it had when this
patch was written; a mismatch aborts before anything is written. Every
replacement must match exactly once. verify_patches.py runs at the end and a
failure aborts. Nothing here touches secrets, data stores or git history.
"""
import json
import os
import subprocess
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."

EXPECTED = {
    "android/app/src/main/java/org/nova/ModelDownloader.kt": "6f8f0a75018660d3acbfbd954ebcece642a08002",
    "android/app/src/main/java/org/nova/FetchLogActivity.kt": "1837fd5e3a83febf87b5351d6ef80cbad4c7d463",
    "android/app/build.gradle": "35d5442ee8cb8fe36383aee7e195ef960efb69f7",
    "tmp/verify_patches.py": "338a321bdccfa1c28de5fcece121810484cb6e02"
}

HUNKS = json.loads(r"""
[
 {
  "path": "android/app/src/main/java/org/nova/ModelDownloader.kt",
  "old": "\n            val code = conn.responseCode\n            if (code != 200 && code != 206) {\n                throw IOException(\"HTTP $code from server\")",
  "new": "\n            val code = conn.responseCode\n            // v9.33.78 \"Unstuck download\": a .part left behind by an earlier\n            // attempt that is already complete, or longer than the object the\n            // server now has, makes every retry answer 416 Range Not\n            // Satisfiable. The download was then stuck for good - the same\n            // \"HTTP 416 from server\" on every tap, with no way out but\n            // clearing app data. Bytes on disk plus a 416 means the partial\n            // cannot be appended to, so drop it and say so plainly; the next\n            // tap starts clean.\n            if (code == 416 && existing > 0) {\n                part.delete()\n                throw IOException(\n                    \"the partial download could not be resumed and was cleared - tap download again\")\n            }\n            if (code != 200 && code != 206) {\n                throw IOException(\"HTTP $code from server\")"
 },
 {
  "path": "android/app/src/main/java/org/nova/FetchLogActivity.kt",
  "old": "    private fun rewriteLogWithout(title: String) {\n        try {\n            val kept = logFile().readLines().filter {\n                it.split('\\t').getOrNull(1)?.equals(title, ignoreCase = true) != true\n            }\n            logFile().writeText(kept.joinToString(\"\\n\") + if (kept.isEmpty()) \"\" else \"\\n\")\n        } catch (e: Exception) { NovaLog.quiet(\"FetchLogActivity\", e) }\n    }",
  "new": "    private fun rewriteLogWithout(title: String) {\n        try {\n            val f = logFile()\n            val kept = f.readLines().filter {\n                it.split('\\t').getOrNull(1)?.equals(title, ignoreCase = true) != true\n            }\n            val body = kept.joinToString(\"\\n\") + if (kept.isEmpty()) \"\" else \"\\n\"\n            // v9.33.78: written atomically. A direct writeText truncates the\n            // file first, so a crash (or the process being killed while the\n            // user is deleting an entry) left an empty or half-written fetch\n            // log - the whole lookup history, gone. tmp + rename means the\n            // log is either the old one or the new one, never a fragment.\n            val tmp = java.io.File(f.parentFile, f.name + \".tmp\")\n            tmp.writeText(body)\n            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }\n        } catch (e: Exception) { NovaLog.quiet(\"FetchLogActivity\", e) }\n    }"
 },
 {
  "path": "android/app/build.gradle",
  "old": "        //   ones that come first, and is capped at the budget. Fixes an empty\n        //   \"from my notes\" window and an answer paragraph being dropped.\n        versionCode 245\n        versionName '9.33.77'\n        // v6.0.0: arm64 only by default. CI also builds a universal APK\n        // (arm64 + x86_64, for emulators/Chromebooks) with:",
  "new": "        //   ones that come first, and is capped at the budget. Fixes an empty\n        //   \"from my notes\" window and an answer paragraph being dropped.\n        // v9.33.78 \"Storage robustness\": a stale partial download that the\n        //   server answers 416 for is cleared instead of failing forever, and\n        //   the fetch log is rewritten atomically.\n        versionCode 246\n        versionName '9.33.78'\n        // v6.0.0: arm64 only by default. CI also builds a universal APK\n        // (arm64 + x86_64, for emulators/Chromebooks) with:"
 },
 {
  "path": "tmp/verify_patches.py",
  "old": "           encoding=\"utf-8\").read())\n\nfor f, text in [(\"MainActivity.kt\", ma), (\"ncie/android/NcieChat.kt\", nc), (\"ncie/android/NcieTune.kt\", tun), (\"ncie/android/NcieLearn.kt\", nl), (\"ncie/android/NcieKnowledge.kt\", nk), (\"ncie/android/NcieExam.kt\", ne), (\"ncie/learn/Learner.kt\", lr), (\"ncie/learn/PersistentLearner.kt\", pl), (\"ncie/learn/LearnedFact.kt\", lf), (\"KnowledgeActivity.kt\", ka), (\"ncie/android/NcieLeftovers.kt\", lo), (\"LeftoverReceiver.kt\", lr2), (\"NovaUi.kt\", load(\"NovaUi.kt\")), (\"NovaHealth.kt\", load(\"NovaHealth.kt\")), (\"SetupActivity.kt\", load(\"SetupActivity.kt\")), (\"HealthActivity.kt\", load(\"HealthActivity.kt\")), (\"HelpActivity.kt\", load(\"HelpActivity.kt\")), (\"ncie/android/NcieStyle.kt\", load(\"ncie/android/NcieStyle.kt\")), (\"NovaListener.kt\", load(\"NovaListener.kt\")), (\"ncie/android/NcieDeadlines.kt\", load(\"ncie/android/NcieDeadlines.kt\")), (\"ncie/android/NcieWeekly.kt\", load(\"ncie/android/NcieWeekly.kt\")), (\"ncie/android/NcieWatchdog.kt\", load(\"ncie/android/NcieWatchdog.kt\")), (\"ncie/android/NcieFocus.kt\", load(\"ncie/android/NcieFocus.kt\")), (\"ncie/android/NovaJarvis.kt\", load(\"ncie/android/NovaJarvis.kt\")), (\"NovaLog.kt\", load(\"NovaLog.kt\")), (\"ncie/android/NcieTools.kt\", ntools), (\"ncie/android/OnlineFetch.kt\", nof), (\"ncie/Tokens.kt\", load(\"ncie/Tokens.kt\"))]:\n    structural(f, text)",
  "new": "           encoding=\"utf-8\").read())\n\n# ---- v9.33.78 \"Storage robustness\" ----\n# Two ways data or a multi-GB download could be lost with nothing reported.\ncheck(\"v9.33.78: a 416 on a stale partial clears it instead of failing forever\",\n      \"if (code == 416 && existing > 0) {\" in load(\"ModelDownloader.kt\")\n      and \"the partial download could not be resumed and was cleared\" in load(\"ModelDownloader.kt\"))\ncheck(\"v9.33.78: the fetch log is rewritten atomically\",\n      'val tmp = java.io.File(f.parentFile, f.name + \".tmp\")' in load(\"FetchLogActivity.kt\"))\n\nfor f, text in [(\"MainActivity.kt\", ma), (\"ncie/android/NcieChat.kt\", nc), (\"ncie/android/NcieTune.kt\", tun), (\"ncie/android/NcieLearn.kt\", nl), (\"ncie/android/NcieKnowledge.kt\", nk), (\"ncie/android/NcieExam.kt\", ne), (\"ncie/learn/Learner.kt\", lr), (\"ncie/learn/PersistentLearner.kt\", pl), (\"ncie/learn/LearnedFact.kt\", lf), (\"KnowledgeActivity.kt\", ka), (\"ncie/android/NcieLeftovers.kt\", lo), (\"LeftoverReceiver.kt\", lr2), (\"NovaUi.kt\", load(\"NovaUi.kt\")), (\"NovaHealth.kt\", load(\"NovaHealth.kt\")), (\"SetupActivity.kt\", load(\"SetupActivity.kt\")), (\"HealthActivity.kt\", load(\"HealthActivity.kt\")), (\"HelpActivity.kt\", load(\"HelpActivity.kt\")), (\"ncie/android/NcieStyle.kt\", load(\"ncie/android/NcieStyle.kt\")), (\"NovaListener.kt\", load(\"NovaListener.kt\")), (\"ncie/android/NcieDeadlines.kt\", load(\"ncie/android/NcieDeadlines.kt\")), (\"ncie/android/NcieWeekly.kt\", load(\"ncie/android/NcieWeekly.kt\")), (\"ncie/android/NcieWatchdog.kt\", load(\"ncie/android/NcieWatchdog.kt\")), (\"ncie/android/NcieFocus.kt\", load(\"ncie/android/NcieFocus.kt\")), (\"ncie/android/NovaJarvis.kt\", load(\"ncie/android/NovaJarvis.kt\")), (\"NovaLog.kt\", load(\"NovaLog.kt\")), (\"ncie/android/NcieTools.kt\", ntools), (\"ncie/android/OnlineFetch.kt\", nof), (\"ncie/Tokens.kt\", load(\"ncie/Tokens.kt\"))]:\n    structural(f, text)"
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
    print("PATCH OK: v9.33.78 applied and verified")
    return 0


sys.exit(main())
