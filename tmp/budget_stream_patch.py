#!/usr/bin/env python3
"""NOVA v0.8.1 app (#1), MainActivity leg: the kernel-sized generation
leash + live streaming in the summarizers.

1. The main chat turn's maxTokens is no longer the raw global setting:
   NcieKnowledge.generationBudget consults the kernel's PLAN — lean turns
   (plain short chat) get half the user's predictLength cap (floor 192),
   everything else (study questions, notes, continuations) and internal
   prompts keep the full cap.
2. The four summarizer generations (doc sections, doc final, notes
   sections, notes final) now stream into the reply bubble live via
   uiProgress() (throttled to ~4 updates/s, bounded by tail300) instead
   of showing a frozen progress line. The chat-compaction summary does
   NOT stream — it runs with no bubble to its name.
3. Guard: two new MA markers, run before any commit.
"""
import subprocess
import sys

MA = "android/app/src/main/java/org/nova/MainActivity.kt"
GUARD = "tmp/verify_patches.py"

src = open(MA, encoding="utf-8").read()
assert src.count("generationBudget(") == 0, "MainActivity already patched"

def swap(old, new, what):
    assert src.count(old) == 1, "anchor not exactly once (" + what + "): " + old[:60]
    return src.replace(old, new)

# 1. the kernel-sized generation leash
src = swap(
    """                NovaEngineAdapter.stream(if (wantMath) mathPrompt(p2) else p2,
                    settings.predictLength)""",
    """                // v0.8.1 (#1): the kernel sizes the generation leash per
                // request — lean turns get half the user cap, everything
                // else the full cap.
                NovaEngineAdapter.stream(if (wantMath) mathPrompt(p2) else p2,
                    NcieKnowledge.generationBudget(
                        if (!plain && userText != null) userText else null,
                        settings.predictLength))""",
    "generation budget")

# 2. the uiProgress helper
src = swap(
    "    /** True when a reply looks cut off mid-sentence at the token limit. */",
    """    /** v0.8.1: live progress for the summarizers — the token stream is
     *  already flowing; this shows it in the reply bubble (throttled to
     *  ~4 updates/s, bounded by tail300) instead of a frozen progress
     *  line. The header names the phase, so the user always knows what
     *  is running. */
    private var lastStreamUi = 0L
    private fun uiProgress(header: String, sb: StringBuilder) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStreamUi < 250) return
        lastStreamUi = now
        adapter.setLastText(header + tail300(sb.toString()))
    }

    /** True when a reply looks cut off mid-sentence at the token limit. */""",
    "uiProgress helper")

# 3-6. the four summarizer collects, live now
src = swap(
    '''                                "present in the text - never invent:" +
                                "\n-----\n$c2\n-----", 280
                        ).collect { sb.append(it) }''',
    '''                                "present in the text - never invent:" +
                                "\n-----\n$c2\n-----", 280
                        ).collect { sb.append(it); uiProgress("Summarizing section ${i + 1}/${chunks.size}\u2026\n\n", sb) }''',
    "doc sections")
src = swap(
    '''                        "Do not skip any topic. Use only the information given:" +
                        "\n\n${dedupeLines(sectionSummaries.toString()).take(11000)}", 1500
                ).collect { sb2.append(it) }''',
    '''                        "Do not skip any topic. Use only the information given:" +
                        "\n\n${dedupeLines(sectionSummaries.toString()).take(11000)}", 1500
                ).collect { sb2.append(it); uiProgress("Writing the final summary\u2026\n\n", sb2) }''',
    "doc final")
src = swap(
    '''                                "stated in the text. Only use facts present - never invent:$antiCot\n-----\n$c2\n-----", 280
                        ).collect { sb.append(it) }''',
    '''                                "stated in the text. Only use facts present - never invent:$antiCot\n-----\n$c2\n-----", 280
                        ).collect { sb.append(it); uiProgress("Summarizing section ${i + 1}/${sections.size}\u2026\n\n", sb) }''',
    "notes sections")
src = swap(
    '''                        "outside knowledge or invent terms.$antiCot\n\n" +
                        dedupeLines(sectionSummaries.toString()).take(11000), 1500
                ).collect { sb2.append(it) }''',
    '''                        "outside knowledge or invent terms.$antiCot\n\n" +
                        dedupeLines(sectionSummaries.toString()).take(11000), 1500
                ).collect { sb2.append(it); uiProgress("Writing the final summary\u2026\n\n", sb2) }''',
    "notes final")

assert src.count("uiProgress(") == 5, "expected 5 uiProgress references, got %d" % src.count("uiProgress(")
assert src.count("generationBudget(") == 1
open(MA, "w", encoding="utf-8").write(src)

# 7. guard: the two new MA markers
g = open(GUARD, encoding="utf-8").read()
assert g.count('("v0.8.1: kernel-sized generation leash", "NcieKnowledge.generationBudget(", 1),') == 0, "guard already patched"
old = '    ("v7.6.5: pressed-state ripple feedback", "fun rippleOverlay(", 1),'
assert g.count(old) == 1
g = g.replace(old, old +
    '\n    ("v0.8.1: kernel-sized generation leash", "NcieKnowledge.generationBudget(", 1),'
    '\n    ("v0.8.1: live streaming in the summarizers", "uiProgress(", 5),')
open(GUARD, "w", encoding="utf-8").write(g)

r = subprocess.run([sys.executable, GUARD, "."], capture_output=True, text=True)
sys.stdout.write(r.stdout)
sys.stderr.write(r.stderr)
sys.exit(r.returncode)
