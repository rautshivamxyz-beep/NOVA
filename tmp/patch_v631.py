#!/usr/bin/env python3
"""NOVA v6.3.1 app patch: OCR'd questions were hijacked as phone commands.

The phone-command feature ("torch off", "open youtube"...) runs BEFORE the
model on every message, with fuzzy matching. A photographed question
containing words like "torch ... off" (physics questions do!) was eaten by
the torch handler - the model never saw it.

Fixes:
1. Phone commands only fire for short single-line typed requests -
   anything over 60 chars or multi-line (OCR/pasted question text) goes
   straight to the model.
2. "Solve it" now sends immediately after the image preview (ChatGPT-style
   flow: photo -> preview -> answer).

Idempotent - safe to run on every CI build.
Usage: patch_v631.py <NOVA repo root>
"""
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
MA = os.path.join(ROOT, "android/app/src/main/java/org/nova/MainActivity.kt")

def rep(src, old, new, what):
    n = src.count(old)
    assert n == 1, "%s: anchor found %dx (expected 1x)" % (what, n)
    return src.replace(old, new)

# ---- 1) phone commands: short single-line typed requests only ----
A1_OLD = '''    private fun tryPhoneCommand(text: String): Boolean {
        val t = text.trim()
'''
A1_NEW = '''    private fun tryPhoneCommand(text: String): Boolean {
        val t = text.trim()
        // v6.3.1: OCR'd question text ("...the torch is switched off...")
        // must reach the model - phone commands are short typed requests,
        // never long multi-line question text
        if (t.length > 60 || t.contains('\\n')) return false
'''

# ---- 2) "Solve it" sends immediately (ChatGPT-style) ----
A2_OLD = '''                        .setPositiveButton("Solve it") { _, _ ->
                            input.setText("Solve this step by step:\\n\\n$txt")
                            input.setSelection(input.text.length)
                            input.requestFocus()
                        }
'''
A2_NEW = '''                        .setPositiveButton("Solve it") { _, _ ->
                            // v6.3.1: send straight away - the preview above
                            // already showed the OCR text
                            input.setText("Solve this step by step:\\n\\n$txt")
                            send()
                        }
'''

src = open(MA, encoding="utf-8").read()
if "v6.3.1" in src:
    print("MainActivity.kt: v6.3.1 patch already applied")
else:
    src = rep(src, A1_OLD, A1_NEW, "phone-command guard")
    src = rep(src, A2_OLD, A2_NEW, "Solve it sends directly")
    open(MA, "w", encoding="utf-8").write(src)
    print("MainActivity.kt: OCR text no longer hijacked by phone commands; Solve it sends directly")
