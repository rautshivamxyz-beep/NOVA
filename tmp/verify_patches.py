#!/usr/bin/env python3
"""NOVA post-patch verification (automatic test, level 1).

Runs in CI right after the patch chain. Checks that every feature marker
from v6.2.3 .. v7.6.0 is present in the generated code exactly as many
times as expected, that no old code survived where a patch should have
replaced it, that the send-order fix is in the right order, and that all
four patched Kotlin files still balance with no invalid escapes.

This is the guard against the #1 build risk from the code review: a patch
silently skipping (stale anchor, stray comment) and shipping the APK
minus a feature with no error anywhere. Also checks build.gradle against
the srdDirs typo corruption seen on 2026-09-26.

Usage: verify_patches.py <NOVA repo root>
Exits 1 (fails CI) if anything is missing.
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
BASE = "android/app/src/main/java/org/nova/"
FILES = ["MainActivity.kt", "Backup.kt", "NotifBrain.kt", "KnowledgeActivity.kt"]
# Stage 5 (#1): send() collapsed - its body moved verbatim to
# ncie/android/NcieChat.kt (MainActivity.ncieSend). The markers that
# lived inside send() are checked against NcieChat.kt now; everything
# else still points at MainActivity.

fails = []

def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        fails.append(name)

def load(f):
    return open(os.path.join(ROOT, BASE + f), encoding="utf-8").read()


ma = load("MainActivity.kt")
nc = load("ncie/android/NcieChat.kt")
bk = load("Backup.kt")
nb = load("NotifBrain.kt")
ka = load("KnowledgeActivity.kt")

# ---- feature markers, exactly as the patch chain writes them ----
MA_MARKERS = [
    ("v6.2.3 base markers", "class MainActivity", 1),
    ("v7.3 greeting fast-path (regex)", "SMALLTALK_REGEX", 1),
    ("v7.4 chip prompt set", "CHIP_PROMPTS", 1),
    # moved to NcieChat.kt below: ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1),
    ("v7.4 chat-switch guard", "val genChat = currentChat", 1),
    ("v7.4 input usable without model", "input.isEnabled = !NovaEngine.isLoading", 1),
    ("v7.5 section extraction prompt", "Extract the key facts", 2),
    # moved to NcieChat.kt below: ("v7.5 lean wiki cap", "val cap = if (tiny) 900 else 1200", 1),
    # moved to NcieChat.kt below: ("v7.5.1 LFM tiny detection", '"1.2b" in mlabel', 1),
    # moved to NcieChat.kt below: ("v7.5.1 tiny notes caps", "if (tiny) 1200 else 2400", 4),
    ("v7.5.1 doc chunk overlap", "takeLast(650)", 1),
    ("v7.5.1 notes chunk overlap", "takeLast(260)", 1),
    ("v7.5.1 anti-invent guardrails", "never invent", 2),
    ("v7.5.1 instant resets", "NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)", 6),
    # moved to NcieChat.kt below: ("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1),
    # moved to NcieChat.kt below: ("v7.6 notes relevance gate", "relevance gate - one shared word", 1),
    ("v7.6 reply boilerplate cleaner", "private fun cleanReplyText", 1),
    # moved to NcieChat.kt below: ("v7.6 input cleared only when consumed", 'if (solveArithmetic(text)) { input.setText("")', 1),
    ("v7.6 notes cache warm-up", "NcieKnowledge.warmUp(this@MainActivity); NcieKnowledge.warmUpNotes(this@MainActivity)", 1),
    ("NCIE polish: summarizer chunks through the boundary", "NcieKnowledge.docChunks(this, doc)", 1),
    ("NCIE polish: save-to-knowledge through the boundary", "NcieKnowledge.addDoc(ctx, nm, msgText)", 1),
    ("NCIE polish: all generation through the kernel engine", "NovaEngineAdapter.stream(", 6),
    ("v7.6.5: pressed-state ripple feedback", "fun rippleOverlay(", 1),
    ("v7.6 compaction chat guard", "remember which chat this compaction belongs to", 1),
    ("NCIE 7: record the completed turn for the learner", "NcieLearn.record(this@MainActivity, userText, replyMsg.text)", 1),
]
for name, marker, n in MA_MARKERS:
    check("%s (x%d)" % (name, n), ma.count(marker) == n)

# markers that moved with send()'s body to ncie/android/NcieChat.kt
NC_MARKERS = [
    ("v7.3 greeting fast-path (use)", "SMALLTALK_REGEX", 2),
    ("v7.4 chip prompt set (use)", "CHIP_PROMPTS", 2),
    ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1),
    ("v7.5/NCIE-6 lean wiki cap", "val cap = if (lean) 900 else 1200", 1),
    ("NCIE 6: kernel context-budget gate in the chat turn", "NcieKnowledge.leanContext(text)", 1),
    ("v7.5.1 LFM tiny detection", '"1.2b" in mlabel', 1),
    ("v7.5.1/NCIE-6 lean notes caps", "if (lean) 1200 else 2400", 4),
    ("v7.6 greeting context reset", "greeting sent into a dirty/stale context", 1),
    ("v7.6 notes relevance gate", "relevance gate - one shared word", 1),
    ("v7.6 input cleared only when consumed", 'if (solveArithmetic(text)) { input.setText("")', 1),
    ("Stage 5 collapse: the moved body is one function", "fun MainActivity.ncieSend()", 1),
    ("Stage 5 collapse: body reached the act capture", "val act = this", 1),
    ("NCIE 7: Smart Skip recall in the chat turn", "NcieLearn.recall(this, text)", 1),
]
for name, marker, n in NC_MARKERS:
    check("%s (x%d)" % (name, n), nc.count(marker) == n)
check("Stage 5 collapse: send() is the thin dispatch", ma.count("ncieSend()") == 1)
check("v7.6 streaming turn through NovaEngineAdapter (all 6 generations now)", ma.count("NovaEngineAdapter.stream(") == 6)

check("v7.4 backup key fix (Backup.kt)", bk.count('put("knowledge_enabled"') == 1)
check("v7.4 notification thread fix (NotifBrain.kt)", nb.count("return@Thread") == 1)
check("v7.4 bounded import (KnowledgeActivity.kt)", ka.count("bound the read") == 1)
check("NCIE polish: import UI through the boundary", ka.count("NcieKnowledge.addDoc(this, name, text)") == 1)
nk = load("ncie/android/NcieKnowledge.kt")
check("NCIE polish: storage exposed on the boundary", nk.count("fun addDoc(c: Context, name: String, text: String)") == 1)
check("NCIE polish: doc chunks exposed on the boundary", nk.count("fun docChunks(c: Context, name: String)") == 1)
# ---- v0.8.1: stale-learner fix + kernel generation budget ----
nl = load("ncie/android/NcieLearn.kt")
check("v0.8.1: learner invalidated when notes change",
      nl.count("fun invalidate()") == 1)
check("v0.8.1: records join the learner's own thread",
      nl.count("io.execute { l.record(userText, response) }") == 1)
check("v0.8.1: addDoc/removeDoc drop the learned cache",
      nk.count("NcieLearn.invalidate()") == 2)
check("v0.8.1: kernel-sized generation leash on the boundary",
      nk.count("fun generationBudget(") == 1)
lr = load("ncie/learn/Learner.kt")
check("v0.8.1: vendored learner has clear() (interface + both impls)",
      lr.count("fun clear()") == 3)
check("v0.8.1: vendored learner has synonym classes",
      lr.count("synonymGroups") == 2)
knn = load("Knowledge.kt")
wc = load("WikiCore.kt")
check("v7.6 atomic notes saves (Knowledge.kt)", knn.count("atomic write") == 1)
check("v7.6 notes warm-up for send gate (Knowledge.kt)", knn.count("warm the cache from a background thread") == 1)
check("v7.6 wiki done marker written last (WikiCore.kt)", wc.count("done.tmp") == 1)
check("v7.6 backup restores exams+reminders (Backup.kt)", bk.count("restore exams and reminders too") == 1)

# ---- build.gradle: guard against the srdDirs corruption seen on 2026-09-26 ----
bg = open(os.path.join(ROOT, "android/app/build.gradle"), encoding="utf-8").read()
check("build.gradle: jniLibs srcDirs intact (no typo corruption)",
      bg.count("jniLibs.srcDirs") == 1 and bg.count("srdDirs") == 0)
check("v7.6.5: version bumped for the polish build",
      bg.count("versionName '7.6.5'") == 1 and bg.count("versionCode 57") == 1)

# ---- old code that must be GONE (a skipped patch leaves these behind) ----
check("old 5-8-sentence summarizer prompts removed", ma.count("5-8 detailed sentences") == 0)
check("old flash reloads in summarizers removed", ma.count("NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath") == 0)

# ---- the send-order fix: no-model tools BEFORE ensureModelReady ----
i_tools = nc.find('if (solveArithmetic(text)) { input.setText("")')
i_model = nc.find("if (!ensureModelReady()) return")
check("v7.4/v7.6 order: calculator/phone commands before model check",
      i_tools != -1 and i_model != -1 and i_tools < i_model)

# ---- structural sanity: braces/parens balance + no invalid escapes ----
def structural(f, text):
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
    check("%s: braces balanced" % f, braces == 0)
    check("%s: parentheses balanced" % f, parens == 0)

for f, text in [("MainActivity.kt", ma), ("ncie/android/NcieChat.kt", nc), ("ncie/android/NcieLearn.kt", nl), ("ncie/android/NcieKnowledge.kt", nk), ("ncie/learn/Learner.kt", lr), ("Backup.kt", bk), ("NotifBrain.kt", nb), ("KnowledgeActivity.kt", ka)]:
    structural(f, text)
    bad = []
    for m in re.finditer(r'"(?:[^"\\]|\\.)*"', text):
        for e in re.finditer(r"\\(.)", m.group(0)):
            if e.group(1) not in "tbnr\"'\\$su":
                bad.append(e.group(1))
    check("%s: no invalid string escapes" % f, not bad)

print("")
if fails:
    print("VERIFY FAILED: %d problem(s)" % len(fails))
    for x in fails:
        print(" - " + x)
    sys.exit(1)
print("VERIFY OK: all patch markers present, code structure sane")
