#!/usr/bin/env python3
"""v9.33.158 "The record says why".

Documentation upkeep. v9.33.148 .. v9.33.156 shipped with no changelog
rationale in android/app/build.gradle, breaking the convention every earlier
release followed. This restores it, and removes the v9.33.157 one-shot
applier workflow (the applier may DELETE a workflow; it may not modify one,
so the deletion rides along here).

Every entry below is reconstructed from the release's own commit title and
the files that release touched. It records WHAT moved, not a mechanism that
was not re-verified, and says so.

Every edit must match EXACTLY ONCE; anything else aborts before writing.
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
fails = []


def read(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def write(rel, text):
    with open(os.path.join(ROOT, rel), "w", encoding="utf-8") as f:
        f.write(text)


def sub_once(rel, old, new, label):
    text = read(rel)
    n = text.count(old)
    if n != 1:
        fails.append("%s: anchor matched %d times (want 1)" % (label, n))
        return
    write(rel, text.replace(old, new))
    print("OK    " + label)


BUILD = "android/app/build.gradle"
VERIFY = "tmp/verify_patches.py"

ENTRIES = (
    "        // ---- v9.33.148 .. v9.33.156: reconstructed record ---------------\n"
    "        // These nine releases shipped without a changelog entry here. The\n"
    "        // entries below were written afterwards from each release's own\n"
    "        // commit title and the files it touched - they record WHAT moved.\n"
    "        // Where the original reasoning was not re-verified, none is claimed.\n"
    "        // v9.33.148 \"The conversation answers for itself\": the conversation\n"
    "        //   store (ChatStore) took over its own read/write path, ChatsActivity\n"
    "        //   followed it, and ChatStoreSaveTest was added to pin the save.\n"
    "        // v9.33.149 \"The reply is saved, or it says so\": a reply that could\n"
    "        //   not be persisted now reports that, in MainActivity.\n"
    "        // v9.33.150 \"A catch that can never run\": a dead try/catch was removed\n"
    "        //   across the ncie kernel (NcieChat, NcieChatExt, NcieTutor, NcieLearn,\n"
    "        //   NcieExam, NcieProfile, NcieSummary, OnlineFetch).\n"
    "        // v9.33.151 \"An append is not a save\": call sites that appended to a\n"
    "        //   file were separated from those that save it (MainActivity,\n"
    "        //   NovaListener, WikiCore, NovaJarvis, NcieEvolve, NcieGround,\n"
    "        //   NcieLearn, NcieTools, NcieTutor, NcieValue).\n"
    "        // v9.33.152 \"A store that could not be read is still not an empty\n"
    "        //   store\": an unreadable store is no longer reported as an empty one\n"
    "        //   (NcieEngines, NcieGround, NcieValue).\n"
    "        // v9.33.153 \"An append that does not copy the file\": the append path\n"
    "        //   stopped rewriting the whole file (NcieFiles, NovaListener,\n"
    "        //   WikiCore), pinned by the new NcieFilesTest.\n"
    "        // v9.33.154: the research gate and durable factual memory were merged\n"
    "        //   onto one shared EvidencePipeline (PR #113).\n"
    "        // v9.33.155 \"One gate, one place\": that shared gate was pinned by\n"
    "        //   EvidencePipelineTest, and the superseded local fetch matcher went.\n"
    "        // v9.33.156 \"One number for the suite\": RegressionLab.EXPECTED_CHECKS\n"
    "        //   became the single home for the suite's size (two tests had disagreed\n"
    "        //   and held the build red), and verify_patches gained a floor so that\n"
    "        //   deleting a check makes the verifier red instead of greener.\n"
    "        // -------------------------------------------------------------------\n"
)

sub_once(
    BUILD,
    "        versionCode 325\n        versionName '9.33.157'\n",
    ENTRIES +
    "        // v9.33.158 \"The record says why\": no behaviour change. The nine\n"
    "        //   releases above are documented here, and the v9.33.157 one-shot\n"
    "        //   applier workflow is removed.\n"
    "        versionCode 326\n"
    "        versionName '9.33.158'\n",
    "build.gradle: reconstructed changelog + version",
)

sub_once(
    VERIFY,
    'check("v9.33.157: version is at least 9.33.157 (325)",\n'
    '      version_at_least("9.33.157", 325))\n',
    'check("v9.33.158: version is at least 9.33.158 (326)",\n'
    '      version_at_least("9.33.158", 326))\n',
    "verify_patches.py: version floor",
)

# The applier may delete a workflow file (it may not create or modify one).
applier = os.path.join(ROOT, ".github/workflows/apply-v933157.yml")
if os.path.exists(applier):
    os.remove(applier)
    print("OK    removed the v9.33.157 one-shot applier")
else:
    print("SKIP  applier already gone")

if not fails:
    b = read(BUILD)
    for need in ("versionName '9.33.158'", "versionCode 326",
                 "v9.33.148", "v9.33.156", "v9.33.158"):
        if need not in b:
            fails.append("build.gradle: missing " + need)
    if 'version_at_least("9.33.158", 326)' not in read(VERIFY):
        fails.append("verify_patches.py: version floor not taught")

if fails:
    print("\nPATCH FAILED: %d problem(s)" % len(fails))
    for f in fails:
        print(" - " + f)
    sys.exit(1)
print("\nPATCH OK: v9.33.158 applied cleanly")
