#!/usr/bin/env python3
"""NOVA post-patch verification (automatic test, level 1).

Runs in CI right after the patch chain. Checks that every feature marker
from v6.2.3 .. v7.5.1 is present in the generated code exactly as many
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

fails = []


def check(name, cond):
    print(("PASS  " if cond else "FAIL  ") + name)
    if not cond:
        fails.append(name)


def load(f):
    return open(os.path.join(ROOT, BASE + f), encoding="utf-8").read()


ma = load("MainActivity.kt")
bk = load("Backup.kt")
nb = load("NotifBrain.kt")
ka = load("KnowledgeActivity.kt")

# ---- feature markers, exactly as the patch chain writes them ----
MA_MARKERS = [
    ("v6.2.3 base markers", "class MainActivity", 1),
    ("v7.3 greeting fast-path (regex + use)", "SMALLTALK_REGEX", 2),
    ("v7.4 chip prompt set", "CHIP_PROMPTS", 2),
    ("v7.4 chip routing check", "val isChip = CHIP_PROMPTS.contains(text)", 1),
    ("v7.4 chat-switch guard", "val genChat = currentChat", 1),
    ("v7.4 input usable without model", "input.isEnabled = !NovaEngine.isLoading", 1),
    ("v7.5 section extraction prompt", "Extract the key facts", 2),
    ("v7.5 lean wiki cap", "val cap = if (tiny) 900 else 1200", 1),
    ("v7.5.1 LFM tiny detection", '"1.2b" in mlabel', 1),
    ("v7.5.1 tiny notes caps", "if (tiny) 1200 else 2400", 4),
    ("v7.5.1 doc chunk overlap", "takeLast(650)", 1),
    ("v7.5.1 notes chunk overlap", "takeLast(260)", 1),
    ("v7.5.1 anti-invent guardrails", "never invent", 2),
    ("v7.5.1 instant resets", "NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)", 6),
]
for name, marker, n in MA_MARKERS:
    check("%s (x%d)" % (name, n), ma.count(marker) == n)

check("v7.4 backup key fix (Backup.kt)", bk.count('put("knowledge_enabled"') == 1)
check("v7.4 notification thread fix (NotifBrain.kt)", nb.count("return@Thread") == 1)
check("v7.4 bounded import (KnowledgeActivity.kt)", ka.count("bound the read") == 1)

# ---- build.gradle: guard against the srdDirs corruption seen on 2026-09-26 ----
bg = open(os.path.join(ROOT, "android/app/build.gradle"), encoding="utf-8").read()
check("build.gradle: jniLibs srcDirs intact (no typo corruption)",
      bg.count("jniLibs.srcDirs") == 1 and bg.count("srdDirs") == 0)

# ---- old code that must be GONE (a skipped patch leaves these behind) ----
check("old 5-8-sentence summarizer prompts removed", ma.count("5-8 detailed sentences") == 0)
check("old flash reloads in summarizers removed", ma.count("NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath") == 0)

# ---- the send-order fix: no-model tools BEFORE ensureModelReady ----
i_tools = ma.find("if (solveArithmetic(text)) return")
i_model = ma.find("if (!ensureModelReady()) return")
check("v7.4 order: calculator/phone commands before model check",
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


for f, text in [("MainActivity.kt", ma), ("Backup.kt", bk), ("NotifBrain.kt", nb), ("KnowledgeActivity.kt", ka)]:
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
