#!/usr/bin/env python3
"""NOVA v7.5.1 app patch: kill flash reloads, lean LFM prefill, chunk overlap.

From the user's plan, verified in the code:

1. The summarizers did a FULL model reload (re-reading the ~800 MB GGUF
   from flash) every few chunks and before every final combine - 4-5
   reloads per summary, tens of seconds of pure flash reading. The v6.2
   instant reset (clear KV + re-process system prompt, sub-second,
   weights stay in RAM) does the same job. All five reload sites swapped.
   Intervals stay at every-3/every-6: with an instant reset they cost
   under a second, and resetting more often keeps the context cleaner.
2. The tiny-model check missed "1.2b", so LFM 2.5 1.2B got the fat
   two-source prefill (2400-char notes AND 2400-char wiki + exam line)
   meant for big models. LFM is now lean: one background source.
   Tiny notes cap raised 900 -> 1200 so lean does not mean starved.
3. Section chunks now overlap ~10% with the previous chunk, so a fact
   sitting on a chunk boundary survives whole in at least one section.
4. Section prompts now say "only use facts present in the text - never
   invent" - extraction guardrails measurably reduce hallucination.

Backslash-safe: @@ marker only.

Idempotent - safe to run on every CI build.
Usage: patch_v751.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

BS = chr(92)
def k(s):
    return s.replace("@@", BS)

def rep(src, old, new, what, count=1):
    n = src.count(old)
    assert n == count, "%s: anchor found %dx (expected %dx)" % (what, n, count)
    return src.replace(old, new)

# ---- 1) reload -> instant reset (5 sites) ----
A_OLD = '''                    if (i in 1 until chunks.size && i % 3 == 0 && NovaEngine.contextDirty) {
                        try { NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                            NovaEngine.activeModelLabel, settings.systemPrompt) } catch (e: Exception) { }
                    }
'''
A_NEW = '''                    if (i in 1 until chunks.size && i % 3 == 0 && NovaEngine.contextDirty) {
                        // v7.5.1: instant KV reset instead of a full model
                        // reload from flash - same clean context, sub-second
                        try { NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt) } catch (e: Exception) { }
                    }
'''
B_OLD = '''                if (NovaEngine.contextDirty) {
                    try { NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                        NovaEngine.activeModelLabel, settings.systemPrompt) } catch (e: Exception) { }
                }
'''
B_NEW = '''                if (NovaEngine.contextDirty) {
                    // v7.5.1: instant KV reset, not a full flash reload
                    try { NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt) } catch (e: Exception) { }
                }
'''
C_OLD = '''                if (NovaEngine.contextDirty) {
                    try {
                        NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                            NovaEngine.activeModelLabel, settings.systemPrompt)
                    } catch (e: Exception) { }
                }
'''
C_NEW = '''                if (NovaEngine.contextDirty) {
                    try {
                        NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt)
                    } catch (e: Exception) { }
                }
'''
D_OLD = '''                    if (i in 1 until sections.size && i % 6 == 0 && NovaEngine.contextDirty) {
                        try { NovaEngine.load(this@MainActivity, NovaEngine.activeModelPath!!,
                            NovaEngine.activeModelLabel, settings.systemPrompt) } catch (e: Exception) { }
                    }
'''
D_NEW = '''                    if (i in 1 until sections.size && i % 6 == 0 && NovaEngine.contextDirty) {
                        // v7.5.1: instant KV reset instead of a full reload
                        try { NovaEngine.resetConversation(this@MainActivity, settings.systemPrompt) } catch (e: Exception) { }
                    }
'''

# ---- 2) LFM 1.2B is 1B-class too ----
E_OLD = '''        val tiny = "1b" in mlabel || "0.6b" in mlabel || "0.5b" in mlabel
'''
E_NEW = '''        // v7.5.1: "1b" missed "1.2b", so LFM 2.5 1.2B got the fat
        // two-source prefill meant for big models - lean now, like 1B
        val tiny = "1b" in mlabel || "1.2b" in mlabel || "0.6b" in mlabel || "0.5b" in mlabel
'''
F_OLD = k('''                if (notes.length > (if (tiny) 900 else 2400))
                    notes = notes.substring(0, if (tiny) 900 else 2400) + "@@n[...more omitted]"
''')
F_NEW = k('''                if (notes.length > (if (tiny) 1200 else 2400))
                    notes = notes.substring(0, if (tiny) 1200 else 2400) + "@@n[...more omitted]"
''')
G_OLD = k('''            if (notes2.length > (if (tiny) 900 else 2400))
                notes2 = notes2.substring(0, if (tiny) 900 else 2400)
''')
G_NEW = k('''            if (notes2.length > (if (tiny) 1200 else 2400))
                notes2 = notes2.substring(0, if (tiny) 1200 else 2400)
''')

# ---- 3) chunk overlap + 4) anti-invent (doc) ----
H_OLD = '''                for ((i, c) in chunks.withIndex()) {
'''
H_NEW = '''                for ((i, c) in chunks.withIndex()) {
                    // v7.5.1: 10% overlap with the previous chunk - a fact
                    // sitting on a chunk boundary stays whole somewhere
                    val c2 = if (i > 0) chunks[i - 1].takeLast(650) + NL + c else c
'''
I_OLD = k('''                                "sentences, no commentary:" +
                                "@@n-----@@n$c@@n-----", 280
''')
I_NEW = k('''                                "sentences, no commentary. Only use facts " +
                                "present in the text - never invent:" +
                                "@@n-----@@n$c2@@n-----", 280
''')

# ---- 3) overlap + 4) anti-invent (notes) ----
J_OLD = '''                for ((i, c) in sections.withIndex()) {
'''
J_NEW = '''                for ((i, c) in sections.withIndex()) {
                    // v7.5.1: overlap with the previous section - boundary
                    // facts survive whole in at least one section
                    val c2 = if (i > 0) sections[i - 1].takeLast(260) + NL + c else c
'''
K_OLD = k('''                                "stated in the text:$antiCot@@n-----@@n$c@@n-----", 280
''')
K_NEW = k('''                                "stated in the text. Only use facts present - never invent:$antiCot@@n-----@@n$c2@@n-----", 280
''')

src = open(MA, encoding="utf-8").read()
if "v7.5.1" in src:
    print("MainActivity.kt: v7.5.1 patch already applied")
else:
    src = rep(src, A_OLD, A_NEW, "doc mid-loop reset")
    src = rep(src, B_OLD, B_NEW, "pre-combine resets", count=2)
    src = rep(src, C_OLD, C_NEW, "notes initial reset")
    src = rep(src, D_OLD, D_NEW, "notes mid-loop reset")
    src = rep(src, E_OLD, E_NEW, "tiny 1.2b")
    src = rep(src, F_OLD, F_NEW, "notes cap")
    src = rep(src, G_OLD, G_NEW, "study notes cap")
    src = rep(src, H_OLD, H_NEW, "doc overlap")
    src = rep(src, I_OLD, I_NEW, "doc prompt c2")
    src = rep(src, J_OLD, J_NEW, "notes overlap")
    src = rep(src, K_OLD, K_NEW, "notes prompt c2")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: v7.5.1 reload-kill + lean-LFM + overlap pack applied")
